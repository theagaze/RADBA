package com.radba.remoto.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.TerminalKind

@Composable
fun TerminalScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val consoleBg = Color(0xFF0C0C0C)
    val consoleSurface = Color(0xFF121212)
    val consoleText = Color(0xFFE6EAF2)
    val promptColor = Color(0xFF5FE3A6)
    val accent = Color(0xFF7DA7FF)
    val errorColor = Color(0xFFFF6B7A)
    val subColor = Color(0xFF7A8296)

    val host = state.prefs.host
    val prompt = if (state.connection == ConnectionState.CONNECTED) {
        "adb@$host> "
    } else {
        "adb@offline> "
    }
    val scrollState = rememberScrollState()

    LaunchedEffect(state.terminalLines.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(consoleBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Projetor Remote Terminal [ADB $host]",
            color = promptColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
        )
        Text(
            text = "Comandos rodam no projetor (adb shell). 'cls' limpa · Ctrl+C cancela · '!comando' não é suportado no Android.",
            color = subColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(consoleSurface)
                .verticalScroll(scrollState)
                .padding(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.terminalLines.forEach { line ->
                    Text(
                        text = line.text,
                        color = when (line.kind) {
                            TerminalKind.COMMAND -> accent
                            TerminalKind.OUTPUT -> consoleText
                            TerminalKind.ERROR -> errorColor
                            TerminalKind.OK -> promptColor
                            TerminalKind.SUB -> subColor
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
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
                // Newlines from a multi-line paste are kept; runTerminal splits and runs each command.
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
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_C &&
                            event.isCtrlPressed
                        ) {
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
                            KeyEvent.KEYCODE_ENTER -> {
                                viewModel.runTerminal(state.terminalDraft)
                                true
                            }
                            else -> false
                        }
                    },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = consoleSurface,
                    unfocusedContainerColor = consoleSurface,
                    focusedTextColor = consoleText,
                    unfocusedTextColor = consoleText,
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
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent)
                    .clickable { viewModel.runTerminal(state.terminalDraft) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Enviar",
                    color = consoleBg,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
            }
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(consoleSurface)
                    .clickable { viewModel.clearTerminal() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Limpar",
                    color = subColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            }
            if (state.terminalBusy) {
                Text(
                    text = "Executando…",
                    color = promptColor,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}
