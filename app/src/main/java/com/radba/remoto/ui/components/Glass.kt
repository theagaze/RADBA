package com.radba.remoto.ui.components

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.data.ToastKind
import com.radba.remoto.data.ToastMessage
import com.radba.remoto.ui.theme.GlassColors
import kotlinx.coroutines.flow.Flow

/** RenderEffect real no API 31+; em versões antigas o vidro mantém sombra/borda sem blur. */
private fun Modifier.glassGlow(accent: Color): Modifier = this.then(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier.graphicsLayer {
            renderEffect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                .asComposeRenderEffect()
        }
    } else {
        Modifier
    }
)

/** Cartão de vidro: sombra, borda sutil, brilho superior e reflexo difuso. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color = GlassColors.acc,
    corner: Dp = 18.dp,
    contentPadding: PaddingValues = PaddingValues(14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    val clickModifier = onClick?.let { callback ->
        Modifier.clickable { runCatching { callback() } }
    } ?: Modifier
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.55f),
                spotColor = accent.copy(alpha = 0.16f),
            )
            .clip(shape)
            .background(GlassColors.glass)
            .border(1.dp, GlassColors.edge, shape)
            .then(clickModifier),
    ) {
        Box(modifier = Modifier.matchParentSize().glassGlow(accent)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-24).dp, y = (-32).dp)
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 28.dp, y = 26.dp)
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(GlassColors.acc2.copy(alpha = 0.14f), Color.Transparent),
                        ),
                    ),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            accent.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content,
        )
    }
}

/** Botão pílula com estados normal/selecionado/erro e clique protegido. */
@Composable
fun GlassButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val accent = when {
        danger -> GlassColors.bad
        selected -> GlassColors.acc
        else -> GlassColors.edgeHi
    }
    val textColor = when {
        danger -> GlassColors.bad
        selected -> GlassColors.acc
        else -> GlassColors.text
    }
    GlassCard(
        // Alvos de toque de pelo menos 44dp, confortáveis em telefone.
        modifier = modifier.height(if (compact) 44.dp else 48.dp),
        onClick = if (enabled) onClick else null,
        accent = accent,
        corner = 50.dp,
        contentPadding = PaddingValues(
            horizontal = if (compact) 10.dp else 14.dp,
            vertical = 8.dp,
        ),
    ) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (enabled) textColor else GlassColors.sub,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** Interruptor de vidro, com rótulo opcional. */
@Composable
fun GlassToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    accent: Color = GlassColors.acc,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label != null) {
            Text(text = label, color = GlassColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        Switch(
            checked = checked,
            onCheckedChange = { value -> runCatching { onCheckedChange(value) } },
            modifier = Modifier.height(48.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlassColors.bg,
                checkedTrackColor = accent,
                uncheckedThumbColor = GlassColors.sub,
                uncheckedTrackColor = GlassColors.glassHi,
            ),
        )
    }
}

/** Slider de vidro com faixa configurável e clique protegido. */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = GlassColors.acc,
) {
    Slider(
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        onValueChange = { next -> runCatching { onValueChange(next) } },
        modifier = modifier.height(44.dp),
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished?.let { callback ->
            { runCatching { callback() } }
        },
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = GlassColors.edge,
        ),
    )
}

/** Modal bottom sheet com aparência de vidro. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(
    title: String,
    subtitle: String? = null,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { runCatching { onDismiss() } },
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        containerColor = GlassColors.glassHi,
        contentColor = GlassColors.text,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Text(text = title, color = GlassColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = subtitle, color = GlassColors.sub, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

private data class RadbaSnackbarVisuals(
    override val message: String,
    val toastKind: ToastKind,
    override val duration: SnackbarDuration,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
) : SnackbarVisuals

/** Overlay global de toasts vindos do ViewModel. */
@Composable
fun ToastHost(toasts: Flow<ToastMessage>, modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(toasts) {
        toasts.collect { message ->
            hostState.showSnackbar(
                RadbaSnackbarVisuals(
                    message = message.text,
                    toastKind = message.kind,
                    duration = if (message.kind == ToastKind.ERROR) {
                        SnackbarDuration.Long
                    } else {
                        SnackbarDuration.Short
                    },
                ),
            )
        }
    }
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        val accent = when ((data.visuals as? RadbaSnackbarVisuals)?.toastKind ?: ToastKind.INFO) {
            ToastKind.OK -> GlassColors.ok
            ToastKind.ERROR -> GlassColors.bad
            ToastKind.INFO -> GlassColors.acc
        }
        GlassCard(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth(),
            accent = accent,
            corner = 14.dp,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = data.visuals.message, color = GlassColors.text, fontSize = 14.sp)
        }
    }
}
