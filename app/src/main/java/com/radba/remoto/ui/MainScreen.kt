package com.radba.remoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.ConnectionState
import com.radba.remoto.data.Tab
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.ToastHost
import com.radba.remoto.ui.screens.AppsScreen
import com.radba.remoto.ui.screens.FilesScreen
import com.radba.remoto.ui.screens.HomeScreen
import com.radba.remoto.ui.screens.PerfScreen
import com.radba.remoto.ui.screens.SettingsScreen
import com.radba.remoto.ui.screens.TerminalScreen
import com.radba.remoto.ui.theme.GlassColors

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxHeight < 520.dp
        Scaffold(
            containerColor = GlassColors.bg,
            snackbarHost = { ToastHost(viewModel.toasts) },
            bottomBar = { BottomNavigation(state.tab, viewModel, compact) },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GlassColors.bg)
                    .padding(padding),
            ) {
                Header(state.tab, state.connection, compact)
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    val homeVisible = state.tab == Tab.HOME
                    // The mirror lives on Início. Keeping that screen composed
                    // while another tab is open keeps its surface (and therefore
                    // the running session) alive across tab switches.
                    if (homeVisible || state.prefs.mirror) {
                        HomeScreen(viewModel, state, showChrome = homeVisible)
                    }
                    if (!homeVisible) {
                        Box(modifier = Modifier.fillMaxSize().background(GlassColors.bg)) {
                            when (state.tab) {
                                Tab.APPS -> AppsScreen(viewModel, state)
                                Tab.FILES -> FilesScreen(viewModel, state)
                                Tab.PERF -> PerfScreen(viewModel, state)
                                Tab.SETTINGS -> SettingsScreen(viewModel, state)
                                Tab.TERMINAL -> TerminalScreen(viewModel, state)
                                Tab.HOME -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(tab: Tab, connection: ConnectionState, compact: Boolean) {
    val (label, color) = when (connection) {
        ConnectionState.CONNECTED -> "● Conectado" to GlassColors.ok
        ConnectionState.DISCONNECTED -> "● Desconectado" to GlassColors.bad
        ConnectionState.CONNECTING -> "● Conectando…" to GlassColors.acc
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "RADBA · ${tab.label}",
            color = GlassColors.sub,
            fontSize = if (compact) 10.sp else 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            color = color,
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun BottomNavigation(current: Tab, viewModel: MainViewModel, compact: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = if (compact) 2.dp else 6.dp),
    ) {
        GlassCard(
            corner = if (compact) 16.dp else 22.dp,
            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (tab in Tab.entries) {
                    val selected = tab == current
                    val tint = if (selected) GlassColors.acc else GlassColors.sub
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = if (compact) 44.dp else 48.dp)
                            .clickable { runCatching { viewModel.selectTab(tab) } }
                            .padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Text(
                            text = tab.glyph,
                            color = tint,
                            fontSize = if (compact) 15.sp else 17.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (!compact) {
                            Text(
                                text = navLabel(tab),
                                color = tint,
                                fontSize = 9.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun navLabel(tab: Tab): String = when (tab) {
    Tab.HOME -> "Início"
    Tab.APPS -> "Apps"
    Tab.FILES -> "Arquivos"
    Tab.PERF -> "Desempenho"
    Tab.SETTINGS -> "Ajustes"
    Tab.TERMINAL -> "Terminal"
}
