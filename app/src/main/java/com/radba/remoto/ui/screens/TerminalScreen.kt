package com.radba.remoto.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.theme.GlassColors

@Composable
fun TerminalScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Digite comandos adb shell. ↑/↓ navega pelo histórico.",
            color = GlassColors.sub,
            fontSize = 11.sp,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(GlassColors.glass)
                .verticalScroll(scrollState)
                .padding(12.dp),
        ) {
            Text(
                text = viewModel.terminalText,
                color = GlassColors.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        TextField(
            value = state.terminalDraft,
            onValueChange = { viewModel.setTerminalDraft(it) },
            singleLine = true,
            placeholder = { Text(text = "comando…", color = GlassColors.sub) },
            modifier = Modifier
                .fillMaxWidth()
                .onKeyEvent { event ->
                    when (event.nativeEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            viewModel.terminalHistoryUp()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            viewModel.terminalHistoryDown()
                            true
                        }
                        android.view.KeyEvent.KEYCODE_ENTER -> {
                            viewModel.runTerminal(state.terminalDraft)
                            true
                        }
                        else -> false
                    }
                },
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassButton(
                "Executar",
                onClick = { viewModel.runTerminal(state.terminalDraft) },
                modifier = Modifier.weight(1f),
                selected = true,
                compact = true,
            )
            GlassButton(
                "Copiar",
                onClick = {
                    runCatching {
                        clipboard.setText(AnnotatedString(viewModel.terminalText))
                        copied = true
                    }
                },
                modifier = Modifier.weight(1f),
                compact = true,
            )
            GlassButton(
                "Limpar",
                onClick = {
                    viewModel.clearTerminal()
                    copied = false
                },
                modifier = Modifier.weight(1f),
                compact = true,
            )
        }
        if (state.terminalBusy) {
            Text(text = "Executando…", color = GlassColors.warn, fontSize = 11.sp)
        } else if (copied) {
            Text(text = "Copiado.", color = GlassColors.ok, fontSize = 11.sp)
        }
    }
}
