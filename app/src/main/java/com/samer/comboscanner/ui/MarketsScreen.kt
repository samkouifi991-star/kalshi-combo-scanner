package com.samer.comboscanner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samer.comboscanner.engine.EvidenceKind
import com.samer.comboscanner.engine.FairEstimate
import com.samer.comboscanner.engine.ScoredLeg

@Composable
fun MarketsScreen(vm: MainViewModel, onLeg: (String) -> Unit, onActivity: () -> Unit) {
    val scan by vm.scan.collectAsState()
    val status by vm.status.collectAsState()
    val settings by vm.settings.collectAsState()
    val warnings by vm.warnings.collectAsState()

    // Screen filters start from the scan settings; changing them here only filters the list.
    var sport by rememberSaveable { mutableStateOf<String?>(null) }
    var minProb by rememberSaveable { mutableStateOf(settings.minProbability) }
    var minEdge by rememberSaveable { mutableStateOf(settings.minEdgePp) }

    val legs = scan?.rankedLegs.orEmpty()
    val qualifying = scan?.qualifyingLegIds.orEmpty().toSet()
    val shown = legs.filter { (sport == null || it.leg.sport == sport) && it.fair.probability >= minProb && it.edgeAfterFeesPp > minEdge }
    val sports = legs.map { it.leg.sport }.distinct().sorted()

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScanStatusBar(status, settings.demoMode, vm::scanNow, onActivity) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sport == null, onClick = { sport = null }, label = { Text("All sports") })
                sports.forEach { s -> FilterChip(selected = sport == s, onClick = { sport = if (sport == s) null else s }, label = { Text(s) }) }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Probability", "${Fmt.pct(minProb)}+", listOf(0.70, 0.75, 0.80, 0.85, 0.90, 0.95).map { "${Fmt.pct(it)}+" to it }) { minProb = it }
                ChoiceChip("Edge", "> ${Fmt.pp(minEdge)}", listOf(0.0, 1.0, 2.0, 3.0, 5.0).map { "> ${Fmt.pp(it)}" to it }) { minEdge = it }
            }
        }
        warnings.forEach { w -> item { Text(w, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = Warning) } }
        item {
            val unpriced = scan?.unpricedLegs?.size ?: 0
            Text(
                "${qualifying.size} qualifying legs · ${shown.size} shown" + if (unpriced > 0) " · $unpriced without enough evidence to price" else "",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (scan == null) item { EmptyState("No scan yet", "Press Scan now. The app finds legs, builds combos, tests entry orders and compares stakes on its own.") }
        else if (shown.isEmpty()) item { EmptyState("No legs match these filters", "Lower the probability or edge filter, or scan again for new prices.") }
        items(shown, key = { it.leg.id }) { s -> LegCard(s, s.leg.id in qualifying) { onLeg(s.leg.id) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun <T> ChoiceChip(label: String, current: String, options: List<Pair<String, T>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        FilterChip(selected = true, onClick = { open = true }, label = { Text("$label $current") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (text, v) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(v); open = false }) }
        }
    }
}

/** Answers: what's the outcome, what can I buy it for, what is it worth. */
@Composable
fun LegCard(s: ScoredLeg, qualifies: Boolean, onClick: () -> Unit) {
    val now = LocalNow.current
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text("${s.leg.matchup} · ${s.leg.outcome}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Kalshi ask ${Fmt.cents(s.leg.askCents)} · Estimated fair probability ${Fmt.pct(s.fair.probability, 1)} · Edge ${Fmt.pp(s.edgeBeforeFeesPp)} before fees",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "After fees ${Fmt.pp(s.edgeAfterFeesPp)} · Return ${Fmt.signedPct(s.returnPerDollar)} per dollar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s.edgeAfterFeesPp > 0) positiveColor() else Negative,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (!qualifies) Pill("Not qualifying")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${s.leg.sport} · ${Fmt.start(s.startTime)} · quote ${Fmt.age(s.leg.quoteTime, now)} · ${confidenceLabel(s.fair)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun confidenceLabel(f: FairEstimate) =
    "${f.confidence.name.lowercase().replaceFirstChar { it.uppercase() }} confidence (${Fmt.pct(f.low, 1)}–${Fmt.pct(f.high, 1)})"

@Composable
fun LegDetailScreen(vm: MainViewModel, legId: String, onCombos: (String) -> Unit) {
    val scan by vm.scan.collectAsState()
    val now = LocalNow.current
    val s = scan?.rankedLegs?.firstOrNull { it.leg.id == legId }
    if (s == null) { EmptyState("Leg not in the latest scan", "Scan again to refresh prices."); return }
    val combosWithLeg = scan?.combos.orEmpty().count { c -> c.candidate.legs.any { it.leg.id == legId } }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("${s.leg.sport} · ${Fmt.start(s.startTime)}", style = MaterialTheme.typography.labelMedium)
                Text(s.leg.matchup, style = MaterialTheme.typography.titleLarge)
                Text(s.leg.outcome + " (${s.leg.side.name})", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LabeledValue("Kalshi ask", Fmt.cents(s.leg.askCents))
                    LabeledValue("Estimated fair probability", Fmt.pct(s.fair.probability, 1))
                }
                Spacer(Modifier.height(8.dp))
                KeyRow("Uncertainty (90%)", "${Fmt.pct(s.fair.low, 1)} – ${Fmt.pct(s.fair.high, 1)}")
                KeyRow("Edge before fees", Fmt.pp(s.edgeBeforeFeesPp))
                KeyRow("Fee per contract", Fmt.cents(s.feePerContractCents))
                KeyRow("Edge after fees", Fmt.pp(s.edgeAfterFeesPp), if (s.edgeAfterFeesPp > 0) positiveColor() else Negative, bold = true)
                KeyRow("Expected return per dollar", Fmt.signedPct(s.returnPerDollar), bold = true)
                KeyRow("Return at low end of estimate", Fmt.signedPct(s.conservativeReturnPerDollar))
                KeyRow("Kalshi price updated", Fmt.age(s.leg.quoteTime, now))
                KeyRow("Market", s.leg.marketTicker)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onCombos(legId) }, modifier = Modifier.fillMaxWidth()) {
                    Text("View combos using this leg ($combosWithLeg)")
                }
            }
            HorizontalDivider()
            Text("How the app priced it", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        }
        items(s.fair.evidence) { e ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        when (e.kind) {
                            EvidenceKind.EXTERNAL_MARKET -> "External market"
                            EvidenceKind.HISTORICAL -> "Historical results"
                            EvidenceKind.KALSHI_REFERENCE -> "Kalshi reference"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    )
                    Text(e.source, style = MaterialTheme.typography.bodyMedium)
                    KeyRow("Probability", Fmt.pct(e.probability, 1) + " ± " + Fmt.pct(e.stdError, 1))
                    if (e.detail.isNotBlank()) Text(e.detail, style = MaterialTheme.typography.bodySmall)
                    Text("Updated ${e.updatedAt?.let { Fmt.age(it, now) } ?: "time unknown"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "Sources are blended by their uncertainty. The Kalshi price itself is never used as evidence of fair value.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
