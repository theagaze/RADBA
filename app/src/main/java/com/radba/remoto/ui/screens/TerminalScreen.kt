package com.radba.remoto.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.TerminalKind
import com.radba.remoto.data.TerminalLine
import com.radba.remoto.ui.theme.GlassColors

private val Mono = FontFamily.Monospace

/**
 * Console style terminal: the command is typed directly on the prompt line, the
 * output scrolls above it and native text selection handles copying.
 */
@Composable
fun TerminalScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val connected = state.connection == ConnectionState.CONNECTED
    val scrollState = rememberScrollState()
    val promptColor = if (connected) GlassColors.ok else GlassColors.bad
    val prompt = if (connected) {
        "adb@${state.prefs.host}:~\$ "
    } else {
        "adb@offline:~\$ "
    }

    LaunchedEffect(state.terminalLines.size) {
        // Follow the tail: wait for the layout pass that knows the new height.
        snapshotFlow { scrollState.maxValue }.collect { max ->
            if (max > 0) scrollState.scrollTo(max)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GlassColors.bg)
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (connected) "adb · ${state.prefs.host}" else "adb · desconectado",
                color = GlassColors.sub,
                fontFamily = Mono,
                fontSize = 10.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (state.terminalBusy) "executando…" else "pronto",
                color = if (state.terminalBusy) GlassColors.warn else GlassColors.sub,
                fontFamily = Mono,
                fontSize = 10.sp,
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState),
        ) {
            SelectionContainer {
                Column {
                    for (line in state.terminalLines) {
                        TerminalLineView(line)
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = prompt,
                color = promptColor,
                fontFamily = Mono,
                fontSize = 13.sp,
            )
            BasicTextField(
                value = state.terminalDraft,
                onValueChange = { viewModel.setTerminalDraft(it) },
                singleLine = true,
                textStyle = TextStyle(
                    color = GlassColors.text,
                    fontFamily = Mono,
                    fontSize = 13.sp,
                ),
                cursorBrush = SolidColor(GlassColors.ok),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { viewModel.runTerminal(state.terminalDraft) },
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 14.dp)
                    .onKeyEvent { event ->
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_C && event.isCtrlPressed) {
                            viewModel.cancelTerminal()
                            return@onKeyEvent true
                        }
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                viewModel.runTerminal(state.terminalDraft)
                                true
                            }
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
            )
        }
    }
}

@Composable
private fun TerminalLineView(line: TerminalLine) {
    val (prefix, color) = when (line.kind) {
        TerminalKind.COMMAND -> "$ " to GlassColors.ok
        TerminalKind.ERROR -> "! " to GlassColors.bad
        TerminalKind.OK -> "✓ " to GlassColors.ok
        TerminalKind.SUB -> "^C " to GlassColors.warn
        TerminalKind.OUTPUT -> "" to GlassColors.text
    }
    Text(
        text = prefix + line.text,
        color = color,
        fontFamily = Mono,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    )
}
