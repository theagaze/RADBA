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
import com.radba.remoto.data.AppMeta
import com.radba.remoto.data.AppPreferences
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.DeviceInfo
import com.radba.remoto.data.ErrorLogEntry
import com.radba.remoto.data.FileEntry
import com.radba.remoto.data.MirrorState
import com.radba.remoto.data.PerfStats
import com.radba.remoto.data.PictureBase
import com.radba.remoto.data.Prefs
import com.radba.remoto.data.ProcessInfo
import com.radba.remoto.data.RootStatus
import com.radba.remoto.data.ScreenshotResult
import com.radba.remoto.data.SettingsNote
import com.radba.remoto.data.Tab
import com.radba.remoto.data.TerminalKind
import com.radba.remoto.data.TerminalLine
import com.radba.remoto.data.ToastKind
import com.radba.remoto.data.ToastMessage
import com.radba.remoto.scrcpy.MirrorState as ScrcpyMirrorState
import com.radba.remoto.scrcpy.ScrcpyClient
import com.radba.remoto.util.clampInt
import com.radba.remoto.util.inputTextCommand
import com.radba.remoto.util.isInternalRoot
import com.radba.remoto.util.normalizeRemote
import com.radba.remoto.util.sanitizeHost
import com.radba.remoto.util.shellQuote
import com.radba.remoto.util.splitHost
import com.radba.remoto.util.trunc
import com.radba.remoto.util.volumeLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val DISPLAY_SIZE_REGEX = Regex("""(\d+)\s*[xX]\s*(\d+)""")

private const val TOUCH_HOVER = 7
private const val MIRROR_START_TIMEOUT_MS = 210_000L
private const val MIRROR_NO_FRAME_TIMEOUT_MS = 12_000L
private const val ERROR_LOG_LIMIT = 40

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
        val errorLog: List<ErrorLogEntry> = emptyList(),
        val volumePct: Int = 100,
        val volumeMax: Int = 15,
        val terminalLines: List<TerminalLine> = emptyList(),
        val terminalDraft: String = "",
        val terminalBusy: Boolean = false,
        val mirrorState: MirrorState = MirrorState.OFF,
        val mirrorError: String = "",
        val mirrorProgress: String = "",
        val mirrorFrameAgeMs: Long = -1L,
        val mirrorTier: String = "60 FPS Fluido",
        val mirrorFps: Int = 0,
        val mirrorLatencyMs: Int = 0,
        val blackout: Boolean = false,
        val brightnessPct: Int = 100,
        val warmPct: Int = 0,
        val mirrorBusy: Boolean = false,
        val screenshot: Bitmap? = null,
        val screenshotStatus: String = "Aguardando…",
        val perf: PerfStats? = null,
        val deviceInfo: DeviceInfo = DeviceInfo(),
        val cpuHistory: List<Float> = emptyList(),
        val ramHistory: List<Float> = emptyList(),
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
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val toasts: SharedFlow<ToastMessage> = _toasts.asSharedFlow()

    val terminalText: String
        get() = _ui.value.terminalLines.joinToString("\n") { it.text }

    private val reconnecting = AtomicBoolean(false)
    private val screenshotBusy = AtomicBoolean(false)
    private val perfBusy = AtomicBoolean(false)
    private val deviceBusy = AtomicBoolean(false)
    private val appMetaBusy = AtomicBoolean(false)
    private val coalescedJobs = ConcurrentHashMap<String, Job>()
    private val metaCache = ConcurrentHashMap<String, AppMeta>()
    private val terminalHistory = mutableListOf<String>()
    private var terminalIndex = -1
    private var terminalJob: Job? = null
    private var metaGeneration = 0
    private var volumeHoldUntilMs = 0L
    private var lastVdimErrorMs = 0L
    private var mirrorRetryAfterMs = 0L
    private var mirrorAttemptId = 0
    private var mirrorBusySinceMs = 0L
    private var mirrorFailStreak = 0
    private var lastOnline: Boolean? = false
    private var lastOrientation = -1
    private var pendingOrientationRestart = false

    @Volatile
    private var lastMirrorFrameAtMs = 0L

    @Volatile
    private var mirrorSurfaceToken: Any? = null

    @Volatile
    private var viewWidth = 0

    @Volatile
    private var viewHeight = 0

    @Volatile
    private var touchStart: Pair<Int, Int>? = null

    @Volatile
    private var touchLast: Pair<Int, Int>? = null

    @Volatile
    private var touchStartMs = 0L

    init {
        adb.setHost(prefsFlow.value.host)
        adb.startMonitor(viewModelScope, controlAlive = { scrcpy.isControlReady() }) { ok ->
            adb.online.value = ok
        }

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
                val mapped = when (scrcpyState) {
                    ScrcpyMirrorState.STARTING -> MirrorState.STARTING
                    ScrcpyMirrorState.ON -> MirrorState.ON
                    ScrcpyMirrorState.OFF -> MirrorState.OFF
                }
                _ui.update {
                    it.copy(
                        mirrorState = mapped,
                        mirrorError = if (mapped == MirrorState.ON) "" else it.mirrorError,
                        mirrorProgress = if (mapped == MirrorState.ON) "" else it.mirrorProgress,
                    )
                }
            }
        }
        viewModelScope.launch {
            scrcpy.error.collect { error ->
                if (error.isNotBlank()) {
                    _ui.update { it.copy(mirrorError = error) }
                    logProblem("scrcpy: ${trunc(error, 220)}")
                }
            }
        }
        viewModelScope.launch {
            scrcpy.mirrorStats.collect { stats ->
                _ui.update {
                    it.copy(
                        mirrorTier = stats.tierName,
                        mirrorFps = stats.fps,
                        mirrorLatencyMs = stats.pipeMs,
                    )
                }
            }
        }

        viewModelScope.launch { runCatching { prefsStore.migrate() } }

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

        periodic(2_000L) {
            if (_ui.value.tab == Tab.PERF && adb.online.value) refreshPerfInternal()
        }
        periodic(1_000L) { mirrorWatchdog() }
        periodic(2_500L) { ensureMirrorInternal() }
        periodic(3_000L) { ensureControlInternal() }
        periodic(4_000L) { fourSecondTick() }
        periodic(5_000L) { fiveSecondTick() }
        periodic(15_000L) { refreshVolumeInternal() }
    }

    override fun onCleared() {
        adb.stopMonitor()
        scrcpy.close()
        adb.close()
        super.onCleared()
    }

    // ------------------------------------------------------------ infra --

    private fun toast(text: String, kind: ToastKind = ToastKind.INFO, logError: Boolean = true) {
        _toasts.tryEmit(ToastMessage(text, kind))
        if (kind == ToastKind.ERROR && logError) logProblem(text)
    }

    private fun note(text: String, kind: ToastKind = ToastKind.INFO, logError: Boolean = true) {
        _ui.update { it.copy(settingsNote = SettingsNote(text, kind)) }
        if (kind == ToastKind.ERROR && logError) logProblem(text)
    }

    private fun noteAndToast(text: String, kind: ToastKind) {
        note(text, kind, logError = false)
        toast(text, kind, logError = false)
        if (kind == ToastKind.ERROR) logProblem(text)
    }

    /** Error-only diagnostic ring. Repeated messages are collapsed with a counter. */
    private fun logProblem(raw: String) {
        if (!_ui.value.prefs.verboseErrors) return
        val message = trunc(raw.trim(), 220)
        if (message.isBlank()) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        _ui.update { state ->
            val existing = state.errorLog.firstOrNull { it.text == message }
            val updated = if (existing != null) {
                state.errorLog.filterNot { it.text == message } +
                    existing.copy(time = time, count = existing.count + 1)
            } else {
                state.errorLog + ErrorLogEntry(time, message)
            }
            state.copy(errorLog = updated.takeLast(ERROR_LOG_LIMIT))
        }
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

    private fun applyReconnectResult(result: Result<Unit>, showMessages: Boolean) {
        if (result.isSuccess) {
            if (showMessages) {
                note("Conectado a ${adb.host.value}.", ToastKind.OK)
                toast("Conectado.", ToastKind.OK)
            }
            handleOnline(true)
        } else {
            val message = result.exceptionOrNull()?.message.orEmpty()
            if (!showMessages) {
                logProblem("Não conectou em ${adb.host.value}: ${trunc(message.ifBlank { "sem resposta" }, 140)}")
            }
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
                    toast("Não conectou.", ToastKind.ERROR, logError = false)
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
                // Control-only scrcpy session makes keys fast even before video
                // is ready; it is independent from the video pipeline.
                safeLaunch { ensureControlInternal() }
                val rootJob = safeLaunchJob { refreshRootFeatures() }
                refreshVolumeInternal()
                refreshSettingsInternal()
                when (_ui.value.tab) {
                    Tab.APPS -> appsShown()
                    Tab.FILES -> reloadFiles()
                    Tab.PERF -> refreshPerf()
                    else -> Unit
                }
                rootJob.join()
                ensureMirrorInternal()
                if (!_ui.value.prefs.mirror) refreshScreenshot()
            }
        } else {
            if (!reconnecting.get()) logProblem("Conexão ADB perdida")
            scrcpy.stop()
            scrcpy.stopControl()
            scrcpy.resetTier()
            scrcpy.resetRootFixes()
            mirrorFailStreak = 0
            coalescedJobs.keys.toList().forEach { coalescedJobs[it]?.cancel() }
            coalescedJobs.clear()
            mirrorRetryAfterMs = 0L
            mirrorAttemptId++
            mirrorBusySinceMs = 0L
            lastMirrorFrameAtMs = 0L
            _ui.update {
                it.copy(
                    connection = ConnectionState.DISCONNECTED,
                    connectionText = "Desconectado",
                    mirrorState = MirrorState.OFF,
                    mirrorError = "",
                    mirrorProgress = "",
                    mirrorFrameAgeMs = -1L,
                    mirrorBusy = false,
                    rootStatus = RootStatus(),
                    screenshot = null,
                    screenshotStatus = "Desconectado",
                )
            }
        }
    }

    private suspend fun fourSecondTick() {
        if (!adb.online.value) return
        val state = _ui.value
        if (state.mirrorState != MirrorState.ON || state.mirrorBusy) return
        // With no visible surface there are no rendered-frame timestamps, so
        // adaptation would misread a tab switch as a network stall and restart
        // the whole mirror. Only adapt while Home owns the surface.
        if (state.tab != Tab.HOME || mirrorSurfaceToken == null || !scrcpy.surfaceReady.value) return
        when (scrcpy.checkNetworkAdapt()) {
            -1 -> {
                note("Rede instável: reduzindo para ${scrcpy.currentTier().name}.", ToastKind.INFO)
                restartMirrorInternal(announce = false)
            }
            1 -> {
                note("Rede estável: subindo para ${scrcpy.currentTier().name}.", ToastKind.INFO)
                restartMirrorInternal(announce = false)
            }
        }
    }

    private suspend fun fiveSecondTick() {
        if (!adb.online.value || _ui.value.prefs.mirror || scrcpy.isReady()) return
        if (_ui.value.tab == Tab.HOME) refreshScreenshot()
    }

    // ---------------------------------------------------------- conexao --

    /** Restart only after a real rotation so the video size and touch map match the new layout. */
    fun onOrientationChanged(orientation: Int) {
        if (lastOrientation == orientation) return
        val first = lastOrientation < 0
        lastOrientation = orientation
        if (first || !adb.online.value || !_ui.value.prefs.mirror) return
        if (_ui.value.mirrorBusy || _ui.value.mirrorState != MirrorState.ON) {
            pendingOrientationRestart = true
            return
        }
        safeLaunch { restartMirrorInternal(announce = false) }
    }

    fun selectTab(tab: Tab) {
        _ui.update { it.copy(tab = tab) }
        when (tab) {
            Tab.HOME -> safeLaunch { ensureMirrorInternal() }
            Tab.APPS -> appsShown()
            Tab.FILES -> reloadFiles()
            Tab.PERF -> refreshPerf()
            else -> Unit
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
                toast("Falha ao reconectar.", ToastKind.ERROR, logError = false)
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
        mirrorSurfaceToken = surface
        lastMirrorFrameAtMs = 0L
        scrcpy.attachSurface(surface)
        _ui.update { it.copy(mirrorFrameAgeMs = -1L) }
    }

    /** A late destroy callback from an old view must not detach the new surface. */
    fun detachMirrorSurface(surface: Surface?) {
        if (surface != null && surface !== mirrorSurfaceToken) return
        mirrorSurfaceToken = null
        lastMirrorFrameAtMs = 0L
        scrcpy.attachSurface(null)
        _ui.update { it.copy(mirrorFrameAgeMs = -1L) }
    }

    fun noteMirrorFrame() {
        lastMirrorFrameAtMs = SystemClock.elapsedRealtime()
    }

    fun updateMirrorView(width: Int, height: Int) {
        viewWidth = width.coerceAtLeast(0)
        viewHeight = height.coerceAtLeast(0)
        scrcpy.setViewSize(viewWidth, viewHeight)
    }

    private suspend fun ensureControlInternal() {
        if (!adb.online.value || scrcpy.isReady() || scrcpy.isControlReady()) return
        scrcpy.setDisplayOff(_ui.value.blackout)
        val started = runCatching { scrcpy.startControl() }.getOrDefault(false)
        if (started && _ui.value.blackout) scrcpy.setDisplayPower(false)
    }

    private fun mirrorBackoffMs(): Long {
        mirrorFailStreak = (mirrorFailStreak + 1).coerceAtMost(6)
        return (2_500L shl (mirrorFailStreak - 1)).coerceAtMost(30_000L)
    }

    /** Surface callbacks restore the stream; they never force a restart. */
    fun ensureMirror() {
        safeLaunch { ensureMirrorInternal() }
    }

    private fun consumePendingOrientationRestart() {
        if (!pendingOrientationRestart) return
        if (SystemClock.elapsedRealtime() < mirrorRetryAfterMs) return
        pendingOrientationRestart = false
        if (adb.online.value && _ui.value.prefs.mirror) {
            safeLaunch { restartMirrorInternal(announce = false) }
        }
    }

    private fun handleMirrorProgress(attempt: Int, message: String) {
        note(message, ToastKind.INFO)
        if (attempt == mirrorAttemptId) {
            _ui.update { it.copy(mirrorProgress = trunc(message, 120)) }
        }
    }

    private suspend fun mirrorWatchdog() {
        val now = SystemClock.elapsedRealtime()
        val frameAge = if (lastMirrorFrameAtMs > 0L) {
            (now - lastMirrorFrameAtMs).coerceAtLeast(0L)
        } else {
            -1L
        }
        _ui.update { it.copy(mirrorFrameAgeMs = frameAge) }
        val state = _ui.value
        if (!state.prefs.mirror) return

        if (state.mirrorBusy || state.mirrorState == MirrorState.STARTING) {
            val since = mirrorBusySinceMs
            if (since <= 0L || now - since <= MIRROR_START_TIMEOUT_MS) return
            mirrorAttemptId++
            mirrorBusySinceMs = 0L
            mirrorRetryAfterMs = now + mirrorBackoffMs()
            scrcpy.stop()
            val message = "O espelho demorou para iniciar; tentando novamente."
            _ui.update {
                it.copy(
                    mirrorState = MirrorState.OFF,
                    mirrorBusy = false,
                    mirrorError = message,
                    mirrorProgress = "",
                )
            }
            noteAndToast(message, ToastKind.ERROR)
            consumePendingOrientationRestart()
            return
        }

        if (state.mirrorState == MirrorState.ON) {
            if (_ui.value.mirrorBusy) return
            val decoderError = scrcpy.decoderError()
            if (decoderError.isNotBlank() && now >= mirrorRetryAfterMs) {
                mirrorRetryAfterMs = now + 5_000L
                val message = "Decoder do vídeo falhou; reiniciando…\n${trunc(decoderError, 140)}"
                _ui.update { it.copy(mirrorError = message) }
                note(message, ToastKind.ERROR)
                safeLaunch { restartMirrorInternal(announce = false) }
                return
            }
            if (frameAge > MIRROR_NO_FRAME_TIMEOUT_MS && now >= mirrorRetryAfterMs) {
                mirrorRetryAfterMs = now + 5_000L
                noteAndToast("Sem frames no espelho; reiniciando…", ToastKind.ERROR)
                safeLaunch { restartMirrorInternal(announce = false) }
            }
        }
    }

    private suspend fun ensureMirrorInternal() {
        val state = _ui.value
        if (!state.prefs.mirror || !adb.online.value) return
        if (scrcpy.isReady() || state.mirrorBusy) return
        val now = SystemClock.elapsedRealtime()
        if (now < mirrorRetryAfterMs) return
        val attempt = ++mirrorAttemptId
        mirrorBusySinceMs = now
        _ui.update {
            it.copy(mirrorBusy = true, mirrorError = "", mirrorProgress = "Iniciando scrcpy…")
        }
        try {
            scrcpy.setDisplayOff(_ui.value.blackout)
            val ok = scrcpy.start { message -> handleMirrorProgress(attempt, message) }
            if (attempt != mirrorAttemptId) return
            if (ok) {
                mirrorFailStreak = 0
                _ui.update { it.copy(mirrorError = "", mirrorProgress = "") }
                if (_ui.value.blackout) scrcpy.setDisplayPower(false)
            } else {
                mirrorRetryAfterMs = SystemClock.elapsedRealtime() + mirrorBackoffMs()
                val reason = scrcpy.error.value.ifBlank { "espelho não iniciou" }
                _ui.update { it.copy(mirrorError = reason, mirrorProgress = "") }
                note("Espelho: ${trunc(reason, 140)}", ToastKind.ERROR)
            }
            refreshScreenshot()
        } catch (t: Throwable) {
            if (attempt == mirrorAttemptId) {
                mirrorRetryAfterMs = SystemClock.elapsedRealtime() + mirrorBackoffMs()
                val reason = trunc(t.message ?: "falha", 140)
                _ui.update { it.copy(mirrorError = reason, mirrorProgress = "") }
                note("Espelho: $reason", ToastKind.ERROR)
            }
        } finally {
            if (attempt == mirrorAttemptId) {
                mirrorBusySinceMs = 0L
                _ui.update { it.copy(mirrorBusy = false) }
                consumePendingOrientationRestart()
            }
        }
    }

    private suspend fun restartMirrorInternal(announce: Boolean) {
        if (!adb.online.value || _ui.value.mirrorBusy) return
        val attempt = ++mirrorAttemptId
        mirrorBusySinceMs = SystemClock.elapsedRealtime()
        _ui.update {
            it.copy(mirrorBusy = true, mirrorError = "", mirrorProgress = "Reiniciando scrcpy…")
        }
        if (announce) note("Iniciando transmissão melhorada…", ToastKind.INFO)
        try {
            scrcpy.setDisplayOff(_ui.value.blackout)
            scrcpy.stop()
            val ok = scrcpy.start { message -> handleMirrorProgress(attempt, message) }
            if (attempt != mirrorAttemptId) return
            if (ok) {
                mirrorFailStreak = 0
                _ui.update { it.copy(mirrorError = "", mirrorProgress = "") }
                if (_ui.value.blackout) scrcpy.setDisplayPower(false)
                if (announce) {
                    note("Transmissão melhorada ativa. Veja na tela Início.", ToastKind.OK)
                    toast("Espelho ativo.", ToastKind.OK)
                }
            } else {
                mirrorRetryAfterMs = SystemClock.elapsedRealtime() + mirrorBackoffMs()
                val reason = scrcpy.error.value.ifBlank { "motivo desconhecido" }
                _ui.update { it.copy(mirrorError = reason, mirrorProgress = "") }
                note("Espelho falhou: ${trunc(reason, 320)}", ToastKind.ERROR)
                if (announce) toast("Espelho falhou (detalhes em Ajustes).", ToastKind.ERROR, logError = false)
            }
        } catch (t: Throwable) {
            if (attempt == mirrorAttemptId) {
                mirrorRetryAfterMs = SystemClock.elapsedRealtime() + mirrorBackoffMs()
                val reason = trunc(failureDetail(t), 320)
                _ui.update { it.copy(mirrorError = reason, mirrorProgress = "") }
                note("Espelho falhou: $reason", ToastKind.ERROR)
                if (announce) toast("Espelho falhou (detalhes em Ajustes).", ToastKind.ERROR, logError = false)
            }
        } finally {
            if (attempt == mirrorAttemptId) {
                mirrorBusySinceMs = 0L
                _ui.update { it.copy(mirrorBusy = false) }
                refreshScreenshot()
                consumePendingOrientationRestart()
            }
        }
    }

    fun startMirror() {
        if (!adb.online.value) {
            noteAndToast("Projetor desconectado. Conecte primeiro.", ToastKind.ERROR)
            return
        }
        if (_ui.value.mirrorBusy) {
            noteAndToast("O espelho já está iniciando…", ToastKind.INFO)
            return
        }
        if (!_ui.value.prefs.mirror) {
            safeLaunch {
                prefsStore.update { it.copy(mirror = true) }
                _ui.update { it.copy(prefs = it.prefs.copy(mirror = true)) }
                mirrorRetryAfterMs = 0L
                restartMirrorInternal(announce = true)
            }
            return
        }
        safeLaunch { restartMirrorInternal(announce = true) }
    }

    fun stopMirror() {
        mirrorAttemptId++
        mirrorBusySinceMs = 0L
        mirrorFailStreak = 0
        scrcpy.stop()
        mirrorRetryAfterMs = 0L
        lastMirrorFrameAtMs = 0L
        _ui.update {
            it.copy(
                mirrorState = MirrorState.OFF,
                mirrorBusy = false,
                mirrorError = "",
                mirrorProgress = "",
                mirrorFrameAgeMs = -1L,
            )
        }
        toast("Espelho desligado.", ToastKind.INFO)
        safeLaunch { refreshScreenshot() }
    }

    fun screenTouch(action: Int, x: Float, y: Float) {
        if (action == TOUCH_HOVER && !scrcpy.isReady()) return
        val mirrorReady = scrcpy.isReady()
        val device = if (mirrorReady) scrcpy.screenToDevice(x, y) else mapFallbackTouch(x, y)
        if (mirrorReady) {
            scrcpy.sendTouch(action, device.first, device.second)
            return
        }
        when (action) {
            0 -> {
                touchStart = device
                touchLast = device
                touchStartMs = SystemClock.elapsedRealtime()
            }
            2 -> touchLast = device
            1 -> {
                val start = touchStart ?: device
                val end = touchLast ?: device
                val duration = (SystemClock.elapsedRealtime() - touchStartMs)
                    .coerceIn(50L, 3_000L).toInt()
                touchStart = null
                touchLast = null
                touchStartMs = 0L
                safeLaunch {
                    val command = "input swipe ${start.first} ${start.second} " +
                        "${end.first} ${end.second} $duration"
                    val result = adb.shell(command)
                    if (!result.ok) toast("Falha ao enviar toque: ${shellDetail(result, 70)}", ToastKind.ERROR)
                }
            }
        }
    }

    private fun mapFallbackTouch(x: Float, y: Float): Pair<Int, Int> {
        val state = _ui.value
        val size = state.screenshot?.let { it.width to it.height }
            ?: parseDisplaySize(state.deviceInfo.wmSize)
            ?: return 0 to 0
        val (deviceW, deviceH) = size
        if (viewWidth <= 0 || viewHeight <= 0) return 0 to 0
        val scale = minOf(viewWidth.toFloat() / deviceW, viewHeight.toFloat() / deviceH)
        if (scale <= 0f || scale.isNaN() || scale.isInfinite()) return 0 to 0
        val left = (viewWidth - deviceW * scale) / 2f
        val top = (viewHeight - deviceH * scale) / 2f
        val px = ((x - left) / scale).toInt().coerceIn(0, deviceW - 1)
        val py = ((y - top) / scale).toInt().coerceIn(0, deviceH - 1)
        return px to py
    }

    private fun parseDisplaySize(raw: String): Pair<Int, Int>? {
        val match = DISPLAY_SIZE_REGEX.find(raw) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) width to height else null
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

    // ------------------------------------------------------- screenshot --

    fun refreshScreenshot() {
        if (!screenshotBusy.compareAndSet(false, true)) return
        safeLaunch {
            try {
                val captureEnabled = adb.online.value && !_ui.value.prefs.mirror
                val capture = if (captureEnabled) {
                    runCatching { services.screenshot() }.getOrNull()
                        ?: ScreenshotResult(null, "captura indisponível")
                } else {
                    ScreenshotResult()
                }
                val bytes = capture.bytes
                val bitmap = if (bytes != null && bytes.isNotEmpty()) {
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                } else {
                    null
                }
                if (bitmap == null) {
                    val detail = capture.reason.ifBlank { "screencap indisponível neste dispositivo" }
                    val status = when {
                        !adb.online.value -> "Desconectado"
                        _ui.value.prefs.mirror -> "Aguardando espelho…"
                        else -> "Captura indisponível: $detail (o toque continua ativo)"
                    }
                    _ui.update { it.copy(screenshot = null, screenshotStatus = status) }
                    if (status.startsWith("Captura indisponível")) logProblem(status)
                } else {
                    val status = "Captura " + SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) +
                        " (desativada)"
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
        val pct = ((info.level.toFloat() / max.toFloat()) * 100f).roundToIntCompat().coerceIn(0, 100)
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

    // ------------------------------------------------------- brilho/root --

    private fun applyVdim(pct: Int, warm: Int) {
        coalesce("vdim") {
            val failure = try {
                services.applyVirtualBrightness(pct, warm).exceptionOrNull()
            } catch (t: Throwable) {
                t
            }
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
                _ui.update { current ->
                    current.copy(
                        prefs = current.prefs.copy(
                            nightBright = state.brightnessPct.coerceIn(10, 99),
                            nightWarm = state.warmPct.coerceIn(0, 100),
                        ),
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
        scrcpy.resetRootFixes()
        val current = prefsFlow.value
        if (current.rootFixVideo) {
            runCatching { applyVideoFixInternal(disable = true) }
        }
        if (current.vBright < 100 || current.vWarm > 0) {
            runCatching { services.applyVirtualBrightness(current.vBright, current.vWarm) }
        }
    }

    fun setRootFix(enabled: Boolean) {
        val previous = prefsFlow.value.rootFixVideo
        scrcpy.resetRootFixes()
        safeLaunch {
            prefsStore.update { it.copy(rootFixVideo = enabled) }
            _ui.update { it.copy(prefs = it.prefs.copy(rootFixVideo = enabled)) }
            if (!adb.online.value) return@safeLaunch
            val applied = runCatching { applyVideoFixInternal(disable = enabled) }.getOrNull()?.isSuccess == true
            if (applied) {
                val message = if (enabled) {
                    "Vídeo corrigido (1008=1 + decoder SW)."
                } else {
                    "Vídeo restaurado (1008=0 + decoder HW)."
                }
                noteAndToast(message, ToastKind.OK)
            } else {
                prefsStore.update { it.copy(rootFixVideo = previous) }
                _ui.update { it.copy(prefs = it.prefs.copy(rootFixVideo = previous)) }
                val message = "Root necessário para ${if (enabled) "corrigir" else "restaurar"} o vídeo."
                noteAndToast(message, ToastKind.ERROR)
            }
        }
    }

    // --------------------------------------------------------- preferencias --

    fun setVerboseErrors(enabled: Boolean) {
        _ui.update {
            it.copy(
                prefs = it.prefs.copy(verboseErrors = enabled),
                errorLog = if (enabled) it.errorLog else emptyList(),
            )
        }
        safeLaunch { prefsStore.update { it.copy(verboseErrors = enabled) } }
    }

    fun clearErrorLog() {
        _ui.update { it.copy(errorLog = emptyList()) }
    }

    fun setMirrorEnabled(enabled: Boolean) {
        _ui.update { it.copy(prefs = it.prefs.copy(mirror = enabled)) }
        safeLaunch { prefsStore.update { it.copy(mirror = enabled) } }
        if (!enabled) {
            stopMirror()
            scrcpy.resetTier()
        } else {
            mirrorRetryAfterMs = 0L
            safeLaunch { ensureMirrorInternal() }
        }
    }

    fun refreshSettings() {
        safeLaunch { refreshSettingsInternal() }
    }

    private suspend fun refreshSettingsInternal() {
        runCatching { adb.cachedRootStatus() }.getOrNull()?.let { cached ->
            _ui.update { it.copy(rootStatus = cached) }
        }
        refreshDeviceInfoInternal()
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
        scrcpy.setDisplayOff(on)
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
                        toast("Não foi possível apagar a tela.", ToastKind.ERROR)
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

    // ---------------------------------------------------------------- perf --

    fun refreshPerf() {
        if (!adb.online.value) return
        safeLaunch {
            refreshDeviceInfoInternal()
            refreshPerfInternal()
        }
    }

    private suspend fun refreshDeviceInfoInternal() {
        if (!adb.online.value || !deviceBusy.compareAndSet(false, true)) return
        try {
            val info = runCatching { services.deviceFullInfo() }.getOrNull() ?: return
            _ui.update { it.copy(deviceInfo = info) }
        } finally {
            deviceBusy.set(false)
        }
    }

    private suspend fun refreshPerfInternal() {
        if (!adb.online.value || !perfBusy.compareAndSet(false, true)) return
        try {
            val stats = runCatching { services.devicePerfStats() }.getOrNull() ?: return
            _ui.update { state ->
                state.copy(
                    perf = stats,
                    cpuHistory = (state.cpuHistory + stats.cpuPct).takeLast(40),
                    ramHistory = (state.ramHistory + stats.ramPct).takeLast(40),
                )
            }
        } finally {
            perfBusy.set(false)
        }
    }

    private fun Float.roundToIntCompat(): Int = if (isNaN()) 0 else Math.round(this)

    // ---------------------------------------------------------------- apps --

    fun appsShown() {
        if (!_ui.value.appsLoaded) {
            loadApps()
        } else {
            safeLaunch {
                refreshProcessesInternal()
                fetchAppMetadata()
            }
        }
    }

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
        metaGeneration++
        safeLaunch {
            val apps = runCatching { services.listLaunchableApps() }.getOrNull()
            if (apps == null) {
                toast("Falha ao listar apps.", ToastKind.ERROR)
                return@safeLaunch
            }
            _ui.update { it.copy(apps = apps, appsLoaded = true) }
            refreshProcessesInternal()
            fetchAppMetadata()
        }
    }

    private suspend fun fetchAppMetadata() {
        if (!adb.online.value || !appMetaBusy.compareAndSet(false, true)) return
        val generation = ++metaGeneration
        try {
            val state = _ui.value
            val pending = state.apps
                .filterNot { metaCache.containsKey(it.packageName) }
                .sortedWith(
                    compareBy<AppInfo>(
                        { !(it.running || it.packageName in state.favorites) },
                        { !(it.running && it.packageName in state.favorites) },
                        { !it.running },
                        { it.packageName !in state.favorites },
                        { it.title.lowercase(Locale.ROOT) },
                    ),
                )
                .map { it.packageName }
            for (pkg in pending) {
                if (generation != metaGeneration || !adb.online.value) break
                val meta = runCatching { services.extractAppMeta(pkg) }
                    .getOrDefault(AppMeta(null, null))
                metaCache[pkg] = meta
                val icon = meta.iconBytes?.let { bytes ->
                    withContext(Dispatchers.IO) {
                        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                    }
                }
                _ui.update { current ->
                    current.copy(
                        apps = current.apps.map { app ->
                            if (app.packageName != pkg) app
                            else app.copy(
                                title = meta.label?.takeIf { it.isNotBlank() } ?: app.title,
                                icon = icon ?: app.icon,
                            )
                        },
                    )
                }
                yield()
            }
        } finally {
            appMetaBusy.set(false)
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

        if (raw.equals("cls", ignoreCase = true) || raw.equals("clear", ignoreCase = true)) {
            clearTerminal()
            _ui.update { it.copy(terminalBusy = false) }
            return
        }

        val hostCommand = raw.startsWith("!")
        val body = if (hostCommand) raw.drop(1).trim() else raw
        if (hostCommand) {
            appendTerminal("! comandos do PC não são suportados no Android.", TerminalKind.ERROR)
            _ui.update { it.copy(terminalBusy = false) }
            return
        }
        val lines = body.lineSequence()
            .map { cleanTerminalLine(it) }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) {
            _ui.update { it.copy(terminalBusy = false) }
            return
        }

        terminalJob = safeLaunchJob {
            try {
                for (line in lines) {
                    appendTerminal("${'$'} $line", TerminalKind.COMMAND)
                    if (!adb.online.value) {
                        appendTerminal("Projetor desconectado: sem resposta do adb.", TerminalKind.ERROR)
                        break
                    }
                    val result = try {
                        adb.shell(line, 120_000L)
                    } catch (t: CancellationException) {
                        throw t
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
                terminalJob = null
                _ui.update { it.copy(terminalBusy = false) }
            }
        }
    }

    fun cancelTerminal() {
        if (!_ui.value.terminalBusy) return
        terminalJob?.cancel()
        terminalJob = null
        appendTerminal("^C", TerminalKind.SUB)
        _ui.update { it.copy(terminalBusy = false, terminalDraft = "") }
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
