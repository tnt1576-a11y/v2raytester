package com.v2raytester.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.v2raytester.TesterViewModel
import com.v2raytester.core.DEFAULT_REACH_TARGETS
import com.v2raytester.core.ReachTarget
import com.v2raytester.ui.theme.*

@Composable
fun SubscriptionDialog(vm: TesterViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(vm.subUrlsText.value) }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("Subscriptions", color = Fg, fontSize = 18.sp)
                Text("One URL per line. # lines ignored.", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    textStyle = TextStyle(fontSize = 11.sp, color = Fg),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Green, unfocusedBorderColor = Stroke,
                        focusedContainerColor = Card2, unfocusedContainerColor = Card2,
                    ),
                )
                if (vm.fetching.value || vm.subProgress.value > 0f) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { vm.subProgress.value },
                        modifier = Modifier.fillMaxWidth(), color = Green, trackColor = Card2)
                }
                if (vm.subStatus.value.isNotEmpty())
                    Text(vm.subStatus.value, color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (vm.fetching.value) {
                        GhostButton("Abort", vm::abortFetch)
                    } else {
                        Button(onClick = { vm.saveSubUrls(text); vm.fetchSubscriptions() },
                            colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)) {
                            Text("Fetch All")
                        }
                    }
                    GhostButton("Save") { vm.saveSubUrls(text) }
                    Spacer(Modifier.weight(1f))
                    GhostButton("Close", onDismiss)
                }
            }
        }
    }
}

@Composable
fun EditSitesDialog(vm: TesterViewModel, onDismiss: () -> Unit) {
    val initial = vm.settings.value.reachTargets.joinToString("\n") { "${it.code} ${it.url}" }
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("Reachability targets", color = Fg, fontSize = 18.sp)
                Text("One per line:  CODE  https://url", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = TextStyle(fontSize = 12.sp, color = Fg),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Green, unfocusedBorderColor = Stroke,
                        focusedContainerColor = Card2, unfocusedContainerColor = Card2,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GhostButton("Reset") {
                        text = DEFAULT_REACH_TARGETS.joinToString("\n") { "${it.code} ${it.url}" }
                    }
                    Spacer(Modifier.weight(1f))
                    GhostButton("Cancel", onDismiss)
                    Button(onClick = {
                        val targets = text.lines().mapNotNull { line ->
                            val parts = line.trim().split(Regex("\\s+"), limit = 2)
                            if (parts.size == 2 && parts[1].contains("://"))
                                ReachTarget(parts[0].take(6), parts[1].trim()) else null
                        }
                        vm.updateSettings(vm.settings.value.copy(reachTargets = targets))
                        onDismiss()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Bg)) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
