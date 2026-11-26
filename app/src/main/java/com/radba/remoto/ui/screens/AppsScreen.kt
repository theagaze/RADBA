package com.radba.remoto.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radba.remoto.MainViewModel
import com.radba.remoto.data.AppInfo
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.GlassSheet
import com.radba.remoto.ui.theme.GlassColors
import com.radba.remoto.util.palette

private data class ConfirmOp(val app: AppInfo, val uninstall: Boolean)

@Composable
fun AppsScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val query = state.appsQuery.trim().lowercase()
    val openCount = state.apps.count { it.running }
    val favoriteCount = state.apps.count { it.packageName in state.favorites }
    val visible = state.apps
        .filter { app ->
            query.isEmpty() || query in app.title.lowercase() || query in app.packageName.lowercase()
        }
        .sortedWith(
            compareBy<AppInfo>(
                { !(it.running || it.packageName in state.favorites) },
                { !(it.running && it.packageName in state.favorites) },
                { !it.running },
                { it.packageName !in state.favorites },
                { it.title.lowercase() },
            ),
        )

    var sheetApp by remember { mutableStateOf<AppInfo?>(null) }
    var confirm by remember { mutableStateOf<ConfirmOp?>(null) }

    LaunchedEffect(Unit) { viewModel.appsShown() }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassCard(
                modifier = Modifier.weight(1f),
                corner = 16.dp,
                contentPadding = PaddingValues(0.dp),
            ) {
                TextField(
                    value = state.appsQuery,
                    onValueChange = { viewModel.setAppsQuery(it) },
                    singleLine = true,
                    placeholder = { Text(text = "Buscar app…", color = GlassColors.sub) },
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
            GlassButton("Atualizar", { viewModel.loadApps() }, compact = true)
        }

        Text(
            text = "  ${visible.size} de ${state.apps.size} apps · $openCount abertos · $favoriteCount favoritos",
            color = GlassColors.sub,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )

        if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Conecte o projetor para ver os apps",
                    color = GlassColors.sub,
                    fontSize = 13.sp,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        favorite = app.packageName in state.favorites,
                        onClick = { sheetApp = app },
                    )
                }
            }
        }
    }

    sheetApp?.let { app ->
        val favorite = app.packageName in state.favorites
        GlassSheet(
            title = app.title,
            subtitle = app.packageName,
            onDismiss = { sheetApp = null },
        ) {
            GlassButton(
                "Abrir",
                onClick = {
                    viewModel.launchApp(app)
                    sheetApp = null
                },
                selected = true,
            )
            GlassButton(
                if (favorite) "Remover dos favoritos" else "Fixar como favorito",
                onClick = {
                    viewModel.toggleFavorite(app.packageName)
                    sheetApp = null
                },
            )
            GlassButton(
                "Forçar parada",
                onClick = {
                    confirm = ConfirmOp(app, false)
                    sheetApp = null
                },
            )
            GlassButton(
                "Desinstalar",
                onClick = {
                    confirm = ConfirmOp(app, true)
                    sheetApp = null
                },
                danger = true,
            )
        }
    }

    confirm?.let { op ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    text = if (op.uninstall) "Desinstalar" else "Forçar parada",
                    color = GlassColors.text,
                )
            },
            text = {
                Text(
                    text = if (op.uninstall) {
                        "Desinstalar ${op.app.packageName}?"
                    } else {
                        "Forçar parada de ${op.app.packageName}?"
                    },
                    color = GlassColors.sub,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (op.uninstall) viewModel.uninstallApp(op.app.packageName)
                    else viewModel.forceStopApp(op.app.packageName)
                    confirm = null
                }) {
                    Text(
                        text = if (op.uninstall) "Desinstalar" else "Forçar parada",
                        color = if (op.uninstall) GlassColors.bad else GlassColors.warn,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(text = "Cancelar", color = GlassColors.sub) }
            },
        )
    }
}

@Composable
private fun AppRow(app: AppInfo, favorite: Boolean, onClick: () -> Unit) {
    val pinnedColor = when {
        app.running -> GlassColors.ok
        favorite -> GlassColors.warn
        else -> null
    }
    GlassCard(
        onClick = onClick,
        corner = 16.dp,
        contentPadding = PaddingValues(0.dp),
    ) {
        if (pinnedColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(pinnedColor),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(palette(app.packageName))),
                contentAlignment = Alignment.Center,
            ) {
                val icon = app.icon
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = "Ícone de ${app.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(42.dp).clip(CircleShape),
                    )
                } else {
                    Text(
                        text = app.title.take(1).uppercase(),
                        color = GlassColors.bg,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = app.title,
                    color = GlassColors.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(
                    text = app.packageName,
                    color = GlassColors.sub,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
                if (app.running || favorite) {
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (app.running) {
                            Text(
                                text = "● ABERTO",
                                color = GlassColors.ok,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(
                                        GlassColors.ok.copy(alpha = 0.16f),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        if (favorite) {
                            Text(
                                text = "★ FAVORITO",
                                color = GlassColors.warn,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(
                                        GlassColors.warn.copy(alpha = 0.16f),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Text(text = "›", color = GlassColors.sub, fontSize = 22.sp)
        }
    }
}
