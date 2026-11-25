package com.radba.remoto.util

import com.radba.remoto.data.Prefs
import java.util.Locale
import kotlin.math.roundToInt

val ALLOWED_ROOTS = listOf("/sdcard", "/storage/emulated/0", "/mnt/sdcard")

fun clamp(value: Float, low: Float = 0f, high: Float = 100f): Float =
    value.coerceIn(low, high)

fun clampInt(value: Int, low: Int = 0, high: Int = 100): Int = value.coerceIn(low, high)

fun trunc(value: String?, max: Int): String {
    val s = value ?: return ""
    return if (s.length <= max) s else s.take(max - 1) + "…"
}

fun human(bytes: Number): String {
    var n = bytes.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB")
    var index = 0
    while (n >= 1024 && index < units.lastIndex) {
        n /= 1024.0
        index++
    }
    return if (index == 0) String.format(Locale.US, "%.0f B", n)
    else String.format(Locale.US, "%.1f %s", n, units[index])
}

fun mmss(seconds: Double): String {
    val total = maxOf(0, seconds.toInt())
    return String.format(Locale.US, "%02d:%02d", total / 60, total % 60)
}

fun shellQuote(value: Any?): String = "'" + value.toString().replace("'", "'\\''") + "'"

fun inputTextCommand(text: String): String = "input text ${shellQuote(text.replace(" ", "%s"))}"

fun normalizeRemote(path: String?): String? {
    if (path.isNullOrBlank()) return null
    val parts = path.trim().split("/").filter { it.isNotEmpty() && it != "." }
    val stack = ArrayDeque<String>()
    for (part in parts) {
        if (part == "..") {
            if (stack.isEmpty()) return null
            stack.removeLast()
        } else {
            stack.addLast(part)
        }
    }
    val result = "/" + stack.joinToString("/")
    if (!ALLOWED_ROOTS.any { result == it || result.startsWith("$it/") }) return null
    return result
}

fun isInternalRoot(path: String): Boolean = ALLOWED_ROOTS.any { path.trimEnd('/') == it }

val HOST_REGEX = Regex("^[A-Za-z0-9._-]+(:\\d{1,5})?$")

fun sanitizeHost(raw: String): String? {
    var host = raw.trim()
    if (host.isEmpty()) return "192.168.100.12:5555"
    if (!HOST_REGEX.matches(host)) return null
    if (!host.contains(":")) host += ":5555"
    return host
}

fun splitHost(input: String): Pair<String, Int> {
    val idx = input.lastIndexOf(':')
    if (idx <= 0) return input to 5555
    val port = input.substring(idx + 1).toIntOrNull() ?: 5555
    return input.substring(0, idx) to port
}

fun fpsServerValue(mode: String): Int = when (mode) {
    "60" -> 60
    "30" -> 30
    "15" -> 15
    else -> 60
}

fun uiFrameDelayMs(mode: String): Int = when (mode) {
    "60" -> 16
    "30" -> 33
    "15" -> 66
    else -> 16
}

data class ResolutionProfile(val maxSize: Int, val bitrate: Int, val outMaxWidth: Int, val outMaxHeight: Int)

fun resolutionProfile(mode: String): ResolutionProfile = when (mode) {
    "nativa" -> ResolutionProfile(0, 8_000_000, 1920, 1080)
    "480" -> ResolutionProfile(854, 2_500_000, 854, 480)
    else -> ResolutionProfile(1280, 5_000_000, 1280, 720)
}

fun encoderPlan(prefs: Prefs): List<Pair<String?, Boolean>> {
    val pref = prefs.videoEncoder.trim()
    if (pref.equals("hardware (padrão)", ignoreCase = true) ||
        pref.equals("hardware", ignoreCase = true) ||
        pref.equals("padrao", ignoreCase = true)
    ) return listOf(null to false)
    if (pref.isNotEmpty() && pref != "auto") {
        val soft = pref.startsWith("c2.") || pref.startsWith("OMX.google.")
        if (pref.startsWith("c2.")) {
            return listOf(pref to true, "OMX.google.h264.encoder" to true, null to false)
        }
        return listOf(pref to soft)
    }
    if (prefs.rootFixVideo) {
        return listOf(
            "c2.android.avc.encoder" to true,
            "OMX.google.h264.encoder" to true,
            null to false,
        )
    }
    return listOf(null to false)
}

fun encoderLabel(encoder: String): String = when {
    encoder == "auto" -> "Auto (software com root)"
    encoder.equals("hardware (padrão)", ignoreCase = true) -> "Hardware (padrão)"
    else -> encoder
}

fun palette(key: String): Long {
    val colors = longArrayOf(
        0xFF7DA7FF, 0xFF5FE3A6, 0xFFFFB86B, 0xFFFF6B7A,
        0xFFB98CFF, 0xFF6FD6FF, 0xFFFFD95A, 0xFF8E97FF,
    )
    return colors[kotlin.math.abs(key.sumOf { it.code }) % colors.size]
}

fun appTitle(packageName: String): String =
    packageName.substringAfterLast('.').replace('_', ' ').replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }

fun shellStripExitCode(raw: ByteArray): ShellDecoded {
    if (raw.size >= 2 && raw[raw.size - 2] == 1.toByte()) {
        val code = raw[raw.size - 1].toInt() and 0x7F
        return ShellDecoded(raw.copyOf(raw.size - 2), code)
    }
    return ShellDecoded(raw, 0)
}

data class ShellDecoded(val data: ByteArray, val exitCode: Int)

fun volumeLevel(pct: Int, max: Int): Int =
    (clampInt(pct) / 100f * (if (max > 0) max else 15)).roundToInt()
