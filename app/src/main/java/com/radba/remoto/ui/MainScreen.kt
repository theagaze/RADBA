package com.radba.remoto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.ToastHost
import com.radba.remoto.ui.screens.AppsScreen
import com.radba.remoto.ui.screens.FilesScreen
import com.radba.remoto.ui.screens.HomeScreen
import com.radba.remoto.ui.screens.SettingsScreen
import com.radba.remoto.ui.screens.TerminalScreen
import com.radba.remoto.ui.theme.GlassColors

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsState(initial = MainViewModel.UiState())

    Scaffold(
        containerColor = GlassColors.bg,
        bottomBar = { BottomNavigation(state.tab, viewModel) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassColors.bg)
                .padding(padding),
        ) {
            Header(state.tab, state.connection, viewModel)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (state.tab) {
                    Tab.HOME -> HomeScreen(viewModel, state)
                    Tab.APPS -> AppsScreen(viewModel, state)
                    Tab.FILES -> FilesScreen(viewModel, state)
                    Tab.SETTINGS -> SettingsScreen(viewModel, state)
                    Tab.TERMINAL -> TerminalScreen(viewModel, state)
                }
                ToastHost(
                    toasts = viewModel.toasts,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Header(tab: Tab, connection: ConnectionState, viewModel: MainViewModel) {
    val status = when (connection) {
        ConnectionState.CONNECTED -> "● Conectado"
        ConnectionState.DISCONNECTED -> "● Desconectado"
        ConnectionState.CONNECTING -> "● Conectando…"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "RADBA REMOTO",
                color = GlassColors.sub,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Text(
                text = tab.label,
                color = GlassColors.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        GlassButton(
            label = status,
            onClick = { viewModel.reconnect() },
            selected = connection == ConnectionState.CONNECTED,
            danger = connection == ConnectionState.DISCONNECTED,
            compact = true,
        )
    }
}

@Composable
private fun BottomNavigation(current: Tab, viewModel: MainViewModel) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        GlassCard(
            corner = 50.dp,
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
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
                            .clickable { runCatching { viewModel.selectTab(tab) } }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = tab.glyph,
                            color = tint,
                            fontSize = 18.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            text = tab.label,
                            color = tint,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
