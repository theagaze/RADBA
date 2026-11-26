package com.radba.remoto.adb

import android.content.Context
import com.radba.remoto.data.FileEntry
import com.radba.remoto.data.RootStatus
import com.radba.remoto.util.normalizeRemote
import com.radba.remoto.util.sanitizeHost
import com.radba.remoto.util.shellQuote
import com.radba.remoto.util.shellStripExitCode
import com.radba.remoto.util.splitHost
import com.radba.remoto.util.trunc
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val ADB_OPEN_TIMEOUT_MS = 8_000L

data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * High level ADB client used by the rest of the app. All blocking library
 * calls are executed on Dispatchers.IO.
 */
class AdbManager(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val instanceLock = Any()

    @Volatile
    private var manager: RadbaAdbConnectionManager? = null

    val host: MutableStateFlow<String> = MutableStateFlow("")
    val online: MutableStateFlow<Boolean> = MutableStateFlow(false)

    @Volatile
    private var rootStatus: RootStatus = RootStatus()

    @Volatile
    private var rootHost: String? = null
    private val rootProbeLock = Mutex()

    /** Disposing a live connection performs blocking socket I/O. */
    private val closeScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var monitorJob: Job? = null

    fun setHost(value: String) {
        val next = sanitizeHost(value) ?: value.trim()
        if (host.value != next) {
            host.value = next
            resetRootCache()
        }
    }

    suspend fun connect(host: String = this@AdbManager.host.value): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val target = sanitizeHost(host) ?: throw IOException("Endereço ADB inválido: $host")
            val (address, port) = splitHost(target)
            val connection = ensureManager()
            runCatching { connection.disconnect() }
            connection.setTimeout(15_000, TimeUnit.MILLISECONDS)
            if (!connection.connect(address, port)) {
                throw IOException("Não foi possível conectar a $target")
            }
            this@AdbManager.host.value = target
            resetRootCache()
            setOnline(true)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            manager?.let { runCatching { it.disconnect() } }
            setOnline(false)
        }
    }

    suspend fun pair(pairHost: String, code: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (address, port) = splitHost(pairHost.trim())
            if (address.isBlank()) throw IOException("Host de pareamento inválido")
            val cleanedCode = code.trim()
            if (cleanedCode.isEmpty()) throw IOException("Código de pareamento vazio")
            val connection = ensureManager()
            if (!connection.pair(address, port, cleanedCode)) {
                throw IOException("Falha no pareamento com $pairHost")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }
    }

    suspend fun reconnect(): Result<Unit> = withContext(Dispatchers.IO) {
        manager?.let { runCatching { it.disconnect() } }
        setOnline(false)
        connect(host.value)
    }

    fun startMonitor(
        scope: CoroutineScope,
        controlAlive: () -> Boolean = { false },
        onStatus: (Boolean) -> Unit,
    ) {
        stopMonitor()
        var lastEmitted: Boolean? = null
        var lastPing = 0L
        var lastReconnect = 0L
        var lastWatchdog = 0L
        monitorJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val connected = online.value && manager?.isConnected == true
                val controlLive = runCatching { controlAlive() }.getOrDefault(false)
                if (!connected && online.value) setOnline(false)

                if (connected && !controlLive && now - lastPing >= 3_000L) {
                    lastPing = now
                    val probe = shell("echo ok", 8_000)
                    if (!probe.ok || !probe.stdout.contains("ok")) {
                        manager?.let { runCatching { it.disconnect() } }
                        setOnline(false)
                    }
                }
                if (!online.value && host.value.isNotBlank() && now - lastReconnect >= 10_000L) {
                    lastReconnect = now
                    reconnect()
                }
                if (online.value && !controlLive && now - lastWatchdog >= 20_000L) {
                    lastWatchdog = now
                    val probe = shell("echo watchdog", 10_000)
                    if (!probe.ok || !probe.stdout.contains("watchdog")) {
                        manager?.let { runCatching { it.disconnect() } }
                        setOnline(false)
                    }
                }
                if (lastEmitted != online.value) {
                    lastEmitted = online.value
                    onStatus(online.value)
                }
                delay(1_000L)
            }
        }
    }

    fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * libadb waits on Object.wait() inside openStream without a timeout and holds
     * its manager lock. Run it on an interruptible thread so a dead adbd cannot
     * freeze every later ADB operation.
     */
    suspend fun openStream(destination: String): AdbStream = withContext(Dispatchers.IO) {
        val connection = manager
            ?: throw IOException("Cliente ADB não inicializado")
        if (!connection.isConnected) throw IOException("ADB desconectado")
        val deferred = CompletableDeferred<AdbStream>()
        val opener = Thread({
            try {
                deferred.complete(connection.openStream(destination))
            } catch (error: Throwable) {
                deferred.completeExceptionally(error)
            }
        }, "adb-open").apply { isDaemon = true }
        opener.start()
        try {
            withTimeout(ADB_OPEN_TIMEOUT_MS) { deferred.await() }
        } catch (error: TimeoutCancellationException) {
            opener.interrupt()
            throw IOException("tempo esgotado ao abrir stream ADB: $destination")
        }
    }

    suspend fun shell(command: String, timeoutMs: Long = 60_000): ShellResult =
        withContext(Dispatchers.IO) {
            val stream = try {
                openStream("shell:$command")
            } catch (error: Exception) {
                return@withContext ShellResult(1, "", error.message ?: "ADB indisponível")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            val expired = AtomicBoolean(false)
            val watchdog = launch {
                delay(timeoutMs.coerceAtLeast(1L))
                expired.set(true)
                closeQuietly(stream)
            }
            var readFailure: String? = null
            try {
                while (true) {
                    val count = stream.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                }
            } catch (error: Exception) {
                if (error !is CancellationException && !expired.get()) {
                    val message = error.message.orEmpty()
                    if (!message.contains("Stream closed", ignoreCase = true)) {
                        readFailure = message.ifEmpty { error.javaClass.simpleName }
                    }
                }
            } finally {
                watchdog.cancel()
                closeQuietly(stream)
            }
            val decoded = shellStripExitCode(output.toByteArray())
            val text = String(decoded.data, StandardCharsets.UTF_8)
            val stderr = when {
                expired.get() -> "tempo esgotado ao executar comando"
                readFailure != null -> readFailure!!
                else -> ""
            }
            ShellResult(if (stderr.isEmpty()) decoded.exitCode else 1, text, stderr)
        }

    suspend fun rootShell(command: String, timeoutMs: Long = 20_000): ShellResult {
        val status = checkRoot()
        if (status.available != true) {
            return ShellResult(1, "", status.detail)
        }
        return when (status.mode) {
            "direct" -> shell(command, timeoutMs)
            "su" -> {
                val first = shell("su -c ${shellQuote(command)}", timeoutMs)
                val text = (first.stdout + " " + first.stderr).lowercase(Locale.ROOT)
                val looksLikeCliError = listOf("usage", "invalid", "unknown option")
                    .any { text.contains(it) }
                if (!first.ok && looksLikeCliError) {
                    val second = shell("su 0 sh -c ${shellQuote(command)}", timeoutMs)
                    if (second.ok) {
                        rootStatus = RootStatus(true, "su0", "su 0 sh -c (uid=0)")
                        rootHost = host.value
                    }
                    second
                } else first
            }
            "su0" -> shell("su 0 sh -c ${shellQuote(command)}", timeoutMs)
            else -> ShellResult(1, "", status.detail)
        }
    }

    fun cachedRootStatus(): RootStatus {
        val cached = rootStatus
        if (rootHost != host.value || cached.available == null) {
            return RootStatus(null, null, cached.detail)
        }
        return cached
    }

    suspend fun checkRoot(force: Boolean = false): RootStatus {
        if (!force) {
            val cached = cachedRootStatus()
            if (cached.available != null) return cached
        }
        return rootProbeLock.withLock {
            if (!force) {
                val cached = cachedRootStatus()
                if (cached.available != null) return@withLock cached
            }
            val probeHost = host.value
            var mode: String? = null
            var detail = "acesso root indisponível"

            val direct = shell("id -u 2>/dev/null || id", 5_000)
            val directOut = direct.stdout.trim()
            if (direct.ok && (directOut == "0" || directOut.contains("uid=0"))) {
                mode = "direct"
                detail = "uid=0 (adbd root)"
            } else {
                val su = shell("su -c ${shellQuote("id")}", 5_000)
                val suOut = su.stdout.trim()
                if (su.ok && suOut.contains("uid=0")) {
                    mode = "su"
                    detail = "su -c (uid=0)"
                } else {
                    val su0 = shell("su 0 sh -c ${shellQuote("id")}", 5_000)
                    val su0Out = su0.stdout.trim()
                    if (su0.ok && su0Out.contains("uid=0")) {
                        mode = "su0"
                        detail = "su 0 sh -c (uid=0)"
                    } else {
                        val raw = (su0.stdout + " " + su0.stderr).trim()
                            .ifEmpty { (su.stdout + " " + su.stderr).trim() }
                            .ifEmpty { direct.stderr.trim() }
                            .lowercase(Locale.ROOT)
                        detail = when {
                            raw.contains("tempo esgotado") -> "tempo esgotado ao verificar root"
                            raw.contains("denied") || raw.contains("permission") || raw.contains("refused") ->
                                "root negado (autorize no Magisk/KernelSU)"
                            raw.contains("not found") || raw.contains("inaccessible") || raw.contains("no such") ->
                                "binário su não encontrado (sem root)"
                            raw.isNotEmpty() -> trunc(raw, 70)
                            else -> "sem acesso root (uid != 0)"
                        }
                    }
                }
            }

            val result = RootStatus(mode != null, mode, detail)
            rootStatus = result
            rootHost = probeHost
            result
        }
    }

    suspend fun remoteFileSize(path: String): Long? = withContext(Dispatchers.IO) {
        runCatching { AdbSync.stat(this@AdbManager, requireAbsoluteRemote(path))?.size }.getOrNull()
    }

    suspend fun pushBytes(bytes: ByteArray, remotePath: String, mode: Int = 0b110100100): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                AdbSync.push(this@AdbManager, requireAbsoluteRemote(remotePath), mode, bytes)
            }
        }

    suspend fun pushLocalFile(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!local.isFile) throw IOException("Arquivo local não encontrado: ${local.path}")
                AdbSync.push(this@AdbManager, requireAbsoluteRemote(remotePath), 0b110100100, local.readBytes())
            }
        }

    suspend fun pullRemoteFile(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                AdbSync.pull(this@AdbManager, requireAbsoluteRemote(remotePath), local)
            }
        }

    suspend fun listRemoteDir(path: String): Result<List<FileEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = normalizeRemote(path)
                    ?: throw IOException("Caminho remoto inválido: $path")
                AdbSync.list(this@AdbManager, normalized)
            }
        }

    override fun close() {
        stopMonitor()
        val connection = synchronized(instanceLock) {
            val current = manager
            manager = null
            current
        }
        setOnline(false)
        if (connection != null) {
            closeScope.launch { runCatching { connection.close() } }
        }
    }

    private fun ensureManager(): RadbaAdbConnectionManager {
        manager?.let { return it }
        return synchronized(instanceLock) {
            manager?.let { return@synchronized it }
            val created = RadbaAdbConnectionManager(appContext)
            manager = created
            created
        }
    }

    private fun setOnline(value: Boolean) {
        if (!value) resetRootCache()
        if (online.value != value) online.value = value
    }

    private fun resetRootCache() {
        rootStatus = RootStatus()
        rootHost = null
    }

    private fun requireAbsoluteRemote(path: String): String {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/")) throw IOException("Caminho remoto inválido: $path")
        val parts = trimmed.split("/").filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw IOException("Caminho remoto inválido: $path")
        return "/" + parts.joinToString("/")
    }

    internal fun closeQuietly(stream: AdbStream?) {
        if (stream == null) return
        runCatching { if (!stream.isClosed) stream.close() }
    }
}
