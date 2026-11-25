package com.radba.remoto.adb

import android.content.Context
import com.radba.remoto.data.PictureBase
import com.radba.remoto.util.clampInt
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Controls picture quality through the bundled tvctl.dex agent. A single
 * long-lived shell stream speaks the agent's line protocol ("g", "b N",
 * "c N", "s N").
 */
class PqController(context: Context, private val adb: AdbManager) : Closeable {
    private val appContext = context.applicationContext
    val available: Boolean = appContext.assets.list("")?.contains(TVCTL_ASSET) == true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    @Volatile
    private var stream: AdbStream? = null

    @Volatile
    private var lines: Channel<String> = Channel(Channel.UNLIMITED)

    @Volatile
    private var pumpJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var deployed = false

    suspend fun state(): PictureBase? {
        if (!available) return null
        val answer = request("g") ?: return null
        val match = STATE_REGEX.find(answer) ?: return null
        val brightness = match.groupValues[1].toIntOrNull() ?: return null
        val contrast = match.groupValues[2].toIntOrNull() ?: return null
        val saturation = match.groupValues[3].toIntOrNull() ?: return null
        return PictureBase(brightness, contrast, saturation)
    }

    suspend fun set(kind: String, value: Int): Boolean {
        if (!available) return false
        val code = when (kind.trim().lowercase()) {
            "brightness", "b" -> "b"
            "contrast", "c" -> "c"
            "saturation", "s" -> "s"
            else -> return false
        }
        val answer = request("$code ${clampInt(value)}") ?: return false
        return answer.startsWith("OK")
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun request(command: String): String? = lock.withLock {
        for (attempt in 0 until 2) {
            var active = stream
            if (active == null || active.isClosed) {
                stop()
                try {
                    start()
                } catch (error: Exception) {
                    continue
                }
                active = stream ?: continue
            }
            val current = active ?: continue

            val payload = (command + "\n").toByteArray(StandardCharsets.UTF_8)
            val written = try {
                withContext(Dispatchers.IO) { current.write(payload, 0, payload.size) }
                true
            } catch (error: Exception) {
                false
            }
            if (!written) {
                stop()
                continue
            }

            val channel = lines
            val answer = withTimeoutOrNull(6_000L) {
                while (true) {
                    val line = try {
                        channel.receive()
                    } catch (error: Exception) {
                        break
                    }
                    if (line == EOF_MARKER) break
                    if (RESPONSE_REGEX.matches(line)) return@withTimeoutOrNull line
                }
                null
            }
            if (answer != null) return@withLock answer
            if (attempt == 0) stop()
        }
        null
    }

    private suspend fun start() {
        if (!available) throw IOException("tvctl.dex não encontrado nos assets")
        if (!deployed) {
            deploy()
            deployed = true
        }
        val opened = adb.openStream("shell:$SERVER_COMMAND")
        val channel = Channel<String>(Channel.UNLIMITED)
        stream = opened
        lines = channel
        pumpJob = scope.launch {
            val buffer = ByteArray(4 * 1024)
            val partial = StringBuilder()
            try {
                while (isActive) {
                    val count = opened.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    for (index in 0 until count) {
                        val byte = buffer[index].toInt() and 0xFF
                        if (byte == '\n'.code || byte == '\r'.code) {
                            val line = partial.toString().trim()
                            partial.setLength(0)
                            if (line.isNotEmpty()) channel.trySend(line)
                        } else if (partial.length < 4096) {
                            partial.append(byte.toChar())
                        }
                    }
                }
            } catch (error: IOException) {
                // The remote shell was closed.
            } finally {
                val tail = partial.toString().trim()
                if (tail.isNotEmpty()) channel.trySend(tail)
                channel.trySend(EOF_MARKER)
                closeQuietly(opened)
            }
        }

        val ready = withTimeoutOrNull(15_000L) {
            while (true) {
                val line = try {
                    channel.receive()
                } catch (error: Exception) {
                    return@withTimeoutOrNull false
                }
                if (line == EOF_MARKER) return@withTimeoutOrNull false
                if (line == "READY") return@withTimeoutOrNull true
            }
        } == true
        if (!ready) {
            stop()
            throw IOException("tvctl não iniciou")
        }
        while (channel.tryReceive().getOrNull() != null) {
            // Discard lines left over from the startup handshake.
        }
    }

    private suspend fun deploy() = withContext(Dispatchers.IO) {
        val local = File(appContext.filesDir, TVCTL_ASSET)
        appContext.assets.open(TVCTL_ASSET).use { input ->
            FileOutputStream(local).use { output -> input.copyTo(output) }
        }
        val size = local.length()
        val remoteSize = adb.remoteFileSize(REMOTE_TVCTL) ?: -1L
        if (remoteSize != size) {
            val pushed = adb.pushLocalFile(local, REMOTE_TVCTL)
            if (pushed.isFailure) {
                throw IOException(
                    "falha ao enviar tvctl.dex",
                    pushed.exceptionOrNull(),
                )
            }
        }
    }

    private fun stop() {
        pumpJob?.cancel()
        pumpJob = null
        val active = stream
        stream = null
        closeQuietly(active)
        lines.close()
        lines = Channel(Channel.UNLIMITED)
    }

    private companion object {
        const val TVCTL_ASSET = "tvctl.dex"
        const val REMOTE_TVCTL = "/data/local/tmp/tvctl.dex"
        const val SERVER_COMMAND =
            "CLASSPATH=$REMOTE_TVCTL:/vendor/framework/droidlogic.software.core.jar " +
                "app_process /system/bin com.tvctl.Server"
        const val EOF_MARKER = "__TVCTL_EOF__"
        val RESPONSE_REGEX = Regex("""^(OK [bcs]|B=\d+ C=\d+ S=\d+( END)?|ERR.*)$""")
        val STATE_REGEX = Regex("""B=(\d+) C=(\d+) S=(\d+)""")
    }
}
