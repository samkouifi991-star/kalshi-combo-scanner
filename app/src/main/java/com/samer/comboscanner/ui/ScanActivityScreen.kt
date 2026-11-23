package com.samer.comboscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Every quote the app has seen, purchased or not, with exact legs, order, stake, payout and time. */
@Composable
fun ScanActivityScreen(vm: MainViewModel) {
    val activity by vm.activity.collectAsState()
    val scan by vm.scan.collectAsState()
    val status by vm.status.collectAsState()
    val legNames = scan?.rankedLegs.orEmpty().associate { it.leg.id to "${it.leg.matchup} · ${it.leg.outcome}" }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("${phaseLabel(status.phase)}: ${status.message}", style = MaterialTheme.typography.bodyMedium)
                scan?.let {
                    Text(
                        "Last scan ${Fmt.clock(it.startedAt)}–${Fmt.clock(it.finishedAt)} · ${it.rankedLegs.size} legs priced · ${it.qualifyingLegIds.size} qualifying · ${it.candidatesBuilt} combos built · ${it.combos.sumOf { c -> c.quotes.size }} quotes",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    it.notes.forEach { n -> Text("• $n", style = MaterialTheme.typography.labelSmall) }
                }
                Text("${activity.size} quotes recorded. These are not purchases.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
            HorizontalDivider()
        }
        items(activity.asReversed(), key = { it.id }) { q ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "${Fmt.date(q.timestamp)} ${Fmt.clock(q.timestamp)} · ${Fmt.money(q.stakeCents)} → ${Fmt.money(q.payoutCents)} (${Fmt.mult(q.multiplier)})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("${q.purpose.name.lowercase().replace('_', ' ')} · ${quoteLabel(q)}" + (q.comboMarketTicker?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall)
                q.entryOrder.forEachIndexed { i, id ->
                    Text("  ${i + 1}. ${legNames[id] ?: id}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}
