package com.radba.remoto.data

import android.graphics.Bitmap

enum class Tab(val label: String, val glyph: String) {
    HOME("Início", "⌂"),
    APPS("Apps", "▦"),
    FILES("Arquivos", "▤"),
    PERF("Desempenho", "⚡"),
    SETTINGS("Ajustes", "⚙"),
    TERMINAL("Terminal", ">_"),
}

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

enum class MirrorState { OFF, STARTING, ON }

data class MirrorStats(
    val fps: Int = 0,
    val pipeMs: Int = 0,
    val tier: Int = 0,
    val tierName: String = "60 FPS Fluido",
)

enum class ToastKind { INFO, OK, ERROR }

data class ToastMessage(
    val text: String,
    val kind: ToastKind = ToastKind.INFO,
    val id: Long = System.nanoTime(),
)

data class PictureBase(
    val brightness: Int = 50,
    val contrast: Int = 50,
    val saturation: Int = 50,
) {
    val hasValues: Boolean get() = brightness != 0 || contrast != 0 || saturation != 0
}

data class Prefs(
    val host: String = "192.168.100.12:5555",
    val dim: Int = 0,
    val mirror: Boolean = true,
    val audioPc: Boolean = false,
    val kb: Boolean = true,
    val base: PictureBase = PictureBase(),
    val rootFixVideo: Boolean = true,
    val vBright: Int = 100,
    val vWarm: Int = 0,
    val nightMode: Boolean = false,
    val nightBright: Int = 35,
    val nightWarm: Int = 0,
)

data class RootStatus(
    val available: Boolean? = null,
    val mode: String? = null,
    val detail: String = "não verificado",
)

data class AppInfo(
    val packageName: String,
    val activity: String,
    val title: String,
    val running: Boolean = false,
    val icon: Bitmap? = null,
)

data class ProcessInfo(val pid: Int, val name: String)

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

data class VolumeInfo(val level: Int, val max: Int)

data class StorageStats(
    val total: Long = 0,
    val used: Long = 0,
    val avail: Long = 0,
    val pct: Float = 0f,
)

data class PerfStats(
    val cpuPct: Float = 0f,
    val ramTotal: Long = 0,
    val ramUsed: Long = 0,
    val ramAvail: Long = 0,
    val ramPct: Float = 0f,
    val swapTotal: Long = 0,
    val swapUsed: Long = 0,
    val tempC: Float? = null,
    val freqMhz: Int? = null,
    val load: String = "",
    val storage: StorageStats? = null,
)

data class DeviceInfo(
    val model: String = "",
    val name: String = "",
    val device: String = "",
    val brand: String = "",
    val manuf: String = "",
    val android: String = "",
    val sdk: String = "",
    val build: String = "",
    val abi: String = "",
    val kernel: String = "",
    val wmSize: String = "",
    val uptimeSeconds: Long = 0,
    val cores: String = "",
    val dataStorage: StorageStats? = null,
    val sdcardStorage: StorageStats? = null,
)

data class AppMeta(
    val label: String?,
    val iconBytes: ByteArray?,
)

enum class TerminalKind { COMMAND, OUTPUT, ERROR, OK, SUB }

data class TerminalLine(val text: String, val kind: TerminalKind = TerminalKind.OUTPUT)

data class SettingsNote(val text: String, val kind: ToastKind = ToastKind.INFO)
