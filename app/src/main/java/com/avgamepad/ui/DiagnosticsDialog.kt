package com.avgamepad.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shows the connection history and the log lines of this application. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsDialog(ui: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val has = ui.diagText.isNotEmpty()
    AlertDialog(
        onDismissRequest = vm::closeDiagnostics,
        title = { Text("Diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The window shows only the lines of this application and of its helper process, " +
                        "and the virtual gamepads as Android sees them. " +
                        "Nothing leaves the device until you select Share.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(
                        if (has) ui.diagText else "Reading the log...",
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(vm::refreshDiagnostics, enabled = !ui.diagBusy) { Text("Refresh") }
                TextButton({ copyText(ctx, ui.diagText) }, enabled = has) { Text("Copy") }
                TextButton({ shareText(ctx, ui.diagText) }, enabled = has) { Text("Share") }
                TextButton(vm::closeDiagnostics) { Text("Close") }
            }
        },
    )
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("AVG diagnostics", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, "Android Virtual Gamepad diagnostics")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(send, "Share the log")) }
}
