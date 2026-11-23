package com.samer.comboscanner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.samer.comboscanner.engine.ComboResult
import com.samer.comboscanner.engine.QuoteRecord
import com.samer.comboscanner.engine.QuoteSource

@Composable
fun CombosScreen(vm: MainViewModel, legFilter: String?, onCombo: (String) -> Unit, onActivity: () -> Unit, onClearLeg: () -> Unit) {
    val scan by vm.scan.collectAsState()
    val status by vm.status.collectAsState()
    val settings by vm.settings.collectAsState()
    var trading by remember { mutableStateOf<ComboResult?>(null) }

    val combos = scan?.combos.orEmpty()
        .filter { legFilter == null || it.candidate.legs.any { l -> l.leg.id == legFilter } }
        .sortedWith(compareByDescending<ComboResult> { it.best != null }.thenByDescending { it.bestReturnPerDollar })
    val legName = legFilter?.let { id -> scan?.rankedLegs?.firstOrNull { it.leg.id == id }?.let { "${it.leg.matchup} · ${it.leg.outcome}" } }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScanStatusBar(status, settings.demoMode, vm::scanNow, onActivity) }
        if (legName != null) item {
            InputChip(selected = true, onClick = onClearLeg, label = { Text("Using: $legName  ✕") }, modifier = Modifier.padding(horizontal = 16.dp))
        }
        scan?.notes?.forEach { n -> item { Text(n, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        when {
            scan == null -> item { EmptyState("No scan yet", "Press Scan now to find legs and build combos.") }
            scan!!.qualifyingLegIds.size < settings.minLegs -> item {
                EmptyState("Not enough qualifying legs", "${scan!!.qualifyingLegIds.size} legs meet your filters and ${settings.minLegs} are needed. The app will wait for new prices.", "Scan again", vm::scanNow)
            }
            combos.isEmpty() -> item { EmptyState("No combos quoted", "No grouping got a quote this scan. Check Scan activity for details.") }
        }
        items(combos, key = { it.candidate.key }) { c ->
            ComboCard(vm, c, settings.quoteFreshSeconds, onOpen = { onCombo(c.candidate.key) }, onTrade = { trading = c })
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    trading?.let { c -> TradeSheet(vm, c.candidate.key, onDismiss = { trading = null }) }
}

fun comboTitle(c: ComboResult) = "${c.candidate.size}-leg combo · ${c.candidate.sports.joinToString(" + ")}"

fun quoteLabel(q: QuoteRecord) = when (q.source) {
    QuoteSource.API_RFQ -> "Verified RFQ quote"
    QuoteSource.MANUAL_APP -> "Quote entered from Kalshi app"
}

/** The main decision card: stake and payout get the most weight, picks right below, trade button last. */
@Composable
fun ComboCard(vm: MainViewModel, c: ComboResult, freshSeconds: Int, onOpen: (() -> Unit)?, onTrade: () -> Unit) {
    val now = LocalNow.current
    val refreshing by vm.refreshing.collectAsState()
    val best = c.best
    val fresh = best != null && now - best.timestamp < freshSeconds * 1000L
    val isRefreshing = best != null && "${c.candidate.key}@${best.stakeCents}" in refreshing
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).let { if (onOpen != null) it.clickable(onClick = onOpen) else it },
        border = if (c.verified && fresh) BorderStroke(1.5.dp, positiveColor()) else null,
        colors = CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comboTitle(c), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (c.verified) Pill("Rechecked", positiveColor())
                if (c.candidate.hasRelatedLegs) { Spacer(Modifier.size(4.dp)); Pill("Related legs", Warning) }
            }
            if (best == null) {
                Text("No quote returned for this grouping.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue("Recommended stake", Fmt.money(best.stakeCents), Modifier.weight(1f), big = true)
                    LabeledValue("Total payout if it wins", Fmt.money(best.payoutCents), Modifier.weight(1f), big = true, valueColor = positiveColor())
                }
                Text("Net profit if it wins ${Fmt.money(best.netProfitIfWinsCents)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue("Offered multiplier", Fmt.mult(best.multiplier), Modifier.weight(1f))
                    LabeledValue("Estimated fair multiplier", Fmt.mult(c.candidate.fairMultiplier), Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                val ret = c.expectedReturnPerDollar(best)
                Row(Modifier.fillMaxWidth()) {
                    LabeledValue("Estimated return per dollar", Fmt.signedPct(ret), Modifier.weight(1f), valueColor = if (ret > 0) positiveColor() else Negative)
                    LabeledValue("Expected profit", Fmt.signedMoney(c.expectedProfitCents(best)), Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${quoteLabel(best)} checked ${Fmt.age(best.timestamp, now)}" + if (!fresh) " · expired" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (fresh) MaterialTheme.colorScheme.onSurfaceVariant else Warning,
                )
            }
            Spacer(Modifier.height(10.dp))
            val order = best?.entryOrder ?: c.candidate.legs.map { it.leg.id }
            order.forEachIndexed { i, id ->
                val l = c.candidate.legs.first { it.leg.id == id }
                Text(
                    "${i + 1}. ${l.leg.matchup} · ${l.leg.outcome}",
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
                Text(
                    "   ${l.leg.sport} · ask ${Fmt.cents(l.leg.askCents)} · fair ${Fmt.pct(l.fair.probability, 1)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (best != null) {
                Spacer(Modifier.height(12.dp))
                if (!fresh) {
                    OutlinedButton(
                        onClick = { vm.refreshQuote(c.candidate.key, best.entryOrder, best.stakeCents) },
                        enabled = !isRefreshing, modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Refresh quote")
                    }
                }
                Button(onClick = onTrade, modifier = Modifier.fillMaxWidth()) { Text("Review & trade · ${Fmt.money(best.stakeCents).removeSuffix(".00")}") }
            }
        }
    }
}
