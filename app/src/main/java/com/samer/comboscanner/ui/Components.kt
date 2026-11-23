package com.samer.comboscanner.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samer.comboscanner.engine.ScanPhase
import com.samer.comboscanner.engine.ScanStatus

/** Current scan status with the Scan now button. Shown on Markets and Combos. Tapping the text opens Scan activity. */
@Composable
fun ScanStatusBar(status: ScanStatus, demo: Boolean, onScan: () -> Unit, onOpenActivity: () -> Unit) {
    val running = status.phase !in setOf(ScanPhase.IDLE, ScanPhase.DONE, ScanPhase.FAILED, ScanPhase.WAITING_FOR_LEGS)
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onOpenActivity)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Radar, null, Modifier.size(16.dp), tint = statusColor(status.phase))
                    Spacer(Modifier.width(6.dp))
                    Text(phaseLabel(status.phase) + if (demo) " · DEMO DATA" else "", style = MaterialTheme.typography.labelMedium, color = statusColor(status.phase))
                }
                Text(status.message, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                val tail = buildString {
                    if (status.quoteBudget > 0) append("${status.quotesUsed}/${status.quoteBudget} quotes · ")
                    append("updated ${Fmt.age(status.updatedAt, LocalNow.current)} · Scan activity ›")
                }
                Text(tail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onScan, enabled = !running) { Text(if (running) "Scanning" else "Scan now") }
        }
        if (running && status.quoteBudget > 0) {
            LinearProgressIndicator(
                progress = { (status.quotesUsed.toFloat() / status.quoteBudget).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

fun phaseLabel(p: ScanPhase) = when (p) {
    ScanPhase.IDLE -> "Idle"
    ScanPhase.LOADING_MARKETS -> "Loading markets"
    ScanPhase.PRICING_LEGS -> "Pricing legs"
    ScanPhase.BUILDING_COMBOS -> "Building combos"
    ScanPhase.QUOTING_GROUPINGS -> "Quoting groupings"
    ScanPhase.TESTING_ORDER -> "Testing entry order"
    ScanPhase.TESTING_STAKES -> "Testing stakes"
    ScanPhase.RECHECKING -> "Rechecking"
    ScanPhase.DONE -> "Scan complete"
    ScanPhase.WAITING_FOR_LEGS -> "Waiting for new prices"
    ScanPhase.FAILED -> "Scan failed"
}

@Composable
private fun statusColor(p: ScanPhase): Color = when (p) {
    ScanPhase.FAILED -> Negative
    ScanPhase.WAITING_FOR_LEGS -> Warning
    ScanPhase.DONE -> positiveColor()
    else -> MaterialTheme.colorScheme.primary
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.secondary) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, big: Boolean = false) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor,
        )
    }
}

@Composable
fun KeyRow(label: String, value: String, valueColor: Color = Color.Unspecified, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun ExpandableSection(title: String, initiallyOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyOpen) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        AnimatedVisibility(open) { Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() } }
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Current time, ticking once a second, so quote ages stay live without each screen running its own timer. */
val LocalNow = androidx.compose.runtime.compositionLocalOf { System.currentTimeMillis() }
