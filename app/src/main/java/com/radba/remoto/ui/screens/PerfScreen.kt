package com.radba.remoto.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.GlassSheet
import com.radba.remoto.ui.theme.GlassColors
import com.radba.remoto.util.human
import java.util.Locale
import kotlin.math.roundToInt

private const val GRAPH_SAMPLES = 40

private fun usageColor(value: Float?, warnAt: Float, badAt: Float): Color = when {
    value == null -> GlassColors.sub
    value >= badAt -> GlassColors.bad
    value >= warnAt -> GlassColors.warn
    else -> GlassColors.ok
}

private fun pctText(value: Float?): String = if (value == null) "--%" else "${value.roundToInt()}%"

@Composable
private fun MetricGraph(
    value: Float?,
    history: List<Float>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxWidth().height(96.dp)) {
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            val topPad = 4.dp.toPx()
            val bottomPad = 4.dp.toPx()
            val plotH = (h - topPad - bottomPad).coerceAtLeast(1f)
            val yFor: (Float) -> Float = { pct -> topPad + plotH * (1f - pct.coerceIn(0f, 100f) / 100f) }

            val barWidth = 10.dp.toPx().coerceAtMost(w * 0.12f).coerceAtLeast(4f)
            val gap = 8.dp.toPx()
            val graphLeft = (barWidth + gap).coerceAtMost(w * 0.35f)
            val graphWidth = (w - graphLeft).coerceAtLeast(0f)

            for (tick in listOf(25f, 50f, 75f)) {
                val y = yFor(tick)
                drawLine(
                    color = GlassColors.edge,
                    start = Offset(graphLeft, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            drawLine(
                color = GlassColors.edge,
                start = Offset(0f, h - bottomPad),
                end = Offset(w, h - bottomPad),
                strokeWidth = 1.dp.toPx(),
            )

            val radiusValue = (barWidth / 2f).coerceAtMost(6.dp.toPx())
            val radius = CornerRadius(radiusValue, radiusValue)
            drawRoundRect(
                color = GlassColors.glassHi,
                topLeft = Offset(0f, topPad),
                size = Size(barWidth, plotH),
                cornerRadius = radius,
            )
            val barH = plotH * (value ?: 0f).coerceIn(0f, 100f) / 100f
            if (barH > 0f) {
                drawRoundRect(
                    color = accent,
                    topLeft = Offset(0f, topPad + plotH - barH),
                    size = Size(barWidth, barH),
                    cornerRadius = radius,
                )
            }

            val samples = history.takeLast(GRAPH_SAMPLES).map { it.coerceIn(0f, 100f) }
            if (samples.size >= 2 && graphWidth > 0f) {
                val step = graphWidth / (GRAPH_SAMPLES - 1)
                val startX = w - step * (samples.size - 1)
                val points = samples.mapIndexed { index, pct ->
                    Offset(startX + index * step, yFor(pct))
                }
                val area = Path().apply {
                    moveTo(points.first().x, topPad + plotH)
                    points.forEach { lineTo(it.x, it.y) }
                    lineTo(points.last().x, topPad + plotH)
                    close()
                }
                drawPath(path = area, color = accent.copy(alpha = 0.18f))
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                drawPath(
                    path = line,
                    color = accent,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerfScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    var processesSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refreshPerf()
        viewModel.refreshSettings()
    }

    val perf = state.perf
    val offline = state.connection == ConnectionState.DISCONNECTED
    val device = state.deviceInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard {
            Text(
                text = "Monitorando a cada 2 s",
                color = GlassColors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            if (offline) {
                Text(
                    text = "● Projetor desconectado",
                    color = GlassColors.bad,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "Os dados podem estar desatualizados. Verifique o endereço ADB e a rede.",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton(
                    label = "Processos",
                    onClick = {
                        viewModel.refreshProcesses()
                        processesSheet = true
                    },
                    modifier = Modifier.width(112.dp),
                    compact = true,
                )
                GlassButton(
                    label = "Acordar tela",
                    onClick = { viewModel.wakeScreen() },
                    modifier = Modifier.width(112.dp),
                    compact = true,
                    selected = true,
                )
                GlassButton(
                    label = "Atualizar",
                    onClick = {
                        viewModel.refreshPerf()
                        viewModel.refreshSettings()
                    },
                    modifier = Modifier.width(112.dp),
                    compact = true,
                )
            }
        }

        GlassCard(accent = usageColor(perf?.cpuPct, 60f, 85f)) {
            val cpuPct = perf?.cpuPct
            val cpuColor = usageColor(cpuPct, 60f, 85f)
            Text(
                text = "Processador (CPU)",
                color = GlassColors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = pctText(cpuPct),
                color = cpuColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (perf != null) {
                val freq = perf.freqMhz?.let { "$it MHz" } ?: "—"
                val temp = perf.tempC?.let { String.format(Locale.US, "%.1f°C", it) } ?: "—"
                val load = perf.load.takeIf { it.isNotBlank() }?.let { "load $it" } ?: "—"
                Text(
                    text = "$freq · $temp · $load",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                Text(
                    text = "Aguardando dados…",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            MetricGraph(
                value = cpuPct,
                history = state.cpuHistory,
                accent = if (cpuPct == null) GlassColors.acc else cpuColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        GlassCard(accent = usageColor(perf?.ramPct, 70f, 88f)) {
            val ramPct = perf?.ramPct
            val ramColor = usageColor(ramPct, 70f, 88f)
            Text(
                text = "Memória RAM",
                color = GlassColors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = pctText(ramPct),
                color = ramColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (perf != null) {
                Text(
                    text = "Usada: ${human(perf.ramUsed)} / ${human(perf.ramTotal)}",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "Disponível: ${human(perf.ramAvail)} · Swap ${human(perf.swapUsed)}/${human(perf.swapTotal)}",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    text = "Aguardando dados…",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            MetricGraph(
                value = ramPct,
                history = state.ramHistory,
                accent = if (ramPct == null) GlassColors.acc else ramColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        val storage = perf?.storage
        val storagePct = storage?.pct
        val storageColor = usageColor(storagePct, 75f, 90f)
        GlassCard(accent = storageColor) {
            Text(
                text = "Memória Interna (Armazenamento)",
                color = GlassColors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = pctText(storagePct),
                color = storageColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
            if (storage != null) {
                Text(
                    text = "Usado: ${human(storage.used)} de ${human(storage.total)}",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "Livre: ${human(storage.avail)}",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                )
            } else {
                Text(
                    text = "Aguardando dados…",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            MetricGraph(
                value = storagePct,
                history = emptyList(),
                accent = if (storagePct == null) GlassColors.acc else storageColor,
                modifier = Modifier.padding(top = 10.dp),
            )
        }

        GlassCard(accent = GlassColors.acc2) {
            Text(
                text = "Sobre o Projetor",
                color = GlassColors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            val hours = device.uptimeSeconds / 3600
            val minutes = (device.uptimeSeconds % 3600) / 60
            val uptime = "${hours}h ${minutes.toString().padStart(2, '0')}m"
            val root = state.rootStatus
            val rootLine = when (root.available) {
                null -> "Root : verificando…"
                true -> "Root : Ativo (${root.detail})"
                false -> "Root : ${root.detail}"
            }
            val lines = listOf(
                "Nome / Dispositivo : ${device.name} (${device.device})",
                "Modelo : ${device.model}",
                "Fabricante / Marca : ${device.manuf} / ${device.brand}",
                "Android / SDK : Android ${device.android} (API ${device.sdk})",
                "Build : ${device.build}",
                "CPU / Núcleos : ${device.abi} (${device.cores} núcleos)",
                "Resolução Nativa : ${device.wmSize}",
                "Kernel : ${device.kernel}",
                "Ligado há (Uptime) : $uptime",
                "ADB / Rede : ${state.prefs.host}",
                rootLine,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (line in lines) {
                    Text(text = line, color = GlassColors.text, fontSize = 12.sp)
                }
            }
        }
    }

    if (processesSheet) {
        GlassSheet(
            title = "Processos",
            subtitle = "${state.processes.size} processos",
            onDismiss = { processesSheet = false },
        ) {
            if (state.processes.isEmpty()) {
                Text(
                    text = "Nenhum processo carregado.",
                    color = GlassColors.sub,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                    items(state.processes, key = { it.pid }) { process ->
                        Text(
                            text = "${process.pid} · ${process.name}",
                            color = GlassColors.text,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}
