package com.radba.remoto.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.Closeable
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

    private val packets = ArrayDeque<EncodedPacket>()
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
        synchronized(lock) { lock.notifyAll() }
    }

    /** Drop non-intra frames until the next key frame (useful after reattach). */
    fun expectKeyFrame() {
        expectKeyFrame = true
    }

    /** Queue one encoded access unit. [flags] use MediaCodec.BUFFER_FLAG_*. */
    fun feed(data: ByteArray, flags: Int, ptsUs: Long) {
        if (closed) return
        val isConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (expectKeyFrame && !isConfig && !isKey) return
        if (isKey) expectKeyFrame = false
        synchronized(lock) {
            if (packets.size >= MAX_PENDING) packets.removeFirst()
            packets.addLast(EncodedPacket(data.copyOf(), flags, ptsUs))
            lock.notifyAll()
        }
    }

    /** Signal that no more encoded data will arrive. */
    fun signalEndOfStream() {
        if (closed) return
        endOfStream = true
        synchronized(lock) { lock.notifyAll() }
    }

    override fun close() {
        if (closed) return
        closed = true
        synchronized(lock) { lock.notifyAll() }
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
                        codec = createCodec(surfaceRef, width, height, mimeType)
                        configured.value = true
                        error.value = ""
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
                            val buffer: ByteBuffer = activeCodec.getInputBuffer(index)
                                ?: throw IllegalStateException("input buffer unavailable")
                            buffer.clear()
                            val count = minOf(packet.data.size, buffer.remaining())
                            buffer.put(packet.data, 0, count)
                            activeCodec.queueInputBuffer(index, 0, count, packet.ptsUs, packet.flags)
                        } catch (t: Throwable) {
                            handleCodecFailure(t)
                            releaseCodec(codec)
                            codec = null
                        }
                    } else {
                        synchronized(lock) { packets.addFirst(packet) }
                        waitForWork(1L)
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
                    val configOnly = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val render = !closed && surfaceRef != null && !configOnly && info.size > 0
                    try {
                        codec.releaseOutputBuffer(index, render)
                    } catch (t: Throwable) {
                        handleCodecFailure(t)
                        return reachedEos
                    }
                    if (!configOnly && info.size > 0 && !firstFrame.value) firstFrame.value = true
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
        var codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, surface, null, 0)
            codec.start()
            return codec
        } catch (t: Throwable) {
            releaseCodec(codec)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                codec = MediaCodec.createDecoderByType(mime)
                try {
                    codec.configure(format, surface, null, MediaCodec.CONFIGURE_FLAG_USE_BLOCK_MODEL)
                    codec.start()
                    return codec
                } catch (blockFailure: Throwable) {
                    releaseCodec(codec)
                    throw blockFailure
                }
            }
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
                lock.wait(timeoutMs)
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
    }
}
