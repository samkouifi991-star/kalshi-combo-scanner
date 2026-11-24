package com.samer.comboscanner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.samer.comboscanner.engine.ComboResult
import com.samer.comboscanner.engine.LongCombos
import com.samer.comboscanner.engine.QuoteRecord

/** Long tab: 100+ leg combos made only of legs priced at the floor (94¢) or higher. */
@Composable
fun LongScreen(vm: MainViewModel, onCombo: (String) -> Unit, onActivity: () -> Unit) {
    val scan by vm.longScan.collectAsState()
    val status by vm.longStatus.collectAsState()
    val settings by vm.settings.collectAsState()
    var trading by remember { mutableStateOf<ComboResult?>(null) }
    val combos = scan?.combos.orEmpty().filter { it.best != null }.sortedByDescending { it.bestReturnPerDollar }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScanStatusBar(status, settings.demoMode, vm::scanLongNow, onActivity) }
        item {
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "${settings.longMinLegs}–${settings.longMaxLegs} legs · every leg ${settings.longMinAskCents}¢+ · positive estimated edge only",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "With this many legs, small errors multiply. A combo only pays off if your legs beat the break-even leg probability shown on each card. Legs from the same game often move together, so the joint numbers are optimistic.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    scan?.let {
                        Text(
                            "${it.qualifyingLegIds.size} legs qualify · ${it.unpricedLegs.size} had no evidence · ${it.candidatesBuilt} groupings built",
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        scan?.notes?.forEach { n -> item { Text(n, Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        if (scan == null) item { EmptyState("No long scan yet", "Press Scan now to build ${settings.longMinLegs}+ leg combos from legs priced ${settings.longMinAskCents}¢ or higher.") }
        else if (combos.isEmpty()) item { EmptyState("No long combos quoted", status.message) }
        items(combos, key = { it.candidate.key }) { c ->
            Column {
                LongSummary(c, c.best!!)
                ComboCard(vm, c, settings.quoteFreshSeconds, onOpen = { onCombo(c.candidate.key) }, onTrade = { trading = c })
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    trading?.let { c -> TradeSheet(vm, c.candidate.key, onDismiss = { trading = null }) }
}

/** The numbers that decide a long combo: joint win chance and the break-even leg probability. */
@Composable
private fun LongSummary(c: ComboResult, q: QuoteRecord) {
    val m = LongCombos.math(c.candidate, q.multiplier)
    Card(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            KeyRow("Estimated joint win probability", "${Fmt.pct(m.jointFair, 2)} (low ${Fmt.pct(m.jointLow, 2)})", bold = true)
            KeyRow("Average leg: estimated vs break-even", "${Fmt.pct(m.averageLegFair, 2)} vs ${Fmt.pct(m.breakEvenLegProbability, 2)}",
                if (m.averageLegFair > m.breakEvenLegProbability) positiveColor() else Negative, bold = true)
            KeyRow("If every leg is 1 pt worse", Fmt.signedPct(m.returnIfLegsOnePpWorse))
        }
    }
}

/** Extra rows for the edge breakdown of long combos. */
@Composable
fun LongMathRows(c: ComboResult, q: QuoteRecord) {
    val m = LongCombos.math(c.candidate, q.multiplier)
    KeyRow("Average estimated leg probability", Fmt.pct(m.averageLegFair, 2))
    KeyRow("Break-even average leg probability", Fmt.pct(m.breakEvenLegProbability, 2))
    KeyRow("Return if every leg is 1 pt worse", Fmt.signedPct(m.returnIfLegsOnePpWorse))
    KeyRow("Return at the low end of every estimate", Fmt.signedPct(m.conservativeReturnPerDollar))
}
