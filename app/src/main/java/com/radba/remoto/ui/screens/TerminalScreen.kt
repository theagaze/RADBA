package com.radba.remoto.ui.screens

import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.TerminalKind
import kotlinx.coroutines.flow.first

@Composable
fun TerminalScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val consoleBg = Color(0xFF08090B)
    val consoleSurface = Color(0xFF111318)
    val consoleFg = Color(0xFFE6EAF2)
    val promptColor = Color(0xFF5FE3A6)
    val accent = Color(0xFF7DA7FF)
    val errorColor = Color(0xFFFF6B7A)
    val subColor = Color(0xFF7A8296)

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var console by remember { mutableStateOf(TextFieldValue("")) }
    var selection by remember { mutableStateOf(TextRange(0)) }

    val host = state.prefs.host
    val prompt = if (state.connection == ConnectionState.CONNECTED) {
        "adb@$host> "
    } else {
        "adb@offline> "
    }
    val consoleBody = buildString {
        append("RADBA Terminal · Windows 11 style · ADB $host\n")
        append("Comandos rodam no projetor (adb shell). 'cls' limpa · toque longo copia.\n")
        append("Toque longo no texto seleciona e abre a barra Copiar do Android.\n\n")
        state.terminalLines.forEach { line ->
            when (line.kind) {
                TerminalKind.COMMAND -> append("$ ")
                TerminalKind.ERROR -> append("! ")
                TerminalKind.OK -> append("✓ ")
                TerminalKind.SUB -> append("^C ")
                TerminalKind.OUTPUT -> Unit
            }
            append(line.text).append('\n')
        }
    }

    LaunchedEffect(consoleBody) {
        val start = selection.start.coerceAtMost(consoleBody.length)
        val end = selection.end.coerceAtMost(consoleBody.length)
        val range = TextRange(start, end)
        console = TextFieldValue(consoleBody, selection = range)
        selection = range
    }
    LaunchedEffect(console.text.length) {
        // Keep the tail visible after the final layout pass; a one-shot
        // snapshot can latch onto an intermediate maxValue while text grows.
        snapshotFlow { scrollState.maxValue }.collect { max ->
            if (max > 0) scrollState.scrollTo(max)
        }
    }

    fun copySelection() {
        val range = selection
        val text = if (range.collapsed) {
            ""
        } else {
            console.text.substring(
                range.start.coerceIn(0, console.text.length),
                range.end.coerceIn(0, console.text.length),
            )
        }
        if (text.isEmpty()) {
            Toast.makeText(context, "Selecione um trecho", Toast.LENGTH_SHORT).show()
        } else {
            clipboard.setText(AnnotatedString(text))
            Toast.makeText(context, "Trecho copiado", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyAll() {
        clipboard.setText(AnnotatedString(console.text))
        Toast.makeText(context, "Terminal copiado", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(consoleBg)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(consoleSurface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Dot(Color(0xFFFF6B7A))
                Dot(Color(0xFFFFD95A))
                Dot(Color(0xFF5FE3A6))
            }
            Text(
                text = "  Projeto Terminal",
                color = consoleFg,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (state.terminalBusy) "● ocupado" else "● pronto",
                color = if (state.terminalBusy) accent else promptColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(consoleSurface),
        ) {
            BasicTextField(
                value = console,
                onValueChange = { next ->
                    console = next
                    selection = next.selection
                },
                readOnly = true,
                maxLines = Int.MAX_VALUE,
                textStyle = TextStyle(
                    color = consoleFg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(12.dp),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = prompt,
                color = promptColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            TextField(
                value = state.terminalDraft,
                onValueChange = { viewModel.setTerminalDraft(it) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { viewModel.runTerminal(state.terminalDraft) },
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .onKeyEvent { event ->
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed) {
                            viewModel.cancelTerminal()
                            return@onKeyEvent true
                        }
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                viewModel.terminalHistoryUp()
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                viewModel.terminalHistoryDown()
                                true
                            }
                            else -> false
                        }
                    },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = consoleSurface,
                    unfocusedContainerColor = consoleSurface,
                    focusedTextColor = consoleFg,
                    unfocusedTextColor = consoleFg,
                    cursorColor = promptColor,
                    focusedIndicatorColor = promptColor,
                    unfocusedIndicatorColor = subColor,
                ),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { viewModel.runTerminal(state.terminalDraft) },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(text = "Enviar", color = accent, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
            TextButton(
                onClick = { viewModel.clearTerminal() },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(text = "Limpar", color = subColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = { copySelection() },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(text = "Copiar seleção", color = accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            TextButton(
                onClick = { copyAll() },
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(text = "Copiar tudo", color = promptColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            if (state.terminalBusy) {
                Text(
                    text = "Executando…",
                    color = promptColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = "Toque longo copia · ↑/↓ histórico",
                color = errorColor,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}
