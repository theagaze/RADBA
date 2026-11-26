package com.radba.remoto.adb

import com.radba.remoto.data.AppInfo
import com.radba.remoto.data.AppMeta
import com.radba.remoto.data.DeviceInfo
import com.radba.remoto.data.FileEntry
import com.radba.remoto.data.PerfStats
import com.radba.remoto.data.ProcessInfo
import com.radba.remoto.data.ScreenshotResult
import com.radba.remoto.data.StorageStats
import com.radba.remoto.data.VolumeInfo
import com.radba.remoto.util.appTitle
import com.radba.remoto.util.clampInt
import com.radba.remoto.util.normalizeRemote
import com.radba.remoto.util.shellQuote
import com.radba.remoto.util.shellStripExitCode
import com.radba.remoto.util.trunc
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.max

/** Device-level operations built on top of ADB shell commands. */
class DeviceServices(private val adb: AdbManager) {
    private val cpuMutex = Mutex()
    private var previousCpu: Pair<Long, Long>? = null

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
            if (name.startsWith("android", ignoreCase = true) ||
                name.startsWith("com.android", ignoreCase = true) ||
                name.startsWith("system", ignoreCase = true) ||
                name.startsWith("zygote", ignoreCase = true)
            ) {
                continue
            }
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

    suspend fun deviceFullInfo(): DeviceInfo {
        val command = buildString {
            append("echo MODEL=$(getprop ro.product.model);")
            append("echo NAME=$(getprop ro.product.name);")
            append("echo DEVICE=$(getprop ro.product.device);")
            append("echo BRAND=$(getprop ro.product.brand);")
            append("echo MANUF=$(getprop ro.product.manufacturer);")
            append("echo ANDROID=$(getprop ro.build.version.release);")
            append("echo SDK=$(getprop ro.build.version.sdk);")
            append("echo BUILD=$(getprop ro.build.display.id);")
            append("echo ABI=$(getprop ro.product.cpu.abi);")
            append("echo KERNEL=$(uname -r);")
            append("echo WMSIZE=$(wm size 2>/dev/null | tail -n 1);")
            append("echo UPTIME=$(cat /proc/uptime 2>/dev/null);")
            append("echo CORES=$(grep -c ^processor /proc/cpuinfo 2>/dev/null);")
            append("echo DFDATA=$(df -k /data 2>/dev/null | tail -n 1);")
            append("echo DFSD=$(df -k /sdcard 2>/dev/null | tail -n 1)")
        }
        val result = adb.shell(command, 15_000)
        val values = HashMap<String, String>()
        for (line in result.stdout.lineSequence()) {
            val index = line.indexOf('=')
            if (index < 0) continue
            values[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        fun field(key: String): String = values[key]?.trim()?.takeIf { it.isNotEmpty() } ?: "?"
        val rawWmSize = values["WMSIZE"].orEmpty().trim()
        val cleanWmSize = rawWmSize.substringAfterLast(' ', rawWmSize).ifEmpty { "?" }
        return DeviceInfo(
            model = field("MODEL"),
            name = field("NAME"),
            device = field("DEVICE"),
            brand = field("BRAND"),
            manuf = field("MANUF"),
            android = field("ANDROID"),
            sdk = field("SDK"),
            build = field("BUILD"),
            abi = field("ABI"),
            kernel = field("KERNEL"),
            wmSize = cleanWmSize,
            uptimeSeconds = values["UPTIME"]?.split(Regex("\\s+"))?.firstOrNull()
                ?.toDoubleOrNull()?.toLong() ?: 0L,
            cores = field("CORES"),
            dataStorage = parseDfKb(values["DFDATA"].orEmpty()),
            sdcardStorage = parseDfKb(values["DFSD"].orEmpty()),
        )
    }

    suspend fun devicePerfStats(): PerfStats? {
        val command = buildString {
            append("head -n 1 /proc/stat;")
            append("echo __MEM__; cat /proc/meminfo;")
            append("echo __LOAD__; cat /proc/loadavg 2>/dev/null;")
            append("echo __TEMP__; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null;")
            append("echo __FREQ__; cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null;")
            append("echo __DF__; df -k /sdcard 2>/dev/null | tail -n 1")
        }
        val result = adb.shell(command, 12_000)
        if (!result.ok && result.stdout.isEmpty()) return null

        val (statPart, afterMem) = splitOnce(result.stdout, "__MEM__")
        val (memPart, afterLoad) = splitOnce(afterMem, "__LOAD__")
        val (loadPart, afterTemp) = splitOnce(afterLoad, "__TEMP__")
        val (tempPart, afterFreq) = splitOnce(afterTemp, "__FREQ__")
        val (freqPart, dfPart) = splitOnce(afterFreq, "__DF__")

        var cpuPct = 0f
        for (line in statPart.lineSequence()) {
            if (!line.startsWith("cpu ")) continue
            val numbers = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (numbers.size >= 4) {
                val idle = numbers[3] + numbers.getOrElse(4) { 0L }
                val total = numbers.sum()
                val previous = cpuMutex.withLock {
                    val current = previousCpu
                    previousCpu = total to idle
                    current
                }
                if (previous != null && total > previous.first) {
                    val deltaTotal = total - previous.first
                    val deltaIdle = idle - previous.second
                    cpuPct = ((deltaTotal - deltaIdle) * 100f / deltaTotal).coerceIn(0f, 100f)
                }
            }
            break
        }

        val memory = HashMap<String, Long>()
        for (match in MEMORY_REGEX.findAll(memPart)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].toLongOrNull() ?: continue
            memory[key] = value * 1024L
        }
        val ramTotal = memory["MemTotal"] ?: 0L
        val ramAvailable = memory["MemAvailable"] ?: ((memory["MemFree"] ?: 0L) + (memory["Cached"] ?: 0L))
        val ramUsed = max(0L, ramTotal - ramAvailable)
        val ramPct = if (ramTotal > 0L) ramUsed * 100f / ramTotal else 0f
        val swapTotal = memory["SwapTotal"] ?: 0L
        val swapUsed = max(0L, swapTotal - (memory["SwapFree"] ?: 0L))

        val tempC = tempPart.lineSequence().firstOrNull()?.trim()?.toFloatOrNull()?.let { raw ->
            if (raw > 200f) raw / 1_000f else raw
        }
        val freqMhz = freqPart.lineSequence().firstOrNull()?.trim()?.toIntOrNull()?.div(1_000)
        val load = loadPart.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(3).joinToString(" ")
        val storage = dfPart.lineSequence().lastOrNull { it.isNotBlank() }?.let { parseDfKb(it) }

        return PerfStats(
            cpuPct = cpuPct,
            ramTotal = ramTotal,
            ramUsed = ramUsed,
            ramAvail = ramAvailable,
            ramPct = ramPct,
            swapTotal = swapTotal,
            swapUsed = swapUsed,
            tempC = tempC,
            freqMhz = freqMhz,
            load = load,
            storage = storage,
        )
    }

    suspend fun extractAppMeta(packageName: String): AppMeta {
        val pathResult = adb.shell(
            "pm path ${shellQuote(packageName)} | head -n 1 | cut -d: -f2",
            10_000,
        )
        val apk = pathResult.stdout.trim()
        if (apk.isEmpty()) return AppMeta(null, null)

        val manifest = unzipGz(apk, "AndroidManifest.xml", 12_000) ?: return AppMeta(null, null)
        val axml = ApkMetaParser.parseAppMeta(manifest)
        var arsc: ByteArray? = null
        val targetIds = buildSet {
            axml.iconRes?.let { add(it) }
            axml.labelRes?.let { add(it) }
        }
        val resolved = if (targetIds.isNotEmpty()) {
            arsc = unzipGz(apk, "resources.arsc", 25_000)
            arsc?.let { ApkMetaParser.parseArsc(it, targetIds) } ?: emptyMap()
        } else {
            emptyMap()
        }

        val label = axml.label ?: axml.labelRes?.let { resolved[it]?.firstOrNull() }
        val candidates = axml.iconRes?.let { resolved[it].orEmpty() }.orEmpty()
        val imagePaths = candidates.filterNot { it.endsWith(".xml", ignoreCase = true) }.toMutableList()

        if (imagePaths.isEmpty() && candidates.isNotEmpty() && arsc != null) {
            for (xmlPath in candidates) {
                val xml = unzipGz(apk, xmlPath, 10_000) ?: continue
                val foregroundId = ApkMetaParser.parseForeground(xml) ?: continue
                val foregroundPaths = ApkMetaParser.parseArsc(arsc!!, setOf(foregroundId))[foregroundId].orEmpty()
                imagePaths.addAll(foregroundPaths.filterNot { it.endsWith(".xml", ignoreCase = true) })
                if (imagePaths.isNotEmpty()) break
            }
        }

        for (path in imagePaths.asReversed()) {
            val bytes = unzipGz(apk, path, 12_000) ?: continue
            if (ApkMetaParser.isImage(bytes)) return AppMeta(label, bytes)
        }
        return AppMeta(label, null)
    }

    private suspend fun unzipGz(apk: String, entry: String, timeoutMs: Long): ByteArray? {
        val command = "unzip -p ${shellQuote(apk)} ${shellQuote(entry)} 2>/dev/null | gzip -1"
        val compressed = readStreamToBytes(adb, "exec-out:sh -c ${shellQuote(command)}", timeoutMs)
            ?: return null
        if (compressed.isEmpty()) return null
        return try {
            val output = ByteArrayOutputStream()
            GZIPInputStream(compressed.inputStream()).use { gzip ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = gzip.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_UNZIP_BYTES) return null
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        } catch (_: IOException) {
            null
        }
    }

    suspend fun screenshot(): ScreenshotResult {
        val direct = readStreamToBytes(adb, "exec-out:screencap -p", 25_000)
        if (direct.isPng()) return ScreenshotResult(direct)
        direct.screenshotFailureReason()?.let { return ScreenshotResult(null, it) }

        val shellRaw = readStreamToBytes(adb, "shell:screencap -p", 25_000)
            ?: return ScreenshotResult(null, direct.screenshotFailureReason() ?: "screencap não respondeu")
        val withoutExit = shellStripExitCode(normalizeCrLf(shellRaw)).data
        if (withoutExit.isPng()) return ScreenshotResult(withoutExit)

        if (withoutExit.size > 4) {
            val header = ByteBuffer.wrap(withoutExit, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            val little = ByteBuffer.wrap(withoutExit, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val body = withoutExit.copyOfRange(4, withoutExit.size)
            if (header > 0 && header == body.size && body.isPng()) return ScreenshotResult(body)
            if (little > 0 && little == body.size && body.isPng()) return ScreenshotResult(body)
        }
        val reason = withoutExit.screenshotFailureReason(includeGeneric = true)
            ?: if (withoutExit.isEmpty()) "screencap retornou vazio" else "screencap retornou dados inválidos"
        return ScreenshotResult(null, reason)
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
        const val MAX_UNZIP_BYTES = 12 * 1024 * 1024
        val VOLUME_REGEX = Regex("""volume is (\d+) in range \[(\d+)\.\.(\d+)\]""")
        val MEMORY_REGEX = Regex("""^(\w+):\s+(\d+)\s+kB""", RegexOption.MULTILINE)
    }
}

private fun splitOnce(text: String, marker: String): Pair<String, String> {
    val index = text.indexOf(marker)
    return if (index < 0) text to "" else text.substring(0, index) to text.substring(index + marker.length)
}

private fun parseDfKb(raw: String): StorageStats? {
    var result: StorageStats? = null
    for (line in raw.lineSequence()) {
        result = parseDfKbLine(line) ?: continue
    }
    return result
}

private fun parseDfKbLine(line: String): StorageStats? {
    val parts = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.size < 5) return null
    val total = parts[1].toLongOrNull() ?: return null
    val used = parts[2].toLongOrNull() ?: return null
    val available = parts[3].toLongOrNull() ?: return null
    val percent = parts[4].removeSuffix("%").toFloatOrNull() ?: return null
    return StorageStats(total * 1024L, used * 1024L, available * 1024L, percent)
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

private fun ByteArray?.screenshotFailureReason(includeGeneric: Boolean = false): String? {
    if (this == null || isEmpty()) return null
    val sample = take(240).toByteArray()
    if (sample.any { it == 0.toByte() }) return null
    val text = String(sample, StandardCharsets.UTF_8).trim()
    if (text.isEmpty()) return null
    val low = text.lowercase(Locale.ROOT)
    val known = when {
        low.contains("permission") || low.contains("denied") ->
            "permissão negada pelo dispositivo"
        low.contains("secure") || low.contains("blocked") ->
            "conteúdo protegido pelo dispositivo"
        else -> null
    }
    if (known != null) return known
    return if (includeGeneric) {
        "screencap retornou dados inválidos: ${trunc(text, 80)}"
    } else {
        null
    }
}

private fun normalizeCrLf(bytes: ByteArray): ByteArray {
    var count = 0
    for (index in 0 until bytes.size - 1) {
        if (bytes[index] == 13.toByte() && bytes[index + 1] == 10.toByte()) count++
    }
    if (count == 0) return bytes
    val result = ByteArray(bytes.size - count)
    var source = 0
    var target = 0
    while (source < bytes.size) {
        if (source + 1 < bytes.size &&
            bytes[source] == 13.toByte() && bytes[source + 1] == 10.toByte()
        ) {
            source += 2
        } else {
            result[target++] = bytes[source++]
        }
    }
    return result
}
