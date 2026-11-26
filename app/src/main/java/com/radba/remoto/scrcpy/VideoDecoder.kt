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
 * The decoder owns its thread and its MediaCodec instance. When the view
 * surface is replaced (rotation, view recreation) the running codec is handed
 * over with [MediaCodec.setOutputSurface], so the stream keeps flowing without
 * waiting for a new key frame. A full codec rebuild is used only when the hand
 * over is not possible.
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
    private var softwareDecoder = false

    @Volatile
    private var lastFedAtMs = 0L

    @Volatile
    private var lastRenderedAtMs = 0L

    /** Measured delay between receiving a packet and rendering its frame. */
    @Volatile
    private var lastLatencyMs = 0L

    private val packets = ArrayDeque<EncodedPacket>()
    private val frameTimes = ArrayDeque<Long>()

    /** Presentation timestamp -> elapsed realtime when the packet was received. */
    private val feedStamps = LinkedHashMap<Long, Long>()

    /** Last SPS/PPS access unit, replayed whenever the codec is recreated. */
    @Volatile
    private var lastConfig: EncodedPacket? = null

    /** SPS/PPS waiting to be prepended to the next media packet. */
    @Volatile
    private var pendingConfig: ByteArray? = null

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
        softwareDecoder: Boolean = false,
    ) {
        if (closed) return
        surfaceRef = surface
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        this.mimeType = mimeType
        this.softwareDecoder = softwareDecoder
        startThread()
    }

    /** Attach (or replace) the TextureView surface used for rendering. */
    fun attachSurface(surface: Surface?) {
        if (closed || surfaceRef === surface) return
        surfaceRef = surface
        surfaceGeneration++
        if (surface == null) {
            // No consumer: nothing can be rendered until a new surface arrives.
            firstFrame.value = false
        }
        synchronized(lock) { (lock as java.lang.Object).notifyAll() }
    }

    /** Drop non-intra frames until the next key frame (useful after reattach). */
    fun expectKeyFrame() {
        expectKeyFrame = true
    }

    /** Queue one encoded access unit. [flags] use MediaCodec.BUFFER_FLAG_*. */
    fun feed(data: ByteArray, flags: Int, ptsUs: Long) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        lastFedAtMs = now
        val isConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (expectKeyFrame && !isConfig && !isKey) {
            // A P-frame cannot decode without the preceding reference frame.
            // Wait for the next IDR emitted by the server instead.
            return
        }
        if (isKey) expectKeyFrame = false
        if (isConfig) {
            // scrcpy sends SPS/PPS as a separate packet. MediaCodec is more
            // reliable when the config is prefixed to the next access unit,
            // exactly like scrcpy's own packet merger does.
            synchronized(lock) {
                // SPS and PPS can arrive as separate config packets; keep all of
                // them so the next access unit carries a complete codec config.
                val previous = pendingConfig
                pendingConfig = if (previous == null) data.copyOf() else previous + data
                val previousSaved = lastConfig?.data
                lastConfig = EncodedPacket(
                    if (previousSaved == null) data.copyOf() else previousSaved + data,
                    flags,
                    ptsUs,
                )
                (lock as java.lang.Object).notifyAll()
            }
            return
        }
        synchronized(lock) {
            val config = pendingConfig
            pendingConfig = null
            // [data] is a freshly allocated packet owned by this decoder, so it
            // can be queued without an extra copy.
            val payload = if (config == null) data else config + data
            if (packets.size >= MAX_PENDING) {
                // The decoder is behind. Keeping a partial GOP would corrupt the
                // picture until the next key frame, so restart from a clean IDR.
                packets.clear()
                if (!isKey) {
                    expectKeyFrame = true
                    return@synchronized
                }
            }
            packets.addLast(EncodedPacket(payload, flags, ptsUs))
            synchronized(feedStamps) {
                feedStamps[ptsUs] = now
                while (feedStamps.size > MAX_FEED_STAMPS) {
                    val oldest = feedStamps.keys.iterator()
                    if (!oldest.hasNext()) break
                    oldest.next()
                    oldest.remove()
                }
            }
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

    /**
     * Real pipeline delay: elapsed time between receiving the encoded packet and
     * rendering the decoded frame that came from it. Returns 0 until a frame has
     * been rendered.
     */
    fun lagMs(): Long = lastLatencyMs

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
        thread = Thread({ decodeLoop() }, "video-decoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun decodeLoop() {
        var codec: MediaCodec? = null
        var codecSurface: Surface? = null
        var codecGeneration = -1
        var inputEosSent = false
        try {
            while (!closed) {
                val target = surfaceRef
                val generation = surfaceGeneration
                if (codec == null || codecGeneration != generation) {
                    // Prefer a zero-copy surface hand over: decoded frames keep
                    // flowing to the new view without an IDR wait.
                    if (codec != null && codecSurface != null && target != null) {
                        val swapped = try {
                            codec.setOutputSurface(target)
                            true
                        } catch (_: Throwable) {
                            false
                        }
                        if (swapped) {
                            codecSurface = target
                            codecGeneration = generation
                            continue
                        }
                    }
                    releaseCodec(codec)
                    codec = null
                    codecSurface = null
                    codecGeneration = generation
                    inputEosSent = false
                    configured.value = false
                    // Packets decoded for the old codec are not valid for the
                    // new one; drop them and wait for a fresh IDR.
                    synchronized(lock) { packets.clear() }
                    expectKeyFrame = true
                    firstFrame.value = false
                    try {
                        val newCodec = createCodec(target, width, height, mimeType)
                        codec = newCodec
                        codecSurface = target
                        configured.value = true
                        error.value = ""
                        // scrcpy sends SPS/PPS only once; replay them before the
                        // next access unit when a codec is created after a
                        // surface change.
                        lastConfig?.let { config ->
                            synchronized(lock) {
                                if (pendingConfig == null) {
                                    pendingConfig = config.data.copyOf()
                                    (lock as java.lang.Object).notifyAll()
                                }
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
                val hasPackets = synchronized(lock) { packets.isNotEmpty() }
                drainOutput(activeCodec, codecGeneration, if (hasPackets) 0L else OUTPUT_TIMEOUT_US)

                if (inputEosSent) {
                    waitForWork(20L)
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
                // Free decoded buffers before retrying the input side.
                drainOutput(codec, surfaceGeneration, 0L)
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

    private fun drainOutput(codec: MediaCodec, generation: Int, timeoutUs: Long): Boolean {
        val info = MediaCodec.BufferInfo()
        var reachedEos = false
        while (!closed && generation == surfaceGeneration) {
            val index = try {
                codec.dequeueOutputBuffer(info, timeoutUs)
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
                    val currentGeneration = generation == surfaceGeneration
                    if (isFrame && currentGeneration) {
                        if (!frameReceived.value) frameReceived.value = true
                        // A successful frame proves the codec recovered from any
                        // transient failure; do not keep restarting forever.
                        error.value = ""
                    }
                    val render = !closed && surfaceRef != null && isFrame && currentGeneration
                    try {
                        codec.releaseOutputBuffer(index, render)
                    } catch (t: Throwable) {
                        if (currentGeneration) handleCodecFailure(t)
                        return reachedEos
                    }
                    if (render) {
                        val now = SystemClock.elapsedRealtime()
                        lastRenderedAtMs = now
                        val fedAt = synchronized(feedStamps) {
                            feedStamps.remove(info.presentationTimeUs)
                        }
                        if (fedAt != null && now >= fedAt) lastLatencyMs = now - fedAt
                        synchronized(frameTimes) {
                            frameTimes.addLast(now)
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
        val candidates = if (softwareDecoder) {
            listOf(SOFTWARE_DECODER_C2, SOFTWARE_DECODER_OMX, null)
        } else {
            listOf(null, SOFTWARE_DECODER_C2, SOFTWARE_DECODER_OMX)
        }
        var lastFailure: Throwable? = null
        for (name in candidates) {
            val codec = try {
                if (name == null) {
                    MediaCodec.createDecoderByType(mime)
                } else {
                    MediaCodec.createByCodecName(name)
                }
            } catch (t: Throwable) {
                lastFailure = t
                null
            } ?: continue
            try {
                codec.configure(format, surface, null, 0)
                codec.start()
                return codec
            } catch (t: Throwable) {
                lastFailure = t
                releaseCodec(codec)
            }
        }
        throw lastFailure ?: IOException("nenhum decodificador H.264 disponível")
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
        const val INPUT_TIMEOUT_US = 8_000L
        const val OUTPUT_TIMEOUT_US = 4_000L
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val SOFTWARE_DECODER_C2 = "c2.android.avc.decoder"
        const val SOFTWARE_DECODER_OMX = "OMX.google.h264.decoder"

        /** At most ~400 ms of video at 60 FPS; keeps latency bounded. */
        const val MAX_PENDING = 24
        const val MAX_FEED_STAMPS = 240
        const val MAX_ENQUEUE_ATTEMPTS = 200
    }
}
