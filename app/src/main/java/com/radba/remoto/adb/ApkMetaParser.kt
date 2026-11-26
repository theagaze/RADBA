package com.radba.remoto.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

object ApkMetaParser {
    data class AxmlApp(val iconRes: Int?, val labelRes: Int?, val label: String?)

    private const val STRING_POOL = 0x0001
    private const val RESOURCE_MAP = 0x0180
    private const val START_ELEMENT = 0x0102
    private const val TABLE_PACKAGE = 0x0200
    private const val TABLE_TYPE = 0x0201
    private const val UTF8_FLAG = 0x100

    private const val TYPE_REFERENCE = 1
    private const val TYPE_STRING = 3

    private const val ATTR_ICON = 0x01010002
    private const val ATTR_LABEL = 0x01010001
    private const val ATTR_FOREGROUND = 0x01010199

    private data class Chunk(val type: Int, val headerSize: Int, val size: Int, val end: Long)

    private data class Attribute(
        val nameIndex: Int,
        val rawIndex: Int,
        val valueType: Int,
        val valueData: Int
    )

    private data class LengthAndPosition(val length: Long, val next: Long)

    private fun buffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun fits(offset: Long, size: Long, limit: Long): Boolean =
        offset >= 0 && size >= 0 && offset <= limit && size <= limit - offset

    private fun u8(buf: ByteBuffer, offset: Long): Int? {
        if (offset < 0 || offset >= buf.limit().toLong()) return null
        return buf.get(offset.toInt()).toInt() and 0xFF
    }

    private fun u16(buf: ByteBuffer, offset: Long): Int? {
        if (!fits(offset, 2, buf.limit().toLong())) return null
        return buf.getShort(offset.toInt()).toInt() and 0xFFFF
    }

    private fun u32(buf: ByteBuffer, offset: Long): Int? {
        if (!fits(offset, 4, buf.limit().toLong())) return null
        return buf.getInt(offset.toInt())
    }

    private fun unsigned(value: Int): Long = value.toLong() and 0xFFFFFFFFL

    private fun readChunk(buf: ByteBuffer, pos: Long, limit: Long): Chunk? {
        if (!fits(pos, 8, limit)) return null
        val type = u16(buf, pos) ?: return null
        val headerSize = u16(buf, pos + 2) ?: return null
        val sizeBits = u32(buf, pos + 4) ?: return null
        val size = unsigned(sizeBits)
        if (size < 8 || size > limit - pos) return null
        return Chunk(type, headerSize, size.toInt(), pos + size)
    }

    private fun copyBytes(buf: ByteBuffer, offset: Long, length: Long, limit: Long): ByteArray? {
        if (!fits(offset, length, limit)) return null
        val result = ByteArray(length.toInt())
        val start = offset.toInt()
        for (i in result.indices) result[i] = buf.get(start + i)
        return result
    }

    private fun readVarint(buf: ByteBuffer, offset: Long, limit: Long): LengthAndPosition? {
        var pos = offset
        var value = 0L
        var shift = 0
        while (shift < 32) {
            if (pos < 0 || pos >= limit) return null
            val byte = u8(buf, pos) ?: return null
            value = value or ((byte.toLong() and 0x7FL) shl shift)
            pos++
            if ((byte and 0x80) == 0) return LengthAndPosition(value, pos)
            shift += 7
        }
        return null
    }

    private fun decodePoolString(buf: ByteBuffer, start: Long, limit: Long, utf8: Boolean): String {
        if (start < 0 || start >= limit) return ""
        if (utf8) {
            val first = readVarint(buf, start, limit) ?: return ""
            val second = readVarint(buf, first.next, limit) ?: return ""
            val raw = copyBytes(buf, second.next, second.length, limit) ?: return ""
            return String(raw, Charsets.UTF_8)
        }
        val first = u16(buf, start) ?: return ""
        var pos = start + 2
        var length = first.toLong()
        if (length and 0x8000L != 0L) {
            if (pos >= limit) return ""
            val extra = u16(buf, pos) ?: return ""
            length = ((length and 0x7FFFL) shl 16) or extra.toLong()
            pos += 2
        }
        val raw = copyBytes(buf, pos, length * 2, limit) ?: return ""
        return String(raw, Charsets.UTF_16LE)
    }

    private fun readStringPool(buf: ByteBuffer, off: Int, end: Int): List<String> {
        val limit = minOf(end.toLong(), buf.limit().toLong())
        if (off.toLong() + 28 > limit) return emptyList()
        val stringCount = u32(buf, off + 8L) ?: return emptyList()
        val flags = u32(buf, off + 16L) ?: return emptyList()
        val stringsStart = u32(buf, off + 20L) ?: return emptyList()
        val count = unsigned(stringCount)
        val offsetsPos = off + 28L
        if (count > (limit - offsetsPos) / 4) return emptyList()

        val base = off.toLong() + unsigned(stringsStart)
        val utf8 = (flags and UTF8_FLAG) != 0
        val strings = ArrayList<String>(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var i = 0L
        while (i < count) {
            val relative = u32(buf, offsetsPos + i * 4) ?: -1
            strings.add(decodePoolString(buf, base + unsigned(relative), limit, utf8))
            i++
        }
        return strings
    }

    private fun readResourceIds(buf: ByteBuffer, pos: Long, end: Long): List<Int> {
        if (end - (pos + 8) < 4) return emptyList()
        val count = (end - (pos + 8)) / 4
        val ids = ArrayList<Int>(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var i = 0L
        while (i < count) {
            ids.add(u32(buf, pos + 8 + i * 4) ?: 0)
            i++
        }
        return ids
    }

    private fun stringAt(strings: List<String>, index: Int): String? =
        if (index >= 0 && index < strings.size) strings[index] else null

    private fun resourceAt(ids: List<Int>, index: Int): Int =
        if (index >= 0 && index < ids.size) ids[index] else 0

    private fun readAttribute(buf: ByteBuffer, pos: Long, limit: Long): Attribute? {
        if (!fits(pos, 20, limit)) return null
        val nameIndex = u32(buf, pos + 4) ?: return null
        val rawIndex = u32(buf, pos + 8) ?: return null
        val valueType = u8(buf, pos + 15) ?: return null
        val valueData = u32(buf, pos + 16) ?: return null
        return Attribute(nameIndex, rawIndex, valueType, valueData)
    }

    fun parseAppMeta(manifest: ByteArray): AxmlApp {
        val buf = buffer(manifest)
        val limit = buf.limit().toLong()
        if (limit < 8) return AxmlApp(null, null, null)

        var pos = 8L
        var strings = emptyList<String>()
        var resourceIds = emptyList<Int>()
        var iconRes: Int? = null
        var labelRes: Int? = null
        var label: String? = null

        while (fits(pos, 8, limit)) {
            val chunk = readChunk(buf, pos, limit) ?: break
            when (chunk.type) {
                STRING_POOL -> strings = readStringPool(buf, pos.toInt(), chunk.end.toInt())
                RESOURCE_MAP -> resourceIds = readResourceIds(buf, pos, chunk.end)
                START_ELEMENT -> {
                    if (fits(pos, 36, limit) && stringAt(strings, u32(buf, pos + 20) ?: -1) == "application") {
                        val attrStart = u16(buf, pos + 24) ?: 0
                        val attrSize = u16(buf, pos + 26) ?: 0
                        val attrCount = u16(buf, pos + 28) ?: 0
                        val attrBase = pos + 16 + attrStart.toLong()
                        var i = 0
                        while (i < attrCount) {
                            val attrPos = attrBase + i.toLong() * attrSize.toLong()
                            val attr = if (fits(attrPos, 20, chunk.end)) {
                                readAttribute(buf, attrPos, chunk.end) ?: break
                            } else {
                                break
                            }
                            val resourceId = resourceAt(resourceIds, attr.nameIndex)
                            val name = stringAt(strings, attr.nameIndex) ?: ""
                            when {
                                resourceId == ATTR_ICON || name == "icon" -> {
                                    if (attr.valueType == TYPE_REFERENCE) iconRes = attr.valueData
                                }
                                resourceId == ATTR_LABEL || name == "label" -> {
                                    if (attr.valueType == TYPE_REFERENCE) {
                                        labelRes = attr.valueData
                                    } else if (attr.valueType == TYPE_STRING) {
                                        stringAt(strings, attr.rawIndex)?.let { label = it }
                                    }
                                }
                            }
                            i++
                        }
                    }
                }
            }
            pos = chunk.end
        }
        return AxmlApp(iconRes, labelRes, label)
    }

    fun parseForeground(adaptiveXml: ByteArray): Int? {
        val buf = buffer(adaptiveXml)
        val limit = buf.limit().toLong()
        if (limit < 8) return null

        var pos = 8L
        var strings = emptyList<String>()
        var resourceIds = emptyList<Int>()

        while (fits(pos, 8, limit)) {
            val chunk = readChunk(buf, pos, limit) ?: break
            when (chunk.type) {
                STRING_POOL -> strings = readStringPool(buf, pos.toInt(), chunk.end.toInt())
                RESOURCE_MAP -> resourceIds = readResourceIds(buf, pos, chunk.end)
                START_ELEMENT -> {
                    if (fits(pos, 36, limit) && stringAt(strings, u32(buf, pos + 20) ?: -1) == "foreground") {
                        val attrStart = u16(buf, pos + 24) ?: 0
                        val attrSize = u16(buf, pos + 26) ?: 0
                        val attrCount = u16(buf, pos + 28) ?: 0
                        val attrBase = pos + 16 + attrStart.toLong()
                        var i = 0
                        while (i < attrCount) {
                            val attrPos = attrBase + i.toLong() * attrSize.toLong()
                            val attr = if (fits(attrPos, 20, chunk.end)) {
                                readAttribute(buf, attrPos, chunk.end) ?: break
                            } else {
                                break
                            }
                            val resourceId = resourceAt(resourceIds, attr.nameIndex)
                            val name = stringAt(strings, attr.nameIndex) ?: ""
                            if ((resourceId == ATTR_FOREGROUND || name == "drawable") &&
                                attr.valueType == TYPE_REFERENCE
                            ) {
                                return attr.valueData
                            }
                            i++
                        }
                    }
                }
            }
            pos = chunk.end
        }
        return null
    }

    fun parseArsc(arsc: ByteArray, targetIds: Set<Int>): Map<Int, List<String>> {
        if (arsc.size < 12 || targetIds.isEmpty()) return emptyMap()
        val buf = buffer(arsc)
        val limit = buf.limit().toLong()
        val headerSize = u16(buf, 2)?.toLong() ?: return emptyMap()
        if (headerSize < 12 || headerSize > limit) return emptyMap()

        var pos = headerSize
        var globalStrings = emptyList<String>()
        val result = LinkedHashMap<Int, MutableList<String>>()

        while (fits(pos, 8, limit)) {
            val chunk = readChunk(buf, pos, limit) ?: break
            when (chunk.type) {
                STRING_POOL -> {
                    if (globalStrings.isEmpty()) {
                        globalStrings = readStringPool(buf, pos.toInt(), chunk.end.toInt())
                    }
                }
                TABLE_PACKAGE -> {
                    if (!fits(pos, 12, chunk.end)) {
                        pos = chunk.end
                        continue
                    }
                    val packageId = u32(buf, pos + 8) ?: 0
                    var ppos = pos + chunk.headerSize.toLong()
                    val pend = chunk.end
                    while (fits(ppos, 8, pend)) {
                        val typeChunk = readChunk(buf, ppos, pend) ?: break
                        if (typeChunk.type == TABLE_TYPE && fits(ppos, 20, typeChunk.end)) {
                            val typeId = u8(buf, ppos + 8) ?: 0
                            val entryCountBits = u32(buf, ppos + 12) ?: 0
                            val entriesStartBits = u32(buf, ppos + 16) ?: 0
                            val entryCount = unsigned(entryCountBits)
                            val offsetsPos = ppos + typeChunk.headerSize.toLong()
                            if (fits(offsetsPos, entryCount * 4, pend)) {
                                val entriesBase = ppos + unsigned(entriesStartBits)
                                var index = 0L
                                while (index < entryCount) {
                                    val offsetBits = u32(buf, offsetsPos + index * 4) ?: -1
                                    if (offsetBits != -1) {
                                        val resourceId = (packageId shl 24) or
                                            (typeId shl 16) or index.toInt()
                                        if (targetIds.contains(resourceId)) {
                                            val entryPos = entriesBase + unsigned(offsetBits)
                                            if (fits(entryPos, 8, pend)) {
                                                val entrySize = u16(buf, entryPos) ?: 0
                                                val flags = u16(buf, entryPos + 2) ?: 1
                                                val valuePos = entryPos + entrySize.toLong()
                                                if ((flags and 1) == 0 &&
                                                    fits(valuePos, 8, pend)
                                                ) {
                                                    val valueType = u8(buf, valuePos + 3) ?: 0
                                                    val valueData = u32(buf, valuePos + 4) ?: 0
                                                    if (valueType == TYPE_STRING) {
                                                        stringAt(globalStrings, valueData)?.let { value ->
                                                            result.getOrPut(resourceId) { ArrayList() }.add(value)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    index++
                                }
                            }
                        }
                        ppos = typeChunk.end
                    }
                }
            }
            pos = chunk.end
        }
        return result.mapValues { it.value.toList() }
    }

    fun isImage(bytes: ByteArray): Boolean {
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return true
        }
        return bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() &&
            bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() &&
            bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() &&
            bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() &&
            bytes[11] == 0x50.toByte()
    }
}
