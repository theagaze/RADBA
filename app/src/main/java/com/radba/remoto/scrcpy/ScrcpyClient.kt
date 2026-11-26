package com.radba.remoto.scrcpy

import android.content.Context
import android.media.MediaCodec
import android.os.SystemClock
import android.view.Surface
import com.radba.remoto.adb.AdbManager
import com.radba.remoto.data.MirrorStats
import com.radba.remoto.data.Prefs
import com.radba.remoto.util.VideoTier
import com.radba.remoto.util.VIDEO_TIERS
import com.radba.remoto.util.encoderPlan
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.math.min

enum class MirrorState { OFF, STARTING, ON }

/**
 * Native scrcpy 2.7 client: the server jar is pushed over ADB and all video /
 * control traffic uses ADB streams (no `adb forward`, no adb binary, no ffmpeg).
 */
class ScrcpyClient(
    context: Context,
    private val adb: AdbManager,
    private val prefsProvider: () -> Prefs,
) : Closeable {
    private val appContext = context.applicationContext

    val state = MutableStateFlow(MirrorState.OFF)
    val error = MutableStateFlow("")
    val activeEncoder = MutableStateFlow("")
    val deviceSize = MutableStateFlow(0 to 0)
    val serverFps = MutableStateFlow(0)
    val mirrorStats = MutableStateFlow(MirrorStats())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var tierIndex = 0

    @Volatile private var lastPacketAtMs = 0L

    @Volatile private var pipeMs = 0.0

    @Volatile private var lastAdaptMs = 0L

    @Volatile private var lowSinceMs = 0L

    @Volatile private var goodSinceMs = 0L

    @Volatile private var generation = 0

    @Volatile private var ready = false

    @Volatile private var serverEnded = false

    @Volatile private var viewWidth = 0

    @Volatile private var viewHeight = 0

    @Volatile private var surfaceRef: Surface? = null

    private var serverStream: AdbStream? = null
    private var videoStream: AdbStream? = null
    private var controlStream: AdbStream? = null
    private var serverJob: Job? = null
    private var videoJob: Job? = null
    private var controlJob: Job? = null
    private var controlScope: CoroutineScope? = null
    private var controlExecutor: ExecutorService? = null
    private var controlChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var decoder: VideoDecoder? = null

    private val logLock = Any()
    private val serverLog = StringBuilder()

    fun attachSurface(surface: Surface?) {
        surfaceRef = surface
        decoder?.attachSurface(surface)
    }

    fun setViewSize(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(0)
        viewHeight = height.coerceAtLeast(0)
    }

    /** Maps centered aspect-fit view coordinates into device pixel coordinates. */
    fun screenToDevice(x: Float, y: Float): Pair<Int, Int> {
        val (deviceW, deviceH) = deviceSize.value
        if (deviceW <= 0 || deviceH <= 0 || viewWidth <= 0 || viewHeight <= 0) return 0 to 0
        val scale = min(viewWidth.toFloat() / deviceW, viewHeight.toFloat() / deviceH)
        if (scale <= 0f || scale.isNaN() || scale.isInfinite()) return 0 to 0
        val renderedW = deviceW * scale
        val renderedH = deviceH * scale
        val left = (viewWidth - renderedW) / 2f
        val top = (viewHeight - renderedH) / 2f
        val px = ((x - left) / scale).toInt().coerceIn(0, deviceW - 1)
        val py = ((y - top) / scale).toInt().coerceIn(0, deviceH - 1)
        return px to py
    }

    suspend fun start(onProgress: ((String) -> Unit)? = null): Boolean {
        if (state.value == MirrorState.STARTING) return false
        if (state.value == MirrorState.ON && isReady()) return true

        val token = ++generation
        teardown()
        state.value = MirrorState.STARTING
        error.value = ""

        return try {
            onProgress?.invoke("Preparando servidor scrcpy…")
            val prefs = prefsProvider()
            val jar = prepareServerJar(onProgress)

            if (prefs.rootFixVideo) applyRootFixes(onProgress)
            pushJarIfNeeded(jar, onProgress)

            val plan = encoderPlan()
            val tier = currentTier()
            var lastReason = "Não foi possível iniciar o espelhamento"

            for ((index, candidate) in plan.withIndex()) {
                if (token != generation) return false
                val (encoder, software) = candidate
                serverFps.value = tier.maxFps
                onProgress?.invoke(
                    "${tier.name} · tentativa ${index + 1}/${plan.size}: ${encoder ?: "hardware (padrão)"}",
                )
                try {
                    launchSession(token, tier, encoder, onProgress)
                    if (token != generation) return false
                    activeEncoder.value = encoder ?: "hardware (padrão)"
                    error.value = ""
                    state.value = MirrorState.ON
                    return true
                } catch (t: Throwable) {
                    if (token != generation || t is CancellationException) return false
                    lastReason = describe(t)
                    onProgress?.invoke(lastReason)
                    teardown()
                    if (index < plan.lastIndex) delay(400L)
                }
            }
            if (token == generation) {
                error.value = lastReason
                state.value = MirrorState.OFF
            }
            false
        } catch (t: Throwable) {
            if (token == generation && t !is CancellationException) {
                error.value = describe(t)
                state.value = MirrorState.OFF
            }
            false
        }
    }

    fun stop() {
        generation++
        teardown()
        state.value = MirrorState.OFF
    }

    fun isReady(): Boolean {
        if (state.value != MirrorState.ON || !ready) return false
        val video = videoStream ?: return false
        val control = controlStream ?: return false
        return !video.isClosed && !control.isClosed && controlJob?.isActive == true
    }

    fun currentTier(): VideoTier = VIDEO_TIERS[tierIndex.coerceIn(VIDEO_TIERS.indices)]

    fun resetTier() {
        tierIndex = 0
        mirrorStats.value = MirrorStats(currentTier().maxFps, 0, 0, currentTier().name)
    }

    /**
     * Mirrors the adaptive logic of RADB 0.2: downgrade after sustained stalls,
     * upgrade only after a long stable period. Returns -1/down, +1/up, 0/no change.
     */
    fun checkNetworkAdapt(): Int {
        val decoder = decoder ?: return 0
        val fps = decoder.fps(1_500L)
        val lag = decoder.lagMs().toDouble()
        pipeMs = if (pipeMs == 0.0) lag else pipeMs * 0.8 + lag * 0.2
        val tier = currentTier()
        mirrorStats.value = MirrorStats(
            fps = fps.toInt(),
            pipeMs = pipeMs.toInt(),
            tier = tierIndex,
            tierName = tier.name,
        )
        if (!isReady()) return 0
        val now = SystemClock.elapsedRealtime()
        if (now - lastAdaptMs < ADAPT_COOLDOWN_MS) return 0

        val rendered = decoder.lastRenderedAt()
        val stall = lastPacketAtMs > 0L && rendered > 0L &&
            (lastPacketAtMs - rendered) > STALL_MS && (now - lastPacketAtMs) < 2_000L
        val highLatency = pipeMs > 75.0 && rendered > 0L && (now - rendered) < 1_000L

        if (stall || highLatency) {
            if (lowSinceMs == 0L) {
                lowSinceMs = now
            } else if (now - lowSinceMs >= 6_000L && tierIndex < VIDEO_TIERS.lastIndex) {
                tierIndex++
                lastAdaptMs = now
                lowSinceMs = 0L
                goodSinceMs = 0L
                return -1
            }
        } else {
            lowSinceMs = 0L
            if (tierIndex > 0 && fps >= 28f && pipeMs < 20.0) {
                if (goodSinceMs == 0L) {
                    goodSinceMs = now
                } else if (now - goodSinceMs >= 25_000L) {
                    tierIndex--
                    lastAdaptMs = now
                    goodSinceMs = 0L
                    return 1
                }
            } else if (fps >= 2f) {
                // Python keeps the upgrade timer untouched when frames are nearly absent.
                goodSinceMs = 0L
            }
        }
        return 0
    }

    fun sendKey(code: Int): Boolean {
        if (!isReady()) return false
        val buffer = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
        appendKey(buffer, code, ACTION_DOWN)
        appendKey(buffer, code, ACTION_UP)
        return enqueue(buffer.array())
    }

    fun sendKeys(codes: List<Int>): Boolean {
        if (!isReady() || codes.isEmpty()) return false
        val buffer = ByteBuffer.allocate(codes.size * 28).order(ByteOrder.BIG_ENDIAN)
        codes.forEach { code ->
            appendKey(buffer, code, ACTION_DOWN)
            appendKey(buffer, code, ACTION_UP)
        }
        return enqueue(buffer.array())
    }

    fun sendText(text: String): Boolean {
        if (!isReady()) return false
        var sent = false
        for (bytes in utf8Chunks(text, MAX_TEXT_BYTES)) {
            val buffer = ByteBuffer.allocate(5 + bytes.size).order(ByteOrder.BIG_ENDIAN)
            buffer.put(TYPE_TEXT.toByte())
            buffer.putInt(bytes.size)
            buffer.put(bytes)
            sent = enqueue(buffer.array()) || sent
        }
        return sent
    }

    fun sendTouch(action: Int, x: Int, y: Int): Boolean {
        if (!isReady()) return false
        val (deviceW, deviceH) = deviceSize.value
        val width = deviceW.coerceAtLeast(1)
        val height = deviceH.coerceAtLeast(1)
        val px = x.coerceIn(0, width - 1)
        val py = y.coerceIn(0, height - 1)
        val downOrMove = action == ACTION_DOWN || action == ACTION_MOVE
        val downOrUp = action == ACTION_DOWN || action == ACTION_UP
        val buffer = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_TOUCH.toByte())
        buffer.put(action.toByte())
        buffer.putLong(-1L)
        buffer.putInt(px)
        buffer.putInt(py)
        buffer.putShort(width.coerceAtMost(65_535).toShort())
        buffer.putShort(height.coerceAtMost(65_535).toShort())
        buffer.putShort((if (downOrMove) 0xFFFF else 0).toShort())
        buffer.putInt(if (downOrUp) 1 else 0)
        buffer.putInt(if (downOrMove) 1 else 0)
        return enqueue(buffer.array())
    }

    fun setDisplayPower(on: Boolean): Boolean {
        if (!isReady()) return false
        val buffer = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN)
        buffer.put(TYPE_POWER.toByte())
        buffer.put((if (on) POWER_MODE_NORMAL else POWER_MODE_OFF).toByte())
        return enqueue(buffer.array())
    }

    /**
     * scrcpy 2.7 has no remote keyframe request on the control channel (type 17
     * is not part of the protocol and must never be sent). Instead, discard
     * inter frames until the server's next keyframe (i-frame-interval=1).
     */
    fun requestKeyframe(): Boolean {
        if (!isReady()) return false
        decoder?.expectKeyFrame()
        return true
    }

    override fun close() {
        stop()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Session setup
    // ------------------------------------------------------------------

    private suspend fun launchSession(
        token: Int,
        tier: VideoTier,
        encoder: String?,
        onProgress: ((String) -> Unit)?,
    ) {
        lastPacketAtMs = 0L
        pipeMs = 0.0
        lastAdaptMs = SystemClock.elapsedRealtime()
        lowSinceMs = 0L
        goodSinceMs = 0L
        mirrorStats.value = MirrorStats(tier.maxFps, 0, tierIndex, tier.name)
        // scrcpy parses scid with Integer.parseInt(value, 16), so it must be a
        // positive 31-bit value. Derive both the command and the socket name
        // from the same number so they always match.
        val scidValue = (UUID.randomUUID().mostSignificantBits and 0x7fff_ffffL).toInt()
        val scid = "%08x".format(scidValue)
        val command = buildServerCommand(tier, encoder, scid)
        synchronized(logLock) { serverLog.setLength(0) }
        serverEnded = false
        ready = false

        onProgress?.invoke("Iniciando servidor no dispositivo…")
        val shell = withContext(Dispatchers.IO) { adb.openStream("shell:$command") }
        serverStream = shell
        serverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                while (isActive && token == generation && !shell.isClosed) {
                    val count = shell.read(buffer, 0, buffer.size)
                    if (count <= 0) break
                    synchronized(logLock) {
                        serverLog.append(String(buffer, 0, count, StandardCharsets.UTF_8))
                        if (serverLog.length > MAX_LOG_CHARS) {
                            serverLog.delete(0, serverLog.length - MAX_LOG_CHARS)
                        }
                    }
                }
            } catch (_: Throwable) {
                // server output is diagnostic only
            } finally {
                if (token == generation) serverEnded = true
            }
        }

        onProgress?.invoke("Conectando streams de vídeo e controle…")
        val streams = openStreams(scid, token, onProgress)
        videoStream = streams.first
        controlStream = streams.second
        ensureControlWriter()

        val (width, height) = deviceSize.value
        val videoDecoder = VideoDecoder()
        decoder = videoDecoder
        videoDecoder.configure(surfaceRef, width, height)
        videoJob = scope.launch(Dispatchers.IO) { readVideo(token, streams.first, videoDecoder) }

        val firstFrame = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MS) {
            combine(videoDecoder.configured, videoDecoder.firstFrame) { configured, frame ->
                configured && frame
            }.first { it }
        }
        if (firstFrame != true) throw IOException("nenhum frame recebido")
    }

    private suspend fun openStreams(
        scid: String,
        token: Int,
        onProgress: ((String) -> Unit)?,
    ): Pair<AdbStream, AdbStream> = withContext(Dispatchers.IO) {
        val destination = "localabstract:scrcpy_$scid"
        val deadline = System.currentTimeMillis() + STREAM_TIMEOUT_MS
        var video: AdbStream? = null
        var control: AdbStream? = null
        try {
            while (video == null && System.currentTimeMillis() < deadline) {
                checkGeneration(token)
                if (serverEnded) throw IOException("o servidor scrcpy foi encerrado antes do vídeo")
                try {
                    video = adb.openStream(destination)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    delay(150L)
                }
            }
            val videoStream = video ?: throw IOException("tempo esgotado ao abrir o stream de vídeo")

            // scrcpy sends one dummy byte on accept so connection errors surface here.
            val dummy = ByteArray(1)
            val dummyRead = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                readFully(videoStream, dummy, 0, 1, token)
            }
            if (dummyRead == null) throw IOException("tempo esgotado no handshake de vídeo")
            onProgress?.invoke("Vídeo conectado; abrindo controle…")

            while (control == null && System.currentTimeMillis() < deadline) {
                checkGeneration(token)
                if (serverEnded) throw IOException("o servidor scrcpy foi encerrado antes do controle")
                try {
                    control = adb.openStream(destination)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    delay(150L)
                }
            }
            val controlStream = control ?: throw IOException("tempo esgotado ao abrir o stream de controle")

            // 64-byte device name + codecId/width/height, all big endian.
            val meta = ByteArray(76)
            val metaRead = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                readFully(videoStream, meta, 0, meta.size, token)
            }
            if (metaRead == null) throw IOException("tempo esgotado ao ler metadados de vídeo")
            val width = ByteBuffer.wrap(meta, 68, 4).order(ByteOrder.BIG_ENDIAN).int
            val height = ByteBuffer.wrap(meta, 72, 4).order(ByteOrder.BIG_ENDIAN).int
            if (width <= 0 || height <= 0) {
                throw IOException("dimensões de vídeo inválidas: ${width}x${height}")
            }
            deviceSize.value = width to height
            onProgress?.invoke("Vídeo ${width}x${height} pronto")
            ready = true
            videoStream to controlStream
        } catch (t: Throwable) {
            closeStream(video)
            closeStream(control)
            throw t
        }
    }

    private suspend fun readVideo(token: Int, stream: AdbStream, videoDecoder: VideoDecoder) =
        withContext(Dispatchers.IO) {
            val header = ByteArray(12)
            val reader = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            try {
                while (isActive && token == generation && !stream.isClosed) {
                    readFully(stream, header, 0, header.size, token)
                    reader.clear()
                    reader.put(header)
                    val packedPts = reader.getLong(0)
                    val size = reader.getInt(8)
                    if (size <= 0 || size > MAX_PACKET_SIZE) {
                        throw IOException("tamanho de pacote de vídeo inválido: $size")
                    }
                    val payload = ByteArray(size)
                    readFully(stream, payload, 0, size, token)
                    lastPacketAtMs = SystemClock.elapsedRealtime()
                    val flags = when {
                        packedPts and CONFIG_FLAG != 0L -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                        packedPts and KEY_FLAG != 0L -> MediaCodec.BUFFER_FLAG_KEY_FRAME
                        else -> 0
                    }
                    videoDecoder.feed(payload, flags, packedPts and PTS_MASK)
                }
            } catch (t: Throwable) {
                if (token == generation && t !is CancellationException) {
                    error.value = "Vídeo interrompido: ${t.message ?: t::class.java.simpleName}"
                    stop()
                }
            }
        }

    private suspend fun readFully(
        stream: AdbStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        token: Int,
    ) = withContext(Dispatchers.IO) {
        var done = 0
        while (done < length) {
            checkGeneration(token)
            if (stream.isClosed) throw IOException("stream de vídeo fechado")
            val count = stream.read(buffer, offset + done, length - done)
            if (count <= 0) throw IOException("stream de vídeo fechado")
            done += count
        }
    }

    private fun checkGeneration(token: Int) {
        if (token != generation) throw CancellationException("espelhamento interrompido")
    }

    // ------------------------------------------------------------------
    // Control channel
    // ------------------------------------------------------------------

    private fun ensureControlWriter() {
        if (controlJob?.isActive == true) return
        if (controlChannel.isClosedForSend) controlChannel = Channel(Channel.UNLIMITED)
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "scrcpy-control").apply { isDaemon = true }
        }
        controlExecutor = executor
        val writerScope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
        controlScope = writerScope
        controlJob = writerScope.launch {
            for (packet in controlChannel) {
                val stream = controlStream
                if (stream == null || stream.isClosed) continue
                try {
                    stream.write(packet, 0, packet.size)
                    stream.flush()
                } catch (_: Throwable) {
                    // a reconnect replaces the stream; queued packets are best effort
                }
            }
        }
    }

    private fun enqueue(packet: ByteArray): Boolean {
        if (!isReady()) return false
        val channel = controlChannel
        if (channel.isClosedForSend) return false
        return channel.trySend(packet).isSuccess
    }

    private fun appendKey(buffer: ByteBuffer, code: Int, action: Int) {
        buffer.put(TYPE_KEY.toByte())
        buffer.put(action.toByte())
        buffer.putInt(code)
        buffer.putInt(0)
        buffer.putInt(0)
    }

    private fun utf8Chunks(text: String, maxBytes: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var output = ByteArrayOutputStream()
        var index = 0
        while (index < text.length) {
            val current = text[index]
            val charLength = if (Character.isHighSurrogate(current) &&
                index + 1 < text.length && Character.isLowSurrogate(text[index + 1])
            ) 2 else 1
            val encoded = text.substring(index, index + charLength).toByteArray(StandardCharsets.UTF_8)
            if (output.size() + encoded.size > maxBytes && output.size() > 0) {
                chunks.add(output.toByteArray())
                output = ByteArrayOutputStream()
            }
            if (encoded.size <= maxBytes) {
                output.write(encoded, 0, encoded.size)
            }
            index += charLength
        }
        if (output.size() > 0) chunks.add(output.toByteArray())
        return chunks
    }

    // ------------------------------------------------------------------
    // Server jar & shell helpers
    // ------------------------------------------------------------------

    private suspend fun prepareServerJar(onProgress: ((String) -> Unit)?) = withContext(Dispatchers.IO) {
        val local = File(appContext.filesDir, SERVER_JAR_NAME)
        fun valid(bytes: ByteArray) = bytes.size > MIN_JAR_SIZE && sha256(bytes) == SERVER_SHA256

        val cached = runCatching { if (local.isFile) local.readBytes() else null }.getOrNull()
        if (cached != null && valid(cached)) return@withContext cached

        onProgress?.invoke("Lendo servidor dos assets…")
        val fromAssets = runCatching {
            appContext.assets.open(SERVER_JAR_NAME).use { it.readBytes() }
        }.getOrNull()
        if (fromAssets != null && valid(fromAssets)) {
            writeAtomically(local, fromAssets)
            return@withContext fromAssets
        }

        onProgress?.invoke("Baixando servidor scrcpy 2.7…")
        val downloaded = downloadServerJar()
        if (!valid(downloaded)) throw IOException("servidor baixado inválido (tamanho ou SHA-256)")
        writeAtomically(local, downloaded)
        downloaded
    }

    private fun downloadServerJar(): ByteArray = ByteArrayOutputStream().use { output ->
        val connection = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            connection.inputStream.use { input ->
                val chunk = ByteArray(256 * 1024)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (count > 0) output.write(chunk, 0, count)
                }
            }
            output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        runCatching {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(target)) {
                target.writeBytes(bytes)
                temporary.delete()
            }
        }.onFailure {
            runCatching { target.writeBytes(bytes) }
            runCatching { temporary.delete() }
        }
    }

    private suspend fun pushJarIfNeeded(jar: ByteArray, onProgress: ((String) -> Unit)?) =
        withContext(Dispatchers.IO) {
            val remoteSize = adb.remoteFileSize(REMOTE_JAR)
            if (remoteSize == jar.size.toLong()) return@withContext
            onProgress?.invoke("Enviando servidor para o dispositivo…")
            val result = adb.pushBytes(jar, REMOTE_JAR)
            if (result.isFailure) {
                throw IOException(
                    "falha ao enviar servidor: ${result.exceptionOrNull()?.message ?: "erro desconhecido"}",
                )
            }
        }

    private suspend fun applyRootFixes(onProgress: ((String) -> Unit)?) {
        onProgress?.invoke("Aplicando ajustes de vídeo (root)…")
        runPrivileged("service call SurfaceFlinger 1008 i32 1", 30_000L)
        val property = runPrivileged("getprop vendor.media.c2.vdec.support", 20_000L)
        if (property.stdout.trim() != "false") {
            runPrivileged(
                "setprop vendor.media.c2.vdec.support false && " +
                    "setprop ctl.restart android-hardware-media-c2-hal-1-2 && " +
                    "setprop ctl.restart media",
                60_000L,
            )
            delay(1_200L)
        }
        runPrivileged("pkill -f 'com.genymobile.scrcpy.Serve[r]' 2>/dev/null; echo ok", 20_000L)
    }

    /**
     * The declared AdbManager API exposes `shell`; when the implementation also
     * exposes `rootShell` (used by the root agent) it is preferred. Reflection
     * keeps this client compatible with both variants.
     */
    private suspend fun runPrivileged(command: String, timeoutMs: Long): PrivilegedResult {
        val candidates = adb.javaClass.methods.filter { method ->
            method.name == "rootShell" && method.parameterTypes.firstOrNull() == String::class.java
        }
        for (method in candidates.sortedByDescending {
            it.parameterTypes.lastOrNull() == Continuation::class.java
        }) {
            try {
                val types = method.parameterTypes
                val isSuspend = types.lastOrNull() == Continuation::class.java
                val deferred = CompletableDeferred<Any?>()
                val arguments = ArrayList<Any?>(types.size)
                for (type in types) {
                    when {
                        type == String::class.java -> arguments.add(command)
                        type == Long::class.javaPrimitiveType || type == Long::class.java ->
                            arguments.add(timeoutMs)
                        isSuspend && type == Continuation::class.java ->
                            arguments.add(object : Continuation<Any?> {
                                override val context: CoroutineContext = EmptyCoroutineContext
                                override fun resumeWith(result: Result<Any?>) {
                                    if (result.isFailure) {
                                        deferred.completeExceptionally(
                                            result.exceptionOrNull() ?: RuntimeException("rootShell failed"),
                                        )
                                    } else {
                                        deferred.complete(result.getOrNull())
                                    }
                                }
                            })
                    }
                }
                val returned = method.invoke(adb, *arguments.toArray())
                val value = if (returned == COROUTINE_SUSPENDED) deferred.await() else returned
                if (value != null) return value.toPrivilegedResult()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
        }
        val fallback = adb.shell(command, timeoutMs)
        return PrivilegedResult(fallback.ok, fallback.stdout, fallback.stderr)
    }

    private fun Any.toPrivilegedResult(): PrivilegedResult {
        fun read(name: String): Any? = runCatching {
            javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(this)
        }.getOrNull() ?: runCatching { javaClass.getField(name).get(this) }.getOrNull()

        return PrivilegedResult(
            ok = (read("getOk") ?: read("ok")) as? Boolean ?: false,
            stdout = (read("getStdout") ?: read("stdout")) as? String ?: "",
            stderr = (read("getStderr") ?: read("stderr")) as? String ?: "",
        )
    }

    private fun buildServerCommand(tier: VideoTier, encoder: String?, scid: String): String {
        val command = StringBuilder(
            "CLASSPATH=$REMOTE_JAR app_process / com.genymobile.scrcpy.Server $SERVER_VERSION " +
                "scid=$scid log_level=info tunnel_forward=true control=true video=true " +
                "audio=false cleanup=false clipboard_autosync=false",
        )
        command.append(" max_size=").append(tier.maxSize)
            .append(" max_fps=").append(tier.maxFps)
            .append(" video_bit_rate=").append(tier.bitrate)
            .append(" video_codec=h264")
        if (!encoder.isNullOrBlank()) command.append(" video_encoder=").append(encoder)
        command.append(
            " video_codec_options=i-frame-interval=1 send_frame_meta=true send_codec_meta=true " +
                "send_device_meta=true send_dummy_byte=true",
        )
        return command.toString()
    }

    // ------------------------------------------------------------------
    // Teardown & diagnostics
    // ------------------------------------------------------------------

    private fun teardown() {
        ready = false
        videoJob?.cancel()
        videoJob = null
        serverJob?.cancel()
        serverJob = null
        controlJob?.cancel()
        controlJob = null
        runCatching { controlChannel.close() }
        runCatching { controlScope?.cancel() }
        controlScope = null
        runCatching { controlExecutor?.shutdownNow() }
        controlExecutor = null
        closeStream(videoStream)
        videoStream = null
        closeStream(controlStream)
        controlStream = null
        closeStream(serverStream)
        serverStream = null
        decoder?.close()
        decoder = null
        serverEnded = false
    }

    private fun closeStream(stream: AdbStream?) {
        if (stream == null) return
        runCatching { if (!stream.isClosed) stream.close() }
    }

    private fun describe(t: Throwable): String {
        val base = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        val tail = synchronized(logLock) { serverLog.toString().takeLast(700) }.trim()
        return if (tail.isEmpty()) base else "$base\n$tail"
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val result = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            result.append(HEX[value ushr 4]).append(HEX[value and 0x0f])
        }
        return result.toString()
    }

    private data class PrivilegedResult(val ok: Boolean, val stdout: String, val stderr: String)

    private companion object {
        const val SERVER_JAR_NAME = "scrcpy-server.jar"
        const val REMOTE_JAR = "/data/local/tmp/scrcpy-server.jar"
        const val SERVER_SHA256 = "a23c5659f36c260f105c022d27bcb3eafffa26070e7baa9eda66d01377a1adba"
        const val SERVER_VERSION = "2.7"
        const val DOWNLOAD_URL =
            "https://github.com/Genymobile/scrcpy/releases/download/v2.7/scrcpy-server-v2.7"
        const val MIN_JAR_SIZE = 10_000
        const val MAX_LOG_CHARS = 16_384
        const val STREAM_TIMEOUT_MS = 25_000L
        const val FIRST_FRAME_TIMEOUT_MS = 14_000L
        const val MAX_PACKET_SIZE = 64 * 1024 * 1024
        const val MAX_TEXT_BYTES = 260
        const val ADAPT_COOLDOWN_MS = 8_000L
        const val STALL_MS = 2_800L
        const val TYPE_KEY = 0
        const val TYPE_TEXT = 1
        const val TYPE_TOUCH = 2
        const val TYPE_POWER = 10
        const val POWER_MODE_OFF = 0
        const val POWER_MODE_NORMAL = 2
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        val CONFIG_FLAG: Long = 1L shl 63
        val KEY_FLAG: Long = 1L shl 62
        val PTS_MASK: Long = 0x3FFF_FFFF_FFFF_FFFFL
        val HEX = "0123456789abcdef".toCharArray()
    }
}
