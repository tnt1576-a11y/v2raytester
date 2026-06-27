package com.v2raytester.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2raytester.TesterViewModel
import com.v2raytester.ui.theme.*

@Composable
fun TesterScreen(vm: TesterViewModel) {
    val ctx = LocalContext.current
    var showSubs by remember { mutableStateOf(false) }
    var showSites by remember { mutableStateOf(false) }

    // Keep the screen on while a test or subscription fetch runs. A screen timeout
    // backgrounds the app, which throttles the test coroutines and can freeze/kill
    // the spawned xray processes — stalling the run. This holds it only while busy.
    val view = LocalView.current
    val keepScreenOn = vm.testing.value || vm.fetching.value
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { vm.appendConfigs(it.readBytes().toString(Charsets.UTF_8)) }
        }
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("V2Ray Tester", color = Fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("latency + site reachability", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))

            InputCard(vm, onLoadFile = { filePicker.launch("*/*") }, onSubs = { showSubs = true })
            Spacer(Modifier.height(8.dp))
            ControlsCard(vm, onEditSites = { showSites = true })
            Spacer(Modifier.height(8.dp))

            Box(Modifier.weight(1f)) { ResultsSection(vm) }

            if (vm.testing.value || vm.progress.value > 0f) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { vm.progress.value },
                    modifier = Modifier.fillMaxWidth(), color = Green, trackColor = Card2,
                )
            }
        }
    }

    if (showSubs) SubscriptionDialog(vm) { showSubs = false }
    if (showSites) EditSitesDialog(vm) { showSites = false }
}

@Composable
private fun InputCard(vm: TesterViewModel, onLoadFile: () -> Unit, onSubs: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp)) {
            OutlinedTextField(
                value = vm.configText.value,
                onValueChange = vm::setConfigText,
                modifier = Modifier.fillMaxWidth().height(120.dp),
                placeholder = { Text("Paste configs, or Load from Subscription…", color = Muted) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = Fg),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Green, unfocusedBorderColor = Stroke,
                    focusedContainerColor = Card2, unfocusedContainerColor = Card2,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(onClick = onSubs, colors = ButtonDefaults.filledTonalButtonColors(containerColor = GreenDeep, contentColor = Fg)) {
                    Text("Subscription")
                }
                GhostButton("Load file", onLoadFile)
                GhostButton("Dedupe", vm::removeDuplicates)
                GhostButton("Clear", vm::clear)
            }
            if (vm.status.value.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(vm.status.value, color = Muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ControlsCard(vm: TesterViewModel, onEditSites: () -> Unit) {
    val s = vm.settings.value
    Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.testing.value) {
                    Button(onClick = vm::stop, colors = ButtonDefaults.buttonColors(containerColor = BadFg, contentColor = Fg)) { Text("Stop") }
                } else {
                    Button(onClick = vm::startTests, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)) {
                        Text("Test All", fontWeight = FontWeight.Bold)
                    }
                }
                SettingMenu("Threads", s.concurrency.toString(), listOf("4", "6", "8", "12", "16", "24", "32", "48", "64")) {
                    vm.updateSettings(s.copy(concurrency = it.toInt()))
                }
                SettingMenu("Timeout", s.timeoutSec.toString(), listOf("5", "8", "10", "15")) {
                    vm.updateSettings(s.copy(timeoutSec = it.toInt()))
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckRow("Geo", s.geo) { vm.updateSettings(s.copy(geo = it)) }
                CheckRow("Skip dead", s.prefilter) { vm.updateSettings(s.copy(prefilter = it)) }
                CheckRow("Sites", s.reachEnabled) { vm.updateSettings(s.copy(reachEnabled = it)) }
                TextButton(onClick = onEditSites) { Text("Edit", color = Green, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun GhostButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Fg),
        border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Green, uncheckedColor = Muted))
        Text(label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
    }
}

@Composable
private fun SettingMenu(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Fg),
            border = androidx.compose.foundation.BorderStroke(1.dp, Stroke),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        ) { Text("$label $value", fontSize = 12.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}
