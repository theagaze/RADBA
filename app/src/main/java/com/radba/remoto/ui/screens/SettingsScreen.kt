package com.radba.remoto.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ToastKind
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.GlassSheet
import com.radba.remoto.ui.components.GlassSlider
import com.radba.remoto.ui.components.GlassToggle
import com.radba.remoto.ui.theme.GlassColors

@Composable
fun SettingsScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val prefs = state.prefs
    var hostDraft by remember { mutableStateOf(prefs.host) }
    LaunchedEffect(prefs.host) { hostDraft = prefs.host }

    var pairDialog by remember { mutableStateOf(false) }
    var pairAddress by remember { mutableStateOf(prefs.host.substringBefore(":") + ":") }
    var pairCode by remember { mutableStateOf("") }
    var processesSheet by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GlassCard {
            Text(text = "Conexão", color = GlassColors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            GlassCard(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                corner = 14.dp,
                contentPadding = PaddingValues(0.dp),
            ) {
                TextField(
                    value = hostDraft,
                    onValueChange = { hostDraft = it },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = GlassColors.glassHi,
                        unfocusedContainerColor = GlassColors.glassHi,
                        focusedTextColor = GlassColors.text,
                        unfocusedTextColor = GlassColors.text,
                        cursorColor = GlassColors.acc,
                        focusedIndicatorColor = GlassColors.acc,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton(
                    "Salvar",
                    onClick = { viewModel.setHost(hostDraft) },
                    modifier = Modifier.weight(1f),
                    selected = true,
                    compact = true,
                )
                GlassButton(
                    "Reconectar",
                    onClick = { viewModel.reconnect() },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
            Text(
                text = "Conexão: ${state.connectionText}",
                color = GlassColors.sub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "Endereço: ${prefs.host}",
                color = GlassColors.sub,
                fontSize = 11.sp,
            )
            state.settingsNote?.let { note ->
                val noteColor = when (note.kind) {
                    ToastKind.OK -> GlassColors.ok
                    ToastKind.ERROR -> GlassColors.bad
                    ToastKind.INFO -> GlassColors.acc
                }
                Text(text = note.text, color = noteColor, fontSize = 11.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton(
                    "Parear (Android 11+)",
                    onClick = {
                        pairAddress = prefs.host.substringBefore(":") + ":"
                        pairCode = ""
                        pairDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                GlassButton(
                    "Ativar Wi-Fi via USB (tcpip 5555)",
                    onClick = { viewModel.enableWifiAdb() },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
            }
        }

        GlassCard {
            Text(
                text = "Transmissão melhorada (scrcpy)",
                color = GlassColors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            GlassToggle(
                checked = prefs.mirror,
                onCheckedChange = { viewModel.setMirrorEnabled(it) },
                label = "Transmissão melhorada (ao vivo)",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                text = "Servidor scrcpy v2.7 integrado no app, sem download e sem adb forward. " +
                    "Perfil automático de 30 a 60 FPS conforme a rede. Ao desativar, usa captura periódica.",
                color = GlassColors.sub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "Perfil ativo: ${state.mirrorTier} · ${state.mirrorFps} FPS · ${state.mirrorLatencyMs}ms",
                color = GlassColors.acc,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = "scrcpy v2.7 integrado · perfil automático 30–60 FPS",
                color = GlassColors.sub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton(
                    "Reiniciar vídeo",
                    onClick = { viewModel.startMirror() },
                    modifier = Modifier.fillMaxWidth(),
                    selected = true,
                    compact = true,
                )
            }
        }

        GlassCard {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Root",
                    color = GlassColors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                val root = state.rootStatus
                val rootLabel = when (root.available) {
                    null -> "não verificado"
                    true -> "● root ok · ${root.detail.ifBlank { "uid=0" }}"
                    false -> "● sem root · ${root.detail.ifBlank { "indisponível" }}"
                }
                val rootColor = when (root.available) {
                    true -> GlassColors.ok
                    false -> GlassColors.bad
                    null -> GlassColors.sub
                }
                Text(
                    text = rootLabel,
                    color = rootColor,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1.4f),
                )
            }
            GlassToggle(
                checked = prefs.rootFixVideo,
                onCheckedChange = { viewModel.setRootFix(it) },
                label = "Corrigir vídeo verde no espelho (Root)",
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                text = "Aplica SurfaceFlinger 1008 i32 1 e força decodificação em software " +
                    "(vendor.media.c2.vdec.support=false) sem alterar arquivos do sistema.",
                color = GlassColors.sub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        GlassCard {
            Text(
                text = "Brilho virtual",
                color = GlassColors.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "☀ Brilho virtual", color = GlassColors.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(text = "${state.brightnessPct}%", color = GlassColors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            GlassSlider(
                value = state.brightnessPct.toFloat(),
                onValueChange = { value -> viewModel.setBrightness(value.toInt(), null, true) },
                modifier = Modifier.fillMaxWidth(),
                valueRange = 10f..100f,
                accent = GlassColors.warn,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "☾ Filtro quente / conforto noturno",
                    color = GlassColors.text,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(text = "${state.warmPct}%", color = GlassColors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            GlassSlider(
                value = state.warmPct.toFloat(),
                onValueChange = { value -> viewModel.setBrightness(state.brightnessPct, value.toInt(), true) },
                modifier = Modifier.fillMaxWidth(),
                valueRange = 0f..100f,
                accent = GlassColors.acc2,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for ((label, pct, warm) in listOf(
                    Triple("Normal 100%", 100, 0),
                    Triple("Cinema 65%", 65, 0),
                    Triple("Madrugada 35%", 35, 15),
                    Triple("Mínimo 15%", 15, 0),
                )) {
                    GlassButton(
                        label,
                        onClick = { viewModel.applyBrightnessPreset(pct, warm) },
                        modifier = Modifier.weight(1f),
                        compact = true,
                    )
                }
            }
            Text(
                text = "Usa SurfaceFlinger 1015 (matriz de cor) + 1008 (desativa HW Overlays) via root. " +
                    "Mínimo seguro: 10%.",
                color = GlassColors.sub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        GlassCard {
            Text(text = "Sistema e controles", color = GlassColors.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "Use a aba Desempenho para cpu, RAM, armazenamento e ficha técnica.",
                color = GlassColors.sub,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton(
                    "Processos",
                    onClick = {
                        viewModel.refreshProcesses()
                        processesSheet = true
                    },
                    modifier = Modifier.weight(1f),
                    compact = true,
                )
                GlassButton(
                    "Acordar tela",
                    { viewModel.wakeScreen() },
                    modifier = Modifier.weight(1f),
                    compact = true,
                    selected = true,
                )
                GlassButton("Atualizar info", { viewModel.refreshSettings() }, modifier = Modifier.weight(1f), compact = true)
            }
        }
    }

    if (pairDialog) {
        AlertDialog(
            onDismissRequest = { pairDialog = false },
            title = { Text(text = "Parear (Android 11+)", color = GlassColors.text) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = pairAddress,
                        onValueChange = { pairAddress = it },
                        singleLine = true,
                        label = { Text(text = "Endereço:porta") },
                        colors = pairingFieldColors(),
                    )
                    TextField(
                        value = pairCode,
                        onValueChange = { pairCode = it },
                        singleLine = true,
                        label = { Text(text = "Código") },
                        colors = pairingFieldColors(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.pair(pairAddress.trim(), pairCode.trim())
                    pairDialog = false
                }) { Text(text = "Parear", color = GlassColors.acc2) }
            },
            dismissButton = {
                TextButton(onClick = { pairDialog = false }) { Text(text = "Cancelar", color = GlassColors.sub) }
            },
        )
    }

    if (processesSheet) {
        GlassSheet(
            title = "Processos",
            subtitle = "${state.processes.size} processos",
            onDismiss = { processesSheet = false },
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(state.processes, key = { it.pid }) { process ->
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "${process.pid} · ${process.name}",
                            color = GlassColors.text,
                            fontSize = 12.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GlassButton(
                                "Matar",
                                onClick = { viewModel.killProcess(process.pid) },
                                compact = true,
                                danger = true,
                            )
                            GlassButton(
                                "Forçar parada",
                                onClick = { viewModel.forceStopPackage(process.name.substringBefore(":")) },
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun pairingFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = GlassColors.glassHi,
    unfocusedContainerColor = GlassColors.glassHi,
    focusedTextColor = GlassColors.text,
    unfocusedTextColor = GlassColors.text,
    cursorColor = GlassColors.acc,
    focusedIndicatorColor = GlassColors.acc,
    unfocusedIndicatorColor = GlassColors.edgeHi,
)
