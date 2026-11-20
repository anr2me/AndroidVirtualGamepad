package com.avgamepad.ui

import android.view.InputDevice
import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shows the key events that Android gives to this application when a gamepad button is pressed:
 * the Android key name, the scan code of the device (the Linux code that the device sent), and the
 * device name. Use it to check what an application receives from a virtual gamepad.
 * Keys of a remote control or a keyboard are not consumed, so the buttons of the window still work.
 */
@Composable
fun InputTestDialog(vm: AppViewModel) {
    val lines = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    AlertDialog(
        onDismissRequest = vm::closeInputTest,
        title = { Text("Input test") },
        text = {
            Column(
                modifier = Modifier
                    .focusRequester(focus)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        val k = e.nativeKeyEvent
                        val gamepad = (k.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)) != 0
                        if (!gamepad) return@onPreviewKeyEvent false
                        if (k.repeatCount == 0) {
                            val action = if (k.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP  "
                            val key = KeyEvent.keyCodeToString(k.keyCode).removePrefix("KEYCODE_")
                            val device = k.device?.name ?: "device ${k.deviceId}"
                            lines.add(0, "$action $key (scan ${k.scanCode}) $device")
                            while (lines.size > 24) lines.removeAt(lines.size - 1)
                        }
                        true
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Press buttons on the controller. The window shows the key that Android gives to the " +
                        "application, the scan code that the device sent, and the device name.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    if (lines.isEmpty()) "No key yet." else lines.joinToString("\n"),
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp,
                    ),
                )
            }
        },
        confirmButton = { TextButton(vm::closeInputTest) { Text("Close") } },
    )
}
