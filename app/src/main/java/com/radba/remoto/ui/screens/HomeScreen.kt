package com.radba.remoto.ui.screens

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitPointerEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.MirrorState
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.GlassSlider
import com.radba.remoto.ui.theme.GlassColors
import com.radba.remoto.util.trunc
import kotlin.math.roundToInt

private val KEYS = listOf(
    "↩" to 4,
    "◀" to 21,
    "▲" to 19,
    "▼" to 20,
    "▶" to 22,
    "OK" to 23,
    "⌂" to 3,
    "≡" to 82,
    "⏯" to 85,
    "⏻" to 26,
)

/**
 * Início: the live mirror plus remote controls.
 *
 * When [showChrome] is false the screen keeps only the video surface, so the
 * session can stay alive behind another tab without showing or capturing any
 * controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    state: MainViewModel.UiState,
    showChrome: Boolean = true,
) {
    var liveTextOpen by remember { mutableStateOf(false) }
    var liveDraft by remember { mutableStateOf("") }
    var controlsExpanded by remember { mutableStateOf(true) }

    val mirrorOn = state.mirrorState == MirrorState.ON
    val waitingFrame = mirrorOn &&
        (state.mirrorFrameAgeMs < 0L || state.mirrorFrameAgeMs > 3_000L)
    val showShot = state.mirrorState == MirrorState.OFF &&
        !state.prefs.mirror && state.screenshot != null
    val progress = state.mirrorProgress.ifBlank { "Preparando transmissão…" }

    val stageMessage = when {
        state.connection == ConnectionState.DISCONNECTED -> "Projetor desconectado"
        state.prefs.mirror && state.mirrorError.isNotBlank() ->
            "Transmissão melhorada reconectando…\n${trunc(state.mirrorError, 110)}"
        waitingFrame -> "Aguardando primeiro frame…\n${trunc(progress, 110)}"
        mirrorOn -> null
        state.prefs.mirror -> "Iniciando transmissão melhorada…\n${trunc(progress, 110)}"
        state.screenshot != null -> null
        else -> state.screenshotStatus.ifBlank { "Aguardando conexão…" }
    }

    val status = when {
        state.connection == ConnectionState.DISCONNECTED -> "Desconectado"
        state.prefs.mirror && state.mirrorError.isNotBlank() -> "Reconectando…"
        waitingFrame -> "Aguardando primeiro frame…"
        mirrorOn -> buildString {
            if (state.mirrorFps >= 1) {
                append(state.mirrorFps.coerceAtMost(60)).append(" FPS")
                if (state.mirrorLatencyMs > 0) {
                    append(" · ").append(state.mirrorLatencyMs).append(" ms")
                }
            } else {
                append(state.mirrorTier)
            }
        }
        state.prefs.mirror -> trunc(progress, 52)
        else -> state.screenshotStatus.ifBlank { "Aguardando…" }
    }

    val screenshotBitmap = remember(state.screenshot) { state.screenshot?.asImageBitmap() }

    val touchModifier = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            runCatching { viewModel.screenTouch(0, down.position.x, down.position.y) }
            var last = down.position
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) {
                    runCatching { viewModel.screenTouch(1, change.position.x, change.position.y) }
                    break
                }
                if (change.position != last) {
                    runCatching { viewModel.screenTouch(2, change.position.x, change.position.y) }
                    last = change.position
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(GlassColors.bg)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            // Render the video at its real aspect ratio, centered, so it is not
            // stretched and the touch mapping (which assumes aspect-fit) matches
            // exactly what the user sees.
            val sourceWidth = when {
                mirrorOn && state.mirrorWidth > 0 && state.mirrorHeight > 0 -> state.mirrorWidth
                showShot -> screenshotBitmap?.width ?: 0
                else -> 0
            }
            val sourceHeight = when {
                mirrorOn && state.mirrorWidth > 0 && state.mirrorHeight > 0 -> state.mirrorHeight
                showShot -> screenshotBitmap?.height ?: 0
                else -> 0
            }
            val videoModifier = if (sourceWidth > 0 && sourceHeight > 0) {
                val scale = minOf(
                    maxWidth.value / sourceWidth.toFloat(),
                    maxHeight.value / sourceHeight.toFloat(),
                )
                Modifier.size(
                    width = (sourceWidth * scale).coerceAtLeast(1f).dp,
                    height = (sourceHeight * scale).coerceAtLeast(1f).dp,
                )
            } else {
                Modifier.fillMaxSize()
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(videoModifier)
                    .onSizeChanged { viewModel.updateMirrorView(it.width, it.height) }
                    .then(if (showChrome) touchModifier else Modifier),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(if (showShot) 0f else 1f),
                    factory = { context ->
                        var mirrorSurface: Surface? = null
                        TextureView(context).apply {
                            isOpaque = true
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    runCatching {
                                        val previous = mirrorSurface
                                        val next = Surface(surfaceTexture)
                                        mirrorSurface = next
                                        viewModel.attachMirrorSurface(next)
                                        viewModel.updateMirrorView(width, height)
                                        viewModel.ensureMirror()
                                        if (previous != null && previous !== next) {
                                            Handler(Looper.getMainLooper()).postDelayed({
                                                runCatching { previous.release() }
                                            }, 500L)
                                        }
                                    }
                                }

                                override fun onSurfaceTextureSizeChanged(
                                    surfaceTexture: SurfaceTexture,
                                    width: Int,
                                    height: Int,
                                ) {
                                    runCatching {
                                        viewModel.updateMirrorView(width, height)
                                        viewModel.ensureMirror()
                                    }
                                }

                                override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                    val previous = mirrorSurface
                                    if (previous != null) {
                                        runCatching { viewModel.detachMirrorSurface(previous) }
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            runCatching { previous.release() }
                                        }, 500L)
                                    }
                                    mirrorSurface = null
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                                    viewModel.noteMirrorFrame()
                                }
                            }
                        }
                    },
                )

                if (showShot && screenshotBitmap != null) {
                    Image(
                        bitmap = screenshotBitmap,
                        contentDescription = "Captura do projetor",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (stageMessage != null) {
                Text(
                    text = stageMessage,
                    color = GlassColors.sub,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(20.dp),
                )
            }
        }

        if (!showChrome) return@Column

        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            corner = 20.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp,
                vertical = 6.dp,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = status,
                    color = if (waitingFrame || (state.mirrorError.isNotBlank() && state.prefs.mirror)) {
                        GlassColors.warn
                    } else {
                        GlassColors.sub
                    },
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "vol ${state.volumePct}%", color = GlassColors.sub, fontSize = 11.sp)
                GlassButton(
                    label = if (controlsExpanded) "⌄" else "⌃",
                    onClick = { controlsExpanded = !controlsExpanded },
                    modifier = Modifier.width(52.dp),
                    compact = true,
                )
            }

            fun submitLiveEnter() {
                viewModel.sendKey(KeyEvent.KEYCODE_ENTER)
                liveDraft = ""
            }

            fun applyLiveChange(next: String) {
                val previous = liveDraft
                when {
                    next.length > previous.length && next.startsWith(previous) -> {
                        val inserted = next.substring(previous.length).replace("\r\n", "\n")
                        if (inserted.isNotEmpty()) viewModel.sendText(inserted)
                        liveDraft = next.takeLast(40)
                    }
                    next.length < previous.length && previous.startsWith(next) -> {
                        val removed = previous.length - next.length
                        viewModel.sendKey(
                            if (removed == 1) KeyEvent.KEYCODE_DEL else KeyEvent.KEYCODE_FORWARD_DEL,
                        )
                        liveDraft = next
                    }
                    else -> liveDraft = next
                }
            }

            if (liveTextOpen) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "AO VIVO",
                        color = GlassColors.bg,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(GlassColors.acc, RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    TextField(
                        value = liveDraft,
                        onValueChange = { applyLiveChange(it) },
                        singleLine = true,
                        placeholder = { Text(text = "Digite ao vivo · cola · Enter envia", color = GlassColors.sub) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitLiveEnter() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = GlassColors.glassHi,
                            unfocusedContainerColor = GlassColors.glassHi,
                            focusedTextColor = GlassColors.text,
                            unfocusedTextColor = GlassColors.text,
                            cursorColor = GlassColors.acc,
                            focusedIndicatorColor = GlassColors.acc,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .onKeyEvent { event ->
                                when (event.nativeKeyEvent.keyCode) {
                                    KeyEvent.KEYCODE_ESCAPE -> {
                                        liveTextOpen = false
                                        liveDraft = ""
                                        true
                                    }
                                    KeyEvent.KEYCODE_ENTER -> {
                                        submitLiveEnter()
                                        true
                                    }
                                    KeyEvent.KEYCODE_DEL -> {
                                        viewModel.sendKey(KeyEvent.KEYCODE_DEL)
                                        liveDraft = liveDraft.dropLast(1)
                                        true
                                    }
                                    KeyEvent.KEYCODE_FORWARD_DEL,
                                    KeyEvent.KEYCODE_TAB,
                                    KeyEvent.KEYCODE_DPAD_UP,
                                    KeyEvent.KEYCODE_DPAD_DOWN,
                                    KeyEvent.KEYCODE_DPAD_LEFT,
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        viewModel.sendKey(event.nativeKeyEvent.keyCode)
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                    GlassButton(
                        label = "↵",
                        onClick = { submitLiveEnter() },
                        modifier = Modifier.width(52.dp),
                        selected = true,
                        compact = true,
                    )
                    GlassButton(
                        label = "✕",
                        onClick = {
                            liveTextOpen = false
                            liveDraft = ""
                        },
                        modifier = Modifier.width(52.dp),
                        compact = true,
                    )
                }
            }

            if (controlsExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 250.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for ((glyph, code) in KEYS) {
                            GlassButton(
                                label = glyph,
                                onClick = { viewModel.sendKey(code) },
                                modifier = Modifier.width(52.dp),
                                selected = glyph == "OK",
                                danger = glyph == "⏻",
                                compact = true,
                            )
                        }
                        GlassButton(
                            label = "⌨ Texto",
                            onClick = {
                                liveTextOpen = !liveTextOpen
                                if (liveTextOpen) controlsExpanded = true
                            },
                            modifier = Modifier.width(76.dp),
                            selected = liveTextOpen,
                            compact = true,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = "🔊", color = GlassColors.acc, fontSize = 14.sp)
                        GlassButton("−", { viewModel.nudgeVolume(-5) }, modifier = Modifier.width(44.dp), compact = true)
                        GlassSlider(
                            value = state.volumePct.toFloat(),
                            onValueChange = { value -> viewModel.setVolumePct(value.roundToInt(), true) },
                            modifier = Modifier.weight(1f),
                        )
                        GlassButton("+", { viewModel.nudgeVolume(5) }, modifier = Modifier.width(44.dp), compact = true)
                        Text(
                            text = "${state.volumePct}%",
                            color = GlassColors.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(42.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(text = "☀", color = GlassColors.warn, fontSize = 14.sp)
                        GlassButton("−", { viewModel.nudgeBrightness(-5) }, modifier = Modifier.width(44.dp), compact = true)
                        GlassSlider(
                            value = state.brightnessPct.toFloat(),
                            onValueChange = { value -> viewModel.setBrightness(value.roundToInt(), null, true) },
                            modifier = Modifier.weight(1f),
                            valueRange = 10f..100f,
                            accent = GlassColors.warn,
                        )
                        GlassButton("+", { viewModel.nudgeBrightness(5) }, modifier = Modifier.width(44.dp), compact = true)
                        Text(
                            text = "${state.brightnessPct}%",
                            color = GlassColors.text,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(42.dp),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GlassButton(
                            label = if (state.brightnessPct < 100 || state.warmPct > 0) {
                                "☀ Restaurar 100%"
                            } else {
                                "☾ Madrugada"
                            },
                            onClick = { viewModel.toggleNight() },
                            modifier = Modifier.weight(1f),
                            selected = state.brightnessPct < 100 || state.warmPct > 0,
                            compact = true,
                        )
                        GlassButton(
                            label = if (state.blackout) "☀ Acordar tela" else "◼ Tela preta",
                            onClick = {
                                if (state.blackout) {
                                    viewModel.setBlackout(false)
                                    viewModel.wakeScreen()
                                } else {
                                    viewModel.setBlackout(true)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            danger = state.blackout,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}
