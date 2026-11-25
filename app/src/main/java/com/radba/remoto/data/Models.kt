package com.radba.remoto.data

enum class Tab(val label: String, val glyph: String) {
    HOME("Início", "⌂"),
    APPS("Apps", "▦"),
    FILES("Arquivos", "▤"),
    SETTINGS("Ajustes", "⚙"),
    TERMINAL("Terminal", "$"),
}

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED }

enum class MirrorState { OFF, STARTING, ON }

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
    val kb: Boolean = true,
    val base: PictureBase = PictureBase(),
    val rootFixVideo: Boolean = true,
    val videoEncoder: String = "auto",
    val fpsMode: String = "auto",
    val resMode: String = "720",
    val hwaccel: Boolean = true,
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
)

data class ProcessInfo(val pid: Int, val name: String)

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
)

data class VolumeInfo(val level: Int, val max: Int)

data class MediaInfo(
    val playing: Boolean = false,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val title: String = "",
    val packageName: String = "",
)

enum class TerminalKind { COMMAND, OUTPUT, ERROR, OK, SUB }

data class TerminalLine(val text: String, val kind: TerminalKind = TerminalKind.OUTPUT)

data class SettingsNote(val text: String, val kind: ToastKind = ToastKind.INFO)
