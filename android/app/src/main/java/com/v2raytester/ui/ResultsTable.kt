package com.v2raytester.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.v2raytester.TesterViewModel
import com.v2raytester.core.Status
import com.v2raytester.core.TestResult
import com.v2raytester.ui.theme.*

private fun fmtReach(reach: Map<String, Boolean>): String =
    reach.entries.joinToString(" ") { it.key + if (it.value) "✓" else "✗" }

private fun statusLabel(s: Status) = when (s) {
    Status.OK -> "online"; Status.TIMEOUT -> "timeout"; Status.FAILED -> "failed"
    Status.UNSUPPORTED -> "unsupported"; Status.PENDING -> "testing…"; Status.ERROR -> "error"
}

private fun statusColor(r: TestResult): Color = when (r.status) {
    Status.OK -> latencyColor(r.latency)
    Status.PENDING -> TestFg
    Status.TIMEOUT, Status.UNSUPPORTED -> WarnFg
    else -> BadFg
}

private fun shareText(ctx: Context, text: String) {
    if (text.isBlank()) return
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(i, "Share working configs"))
}

private fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("config", text))
}

@Composable
fun ResultsSection(vm: TesterViewModel) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var actionIdx by remember { mutableStateOf<Int?>(null) }
    val order = if (tab == 0) vm.allOrder else vm.workingOrder

    Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { shareText(ctx, vm.workingLinks()) }) {
                    Text("Share working (${vm.workingOrder.size})", color = Green, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("sort:", color = Muted, fontSize = 11.sp)
                SortChip("lat") { vm.sort(order, "latency") }
                SortChip("ping") { vm.sort(order, "ping") }
                SortChip("name") { vm.sort(order, "tag") }
            }
            TabRow(selectedTabIndex = tab, containerColor = Card2, contentColor = Green) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("All (${vm.allOrder.size})") })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("Working ✓ (${vm.workingOrder.size})") })
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(order, key = { _, idx -> idx }) { pos, idx ->
                    ResultRow(pos + 1, idx, vm) { actionIdx = idx }
                }
            }
        }
    }

    actionIdx?.let { idx ->
        RowActionDialog(vm, idx, ctx) { actionIdx = null }
    }
}

@Composable
private fun SortChip(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun ResultRow(pos: Int, idx: Int, vm: TesterViewModel, onTap: () -> Unit) {
    val r = vm.results[idx] ?: return
    Column(Modifier.fillMaxWidth().clickable { onTap() }.padding(vertical = 6.dp, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("$pos", color = Muted, fontSize = 12.sp, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f)) {
                Text(r.node.tag.ifEmpty { r.node.type }, color = Fg, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.node.type} · ${r.node.server}:${r.node.port}", color = Muted, fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (r.reach.isNotEmpty())
                    Text(fmtReach(r.reach), color = Muted, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(r.latency?.let { "$it ms" } ?: statusLabel(r.status),
                    color = statusColor(r), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                val meta = buildList {
                    r.tcpPing?.let { add("ping ${it}ms") }
                    if (r.country.isNotEmpty()) add(r.country)
                }.joinToString("  ")
                if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 10.sp)
            }
        }
    }
    HorizontalDivider(color = Stroke.copy(alpha = 0.4f))
}

@Composable
private fun RowActionDialog(vm: TesterViewModel, idx: Int, ctx: Context, onDismiss: () -> Unit) {
    val raw = vm.results[idx]?.node?.raw ?: ""
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = Card), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(8.dp).width(220.dp)) {
                ActionItem("Retest") { vm.retest(idx); onDismiss() }
                ActionItem("Copy link") { copyText(ctx, raw); onDismiss() }
                ActionItem("Share") { shareText(ctx, raw); onDismiss() }
                ActionItem("Delete row") { vm.deleteRow(idx); onDismiss() }
            }
        }
    }
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Text(label, color = Fg, fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp, horizontal = 10.dp))
}
