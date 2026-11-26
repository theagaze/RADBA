package com.radba.remoto.adb

import com.radba.remoto.data.FileEntry
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

private const val SYNC_TIMEOUT_MS = 60_000L
private const val MAX_SYNC_PAYLOAD = 64 * 1024
private const val MAX_RESPONSE_PAYLOAD = 32 * 1024 * 1024

internal data class SyncStat(val mode: Long, val size: Long, val mtime: Long)

/** Raw ADB `sync:` protocol (v1 messages) used for stat/list/push/pull. */
internal object AdbSync {
    suspend fun stat(adb: AdbManager, path: String): SyncStat? = withSyncStream(adb) { stream ->
        sendRequest(stream, "STAT", path.toByteArray(StandardCharsets.UTF_8))
        val header = readPacketHeader(stream) ?: return@withSyncStream null
        if (header.id != "STAT") return@withSyncStream null
        val fields = readExact(stream, 12) ?: return@withSyncStream null
        val buffer = ByteBuffer.wrap(fields).order(ByteOrder.LITTLE_ENDIAN)
        SyncStat(
            mode = buffer.int.toUnsignedLong(),
            size = buffer.int.toUnsignedLong(),
            mtime = buffer.int.toUnsignedLong(),
        )
    }

    suspend fun push(adb: AdbManager, path: String, mode: Int, bytes: ByteArray) =
        withSyncStream(adb) { stream ->
            val spec = "$path,$mode".toByteArray(StandardCharsets.UTF_8)
            sendRequest(stream, "SEND", spec)

            var offset = 0
            while (offset < bytes.size) {
                val length = minOf(MAX_SYNC_PAYLOAD, bytes.size - offset)
                sendRequest(stream, "DATA", bytes.copyOfRange(offset, offset + length))
                offset += length
            }

            val done = ByteArray(8)
            System.arraycopy("DONE".toByteArray(StandardCharsets.US_ASCII), 0, done, 0, 4)
            writeLe32(done, 4, (System.currentTimeMillis() / 1000L).toInt())
            stream.write(done, 0, done.size)
            stream.flush()

            val header = readPacketHeader(stream)
                ?: throw IOException("sem resposta do ADB ao enviar $path")
            when (header.id) {
                "OKAY" -> Unit
                "FAIL" -> throw IOException(readFailure(stream, header.value, "falha ao enviar $path"))
                else -> throw IOException("resposta inesperada do ADB: ${header.id}")
            }
        }

    suspend fun pull(adb: AdbManager, path: String, local: File) = withSyncStream(adb) { stream ->
        val parent = local.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw IOException("não foi possível criar ${parent.absolutePath}")
        }
        // Stream DATA packets straight to disk: buffering a whole remote file
        // in memory can exhaust the heap on large videos or logs.
        FileOutputStream(local).use { output ->
            sendRequest(stream, "RECV", path.toByteArray(StandardCharsets.UTF_8))
            var done = false
            while (!done) {
                val header = readPacketHeader(stream)
                    ?: throw IOException("sem resposta do ADB ao ler $path")
                when (header.id) {
                    "DATA" -> {
                        if (header.value < 0 || header.value > MAX_SYNC_PAYLOAD) {
                            throw IOException("tamanho inválido ao ler $path")
                        }
                        val payload = readExact(stream, header.value)
                            ?: throw IOException("dados truncados ao ler $path")
                        output.write(payload)
                    }
                    "DONE" -> done = true
                    "FAIL" -> throw IOException(readFailure(stream, header.value, "falha ao ler $path"))
                    else -> throw IOException("resposta inesperada do ADB: ${header.id}")
                }
            }
            output.flush()
        }
    }

    suspend fun list(adb: AdbManager, path: String): List<FileEntry> = withSyncStream(adb) { stream ->
        sendRequest(stream, "LIST", path.toByteArray(StandardCharsets.UTF_8))
        val entries = mutableListOf<FileEntry>()
        while (true) {
            val header = readPacketHeader(stream) ?: break
            when (header.id) {
                "DENT" -> {
                    val fields = readExact(stream, 12) ?: break
                    val buffer = ByteBuffer.wrap(fields).order(ByteOrder.LITTLE_ENDIAN)
                    val mode = buffer.int.toUnsignedLong()
                    val size = buffer.int.toUnsignedLong()
                    buffer.int // mtime
                    val nameLength = header.value
                    if (nameLength <= 0 || nameLength > MAX_RESPONSE_PAYLOAD) break
                    val nameBytes = readExact(stream, nameLength) ?: break
                    val name = String(nameBytes, StandardCharsets.UTF_8)
                    if (name.isEmpty() || name == "." || name == ".." || name.contains('/')) continue
                    val fullPath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                    entries.add(
                        FileEntry(
                            name = name,
                            path = fullPath,
                            isDir = (mode and 0xF000L) == 0x4000L,
                            size = size,
                        ),
                    )
                }
                "DONE" -> break
                "FAIL" -> throw IOException(readFailure(stream, header.value, "falha ao listar $path"))
                else -> break
            }
        }
        entries
    }
}

/** Reads a complete ADB stream as raw bytes; returns null on open/timeout failure. */
internal suspend fun readStreamToBytes(
    adb: AdbManager,
    destination: String,
    timeoutMs: Long,
): ByteArray? = withContext(Dispatchers.IO) {
    val stream = try {
        adb.openStream(destination)
    } catch (error: Exception) {
        return@withContext null
    }
    val expired = AtomicBoolean(false)
    val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val watchdog = watchdogScope.launch {
        delay(timeoutMs.coerceAtLeast(1L))
        expired.set(true)
        closeQuietly(stream)
    }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    try {
        while (true) {
            val count = stream.read(buffer, 0, buffer.size)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // The daemon closes the stream at EOF or the watchdog cancels a slow read.
    } finally {
        watchdog.cancel()
        watchdogScope.cancel()
        closeQuietly(stream)
    }
    if (expired.get()) null else output.toByteArray()
}

internal fun closeQuietly(stream: AdbStream?) {
    if (stream == null) return
    runCatching { if (!stream.isClosed) stream.close() }
}

private suspend fun <T> withSyncStream(
    adb: AdbManager,
    block: suspend (AdbStream) -> T,
): T = withContext(Dispatchers.IO) {
    val stream = adb.openStream("sync:")
    val watchdogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val watchdog = watchdogScope.launch {
        delay(SYNC_TIMEOUT_MS)
        closeQuietly(stream)
    }
    try {
        block(stream)
    } finally {
        watchdog.cancel()
        watchdogScope.cancel()
        closeQuietly(stream)
    }
}

private data class PacketHeader(val id: String, val value: Int)

private fun sendRequest(stream: AdbStream, id: String, payload: ByteArray) {
    val request = ByteArray(8 + payload.size)
    val idBytes = id.toByteArray(StandardCharsets.US_ASCII)
    System.arraycopy(idBytes, 0, request, 0, 4)
    writeLe32(request, 4, payload.size)
    System.arraycopy(payload, 0, request, 8, payload.size)
    stream.write(request, 0, request.size)
    stream.flush()
}

private fun readPacketHeader(stream: AdbStream): PacketHeader? {
    val bytes = readExact(stream, 8) ?: return null
    val id = String(bytes, 0, 4, StandardCharsets.US_ASCII)
    return PacketHeader(id, readLe32(bytes, 4))
}

private fun readFailure(stream: AdbStream, length: Int, fallback: String): String {
    if (length <= 0 || length > MAX_RESPONSE_PAYLOAD) return fallback
    val payload = readExact(stream, length) ?: return fallback
    return messageOf(payload, fallback)
}

private fun readExact(stream: AdbStream, size: Int): ByteArray? {
    if (size < 0 || size > MAX_RESPONSE_PAYLOAD) return null
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = try {
            stream.read(result, offset, size - offset)
        } catch (error: IOException) {
            return null
        }
        if (count <= 0) return null
        offset += count
    }
    return result
}

private fun writeLe32(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

private fun readLe32(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8) or
        ((source[offset + 2].toInt() and 0xFF) shl 16) or
        ((source[offset + 3].toInt() and 0xFF) shl 24)

private fun messageOf(payload: ByteArray, fallback: String): String {
    val text = String(payload, StandardCharsets.UTF_8).trim()
    return text.ifEmpty { fallback }
}

private fun Int.toUnsignedLong(): Long = this.toLong() and 0xFFFF_FFFFL
