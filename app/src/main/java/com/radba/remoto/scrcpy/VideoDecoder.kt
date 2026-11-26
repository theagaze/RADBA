package com.radba.remoto.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * A small synchronous MediaCodec H.264 decoder driven by a dedicated thread.
 *
 * The decoder owns its thread and its MediaCodec instance. Surfaces may be
 * attached, detached or replaced at any time: a change is picked up by the
 * decoding thread, which releases the old codec and configures a new one with
 * the current surface.
 */
class VideoDecoder : Closeable {
    /** True while a MediaCodec is configured and started. */
    val configured = MutableStateFlow(false)

    /** True after at least one buffer has been rendered to a surface. */
    val firstFrame = MutableStateFlow(false)

    /** True after at least one frame has been decoded, even without a surface. */
    val frameReceived = MutableStateFlow(false)

    /** Last configuration/decoding failure, empty when healthy. */
    val error = MutableStateFlow("")

    @Volatile
    private var closed = false

    @Volatile
    private var surfaceRef: Surface? = null

    @Volatile
    private var surfaceGeneration = 0

    @Volatile
    private var expectKeyFrame = false

    @Volatile
    private var endOfStream = false

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    @Volatile
    private var mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC

    @Volatile
    private var lastFedAtMs = 0L

    @Volatile
    private var lastRenderedAtMs = 0L

    private val packets = ArrayDeque<EncodedPacket>()
    private val frameTimes = ArrayDeque<Long>()

    /** Last SPS/PPS access unit, replayed whenever the codec is recreated. */
    @Volatile
    private var lastConfig: EncodedPacket? = null

    private val lock = Any()
    private var thread: Thread? = null

    /**
     * Supplies the stream parameters and starts the decoder thread. Calling
     * this again is harmless while the thread is alive.
     */
    fun configure(
        surface: Surface?,
        width: Int,
        height: Int,
        mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC,
    ) {
        if (closed) return
        surfaceRef = surface
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        this.mimeType = mimeType
        startThread()
    }

    /** Attach (or replace) the TextureView surface used for rendering. */
    fun attachSurface(surface: Surface?) {
        if (closed || surfaceRef === surface) return
        surfaceRef = surface
        surfaceGeneration++
        expectKeyFrame = true
        synchronized(lock) { (lock as java.lang.Object).notifyAll() }
    }

    /** Drop non-intra frames until the next key frame (useful after reattach). */
    fun expectKeyFrame() {
        expectKeyFrame = true
    }

    /** Queue one encoded access unit. [flags] use MediaCodec.BUFFER_FLAG_*. */
    fun feed(data: ByteArray, flags: Int, ptsUs: Long) {
        if (closed) return
        lastFedAtMs = SystemClock.elapsedRealtime()
        val isConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (expectKeyFrame && !isConfig && !isKey) return
        if (isKey) expectKeyFrame = false
        if (isConfig) {
            synchronized(lock) { lastConfig = EncodedPacket(data.copyOf(), flags, ptsUs) }
        }
        synchronized(lock) {
            if (packets.size >= MAX_PENDING) packets.removeFirst()
            packets.addLast(EncodedPacket(data.copyOf(), flags, ptsUs))
            (lock as java.lang.Object).notifyAll()
        }
    }

    /** Rendered frames in the last [windowMs], used by the adaptive video tiers. */
    fun fps(windowMs: Long = 1_500L): Float {
        val now = SystemClock.elapsedRealtime()
        synchronized(frameTimes) {
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > windowMs) {
                frameTimes.removeFirst()
            }
            return frameTimes.size * 1_000f / windowMs.toFloat()
        }
    }

    fun lastRenderedAt(): Long = lastRenderedAtMs

    /** Approximate queue delay between the newest fed packet and rendered frame. */
    fun lagMs(): Long {
        val fed = lastFedAtMs
        val rendered = lastRenderedAtMs
        return if (fed > 0L && rendered >= fed) rendered - fed else 0L
    }

    /** Signal that no more encoded data will arrive. */
    fun signalEndOfStream() {
        if (closed) return
        endOfStream = true
        synchronized(lock) { (lock as java.lang.Object).notifyAll() }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { (lock as java.lang.Object).notifyAll() }
        val worker = thread
        worker?.interrupt()
        try {
            worker?.join(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        configured.value = false
    }

    private fun startThread() {
        if (thread?.isAlive == true) return
        endOfStream = false
        configured.value = false
        synchronized(frameTimes) { frameTimes.clear() }
        thread = Thread({ decodeLoop() }, "scrcpy-decoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var seenGeneration = -1
        var inputEosSent = false
        try {
            while (!closed) {
                if (codec == null || seenGeneration != surfaceGeneration) {
                    seenGeneration = surfaceGeneration
                    releaseCodec(codec)
                    codec = null
                    inputEosSent = false
                    configured.value = false
                    try {
                        val newCodec = createCodec(surfaceRef, width, height, mimeType)
                        codec = newCodec
                        configured.value = true
                        error.value = ""
                        // scrcpy sends SPS/PPS only once; replay them so a codec
                        // created after a surface change can decode immediately.
                        lastConfig?.let { config ->
                            try {
                                enqueueWholePacket(newCodec, config)
                            } catch (t: Throwable) {
                                handleCodecFailure(t)
                                releaseCodec(newCodec)
                                codec = null
                            }
                        }
                    } catch (t: Throwable) {
                        configured.value = false
                        error.value = "Decoder: ${t.message ?: t::class.java.simpleName}"
                        waitForWork(250L)
                        continue
                    }
                }

                val activeCodec = codec ?: continue
                val outputDone = drainOutput(activeCodec)

                if (inputEosSent) {
                    if (outputDone) {
                        // End of stream fully drained; keep the decoder alive in case
                        // the surface is replaced and new data arrives.
                        waitForWork(50L)
                    }
                    continue
                }

                val packet = synchronized(lock) {
                    if (packets.isEmpty()) null else packets.removeFirst()
                }
                if (packet != null) {
                    try {
                        enqueueWholePacket(activeCodec, packet)
                    } catch (t: Throwable) {
                        handleCodecFailure(t)
                        synchronized(lock) { packets.addFirst(packet) }
                        releaseCodec(codec)
                        codec = null
                    }
                } else if (endOfStream) {
                    val index = try {
                        activeCodec.dequeueInputBuffer(INPUT_TIMEOUT_US)
                    } catch (t: Throwable) {
                        handleCodecFailure(t)
                        releaseCodec(codec)
                        codec = null
                        continue
                    }
                    if (index >= 0) {
                        try {
                            activeCodec.queueInputBuffer(
                                index,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEosSent = true
                        } catch (t: Throwable) {
                            handleCodecFailure(t)
                            releaseCodec(codec)
                            codec = null
                        }
                    }
                } else {
                    waitForWork(20L)
                }
            }
        } catch (t: Throwable) {
            if (!closed) handleCodecFailure(t)
        } finally {
            releaseCodec(codec)
            configured.value = false
        }
    }

    /**
     * Queues a complete access unit, splitting it across input buffers when it
     * is larger than the codec's maximum. Only the final chunk carries the
     * original flags; earlier chunks are marked as partial frames.
     */
    private fun enqueueWholePacket(codec: MediaCodec, packet: EncodedPacket) {
        var offset = 0
        var stalled = 0
        while (offset < packet.data.size) {
            val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
            if (index < 0) {
                if (++stalled > MAX_ENQUEUE_ATTEMPTS) {
                    throw IOException("codec sem buffers de entrada disponíveis")
                }
                waitForWork(1L)
                continue
            }
            stalled = 0
            val buffer: ByteBuffer = codec.getInputBuffer(index)
                ?: throw IllegalStateException("input buffer unavailable")
            buffer.clear()
            val chunk = minOf(packet.data.size - offset, buffer.remaining())
            if (chunk <= 0) throw IllegalStateException("input buffer vazio")
            buffer.put(packet.data, offset, chunk)
            offset += chunk
            val flags = if (offset >= packet.data.size) {
                packet.flags
            } else {
                packet.flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            codec.queueInputBuffer(index, 0, chunk, packet.ptsUs, flags)
        }
    }

    private fun drainOutput(codec: MediaCodec): Boolean {
        val info = MediaCodec.BufferInfo()
        var reachedEos = false
        while (!closed) {
            val index = try {
                codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (t: Throwable) {
                handleCodecFailure(t)
                return reachedEos
            }
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return reachedEos
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED, MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> {
                    if (index < 0) return reachedEos
                    val configOnly = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isFrame = !configOnly &&
                        (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                    if (isFrame && !frameReceived.value) frameReceived.value = true
                    val render = !closed && surfaceRef != null && isFrame
                    try {
                        codec.releaseOutputBuffer(index, render)
                    } catch (t: Throwable) {
                        handleCodecFailure(t)
                        return reachedEos
                    }
                    if (render) {
                        lastRenderedAtMs = SystemClock.elapsedRealtime()
                        synchronized(frameTimes) {
                            frameTimes.addLast(lastRenderedAtMs)
                            while (frameTimes.size > 120) frameTimes.removeFirst()
                        }
                        if (!firstFrame.value) firstFrame.value = true
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) reachedEos = true
                }
            }
        }
        return reachedEos
    }

    private fun createCodec(surface: Surface?, width: Int, height: Int, mime: String): MediaCodec {
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, surface, null, 0)
            codec.start()
            return codec
        } catch (t: Throwable) {
            releaseCodec(codec)
            throw t
        }
    }

    private fun handleCodecFailure(t: Throwable) {
        if (!closed) error.value = "Decoder: ${t.message ?: t::class.java.simpleName}"
    }

    private fun waitForWork(timeoutMs: Long) {
        if (closed) return
        synchronized(lock) {
            try {
                (lock as java.lang.Object).wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun releaseCodec(codec: MediaCodec?) {
        if (codec == null) return
        try {
            codec.stop()
        } catch (_: Throwable) {
            // ignore: codec may never have been started
        }
        try {
            codec.release()
        } catch (_: Throwable) {
            // ignore
        }
    }

    private data class EncodedPacket(val data: ByteArray, val flags: Int, val ptsUs: Long)

    private companion object {
        const val INPUT_TIMEOUT_US = 20_000L
        const val OUTPUT_TIMEOUT_US = 2_000L
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val MAX_PENDING = 48
        const val MAX_ENQUEUE_ATTEMPTS = 5_000
    }
}
