package com.radba.remoto.ui.screens

import android.os.SystemClock
import android.view.Surface
import android.view.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.awaitEachGesture
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEventScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.MirrorState
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassSlider
import com.radba.remoto.ui.theme.GlassColors
import com.radba.remoto.util.mmss
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

@Composable
fun HomeScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    var textDialog by remember { mutableStateOf(false) }
    var textDraft by remember { mutableStateOf("") }
    val lastHover = remember { longArrayOf(0L) }
    val shot = state.screenshot
    val showShot = state.mirrorState != MirrorState.ON && shot != null

    Column(modifier = Modifier.fillMaxSize().background(GlassColors.bg)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black),
        ) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (showShot) 0f else 1f)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            runCatching { viewModel.screenTouch(0, down.position.x, down.position.y) }
                            var last = down.position
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    runCatching { viewModel.screenTouch(1, change.position.x, change.position.y) }
                                    active = false
                                } else if (change.position != last) {
                                    runCatching { viewModel.screenTouch(2, change.position.x, change.position.y) }
                                    last = change.position
                                }
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Move && state.mirrorState == MirrorState.ON) {
                                    val change = event.changes.firstOrNull()
                                    if (change != null && !change.pressed) {
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastHover[0] >= 50L) {
                                            lastHover[0] = now
                                            runCatching {
                                                viewModel.screenTouch(7, change.position.x, change.position.y)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
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
                                    mirrorSurface?.release()
                                    mirrorSurface = Surface(surfaceTexture)
                                    viewModel.attachMirrorSurface(mirrorSurface)
                                    viewModel.updateMirrorView(width, height)
                                    viewModel.startMirror()
                                }
                            }

                            override fun onSurfaceTextureSizeChanged(
                                surfaceTexture: SurfaceTexture,
                                width: Int,
                                height: Int,
                            ) {
                                runCatching {
                                    viewModel.updateMirrorView(width, height)
                                    viewModel.startMirror()
                                }
                            }

                            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                                runCatching { viewModel.attachMirrorSurface(null) }
                                mirrorSurface?.release()
                                mirrorSurface = null
                                return true
                            }

                            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                        }
                    }
                },
            )
            if (showShot && shot != null) {
                Image(
                    bitmap = shot.asImageBitmap(),
                    contentDescription = "Captura do projetor",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.mirrorState != MirrorState.ON && shot == null) {
                Text(
                    text = "Aguardando…",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        val status = when {
            state.mirrorState == MirrorState.ON ->
                "Espelho ao vivo" + if (state.activeEncoder.isNotBlank()) " · ${state.activeEncoder}" else ""
            state.mirrorState == MirrorState.STARTING -> "Iniciando espelho…"
            state.mirrorError.isNotBlank() -> state.mirrorError
            shot != null -> state.screenshotStatus
            else -> "Aguardando…"
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for ((glyph, code) in KEYS) {
                    GlassButton(
                        label = glyph,
                        onClick = { viewModel.sendKey(code) },
                        modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                        selected = glyph == "OK",
                        danger = glyph == "⏻",
                        compact = true,
                    )
                }
                GlassButton(
                    label = "⌨",
                    onClick = { textDialog = true },
                    modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                    compact = true,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton("−", { viewModel.nudgeVolume(-5) }, modifier = Modifier.weight(0.10f), compact = true)
                GlassSlider(
                    value = state.volumePct.toFloat(),
                    onValueChange = { value -> viewModel.setVolumePct(value.roundToInt(), true) },
                    modifier = Modifier.weight(0.62f),
                )
                GlassButton("+", { viewModel.nudgeVolume(5) }, modifier = Modifier.weight(0.10f), compact = true)
                Text(
                    text = "${state.volumePct}%",
                    color = GlassColors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.18f),
                )
            }
            Text(
                text = "$status · volume ${state.volumePct}/${state.volumeMax}",
                color = GlassColors.sub,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
                maxLines = 1,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "☀", color = GlassColors.warn, fontSize = 15.sp)
                GlassButton("−", { viewModel.nudgeBrightness(-5) }, modifier = Modifier.weight(0.10f), compact = true)
                GlassSlider(
                    value = state.brightnessPct.toFloat(),
                    onValueChange = { value -> viewModel.setBrightness(value.roundToInt(), null, true) },
                    modifier = Modifier.weight(0.48f),
                    valueRange = 10f..100f,
                    accent = GlassColors.warn,
                )
                GlassButton("+", { viewModel.nudgeBrightness(5) }, modifier = Modifier.weight(0.10f), compact = true)
                Text(
                    text = "${state.brightnessPct}%",
                    color = GlassColors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(0.12f),
                )
                GlassButton(
                    label = "☾ Madrugada",
                    onClick = { viewModel.toggleNight() },
                    modifier = Modifier.weight(0.24f),
                    selected = state.brightnessPct < 100 || state.warmPct > 0,
                    compact = true,
                )
            }

            var dragFraction by remember { mutableFloatStateOf(-1f) }
            val duration = state.media.duration
            val fraction = if (duration > 0.0) {
                (state.mediaNow / duration).coerceIn(0.0, 1.0).toFloat()
            } else {
                0f
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton("⏪ 10s", { viewModel.mediaSeek(-10) }, modifier = Modifier.weight(0.18f), compact = true)
                GlassSlider(
                    value = if (dragFraction >= 0f) dragFraction else fraction,
                    onValueChange = { value -> dragFraction = value },
                    onValueChangeFinished = {
                        if (dragFraction >= 0f) viewModel.seekToFraction(dragFraction)
                        dragFraction = -1f
                    },
                    modifier = Modifier.weight(0.48f),
                    valueRange = 0f..1f,
                )
                GlassButton("10s ⏩", { viewModel.mediaSeek(10) }, modifier = Modifier.weight(0.18f), compact = true)
                GlassButton("↻", { viewModel.pollMedia() }, modifier = Modifier.weight(0.08f), compact = true)
                Text(
                    text = "${mmss(state.mediaNow)}/${mmss(duration)}",
                    color = GlassColors.sub,
                    fontSize = 10.sp,
                    modifier = Modifier.weight(0.24f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton(
                    label = if (state.mirrorState == MirrorState.OFF) "▶ Iniciar espelho" else "■ Parar espelho",
                    onClick = {
                        if (state.mirrorState == MirrorState.OFF) viewModel.startMirror() else viewModel.stopMirror()
                    },
                    modifier = Modifier.weight(1f),
                    selected = state.mirrorState == MirrorState.ON,
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

    if (textDialog) {
        AlertDialog(
            onDismissRequest = { textDialog = false },
            title = { Text(text = "Enviar texto", color = GlassColors.text) },
            text = {
                TextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    singleLine = true,
                    label = { Text(text = "Texto") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GlassColors.glassHi,
                        unfocusedContainerColor = GlassColors.glassHi,
                        focusedTextColor = GlassColors.text,
                        unfocusedTextColor = GlassColors.text,
                        cursorColor = GlassColors.acc,
                        focusedIndicatorColor = GlassColors.acc,
                        unfocusedIndicatorColor = GlassColors.edgeHi,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { viewModel.sendText(textDraft) }
                    textDialog = false
                }) { Text(text = "Enviar", color = GlassColors.acc) }
            },
            dismissButton = {
                TextButton(onClick = { textDialog = false }) { Text(text = "Cancelar", color = GlassColors.sub) }
            },
        )
    }
}
