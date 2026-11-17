package com.avgamepad.ui

import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avgamepad.backend.BackendManager
import com.avgamepad.data.Store
import com.avgamepad.model.*
import kotlinx.coroutines.delay

@Composable
fun AvgTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Color(0xFF64B5F6), background = Color(0xFF101418), surface = Color(0xFF1A2027)),
        content = content,
    )
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppScreen(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val be by BackendManager.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { while (true) { vm.checkAcc(); delay(1000) } }

    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Android Virtual Gamepad", style = MaterialTheme.typography.headlineSmall) }

        item {
            Section("Access mode") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendType.entries.forEach { t ->
                        FilterChip(selected = ui.backend == t, onClick = { vm.setBackend(t) }, label = { Text(t.title) })
                    }
                }
                Text(be.message, style = MaterialTheme.typography.bodyMedium)
                if (ui.probe.isNotBlank()) Text(ui.probe, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = vm::reconnect) { Text("Reconnect") }
            }
        }

        if (ui.backend == BackendType.ACCESSIBILITY) {
            item { AccessibilitySection(ui, vm) }
        } else {
            item {
                Section("Virtual gamepads") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ui.configs.indices.forEach { i ->
                            FilterChip(
                                ui.slot == i, { vm.selectSlot(i) },
                                { Text("Pad ${i + 1}" + if (i in ui.runningSlots) " (on)" else "") },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(vm::addSlot, enabled = ui.configs.size < Store.MAX_SLOTS) { Text("Add pad") }
                        OutlinedButton(
                            vm::removeLastSlot,
                            enabled = ui.configs.size > 1 && (ui.configs.size - 1) !in ui.runningSlots,
                        ) { Text("Remove last pad") }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(ui.bootStart, vm::setBootStart)
                        Spacer(Modifier.width(8.dp))
                        Text("Start pads at boot")
                    }
                    if (ui.bootStart) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Switch(ui.config.autoStart, { v -> vm.edit { it.copy(autoStart = v) } })
                            Spacer(Modifier.width(8.dp))
                            Text("Start Pad ${ui.slot + 1} at boot")
                        }
                        Text(
                            "At boot, Root must be allowed for this app, or Shizuku must be running. " +
                                "The pad waits for the source device if it is not connected.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("The settings below belong to Pad ${ui.slot + 1}.", style = MaterialTheme.typography.bodySmall)
                    Text(if (ui.status.isNotBlank()) ui.status else if (ui.running) "Running" else "Stopped")
                    if (ui.log.isNotBlank()) Text(ui.log, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::start, enabled = !ui.running && be.connected) { Text("Start pad ${ui.slot + 1}") }
                        OutlinedButton(onClick = vm::stop, enabled = ui.running) { Text("Stop") }
                        if (ui.runningSlots.size > 1) TextButton(onClick = vm::stopAll) { Text("Stop all") }
                    }
                }
            }
            item {
                Section("Source device (input)") {
                    Text("Select the controller, for example the PS2 adapter.", style = MaterialTheme.typography.bodySmall)
                    ui.devices.filter { it.readable }.forEach { d ->
                        DeviceRow(d, ui.config.sourcePath == d.path) { vm.selectSource(d) }
                        ui.configs.withIndex().firstOrNull { it.index != ui.slot && it.value.sourcePath == d.path }?.let {
                            Text("Selected by Pad ${it.index + 1}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    OutlinedButton(onClick = vm::refreshDevices, enabled = be.connected) { Text("Refresh list") }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(ui.config.grab, { v -> vm.edit { it.copy(grab = v) } })
                        Spacer(Modifier.width(8.dp))
                        Text("Grab source (hide original events)")
                    }
                }
            }
            item {
                Section("Output") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Auto", "uinput" to "Virtual gamepad", "device" to "Existing device", "inject" to "Key injection", "keyboard" to "Keyboard only")
                            .forEach { (k, t) ->
                                FilterChip(ui.config.outputMode == k, { vm.edit { c -> c.copy(outputMode = k) } }, { Text(t) })
                            }
                    }
                    Text(
                        "Auto tries: virtual gamepad, then the selected existing device, then key injection.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Virtual gamepad identity:", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PadProfile.entries.forEach { pr ->
                            FilterChip(ui.config.padProfile == pr, { vm.selectProfile(pr) }, { Text(pr.title) })
                        }
                    }
                    Text(
                        "ID %04x:%04x, name \"%s\", %s axes".format(
                            ui.config.virtualVendor, ui.config.virtualProduct,
                            ui.config.virtualName, ui.config.axisLayout.title,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ui.config.padProfile == PadProfile.CUSTOM) CustomProfileFields(ui.config, vm)

                    Text("L2 / R2 output:", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("analog" to "Analog axes", "digital" to "Digital buttons", "both" to "Both")
                            .forEach { (k, t) ->
                                FilterChip(ui.config.triggerMode == k, { vm.edit { c -> c.copy(triggerMode = k) } }, { Text(t) })
                            }
                    }
                    Text("Rumble:", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("off" to "Off", "controller" to "Controller", "phone" to "Phone", "both" to "Both")
                            .forEach { (k, t) ->
                                FilterChip(ui.config.rumble == k, { vm.edit { c -> c.copy(rumble = k) } }, { Text(t) })
                            }
                    }
                    Text(
                        "Rumble needs the virtual gamepad output. The real controller must support rumble and allow writing. The phone uses its vibrator.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (ui.config.triggerButtons && ui.config.padProfile == PadProfile.XBOX360) {
                        Text(
                            "The Xbox 360 key layout of Android can ignore the L2/R2 buttons. Use PlayStation or Generic for digital L2/R2.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(ui.config.dpadAsHat, { v -> vm.edit { it.copy(dpadAsHat = v) } })
                        Spacer(Modifier.width(8.dp))
                        Text("D-pad as hat axis (off = D-pad buttons)")
                    }
                    if (ui.config.outputMode == "auto" || ui.config.outputMode == "device") {
                        Text("Existing device to use as output:", fontWeight = FontWeight.Bold)
                        DeviceRowNone(ui.config.targetPath.isBlank()) { vm.selectTarget(null) }
                        ui.devices.filter { it.writable && it.path != ui.config.sourcePath }.forEach { d ->
                            DeviceRow(d, ui.config.targetPath == d.path) { vm.selectTarget(d) }
                        }
                    }
                    Text("Output changes apply after Stop and Start.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item { KeyboardSection(ui, vm) }
            item {
                Section("Mapping") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.preset("passthrough") }) { Text("Preset: standard") }
                        OutlinedButton({ vm.preset("ps2") }) { Text("Preset: PS2 example") }
                    }
                    Text(
                        "For each control, tap Learn. Then press the button or move the stick on the source device. Stop the remapper first.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(Pad.entries.toList()) { pad -> PadRow(pad, ui, vm) }
        }
    }

    ui.editing?.let { AccDialog(it, vm) }
    if (ui.kbdPendingSrc != null || ui.kbdEditIndex != null) KeyPickerDialog(vm)
}

@Composable
private fun DeviceRow(d: DeviceInfo, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(d.name, fontWeight = FontWeight.Bold)
            Text(
                "${d.path}  %04x:%04x".format(d.vendor, d.product) + if (d.padKeys) "  gamepad" else "",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeviceRowNone(selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) { Text("None", Modifier.padding(10.dp)) }
}

@Composable
private fun PadRow(pad: Pad, ui: UiState, vm: AppViewModel) {
    val rules = ui.config.rules.filter { it.dst == pad }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(pad.label, fontWeight = FontWeight.Bold)
            Text(
                when {
                    ui.learning == pad -> "Press a control on the source device..."
                    rules.isEmpty() -> "Not mapped"
                    else -> rules.joinToString { Names.pretty(it.src) + if (it.invert) " (inverted)" else "" }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.learn(pad) }, enabled = !ui.running && ui.learning == null) { Text("Learn") }
                TextButton({ vm.clear(pad) }) { Text("Clear") }
                if (pad.isAxis && !pad.isTrigger) TextButton({ vm.toggleInvert(pad) }) { Text("Invert") }
            }
        }
    }
}

@Composable
private fun AccessibilitySection(ui: UiState, vm: AppViewModel) {
    val ctx = LocalContext.current
    Section("Accessibility mapping") {
        Text(
            "This mode needs no root. The system service reads the key events of gamepads. " +
                "Android does not allow this service to create gamepad events. " +
                "It can only run Android actions or block a key.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(if (ui.accServiceOn) "Service status: enabled" else "Service status: disabled")
        Text(
            "Android starts the accessibility service at boot when it is enabled in the system settings.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton({
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Open accessibility settings") }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(ui.accEnabled, vm::setAccEnabled)
            Spacer(Modifier.width(8.dp))
            Text("Mapping active")
        }
        ui.accRules.sortedBy { it.keyCode }.forEach { r ->
            Card(onClick = { vm.editAcc(r) }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${KeyEvent.keyCodeToString(r.keyCode)}  ->  ${r.action}" +
                        if (r.action == "TAP") " (${(r.x * 100).toInt()}%, ${(r.y * 100).toInt()}%)" else "",
                    Modifier.padding(10.dp),
                )
            }
        }
        Button(vm::startCapture, enabled = !ui.capturing) {
            Text(if (ui.capturing) "Press a gamepad button..." else "Add key")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccDialog(rule: AccRule, vm: AppViewModel) {
    var action by remember(rule) { mutableStateOf(rule.action) }
    var x by remember(rule) { mutableFloatStateOf(rule.x) }
    var y by remember(rule) { mutableFloatStateOf(rule.y) }
    AlertDialog(
        onDismissRequest = { vm.editAcc(null) },
        title = { Text(KeyEvent.keyCodeToString(rule.keyCode)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccRule.ACTIONS.forEach { a ->
                        FilterChip(action == a, { action = a }, { Text(a) })
                    }
                }
                if (action == "TAP") {
                    Text("X: ${(x * 100).toInt()}%")
                    Slider(x, { x = it })
                    Text("Y: ${(y * 100).toInt()}%")
                    Slider(y, { y = it })
                }
            }
        },
        confirmButton = { TextButton({ vm.saveAcc(AccRule(rule.keyCode, action, x, y)) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton({ vm.deleteAcc(rule.keyCode) }) { Text("Delete") }
                TextButton({ vm.editAcc(null) }) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomProfileFields(c: EngineConfig, vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HexField("Vendor ID (hex)", c.customVendor, Modifier.weight(1f)) { v -> vm.edit { it.copy(customVendor = v) } }
            HexField("Product ID (hex)", c.customProduct, Modifier.weight(1f)) { v -> vm.edit { it.copy(customProduct = v) } }
        }
        var name by remember { mutableStateOf(c.customName) }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; vm.edit { c2 -> c2.copy(customName = it) } },
            label = { Text("Device name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Axis layout:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AxisLayout.entries.forEach { l ->
                FilterChip(c.customAxes.equals(l.name, true), { vm.edit { it.copy(customAxes = l.name) } }, { Text(l.title) })
            }
        }
    }
}

@Composable
private fun HexField(label: String, value: Int, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    var text by remember { mutableStateOf("%04x".format(value)) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val clean = t.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(4)
            text = clean
            clean.toIntOrNull(16)?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyboardSection(ui: UiState, vm: AppViewModel) {
    val c = ui.config
    Section("Keyboard keys (Pad ${ui.slot + 1})") {
        Text(
            "Send keyboard keys from buttons or sticks of the source device. Needs root or Shizuku.",
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "uinput" to "Virtual keyboard", "inject" to "Key injection").forEach { (k, t) ->
                FilterChip(c.kbdMode == k, { vm.edit { it.copy(kbdMode = k) } }, { Text(t) })
            }
        }
        Text(
            "Virtual keyboard: a keyboard device. Android can hide the on-screen keyboard while it exists. " +
                "Key injection: events go to the focused app. A held key does not repeat.",
            style = MaterialTheme.typography.bodySmall,
        )
        c.kbdRules.forEachIndexed { i, r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${Names.pretty(r.src)}  ->  ${KbKeys.label(r.key)}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ vm.editKbdKey(i) }) { Text("Change key") }
                        TextButton({ vm.deleteKbd(i) }) { Text("Delete") }
                    }
                }
            }
        }
        Button({ vm.learnKbd() }, enabled = !ui.running && !ui.learningKbd) {
            Text(if (ui.learningKbd) "Press a control on the source device..." else "Add key mapping")
        }
        if (c.outputMode == "keyboard" && c.kbdRules.isEmpty()) {
            Text("Keyboard only mode needs at least one key mapping.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPickerDialog(vm: AppViewModel) {
    var group by remember { mutableStateOf(KbKeys.groups.first()) }
    AlertDialog(
        onDismissRequest = vm::cancelKbdPicker,
        title = { Text("Select the key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KbKeys.groups.forEach { g -> FilterChip(group == g, { group = g }, { Text(g) }) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KbKeys.inGroup(group).forEach { k -> OutlinedButton({ vm.pickKbdKey(k.linux) }) { Text(k.name) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(vm::cancelKbdPicker) { Text("Cancel") } },
    )
}
