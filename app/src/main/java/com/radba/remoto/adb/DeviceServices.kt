package com.radba.remoto.adb

import com.radba.remoto.data.AppInfo
import com.radba.remoto.data.FileEntry
import com.radba.remoto.data.MediaInfo
import com.radba.remoto.data.ProcessInfo
import com.radba.remoto.data.VolumeInfo
import com.radba.remoto.util.appTitle
import com.radba.remoto.util.clampInt
import com.radba.remoto.util.normalizeRemote
import com.radba.remoto.util.shellQuote
import com.radba.remoto.util.shellStripExitCode
import com.radba.remoto.util.trunc
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Device-level operations built on top of ADB shell commands. */
class DeviceServices(private val adb: AdbManager) {
    private var encoderCacheHost: String? = null
    private var encoderCache: List<String>? = null

    suspend fun getVolume(): VolumeInfo? {
        val result = adb.shell("cmd media_session volume --stream 3 --get", 20_000)
        val match = VOLUME_REGEX.find(result.stdout) ?: return null
        val level = match.groupValues[1].toIntOrNull() ?: return null
        val max = match.groupValues[3].toIntOrNull() ?: return null
        return VolumeInfo(level, max)
    }

    suspend fun setVolume(level: Int): Boolean {
        val result = adb.shell(
            "cmd media_session volume --stream 3 --set ${maxOf(0, level)}",
            20_000,
        )
        return result.ok
    }

    suspend fun applyVirtualBrightness(pct: Int, warmth: Int): Result<Unit> {
        val percent = clampInt(pct, 10, 100)
        val warm = clampInt(warmth, 0, 100)
        val command = if (percent >= 100 && warm == 0) {
            "service call SurfaceFlinger 1015 i32 0"
        } else {
            val ratio = percent / 100.0
            val w = warm / 100.0
            val red = ratio
            val green = ratio * (1.0 - 0.14 * w)
            val blue = ratio * (1.0 - 0.36 * w)
            String.format(
                Locale.US,
                "service call SurfaceFlinger 1008 i32 1 && " +
                    "service call SurfaceFlinger 1015 i32 1 f %.3f f 0 f 0 f 0 " +
                    "f 0 f %.3f f 0 f 0 f 0 f 0 f %.3f f 0 f 0 f 0 f 0 f 1",
                red,
                green,
                blue,
            )
        }
        return resultOf(adb.rootShell(command, 12_000))
    }

    suspend fun setHwOverlays(disable: Boolean): Result<Unit> {
        val value = if (disable) 1 else 0
        val result = adb.rootShell("service call SurfaceFlinger 1008 i32 $value", 12_000)
        return resultOf(result)
    }

    suspend fun setSoftwareVideoDecode(enable: Boolean): Result<Unit> {
        val target = if (enable) "false" else "true"
        val current = adb.shell("getprop vendor.media.c2.vdec.support", 6_000)
            .stdout.trim().lowercase(Locale.ROOT)
        if (current == target) return Result.success(Unit)

        val command = "setprop vendor.media.c2.vdec.support $target && " +
            "setprop ctl.restart android-hardware-media-c2-hal-1-2 && " +
            "setprop ctl.restart media"
        val result = adb.rootShell(command, 15_000)
        val checked = resultOf(result)
        if (checked.isSuccess) delay(1_500)
        return checked
    }

    suspend fun applyVideoFix(disable: Boolean): Result<Unit> = setHwOverlays(disable)

    suspend fun listLaunchableApps(): List<AppInfo> {
        val apps = LinkedHashMap<String, AppInfo>()
        for (category in listOf("LAUNCHER", "LEANBACK_LAUNCHER")) {
            val command = "cmd package query-activities --brief -a android.intent.action.MAIN " +
                "-c android.intent.category.$category"
            val result = adb.shell(command, 30_000)
            for (rawLine in result.stdout.lineSequence()) {
                val line = rawLine.trim()
                if (line.isEmpty() || '/' !in line || ' ' in line) continue
                if (line.startsWith("Activity") || line.startsWith("priority") || line.startsWith("cmd:")) continue
                val packageName = line.substringBefore('/')
                if (packageName.isEmpty() || apps.containsKey(packageName)) continue
                apps[packageName] = AppInfo(
                    packageName = packageName,
                    activity = line,
                    title = appTitle(packageName),
                )
            }
        }
        return apps.values.sortedBy { it.packageName }
    }

    suspend fun listProcesses(): List<ProcessInfo> {
        val result = adb.shell("ps -A -o PID,NAME 2>/dev/null || ps", 30_000)
        val processes = mutableListOf<ProcessInfo>()
        for (line in result.stdout.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val pid = parts[0].toIntOrNull() ?: continue
            val name = parts.last()
            if (name == "NAME" || name == "CMD" || name.startsWith("[")) continue
            processes.add(ProcessInfo(pid, name))
        }
        return processes.sortedBy { it.pid }
    }

    suspend fun listDir(path: String): List<FileEntry> {
        val normalized = normalizeRemote(path) ?: return emptyList()
        val directory = if (normalized.endsWith("/")) normalized else "$normalized/"
        val result = adb.shell("ls -la ${shellQuote(directory)} 2>&1", 60_000)
        val entries = mutableListOf<FileEntry>()
        for (rawLine in result.stdout.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isEmpty() || line.startsWith("total ") || line.startsWith("ls:")) continue
            val parts = line.trim().split(Regex("\\s+"), limit = 8)
            if (parts.size < 8) continue
            val mode = parts[0]
            val size = parts[4].toLongOrNull() ?: 0L
            val rawName = parts[7]
            val name = rawName.substringBefore(" -> ").trim()
            if (name.isEmpty() || name == "." || name == "..") continue
            val fullPath = normalized.trimEnd('/') + "/" + name
            entries.add(FileEntry(name, fullPath, mode.startsWith("d"), size))
        }
        return entries.sortedWith(compareBy<FileEntry> { !it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) })
    }

    suspend fun systemInfo(): String {
        val result = adb.shell(
            "getprop ro.product.model; getprop ro.build.version.release; df -h /sdcard | tail -n 1",
            20_000,
        )
        val lines = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val model = lines.getOrNull(0)?.ifEmpty { "?" } ?: "?"
        val android = lines.getOrNull(1)?.ifEmpty { "?" } ?: "?"
        val storage = lines.getOrNull(2)?.let { line ->
            val fields = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (fields.size >= 4) "Armazenamento livre: ${fields[3]} de ${fields[1]}"
            else "Armazenamento livre: $line"
        } ?: "Armazenamento livre: ?"
        return "Modelo: $model\nAndroid: $android\n$storage"
    }

    suspend fun screenshot(): ByteArray? {
        val direct = readStreamToBytes(adb, "exec-out:screencap -p", 25_000)
        if (direct.isPng()) return direct

        val shellRaw = readStreamToBytes(adb, "shell:screencap -p", 25_000) ?: return null
        val withoutExit = shellStripExitCode(shellRaw).data
        if (withoutExit.isPng()) return withoutExit

        if (withoutExit.size > 4) {
            val header = ByteBuffer.wrap(withoutExit, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val little = ByteBuffer.wrap(withoutExit, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = withoutExit.copyOfRange(4, withoutExit.size)
            if (header > 0 && header == body.size && body.isPng()) return body
            if (little > 0 && little == body.size && body.isPng()) return body
        }
        return null
    }

    suspend fun listAvcEncoders(force: Boolean = false): List<String> {
        val currentHost = adb.host.value
        if (!force && encoderCacheHost == currentHost && encoderCache != null) {
            return encoderCache ?: emptyList()
        }

        val found = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        fun add(name: String) {
            val clean = name.trim().trim('\'', '"')
            if (clean.isEmpty() || clean in seen) return
            val lower = clean.lowercase(Locale.ROOT)
            if (lower.contains("decod") || lower.endsWith(".dec")) return
            seen.add(clean)
            found.add(clean)
        }

        val scrcpy = adb.shell(
            "CLASSPATH=/data/local/tmp/scrcpy-server.jar app_process / " +
                "com.genymobile.scrcpy.Server 2.7 list_encoders=true",
            12_000,
        )
        ENCODER_REGEX.findAll(scrcpy.stdout).forEach { add(it.groupValues[1]) }

        if (found.isEmpty()) {
            val codecs = adb.shell("dumpsys media.codec", 15_000)
            CODEC_REGEX.findAll(codecs.stdout).forEach { add(it.value) }
        }

        val result = found.toList()
        encoderCacheHost = currentHost
        encoderCache = result
        return result
    }

    suspend fun mediaInfo(): MediaInfo {
        val output = adb.shell("dumpsys media_session; echo __UPTIME__; cat /proc/uptime", 20_000).stdout
        val parts = output.split("__UPTIME__", limit = 2)
        val text = parts[0]
        val uptime = parts.getOrNull(1)?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toDoubleOrNull()

        data class State(val state: Int, val position: Long, val speed: Double, val updated: Long)
        val states = mutableListOf<State>()
        PLAYBACK_LINE_REGEX.findAll(text).forEach { lineMatch ->
            val line = lineMatch.value
            val state = STATE_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            val position = POSITION_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: return@forEach
            val speed = SPEED_REGEX.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1.0
            val updated = UPDATED_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            states.add(State(state, position, speed, updated))
        }
        val chosen = states.firstOrNull { it.state == 3 } ?: states.firstOrNull()
        var playing = false
        var position = 0.0
        if (chosen != null) {
            playing = chosen.state == 3
            position = maxOf(0L, chosen.position) / 1000.0
            if (playing && uptime != null && chosen.updated > 0L) {
                val nowMs = uptime * 1_000.0
                val elapsed = (nowMs - chosen.updated) / 1_000.0
                if (elapsed in 0.0..<600.0) position += elapsed * chosen.speed
            }
        }
        val duration = DURATION_REGEX.find(text)?.groupValues?.get(1)?.toLongOrNull()
            ?.div(1_000.0) ?: 0.0
        val title = DESCRIPTION_REGEX.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val packageName = PACKAGE_REGEX.find(text)?.groupValues?.get(1).orEmpty()
        return MediaInfo(
            playing = playing,
            position = position,
            duration = duration,
            title = title,
            packageName = packageName,
        )
    }

    private fun resultOf(result: ShellResult): Result<Unit> {
        if (result.ok && sfOk(result)) return Result.success(Unit)
        return Result.failure(IOException(sfError(result)))
    }

    private fun sfOk(result: ShellResult): Boolean {
        if (!result.ok) return false
        val text = (result.stdout + " " + result.stderr).lowercase(Locale.ROOT)
        return listOf("permission", "denied", "exception", "error", "not found", "failed")
            .none { text.contains(it) }
    }

    private fun sfError(result: ShellResult): String {
        val message = result.stderr.ifEmpty { result.stdout }.trim()
        return if (message.isNotEmpty()) trunc(message, 100) else "código ${result.exitCode}"
    }

    private companion object {
        val VOLUME_REGEX = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)\]""")
        val ENCODER_REGEX = Regex("""--video-codec=h264\s+--video-encoder=(\S+)""")
        val CODEC_REGEX = Regex(
            """\b(?:c2|OMX)\.[A-Za-z0-9_.-]*(?:avc|h264|264)[A-Za-z0-9_.-]*\b""",
            RegexOption.IGNORE_CASE,
        )
        val PLAYBACK_LINE_REGEX = Regex("""PlaybackState \{[^\n]*""")
        val STATE_REGEX = Regex("""state=(\d+)""")
        val POSITION_REGEX = Regex("""(?<!buffered )position=(-?\d+)""")
        val SPEED_REGEX = Regex("""speed=(-?[\d.]+)""")
        val UPDATED_REGEX = Regex("""updated=(\d+)""")
        val DURATION_REGEX = Regex("""(?:android\.media\.metadata\.DURATION|duration)=(\d+)""")
        val DESCRIPTION_REGEX = Regex("""description=([^\n,]+)""")
        val PACKAGE_REGEX = Regex("""package=([A-Za-z0-9_.]+)""")
    }
}

private val PNG_MAGIC = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
)

private fun ByteArray?.isPng(): Boolean {
    if (this == null || size < PNG_MAGIC.size) return false
    for (index in PNG_MAGIC.indices) {
        if (this[index] != PNG_MAGIC[index]) return false
    }
    return true
}
