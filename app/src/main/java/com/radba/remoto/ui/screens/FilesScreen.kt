package com.radba.remoto.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.radba.remoto.data.FileEntry
import com.radba.remoto.ui.components.GlassButton
import com.radba.remoto.ui.components.GlassCard
import com.radba.remoto.ui.components.GlassSheet
import com.radba.remoto.ui.theme.GlassColors
import com.radba.remoto.util.human
import com.radba.remoto.util.palette

private enum class TextOpKind { RENAME, COPY, MOVE }

private data class TextOp(val kind: TextOpKind, val entry: FileEntry)

@Composable
fun FilesScreen(viewModel: MainViewModel, state: MainViewModel.UiState) {
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.uploadFile(uri)
    }
    val apkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.installApk(uri)
    }
    var downloadTarget by remember { mutableStateOf<FileEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri != null) downloadTarget?.let { viewModel.downloadFile(it, uri) }
        downloadTarget = null
    }

    var pathDraft by remember { mutableStateOf(state.filesPath) }
    LaunchedEffect(state.filesPath) { pathDraft = state.filesPath }

    var sheetEntry by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var textOp by remember { mutableStateOf<TextOp?>(null) }
    var textDraft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                corner = 16.dp,
                contentPadding = PaddingValues(0.dp),
            ) {
                TextField(
                    value = pathDraft,
                    onValueChange = { pathDraft = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GlassButton("Ir", { viewModel.setFilesPath(pathDraft) }, modifier = Modifier.weight(1f), compact = true)
                GlassButton("⬆ Acima", { viewModel.filesParent() }, modifier = Modifier.weight(1f), compact = true)
                GlassButton("↻ Atualizar", { viewModel.reloadFiles() }, modifier = Modifier.weight(1f), compact = true)
            }
        }

        if (state.files.isEmpty()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(text = "Pasta vazia", color = GlassColors.sub, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.files, key = { it.path }) { entry ->
                    FileRow(entry) {
                        if (entry.isDir) viewModel.setFilesPath(entry.path) else sheetEntry = entry
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassButton(
                "Enviar",
                { uploadLauncher.launch("*/*") },
                modifier = Modifier.weight(1f),
                selected = true,
                compact = true,
            )
            GlassButton(
                "Nova pasta",
                {
                    textDraft = ""
                    newFolderDialog = true
                },
                modifier = Modifier.weight(1f),
                compact = true,
            )
            GlassButton(
                "Instalar APK",
                { apkLauncher.launch(arrayOf("application/vnd.android.package-archive")) },
                modifier = Modifier.weight(1f),
                compact = true,
            )
        }
    }

    sheetEntry?.let { entry ->
        GlassSheet(
            title = entry.name,
            subtitle = if (entry.isDir) "Pasta" else human(entry.size),
            onDismiss = { sheetEntry = null },
        ) {
            GlassButton(
                "Baixar para o celular",
                onClick = {
                    downloadTarget = entry
                    runCatching { downloadLauncher.launch(entry.name) }
                    sheetEntry = null
                },
                selected = true,
            )
            GlassButton(
                "Renomear",
                onClick = {
                    textDraft = entry.name
                    textOp = TextOp(TextOpKind.RENAME, entry)
                    sheetEntry = null
                },
            )
            GlassButton(
                "Copiar para…",
                onClick = {
                    textDraft = state.filesPath
                    textOp = TextOp(TextOpKind.COPY, entry)
                    sheetEntry = null
                },
            )
            GlassButton(
                "Mover para…",
                onClick = {
                    textDraft = state.filesPath
                    textOp = TextOp(TextOpKind.MOVE, entry)
                    sheetEntry = null
                },
            )
            GlassButton(
                "Apagar",
                onClick = {
                    deleteTarget = entry
                    sheetEntry = null
                },
                danger = true,
            )
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(text = "Apagar", color = GlassColors.text) },
            text = { Text(text = "Apagar “${entry.name}”? Não pode ser desfeito.", color = GlassColors.sub) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFile(entry)
                        deleteTarget = null
                    },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(text = "Apagar", color = GlassColors.bad) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(text = "Cancelar", color = GlassColors.sub) }
            },
        )
    }

    if (newFolderDialog) {
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text(text = "Nova pasta", color = GlassColors.text) },
            text = {
                TextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    singleLine = true,
                    label = { Text(text = "Nome da pasta:") },
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.newFolder(textDraft.trim())
                        newFolderDialog = false
                    },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(text = "Criar", color = GlassColors.acc) }
            },
            dismissButton = {
                TextButton(
                    onClick = { newFolderDialog = false },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(text = "Cancelar", color = GlassColors.sub) }
            },
        )
    }

    textOp?.let { op ->
        val title = when (op.kind) {
            TextOpKind.RENAME -> "Renomear"
            TextOpKind.COPY -> "Copiar para"
            TextOpKind.MOVE -> "Mover para"
        }
        val fieldLabel = if (op.kind == TextOpKind.RENAME) "Novo nome:" else "Pasta de destino:"
        AlertDialog(
            onDismissRequest = { textOp = null },
            title = { Text(text = title, color = GlassColors.text) },
            text = {
                TextField(
                    value = textDraft,
                    onValueChange = { textDraft = it },
                    singleLine = true,
                    label = { Text(text = fieldLabel) },
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
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (op.kind) {
                            TextOpKind.RENAME -> viewModel.renameFile(op.entry, textDraft.trim())
                            TextOpKind.COPY -> viewModel.copyFile(op.entry, textDraft.trim())
                            TextOpKind.MOVE -> viewModel.moveFile(op.entry, textDraft.trim())
                        }
                        textOp = null
                    },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(text = "Confirmar", color = GlassColors.acc) }
            },
            dismissButton = {
                TextButton(
                    onClick = { textOp = null },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(text = "Cancelar", color = GlassColors.sub) }
            },
        )
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    val sub = if (entry.isDir) "Pasta" else human(entry.size)
    val letter = if (entry.isDir) {
        "▸"
    } else if ('.' in entry.name) {
        entry.name.substringAfterLast('.').take(3).uppercase()
    } else {
        "▤"
    }
    GlassCard(
        onClick = onClick,
        corner = 16.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassCard(
                modifier = Modifier.size(36.dp),
                corner = 12.dp,
                accent = if (entry.isDir) GlassColors.acc else Color(palette(entry.name)),
                contentPadding = PaddingValues(0.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = letter,
                        color = if (entry.isDir) GlassColors.acc else GlassColors.text,
                        fontSize = if (entry.isDir) 16.sp else 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = entry.name,
                    color = GlassColors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Text(text = sub, color = GlassColors.sub, fontSize = 11.sp)
            }
            Text(text = "›", color = GlassColors.sub, fontSize = 22.sp)
        }
    }
}
