package com.radba.remoto

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radba.remoto.adb.AdbManager
import com.radba.remoto.adb.DeviceServices
import com.radba.remoto.adb.PqController
import com.radba.remoto.adb.ShellResult
import com.radba.remoto.data.AppInfo
import com.radba.remoto.data.AppPreferences
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.FileEntry
import com.radba.remoto.data.MediaInfo
import com.radba.remoto.data.MirrorState
import com.radba.remoto.data.PictureBase
import com.radba.remoto.data.Prefs
import com.radba.remoto.data.ProcessInfo
import com.radba.remoto.data.RootStatus
import com.radba.remoto.data.SettingsNote
import com.radba.remoto.data.Tab
import com.radba.remoto.data.TerminalKind
import com.radba.remoto.data.TerminalLine
import com.radba.remoto.data.ToastKind
import com.radba.remoto.data.ToastMessage
import com.radba.remoto.scrcpy.ScrcpyClient
import com.radba.remoto.util.clampInt
import com.radba.remoto.util.encoderLabel
import com.radba.remoto.util.fpsServerValue
import com.radba.remoto.util.inputTextCommand
import com.radba.remoto.util.isInternalRoot
import com.radba.remoto.util.normalizeRemote
import com.radba.remoto.util.sanitizeHost
import com.radba.remoto.util.shellQuote
import com.radba.remoto.util.splitHost
import com.radba.remoto.util.trunc
import com.radba.remoto.util.volumeLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class MainViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val tab: Tab = Tab.HOME,
        val connection: ConnectionState = ConnectionState.CONNECTING,
        val connectionText: String = "Conectando…",
        val prefs: Prefs = Prefs(),
        val favorites: Set<String> = emptySet(),
        val rootStatus: RootStatus = RootStatus(),
        val apps: List<AppInfo> = emptyList(),
        val appsQuery: String = "",
        val appsLoaded: Boolean = false,
        val processes: List<ProcessInfo> = emptyList(),
        val filesPath: String = "/sdcard",
        val files: List<FileEntry> = emptyList(),
        val settingsNote: SettingsNote? = null,
        val systemInfo: String = "Conecte o projetor…",
        val encoders: List<String> = listOf(
            "auto", "hardware (padrão)", "c2.android.avc.encoder", "OMX.google.h264.encoder",
        ),
        val volumePct: Int = 100,
        val volumeMax: Int = 15,
        val terminalLines: List<TerminalLine> = listOf(
            TerminalLine("$ digite um comando e pressione Enter", TerminalKind.SUB),
        ),
        val terminalDraft: String = "",
        val terminalBusy: Boolean = false,
        val mirrorState: MirrorState = MirrorState.OFF,
        val mirrorError: String = "",
        val activeEncoder: String = "",
        val deviceSize: Pair<Int, Int> = 1280 to 720,
        val serverFps: Int = 0,
        val blackout: Boolean = false,
        val media: MediaInfo = MediaInfo(),
        val mediaNow: Double = 0.0,
        val brightnessPct: Int = 100,
        val warmPct: Int = 0,
        val mirrorBusy: Boolean = false,
        val installing: Boolean = false,
        val screenshot: Bitmap? = null,
        val screenshotStatus: String = "Aguardando…",
    )

    private val ctx: Application = getApplication()
    private val prefsStore = AppPreferences(ctx)
    private val prefsFlow: StateFlow<Prefs> = prefsStore.prefs.stateIn(
        viewModelScope, SharingStarted.Eagerly, Prefs(),
    )
    private val favoritesFlow: StateFlow<Set<String>> = prefsStore.favorites.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptySet(),
    )

    private val adb = AdbManager(ctx)
    private val services = DeviceServices(adb)
    private val pq = PqController(ctx, adb)
    private val scrcpy = ScrcpyClient(ctx, adb) { prefsFlow.value }

    private val _ui = MutableStateFlow(
        UiState(prefs = prefsFlow.value, favorites = favoritesFlow.value),
    )
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    private val _toasts = MutableSharedFlow<ToastMessage>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val toasts: SharedFlow<ToastMessage> = _toasts.asSharedFlow()

    val terminalText: String
        get() = _ui.value.terminalLines.joinToString("\n") { it.text }

    private val reconnecting = AtomicBoolean(false)
    private val installingGuard = AtomicBoolean(false)
    private val screenshotBusy = AtomicBoolean(false)
    private val coalescedJobs = ConcurrentHashMap<String, Job>()
    private val terminalHistory = mutableListOf<String>()
    private var terminalIndex = -1
    private var volumeHoldUntilMs = 0L
    private var lastVdimErrorMs = 0L
    private var mirrorRetryAfterMs = 0L
    private var lastOnline: Boolean? = false

    init {
        adb.setHost(prefsFlow.value.host)
        adb.startMonitor(viewModelScope) { ok -> adb.online.value = ok }

        viewModelScope.launch {
            prefsFlow.collect { p ->
                _ui.update {
                    it.copy(
                        prefs = p,
                        brightnessPct = p.vBright,
                        warmPct = p.vWarm,
                    )
                }
            }
        }
        viewModelScope.launch {
            favoritesFlow.collect { values -> _ui.update { it.copy(favorites = values) } }
        }
        viewModelScope.launch {
            adb.online.collect { ok -> handleOnline(ok) }
        }
        viewModelScope.launch {
            scrcpy.state.collect { scrcpyState ->
                val mapped = when (scrcpyState.name) {
                    "STARTING" -> MirrorState.STARTING
                    "ON" -> MirrorState.ON
                    else -> MirrorState.OFF
                }
                _ui.update { it.copy(mirrorState = mapped) }
            }
        }
        viewModelScope.launch {
            scrcpy.error.collect { error -> _ui.update { it.copy(mirrorError = error) } }
        }
        viewModelScope.launch {
            scrcpy.activeEncoder.collect { encoder -> _ui.update { it.copy(activeEncoder = encoder) } }
        }
        viewModelScope.launch {
            scrcpy.deviceSize.collect { size ->
                if (size.first > 0 && size.second > 0) _ui.update { it.copy(deviceSize = size) }
            }
        }
        viewModelScope.launch {
            scrcpy.serverFps.collect { fps -> _ui.update { it.copy(serverFps = fps) } }
        }

        reconnecting.set(true)
        safeLaunch {
            try {
                val result = try {
                    adb.reconnect()
                } catch (t: Throwable) {
                    Result.failure<Unit>(t)
                }
                applyReconnectResult(result, showMessages = false)
            } finally {
                reconnecting.set(false)
            }
        }

        periodic(5_000L) { fiveSecondTick() }
        periodic(15_000L) { refreshVolumeInternal() }
    }

    // ------------------------------------------------------------ infra --

    private fun toast(text: String, kind: ToastKind = ToastKind.INFO) {
        _toasts.tryEmit(ToastMessage(text, kind))
    }

    private fun note(text: String, kind: ToastKind = ToastKind.INFO) {
        _ui.update { it.copy(settingsNote = SettingsNote(text, kind)) }
    }

    private fun noteAndToast(text: String, kind: ToastKind) {
        note(text, kind)
        toast(text, kind)
    }

    private fun safeLaunchJob(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            toast("Erro: ${trunc(t.message ?: "falha", 100)}", ToastKind.ERROR)
        }
    }

    private fun safeLaunch(block: suspend () -> Unit) {
        safeLaunchJob(block)
    }

    private fun periodic(intervalMs: Long, block: suspend () -> Unit) {
        viewModelScope.launch {
            while (isActive) {
                try {
                    block()
                } catch (_: Throwable) {
                    // Um ciclo com falha nao interrompe as atualizacoes periodicas.
                }
                delay(intervalMs)
            }
        }
    }

    private fun coalesce(key: String, block: suspend () -> Unit) {
        coalescedJobs[key]?.cancel()
        val job = safeLaunchJob(block)
        coalescedJobs[key] = job
        job.invokeOnCompletion {
            if (coalescedJobs[key] === job) coalescedJobs.remove(key)
        }
    }

    private fun detail(result: ShellResult): String =
        result.stderr.trim().ifBlank { result.stdout.trim() }.ifBlank { "código ${result.exitCode}" }

    private fun failureDetail(error: Throwable?): String =
        trunc(error?.message?.takeIf { it.isNotBlank() } ?: "sem resposta", 110)

    private fun shellDetail(result: ShellResult, max: Int = 110): String =
        trunc(detail(result), max)

    private fun localServerAvailable(): Boolean {
        val assets = runCatching { ctx.assets.list("") }.getOrNull() ?: emptyArray()
        return assets.contains("scrcpy-server.jar") || File(ctx.filesDir, "scrcpy-server.jar").exists()
    }

    private fun applyReconnectResult(result: Result<Unit>, showMessages: Boolean) {
        if (result.isSuccess) {
            if (showMessages) {
                note("Conectado a ${adb.host.value}.", ToastKind.OK)
                toast("Conectado.", ToastKind.OK)
            }
            handleOnline(true)
        } else {
            val message = result.exceptionOrNull()?.message.orEmpty()
            if (showMessages) {
                if (message.contains("unauthorized", ignoreCase = true)) {
                    note("Autorize a depuração na tela do aparelho.", ToastKind.ERROR)
                    toast("Autorize no aparelho.", ToastKind.ERROR)
                } else {
                    note(
                        "Não conectou em ${adb.host.value}: ${trunc(message.ifBlank { "sem resposta" }, 110)} " +
                            "(no Android 11+ use 'Parear' antes)",
                        ToastKind.ERROR,
                    )
                    toast("Não conectou.", ToastKind.ERROR)
                }
            }
            handleOnline(false)
            _ui.update { it.copy(connection = ConnectionState.DISCONNECTED, connectionText = "Desconectado") }
        }
    }

    private fun handleOnline(ok: Boolean) {
        if (lastOnline == ok) return
        lastOnline = ok
        if (ok) {
            _ui.update {
                it.copy(
                    connection = ConnectionState.CONNECTED,
                    connectionText = "Conectado a ${adb.host.value}",
                )
            }
            safeLaunch {
                refreshVolumeInternal()
                refreshSettingsInternal()
                refreshRootFeatures()
                refreshScreenshot()
                if (_ui.value.tab == Tab.HOME) ensureMirrorInternal()
            }
        } else {
            scrcpy.stop()
            coalescedJobs.keys.toList().forEach { coalescedJobs[it]?.cancel() }
            coalescedJobs.clear()
            mirrorRetryAfterMs = 0L
            _ui.update {
                it.copy(
                    connection = ConnectionState.DISCONNECTED,
                    connectionText = "Desconectado",
                    mirrorState = MirrorState.OFF,
                    mirrorError = "",
                    activeEncoder = "",
                    serverFps = 0,
                    mirrorBusy = false,
                    rootStatus = RootStatus(),
                    screenshot = null,
                    screenshotStatus = "Projetor desconectado",
                    systemInfo = "Conecte o projetor…",
                )
            }
        }
    }

    private suspend fun fiveSecondTick() {
        if (!adb.online.value) return
        val state = _ui.value
        if (state.tab != Tab.HOME) return
        if (!scrcpy.isReady()) {
            refreshScreenshot()
            ensureMirrorInternal()
        }
        pollMedia()
    }

    // ---------------------------------------------------------- conexao --

    fun selectTab(tab: Tab) {
        val previous = _ui.value.tab
        _ui.update { it.copy(tab = tab) }
        if (tab != Tab.HOME && previous == Tab.HOME) {
            scrcpy.stop()
            _ui.update { it.copy(mirrorState = MirrorState.OFF, mirrorBusy = false) }
        } else if (tab == Tab.HOME && previous != Tab.HOME) {
            mirrorRetryAfterMs = 0L
            safeLaunch { ensureMirrorInternal() }
        }
    }

    fun reconnect() {
        if (!reconnecting.compareAndSet(false, true)) {
            note("Já está reconectando…", ToastKind.INFO)
            return
        }
        note("Reconectando ao projetor…", ToastKind.INFO)
        safeLaunch {
            try {
                val result = try {
                    adb.reconnect()
                } catch (t: Throwable) {
                    Result.failure<Unit>(t)
                }
                applyReconnectResult(result, showMessages = true)
            } catch (t: Throwable) {
                note("Falha ao reconectar: ${trunc(t.message, 110)}", ToastKind.ERROR)
                toast("Falha ao reconectar.", ToastKind.ERROR)
                handleOnline(false)
            } finally {
                reconnecting.set(false)
            }
        }
    }

    fun setHost(raw: String) {
        val host = sanitizeHost(raw)
        if (host == null) {
            noteAndToast(
                "Endereço inválido. Use IP:porta, ex.: 192.168.0.10:5555",
                ToastKind.ERROR,
            )
            return
        }
        safeLaunch {
            prefsStore.update { it.copy(host = host) }
            adb.setHost(host)
            _ui.update { it.copy(prefs = it.prefs.copy(host = host)) }
            note("Endereço salvo: $host. Reconectando…", ToastKind.INFO)
            reconnect()
        }
    }

    fun pair(pairAddress: String, code: String) {
        val trimmed = pairAddress.trim()
        if (trimmed.isBlank() || code.isBlank()) {
            noteAndToast("Informe o IP:porta de pareamento e o código.", ToastKind.ERROR)
            return
        }
        val (hostName, port) = splitHost(trimmed)
        val pairHost = "$hostName:$port"
        note("Pareando com $pairHost…", ToastKind.INFO)
        safeLaunch {
            val result = try {
                adb.pair(pairHost, code.trim())
            } catch (t: Throwable) {
                Result.failure<Unit>(t)
            }
            if (result.isSuccess) {
                val connected = try {
                    adb.connect()
                } catch (_: Throwable) {
                    Result.failure<Unit>(Throwable("sem resposta"))
                }
                if (connected.isSuccess) {
                    note("Pareado e conectado em ${adb.host.value}!", ToastKind.OK)
                    toast("Pareado e conectado em ${adb.host.value}!", ToastKind.OK)
                    handleOnline(true)
                } else {
                    noteAndToast(
                        "Pareado! Agora salve o IP:porta principal da Depuração sem fio e clique Reconectar.",
                        ToastKind.OK,
                    )
                }
            } else {
                note("Falha no pareamento: ${trunc(failureDetail(result.exceptionOrNull()), 140)}", ToastKind.ERROR)
                toast("Falha no pareamento.", ToastKind.ERROR)
            }
        }
    }

    fun enableWifiAdb() {
        note("Ativando ADB Wi-Fi (tcpip 5555)…", ToastKind.INFO)
        safeLaunch {
            val rootResult = try {
                adb.rootShell("setprop service.adb.tcp.port 5555 && stop adbd && start adbd")
            } catch (t: Throwable) {
                ShellResult(1, "", t.message ?: "sem resposta")
            }
            if (!rootResult.ok) {
                note(
                    "USB não encontrado para tcpip 5555: ${trunc(detail(rootResult), 90)}",
                    ToastKind.ERROR,
                )
                toast("Falha ao ativar ADB Wi-Fi.", ToastKind.ERROR)
                return@safeLaunch
            }
            val connected = try {
                adb.reconnect()
            } catch (t: Throwable) {
                Result.failure<Unit>(t)
            }
            if (connected.isSuccess) {
                note("ADB Wi-Fi ativo e conectado em ${adb.host.value}. Pode desligar o USB.", ToastKind.OK)
                toast("ADB Wi-Fi ativo. Pode desligar o USB.", ToastKind.OK)
                handleOnline(true)
            } else {
                note(
                    "tcpip 5555 ok, mas não conectou em ${adb.host.value}: " +
                        trunc(failureDetail(connected.exceptionOrNull()), 90),
                    ToastKind.ERROR,
                )
                toast("Não conectou.", ToastKind.ERROR)
                handleOnline(false)
            }
        }
    }

    // ------------------------------------------------------------ espelho --

    fun attachMirrorSurface(surface: Surface?) {
        scrcpy.attachSurface(surface)
    }

    fun updateMirrorView(width: Int, height: Int) {
        scrcpy.setViewSize(width, height)
    }

    private suspend fun ensureMirrorInternal() {
        val state = _ui.value
        if (state.tab != Tab.HOME || !state.prefs.mirror || !adb.online.value) return
        if (scrcpy.isReady() || state.mirrorBusy) return
        if (SystemClock.elapsedRealtime() < mirrorRetryAfterMs) return
        _ui.update { it.copy(mirrorBusy = true) }
        try {
            val ok = scrcpy.start { message -> note(message, ToastKind.INFO) }
            if (ok) {
                if (_ui.value.blackout) scrcpy.setDisplayPower(false)
            } else {
                mirrorRetryAfterMs = SystemClock.elapsedRealtime() + 25_000L
                toast("Espelho: ${trunc(scrcpy.error.value, 70)}", ToastKind.ERROR)
            }
        } catch (t: Throwable) {
            mirrorRetryAfterMs = SystemClock.elapsedRealtime() + 25_000L
            toast("Espelho: ${trunc(t.message ?: "falha", 70)}", ToastKind.ERROR)
        } finally {
            _ui.update { it.copy(mirrorBusy = false) }
            refreshScreenshot()
        }
    }

    fun startMirror() {
        if (!adb.online.value) {
            noteAndToast("Projetor desconectado. Conecte primeiro.", ToastKind.ERROR)
            return
        }
        if (_ui.value.mirrorBusy) {
            note("O espelho já está iniciando…", ToastKind.INFO)
            return
        }
        _ui.update { it.copy(mirrorBusy = true) }
        note("Iniciando espelho (pode levar alguns segundos)…", ToastKind.INFO)
        safeLaunch {
            try {
                scrcpy.stop()
                val ok = scrcpy.start { message -> note(message, ToastKind.INFO) }
                if (ok) {
                    if (_ui.value.blackout) scrcpy.setDisplayPower(false)
                    note("Espelho ao vivo ativo. Veja na tela Início.", ToastKind.OK)
                    toast("Espelho ativo.", ToastKind.OK)
                } else {
                    val reason = scrcpy.error.value.ifBlank { "motivo desconhecido" }
                    note("Espelho falhou: ${trunc(reason, 320)}", ToastKind.ERROR)
                    toast("Espelho falhou (detalhes em Ajustes).", ToastKind.ERROR)
                    mirrorRetryAfterMs = SystemClock.elapsedRealtime() + 25_000L
                }
            } catch (t: Throwable) {
                note("Espelho falhou: ${trunc(failureDetail(t), 320)}", ToastKind.ERROR)
                toast("Espelho falhou (detalhes em Ajustes).", ToastKind.ERROR)
                mirrorRetryAfterMs = SystemClock.elapsedRealtime() + 25_000L
            } finally {
                _ui.update { it.copy(mirrorBusy = false) }
                refreshScreenshot()
            }
        }
    }

    fun stopMirror() {
        scrcpy.stop()
        mirrorRetryAfterMs = 0L
        _ui.update { it.copy(mirrorState = MirrorState.OFF, mirrorBusy = false) }
        toast("Espelho desligado.", ToastKind.INFO)
        refreshScreenshot()
    }

    fun screenTouch(action: Int, x: Float, y: Float) {
        if (action == 7 && !scrcpy.isReady()) return
        val device = scrcpy.screenToDevice(x, y)
        if (scrcpy.sendTouch(action, device.first, device.second)) return
        safeLaunch {
            val result = adb.shell("input tap ${device.first} ${device.second}")
            if (!result.ok) toast("Falha ao enviar toque: ${shellDetail(result, 70)}", ToastKind.ERROR)
        }
    }

    fun sendKey(code: Int) {
        if (!adb.online.value) {
            toast("Projetor desconectado.", ToastKind.ERROR)
            return
        }
        if (scrcpy.sendKey(code)) return
        safeLaunch {
            val result = adb.shell("input keyevent $code")
            if (!result.ok) toast("Falha ao enviar tecla: ${shellDetail(result, 70)}", ToastKind.ERROR)
        }
    }

    fun sendText(text: String) {
        if (text.isEmpty()) return
        if (scrcpy.sendText(text)) return
        safeLaunch {
            val result = adb.shell(inputTextCommand(text))
            if (!result.ok) toast("Falha ao digitar: ${shellDetail(result, 70)}", ToastKind.ERROR)
        }
    }

    fun mediaSeek(seconds: Int) {
        if (seconds == 0 || !adb.online.value) return
        val code = if (seconds > 0) 90 else 89
        val count = (abs(seconds.toFloat()) / 10f).roundToInt().coerceIn(1, 60)
        if (!scrcpy.sendKeys(List(count) { code })) {
            safeLaunch {
                repeat(count) { adb.shell("input keyevent $code") }
            }
        }
        val next = maxOf(0.0, _ui.value.mediaNow + seconds)
        _ui.update { state ->
            val updated = if (state.media.duration > 0.0) {
                state.media.copy(position = next.coerceAtMost(state.media.duration))
            } else {
                state.media
            }
            state.copy(mediaNow = next, media = updated)
        }
    }

    fun seekToFraction(frac: Float) {
        val duration = _ui.value.media.duration
        if (duration <= 0.0) return
        val target = (frac.coerceIn(0f, 1f) * duration).toDouble()
        mediaSeek((target - _ui.value.mediaNow).roundToInt())
    }

    fun pollMedia() {
        if (!adb.online.value) return
        safeLaunch {
            val info = runCatching { services.mediaInfo() }.getOrNull() ?: return@safeLaunch
            _ui.update {
                it.copy(
                    media = info,
                    mediaNow = if (info.position > 0.0) info.position else it.mediaNow,
                )
            }
        }
    }

    // ------------------------------------------------------- screenshot --

    fun refreshScreenshot() {
        if (!screenshotBusy.compareAndSet(false, true)) return
        safeLaunch {
            try {
                val bytes = if (adb.online.value) runCatching { services.screenshot() }.getOrNull() else null
                val bitmap = if (!bytes.isNullOrEmpty()) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                } else {
                    null
                }
                if (bitmap == null) {
                    val status = if (adb.online.value) {
                        "Sem captura (tela bloqueada ou sem permissão)"
                    } else {
                        "Projetor desconectado"
                    }
                    _ui.update { it.copy(screenshot = null, screenshotStatus = status) }
                } else {
                    var status = "Captura " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    if (!localServerAvailable()) {
                        status += " · baixe a transmissão em Ajustes"
                    } else if (_ui.value.mirrorError.isNotBlank()) {
                        status += " · ${trunc(_ui.value.mirrorError, 60)}"
                    }
                    _ui.update { it.copy(screenshot = bitmap, screenshotStatus = status) }
                }
            } finally {
                screenshotBusy.set(false)
            }
        }
    }

    // ------------------------------------------------------------ volume --

    private suspend fun refreshVolumeInternal() {
        if (!adb.online.value) return
        val info = runCatching { services.getVolume() }.getOrNull() ?: return
        if (SystemClock.elapsedRealtime() - volumeHoldUntilMs <= 2_500L) return
        val max = if (info.max > 0) info.max else 15
        val pct = ((info.level.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        _ui.update { it.copy(volumeMax = max, volumePct = pct) }
    }

    fun refreshVolume() {
        safeLaunch { refreshVolumeInternal() }
    }

    fun setVolumePct(pct: Int, recordHold: Boolean = true) {
        val value = clampInt(pct)
        if (recordHold) volumeHoldUntilMs = SystemClock.elapsedRealtime()
        _ui.update { it.copy(volumePct = value) }
        coalesce("volume") {
            val level = volumeLevel(value, _ui.value.volumeMax)
            val ok = runCatching { services.setVolume(level) }.getOrDefault(false)
            if (!ok) toast("Falha ao ajustar volume.", ToastKind.ERROR)
        }
    }

    fun nudgeVolume(delta: Int) {
        setVolumePct(_ui.value.volumePct + delta)
    }

    // --------------------------------------------------------- brilho/root --

    private fun applyVdim(pct: Int, warm: Int) {
        coalesce("vdim") {
            val result = runCatching { services.applyVirtualBrightness(pct, warm) }
            val failure = result.exceptionOrNull()
            if (failure != null) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastVdimErrorMs > 10_000L) {
                    lastVdimErrorMs = now
                    toast("Brilho virtual: ${trunc(failure.message, 80)}", ToastKind.ERROR)
                }
            }
        }
    }

    fun setBrightness(pct: Int, warm: Int? = null, save: Boolean = true) {
        val nextBright = pct.coerceIn(10, 100)
        val nextWarm = (warm ?: _ui.value.warmPct).coerceIn(0, 100)
        _ui.update { it.copy(brightnessPct = nextBright, warmPct = nextWarm) }
        if (save) {
            safeLaunch {
                prefsStore.update {
                    it.copy(
                        vBright = nextBright,
                        vWarm = nextWarm,
                        nightMode = nextBright < 100 || nextWarm > 0,
                    )
                }
                _ui.update { state ->
                    state.copy(
                        prefs = state.prefs.copy(
                            vBright = nextBright,
                            vWarm = nextWarm,
                            nightMode = nextBright < 100 || nextWarm > 0,
                        ),
                    )
                }
            }
        }
        if (adb.online.value) {
            applyVdim(nextBright, nextWarm)
        } else if (save) {
            noteAndToast("Brilho virtual salvo (aplica ao conectar).", ToastKind.INFO)
        }
    }

    fun nudgeBrightness(delta: Int) {
        setBrightness(_ui.value.brightnessPct + delta, _ui.value.warmPct)
    }

    fun toggleNight() {
        val state = _ui.value
        if (state.brightnessPct < 100 || state.warmPct > 0) {
            safeLaunch {
                prefsStore.update {
                    it.copy(
                        nightBright = state.brightnessPct.coerceIn(10, 99),
                        nightWarm = state.warmPct.coerceIn(0, 100),
                    )
                }
            }
            setBrightness(100, 0)
        } else {
            val p = state.prefs
            setBrightness(p.nightBright, p.nightWarm)
        }
    }

    fun applyBrightnessPreset(pct: Int, warm: Int) {
        setBrightness(pct, warm)
    }

    private suspend fun applyVideoFixInternal(disable: Boolean): Result<Unit> {
        val overlays = services.applyVideoFix(disable = disable)
        if (overlays.isFailure) return overlays
        return services.setSoftwareVideoDecode(enable = disable)
    }

    private suspend fun refreshRootFeatures() {
        val status = runCatching { adb.checkRoot() }.getOrElse {
            RootStatus(available = false, detail = trunc(it.message, 90))
        }
        _ui.update { it.copy(rootStatus = status) }
        if (status.available != true) return
        val current = prefsFlow.value
        if (current.rootFixVideo) {
            runCatching { applyVideoFixInternal(disable = true) }
        }
        if (current.vBright < 100 || current.vWarm > 0) {
            runCatching { services.applyVirtualBrightness(current.vBright, current.vWarm) }
        }
    }

    fun testRoot() {
        note("Verificando acesso root…", ToastKind.INFO)
        safeLaunch {
            val status = runCatching { adb.checkRoot(force = true) }.getOrNull()
            if (status == null) {
                note("Falha ao verificar root: sem resposta", ToastKind.ERROR)
                toast("Falha ao verificar root.", ToastKind.ERROR)
                return@safeLaunch
            }
            val message = if (status.available == true) {
                "Root disponível · ${status.detail.ifBlank { "uid=0" }}"
            } else {
                "Sem root · ${status.detail.ifBlank { "indisponível" }}"
            }
            val kind = if (status.available == true) ToastKind.OK else ToastKind.ERROR
            note(message, kind)
            toast(message, kind)
            _ui.update { it.copy(rootStatus = status) }
            if (status.available == true) {
                val current = prefsFlow.value
                if (current.rootFixVideo) runCatching { applyVideoFixInternal(disable = true) }
                if (current.vBright < 100 || current.vWarm > 0) {
                    runCatching { services.applyVirtualBrightness(current.vBright, current.vWarm) }
                }
            }
        }
    }

    fun setRootFix(enabled: Boolean) {
        val previous = prefsFlow.value.rootFixVideo
        safeLaunch {
            prefsStore.update { it.copy(rootFixVideo = enabled) }
            _ui.update { it.copy(prefs = it.prefs.copy(rootFixVideo = enabled)) }
            if (!adb.online.value) return@safeLaunch
            val result = runCatching { applyVideoFixInternal(disable = enabled) }
            val failure = result.exceptionOrNull()
            val ok = result.isSuccess && result.getOrNull()?.isSuccess == true
            if (ok) {
                val message = if (enabled) {
                    "Vídeo corrigido (1008=1 + decoder SW)."
                } else {
                    "Vídeo restaurado (1008=0 + decoder HW)."
                }
                noteAndToast(message, ToastKind.OK)
            } else {
                prefsStore.update { it.copy(rootFixVideo = previous) }
                _ui.update { it.copy(prefs = it.prefs.copy(rootFixVideo = previous)) }
                val detail = failure?.message?.ifBlank { null }
                    ?: result.getOrNull()?.exceptionOrNull()?.message?.ifBlank { null }
                    ?: "sem resposta"
                val message = "Root necessário para ${if (enabled) "corrigir" else "restaurar"} o vídeo: ${trunc(detail, 120)}"
                noteAndToast(message, ToastKind.ERROR)
            }
        }
    }

    // --------------------------------------------------------- preferencias --

    fun setMirrorEnabled(enabled: Boolean) {
        safeLaunch {
            prefsStore.update { it.copy(mirror = enabled) }
            _ui.update { it.copy(prefs = it.prefs.copy(mirror = enabled)) }
        }
        if (!enabled) {
            stopMirror()
            toast("Espelho desativado.", ToastKind.INFO)
        } else {
            mirrorRetryAfterMs = 0L
            safeLaunch { ensureMirrorInternal() }
            toast("Espelho ativado.", ToastKind.INFO)
        }
    }

    fun setKeyboardEnabled(enabled: Boolean) {
        safeLaunch {
            prefsStore.update { it.copy(kb = enabled) }
            _ui.update { it.copy(prefs = it.prefs.copy(kb = enabled)) }
        }
    }

    fun setEncoder(encoder: String) {
        safeLaunch {
            prefsStore.update { it.copy(videoEncoder = encoder) }
            _ui.update { it.copy(prefs = it.prefs.copy(videoEncoder = encoder)) }
        }
        val label = encoderLabel(encoder)
        toast("Encoder: $label · reiniciando espelho…", ToastKind.INFO)
        if (adb.online.value) startMirror()
    }

    fun setFpsMode(mode: String) {
        if (mode !in setOf("auto", "60", "30", "15")) return
        safeLaunch {
            prefsStore.update { it.copy(fpsMode = mode) }
            _ui.update { it.copy(prefs = it.prefs.copy(fpsMode = mode)) }
        }
        toast("FPS do espelho: ${if (mode == "auto") "Dinâmico" else mode}", ToastKind.INFO)
        if (_ui.value.mirrorState == MirrorState.ON && _ui.value.serverFps != fpsServerValue(mode)) {
            startMirror()
        }
    }

    fun setResolution(mode: String) {
        if (mode !in setOf("nativa", "720", "480")) return
        safeLaunch {
            prefsStore.update { it.copy(resMode = mode) }
            _ui.update { it.copy(prefs = it.prefs.copy(resMode = mode)) }
        }
        toast("Resolução do espelho: ${mode.replaceFirstChar { it.uppercaseChar() }}", ToastKind.INFO)
        if (_ui.value.mirrorState == MirrorState.ON) startMirror()
    }

    fun setHwAccel(enabled: Boolean) {
        safeLaunch {
            prefsStore.update { it.copy(hwaccel = enabled) }
            _ui.update { it.copy(prefs = it.prefs.copy(hwaccel = enabled)) }
        }
        val verb = if (enabled) "ativada" else "desativada"
        if (_ui.value.mirrorState == MirrorState.ON) {
            toast("Aceleração por hardware $verb · reiniciando espelho…", ToastKind.INFO)
            startMirror()
        } else {
            toast("Aceleração por hardware $verb.", ToastKind.INFO)
        }
    }

    fun refreshEncoders() {
        safeLaunch { refreshEncodersInternal(force = true, announce = true) }
    }

    private suspend fun refreshEncodersInternal(force: Boolean, announce: Boolean) {
        if (!adb.online.value) return
        if (announce) note("Procurando encoders H.264 no projetor…", ToastKind.INFO)
        val found = runCatching { services.listAvcEncoders(force) }.getOrDefault(emptyList())
        val defaults = listOf("auto", "hardware (padrão)", "c2.android.avc.encoder", "OMX.google.h264.encoder")
        val merged = (defaults + found.map { it.trim() }.filter { it.isNotEmpty() }).distinct()
        _ui.update { it.copy(encoders = merged) }
        if (announce) {
            val message = "${merged.size} opção(ões) de encoder."
            note(message, ToastKind.OK)
            toast(message, ToastKind.INFO)
        }
    }

    fun installLive() {
        if (!installingGuard.compareAndSet(false, true)) {
            note("Já está baixando…", ToastKind.INFO)
            return
        }
        _ui.update { it.copy(installing = true) }
        note("Iniciando download…", ToastKind.INFO)
        safeLaunch {
            val ok = runCatching {
                val name = "scrcpy-server.jar"
                val names = ctx.assets.list("") ?: emptyArray()
                if (names.contains(name)) {
                    val target = File(ctx.filesDir, name)
                    ctx.assets.open(name).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    true
                } else {
                    File(ctx.filesDir, name).exists()
                }
            }.getOrDefault(false)
            _ui.update { it.copy(installing = false) }
            installingGuard.set(false)
            if (ok) {
                note("Pronto: servidor scrcpy disponível.", ToastKind.OK)
                toast("Transmissão ao vivo pronta.", ToastKind.OK)
                mirrorRetryAfterMs = 0L
            } else {
                note("Falhou: servidor scrcpy não encontrado no pacote.", ToastKind.ERROR)
                toast("Falha ao baixar a transmissão.", ToastKind.ERROR)
            }
        }
    }

    fun refreshSettings() {
        safeLaunch { refreshSettingsInternal() }
    }

    private suspend fun refreshSettingsInternal() {
        val info = runCatching { services.systemInfo() }.getOrNull()
        _ui.update {
            it.copy(systemInfo = info?.takeIf { value -> value.isNotBlank() } ?: "Sem dados (projetor desconectado?)")
        }
        runCatching { adb.cachedRootStatus() }.getOrNull()?.let { cached ->
            _ui.update { it.copy(rootStatus = cached) }
        }
        refreshEncodersInternal(force = false, announce = false)
        refreshPicture()
    }

    private suspend fun refreshPicture() {
        if (_ui.value.blackout) return
        val state = runCatching { pq.state() }.getOrNull()
        if (state != null && state.hasValues) {
            prefsStore.update { it.copy(base = state) }
            _ui.update { it.copy(prefs = it.prefs.copy(base = state)) }
        }
    }

    private suspend fun pushPicture(base: PictureBase) {
        if (!pq.available) return
        listOf("b" to base.brightness, "c" to base.contrast, "s" to base.saturation).forEach { (kind, value) ->
            runCatching { pq.set(kind, value) }
        }
    }

    fun wakeScreen() {
        if (_ui.value.blackout) setBlackout(false)
        sendKey(224)
    }

    fun setBlackout(on: Boolean) {
        if (!adb.online.value) {
            toast("Projetor desconectado.", ToastKind.ERROR)
            return
        }
        if (on) {
            safeLaunch {
                val current = if (pq.available) {
                    runCatching { pq.state() }.getOrNull()?.takeIf { it.hasValues } ?: prefsFlow.value.base
                } else {
                    prefsFlow.value.base
                }
                if (current.hasValues) {
                    prefsStore.update { it.copy(base = current) }
                    _ui.update { it.copy(prefs = it.prefs.copy(base = current)) }
                }
                _ui.update { it.copy(blackout = true) }
                val viaScrcpy = runCatching { scrcpy.setDisplayPower(false) }.getOrDefault(false)
                val havePq = pq.available
                if (havePq) {
                    listOf("b", "c", "s").forEach { kind -> runCatching { pq.set(kind, 0) } }
                }
                when {
                    viaScrcpy -> toast("Tela apagada. Os apps continuam rodando.", ToastKind.OK)
                    havePq -> toast("Imagem apagada por brilho/contraste (espelho scrcpy inativo).", ToastKind.OK)
                    else -> {
                        _ui.update { it.copy(blackout = false) }
                        toast(
                            "Não deu para apagar: espelho scrcpy inativo e tools/tvctl.dex não encontrado.",
                            ToastKind.ERROR,
                        )
                    }
                }
                refreshScreenshot()
            }
        } else {
            _ui.update { it.copy(blackout = false) }
            safeLaunch {
                scrcpy.setDisplayPower(true)
                pushPicture(prefsFlow.value.base)
                toast("Tela restaurada.", ToastKind.OK)
                refreshScreenshot()
            }
        }
    }

    // ---------------------------------------------------------------- apps --

    private fun applyRunningFlags(processes: List<ProcessInfo>) {
        val names = processes.map { it.name.substringBefore(':') }.toSet()
        _ui.update { state ->
            state.copy(apps = state.apps.map { app -> app.copy(running = app.packageName in names) })
        }
    }

    private suspend fun refreshProcessesInternal() {
        if (!adb.online.value) return
        val processes = runCatching { services.listProcesses() }.getOrDefault(emptyList())
        _ui.update { it.copy(processes = processes) }
        applyRunningFlags(processes)
    }

    fun loadApps() {
        if (!adb.online.value) {
            toast("Projetor desconectado.", ToastKind.ERROR)
            return
        }
        safeLaunch {
            val apps = runCatching { services.listLaunchableApps() }.getOrNull()
            if (apps == null) {
                toast("Falha ao listar apps.", ToastKind.ERROR)
                return@safeLaunch
            }
            _ui.update { it.copy(apps = apps, appsLoaded = true) }
            refreshProcessesInternal()
        }
    }

    fun setAppsQuery(query: String) {
        _ui.update { it.copy(appsQuery = query) }
    }

    private fun runShellFeedback(
        command: String,
        okMessage: String,
        errorMessage: String,
        after: suspend () -> Unit = {},
    ) {
        safeLaunch {
            val result = runCatching { adb.shell(command, 120_000L) }.getOrDefault(
                ShellResult(1, "", "sem resposta"),
            )
            if (result.ok) {
                toast(okMessage, ToastKind.OK)
                after()
            } else {
                toast("$errorMessage ${shellDetail(result, 100)}".trim(), ToastKind.ERROR)
            }
        }
    }

    fun launchApp(app: AppInfo) {
        runShellFeedback("am start -n ${shellQuote(app.activity)}", "Abrindo…", "Falha ao abrir.")
    }

    fun toggleFavorite(pkg: String) {
        safeLaunch {
            val current = favoritesFlow.value
            val next = if (pkg in current) current - pkg else current + pkg
            prefsStore.setFavorites(next)
            _ui.update { it.copy(favorites = next) }
        }
    }

    fun forceStopApp(pkg: String) {
        runShellFeedback(
            "am force-stop ${shellQuote(pkg)}",
            "App parado.",
            "Falha.",
            after = { refreshProcessesInternal() },
        )
    }

    fun uninstallApp(pkg: String) {
        runShellFeedback(
            "pm uninstall ${shellQuote(pkg)}",
            "App desinstalado.",
            "Falha.",
            after = { loadApps() },
        )
    }

    fun refreshProcesses() {
        safeLaunch { refreshProcessesInternal() }
    }

    fun killProcess(pid: Int) {
        runShellFeedback(
            "kill -9 $pid",
            "Encerrado.",
            "Falha.",
            after = { refreshProcessesInternal() },
        )
    }

    fun forceStopPackage(pkg: String) {
        runShellFeedback(
            "am force-stop ${shellQuote(pkg)}",
            "Parado.",
            "Falha.",
            after = { refreshProcessesInternal() },
        )
    }

    // --------------------------------------------------------------- files --

    private fun validRemote(path: String?): String? = normalizeRemote(path)

    private fun guardedEntry(entry: FileEntry): String? {
        val path = validRemote(entry.path) ?: run {
            toast("Essa pasta não pode ser alterada.", ToastKind.ERROR)
            return null
        }
        if (isInternalRoot(path)) {
            toast("Essa pasta não pode ser alterada.", ToastKind.ERROR)
            return null
        }
        return path
    }

    fun setFilesPath(path: String) {
        val normalized = validRemote(path)
        if (normalized == null) {
            toast("Use apenas a memória interna (/sdcard).", ToastKind.ERROR)
            return
        }
        _ui.update { it.copy(filesPath = normalized) }
        reloadFiles()
    }

    fun filesParent() {
        val current = _ui.value.filesPath
        if (isInternalRoot(current)) return
        val parent = current.substringBeforeLast('/', "").ifEmpty { "/sdcard" }
        setFilesPath(parent)
    }

    fun reloadFiles() {
        if (!adb.online.value) {
            toast("Projetor desconectado.", ToastKind.ERROR)
            return
        }
        val path = validRemote(_ui.value.filesPath) ?: "/sdcard"
        safeLaunch {
            val entries = runCatching { services.listDir(path) }.getOrDefault(emptyList())
            _ui.update { it.copy(files = entries, filesPath = path) }
        }
    }

    private fun displayName(uri: Uri): String? {
        val cursor = runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)
        }.getOrNull()
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return it.getString(index)
            }
        }
        return uri.lastPathSegment
    }

    private fun safeFileName(name: String?): String {
        val base = name?.substringAfterLast('/').orEmpty()
        val cleaned = base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return cleaned.ifEmpty { "arquivo" }
    }

    private fun copyUriToCache(uri: Uri, hintName: String): File? = runCatching {
        val file = File(ctx.cacheDir, "radba-${System.currentTimeMillis()}-${safeFileName(displayName(uri) ?: hintName)}")
        val input = ctx.contentResolver.openInputStream(uri) ?: return@runCatching null
        input.use { stream -> file.outputStream().use { output -> stream.copyTo(output) } }
        file
    }.getOrNull()

    private fun copyCacheToUri(file: File, uri: Uri): Boolean = runCatching {
        val output = ctx.contentResolver.openOutputStream(uri) ?: return@runCatching false
        output.use { stream -> file.inputStream().use { input -> input.copyTo(stream) } }
        true
    }.getOrDefault(false)

    private fun fileFeedback(result: ShellResult) {
        if (result.ok) {
            toast("Concluído.", ToastKind.OK)
            reloadFiles()
        } else {
            toast("Falhou: ${shellDetail(result)}", ToastKind.ERROR)
        }
    }

    fun uploadFile(uri: Uri) {
        if (!adb.online.value) {
            toast("Projetor desconectado.", ToastKind.ERROR)
            return
        }
        val name = safeFileName(displayName(uri) ?: "arquivo")
        val destination = validRemote("${_ui.value.filesPath.trimEnd('/')}/$name")
        if (destination == null) {
            toast("Destino inválido (use /sdcard/…).", ToastKind.ERROR)
            return
        }
        toast("Enviando…")
        safeLaunch {
            val local = copyUriToCache(uri, name)
            if (local == null) {
                toast("Falhou: não foi possível ler o arquivo.", ToastKind.ERROR)
                return@safeLaunch
            }
            try {
                val result = runCatching { adb.pushLocalFile(local, destination) }.getOrNull()
                if (result?.isSuccess == true) {
                    toast("Concluído.", ToastKind.OK)
                    reloadFiles()
                } else {
                    toast("Falhou: ${trunc(failureDetail(result?.exceptionOrNull()), 110)}", ToastKind.ERROR)
                }
            } finally {
                local.delete()
            }
        }
    }

    fun downloadFile(entry: FileEntry, uri: Uri) {
        val path = validRemote(entry.path)
        if (path == null) {
            toast("Destino inválido (use /sdcard/…).", ToastKind.ERROR)
            return
        }
        toast("Baixando…")
        safeLaunch {
            val local = File(ctx.cacheDir, "radba-download-${System.currentTimeMillis()}-${safeFileName(entry.name)}")
            try {
                val pulled = runCatching { adb.pullRemoteFile(path, local) }.getOrNull()
                if (pulled?.isSuccess != true) {
                    toast("Falhou: ${trunc(failureDetail(pulled?.exceptionOrNull()), 110)}", ToastKind.ERROR)
                    return@safeLaunch
                }
                if (copyCacheToUri(local, uri)) {
                    toast("Concluído.", ToastKind.OK)
                } else {
                    toast("Falhou: não foi possível salvar no destino.", ToastKind.ERROR)
                }
            } finally {
                local.delete()
            }
        }
    }

    private suspend fun pushApkToTemp(local: File, remote: String): Boolean {
        val pushed = runCatching { adb.pushLocalFile(local, remote) }.getOrNull()
        if (pushed?.isSuccess == true) return true
        if (!adb.shell(": > $remote", 30_000L).ok) return false
        val encoded = runCatching {
            Base64.encodeToString(local.readBytes(), Base64.NO_WRAP)
        }.getOrNull() ?: return false
        for (chunk in encoded.chunked(16_000)) {
            val appended = adb.shell(
                "printf '%s' ${shellQuote(chunk)} | base64 -d >> $remote",
                60_000L,
            )
            if (!appended.ok) return false
        }
        return true
    }

    fun installApk(uri: Uri) {
        if (!adb.online.value) {
            toast("Projetor desconectado.", ToastKind.ERROR)
            return
        }
        toast("Instalando…")
        safeLaunch {
            val local = copyUriToCache(uri, "app.apk")
            if (local == null) {
                toast("Falha na instalação: não foi possível ler o APK.", ToastKind.ERROR)
                return@safeLaunch
            }
            val remote = "/data/local/tmp/radba-install.apk"
            try {
                if (!pushApkToTemp(local, remote)) {
                    toast("Falha na instalação: não foi possível enviar o APK.", ToastKind.ERROR)
                    return@safeLaunch
                }
                val result = runCatching { adb.shell("pm install -r $remote", 900_000L) }.getOrDefault(
                    ShellResult(1, "", "sem resposta"),
                )
                runCatching { adb.shell("rm -f $remote") }
                val combined = "${result.stdout}\n${result.stderr}".trim()
                if (result.ok && (combined.contains("Success", ignoreCase = true) || result.stderr.isBlank())) {
                    toast("App instalado.", ToastKind.OK)
                    loadApps()
                } else {
                    toast(
                        "Falha na instalação: ${trunc(combined.takeLast(110), 110)}",
                        ToastKind.ERROR,
                    )
                }
            } finally {
                local.delete()
            }
        }
    }

    fun renameFile(entry: FileEntry, newName: String) {
        val path = guardedEntry(entry) ?: return
        val name = newName.trim()
        if (name.isBlank() || name.contains('/')) return
        val destination = validRemote("${path.substringBeforeLast('/')}/$name")
        if (destination == null) {
            toast("Destino inválido (use /sdcard/…).", ToastKind.ERROR)
            return
        }
        safeLaunch {
            val result = runCatching {
                adb.shell("mv -f ${shellQuote(path)} ${shellQuote(destination)}", 120_000L)
            }.getOrDefault(ShellResult(1, "", "sem resposta"))
            fileFeedback(result)
        }
    }

    private fun copyOrMove(entry: FileEntry, target: String, move: Boolean) {
        val path = guardedEntry(entry) ?: return
        val destination = validRemote(target)
        if (destination == null) {
            toast("Destino inválido (use /sdcard/…).", ToastKind.ERROR)
            return
        }
        val command = "${if (move) "mv -f" else "cp -r"} ${shellQuote(path)} ${shellQuote(destination.trimEnd('/') + "/")}"
        safeLaunch {
            val result = runCatching { adb.shell(command, 600_000L) }.getOrDefault(
                ShellResult(1, "", "sem resposta"),
            )
            fileFeedback(result)
        }
    }

    fun copyFile(entry: FileEntry, target: String) = copyOrMove(entry, target, false)

    fun moveFile(entry: FileEntry, target: String) = copyOrMove(entry, target, true)

    fun deleteFile(entry: FileEntry) {
        val path = guardedEntry(entry) ?: return
        safeLaunch {
            val result = runCatching { adb.shell("rm -rf ${shellQuote(path)}", 300_000L) }.getOrDefault(
                ShellResult(1, "", "sem resposta"),
            )
            fileFeedback(result)
        }
    }

    fun newFolder(name: String) {
        val value = name.trim()
        if (value.isBlank() || value.contains('/')) return
        val destination = validRemote("${_ui.value.filesPath.trimEnd('/')}/$value")
        if (destination == null) {
            toast("Destino inválido (use /sdcard/…).", ToastKind.ERROR)
            return
        }
        safeLaunch {
            val result = runCatching { adb.shell("mkdir -p ${shellQuote(destination)}", 60_000L) }.getOrDefault(
                ShellResult(1, "", "sem resposta"),
            )
            fileFeedback(result)
        }
    }

    // ------------------------------------------------------------ terminal --

    private fun cleanTerminalLine(raw: String): String {
        var body = raw.trim()
        val low = body.lowercase(Locale.ROOT)
        when {
            low.startsWith("adb shell") -> body = body.substring("adb shell".length).trim()
            low.startsWith("adb ") -> body = body.substring(4).trim()
        }
        if (body.length >= 2 &&
            ((body.first() == '"' && body.last() == '"') || (body.first() == '\'' && body.last() == '\''))
        ) {
            body = body.substring(1, body.length - 1).trim()
        }
        return body
    }

    private fun appendTerminal(text: String, kind: TerminalKind = TerminalKind.OUTPUT) {
        _ui.update { state ->
            val lines = state.terminalLines + TerminalLine(text, kind)
            state.copy(terminalLines = if (lines.size > 1_000) lines.takeLast(1_000) else lines)
        }
    }

    fun runTerminal(command: String) {
        val raw = command.trim()
        if (raw.isEmpty() || _ui.value.terminalBusy) return
        if (terminalHistory.lastOrNull() != raw) {
            terminalHistory.add(raw)
            if (terminalHistory.size > 200) terminalHistory.subList(0, terminalHistory.size - 200).clear()
        }
        terminalIndex = -1
        _ui.update { it.copy(terminalDraft = "", terminalBusy = true) }

        val hostCommand = raw.startsWith("!")
        val body = if (hostCommand) raw.drop(1).trim() else raw
        val lines = body.lineSequence()
            .map { cleanTerminalLine(it) }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) {
            _ui.update { it.copy(terminalBusy = false) }
            return
        }

        safeLaunch {
            try {
                val commands = if (hostCommand) listOf(lines.first()) else lines
                for (line in commands) {
                    appendTerminal(("${'$'} " + (if (hostCommand) "! " else "")) + line, TerminalKind.COMMAND)
                    if (hostCommand) {
                        appendTerminal("! comandos do PC não são suportados no Android.", TerminalKind.ERROR)
                        continue
                    }
                    if (!adb.online.value) {
                        appendTerminal("Projetor desconectado: sem resposta do adb.", TerminalKind.ERROR)
                        break
                    }
                    val result = try {
                        adb.shell(line, 120_000L)
                    } catch (t: Throwable) {
                        appendTerminal(trunc(t.message ?: "Falha no comando.", 200), TerminalKind.ERROR)
                        break
                    }
                    val output = (result.stdout.trim() + if (result.stderr.isNotBlank()) {
                        "\n${result.stderr.trim()}"
                    } else {
                        ""
                    }).trim()
                    if (output.isNotEmpty()) {
                        appendTerminal(output, if (result.ok) TerminalKind.OUTPUT else TerminalKind.ERROR)
                    }
                    if (!result.ok) appendTerminal("[exit ${result.exitCode}]", TerminalKind.ERROR)
                }
            } finally {
                _ui.update { it.copy(terminalBusy = false) }
            }
        }
    }

    fun clearTerminal() {
        _ui.update { it.copy(terminalLines = emptyList()) }
    }

    fun terminalHistoryUp() {
        if (terminalHistory.isEmpty()) return
        terminalIndex = if (terminalIndex < 0) {
            terminalHistory.lastIndex
        } else {
            (terminalIndex - 1).coerceAtLeast(0)
        }
        _ui.update { it.copy(terminalDraft = terminalHistory[terminalIndex]) }
    }

    fun terminalHistoryDown() {
        if (terminalIndex < 0) return
        terminalIndex++
        if (terminalIndex >= terminalHistory.size) {
            terminalIndex = -1
            _ui.update { it.copy(terminalDraft = "") }
        } else {
            _ui.update { it.copy(terminalDraft = terminalHistory[terminalIndex]) }
        }
    }

    fun setTerminalDraft(value: String) {
        _ui.update { it.copy(terminalDraft = value) }
    }
}
