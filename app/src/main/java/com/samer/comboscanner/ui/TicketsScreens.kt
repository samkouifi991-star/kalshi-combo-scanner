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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.samer.comboscanner.data.FillSource
import com.samer.comboscanner.data.LegState
import com.samer.comboscanner.data.Ticket
import com.samer.comboscanner.data.TicketStatus
import com.samer.comboscanner.engine.QuotePurpose
import com.samer.comboscanner.engine.QuoteSource

@Composable
fun OpenScreen(vm: MainViewModel, onTicket: (String) -> Unit) {
    val tickets by vm.tickets.collectAsState()
    val open = tickets.filter { it.status == TicketStatus.OPEN }.sortedBy { it.nextEventTime }
    LaunchedEffect(Unit) { vm.refreshTickets() }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    LabeledValue("Total spent", Fmt.money(open.sumOf { it.spentCents }), Modifier.weight(1f))
                    LabeledValue("Open combos", open.size.toString(), Modifier.weight(1f))
                    LabeledValue("Remaining potential payout", Fmt.money(open.sumOf { it.potentialPayoutCents }), Modifier.weight(1.3f))
                }
            }
        }
        if (open.isEmpty()) item { EmptyState("No open combos", "After you buy in Kalshi, use Mark as purchased on the trade sheet and the combo shows up here.") }
        items(open, key = { it.id }) { t -> TicketCard(vm, t) { onTicket(t.id) } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TicketCard(vm: MainViewModel, t: Ticket, onClick: () -> Unit) {
    var expanded by rememberSaveable(t.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text("${t.legs.size}-leg combo · ${t.sports.joinToString(" + ")}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                LabeledValue("Amount paid", Fmt.money(t.spentCents), Modifier.weight(1f))
                LabeledValue("Potential payout", Fmt.money(t.potentialPayoutCents), Modifier.weight(1f), valueColor = positiveColor())
            }
            Text(progress(t), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            Text("Next: ${Fmt.start(t.nextEventTime.takeIf { it != Long.MAX_VALUE })}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (t.fills.size > 1) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide ${t.fills.size} purchases" else "Show ${t.fills.size} purchases") }
            }
            if (expanded || t.fills.size == 1) t.fills.forEach { f ->
                Text(
                    "${Fmt.clock(f.timestamp)} ${Fmt.date(f.timestamp)} · ${Fmt.money(f.stakeCents)} → ${Fmt.money(f.payoutCents)} · ${fillLabel(f.source)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(8.dp))
            RecheckSameStake(vm, t)
        }
    }
}

/** Gets a fresh quote for the same legs, order and stake. It never changes the recorded position. */
@Composable
private fun RecheckSameStake(vm: MainViewModel, t: Ticket) {
    val stake = t.fills.last().stakeCents
    val payout = t.fills.last().payoutCents
    var result by remember { mutableStateOf<String?>(null) }
    val inScan = vm.repo.combo(t.comboKey) != null
    OutlinedButton(
        enabled = inScan,
        onClick = {
            result = "Checking…"
            vm.refreshQuote(t.comboKey, t.legs.map { it.leg.id }, stake, QuotePurpose.SAME_STAKE_RECHECK) { r ->
                result = r.fresh?.let { q ->
                    if (q.payoutCents == payout) "Another ${Fmt.money(stake)} still pays ${Fmt.money(q.payoutCents)}."
                    else "Another ${Fmt.money(stake)} now pays ${Fmt.money(q.payoutCents)} (you got ${Fmt.money(payout)})."
                } ?: "No quote: ${r.message}"
            }
        },
    ) { Text("Recheck same stake · ${Fmt.money(stake)}") }
    if (!inScan) Text("Scan again to recheck: this combo isn't in the latest scan.", style = MaterialTheme.typography.labelSmall)
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

fun fillLabel(s: FillSource) = when (s) {
    FillSource.CONFIRMED -> "Confirmed fill"
    FillSource.MANUAL -> "Manually recorded"
}

fun progress(t: Ticket): String = listOf(
    LegState.WON to "won", LegState.LOST to "lost", LegState.LIVE to "live", LegState.UPCOMING to "upcoming", LegState.VOID to "void",
).mapNotNull { (s, label) -> t.count(s).takeIf { it > 0 }?.let { "$it $label" } }.joinToString(" · ")

@Composable
fun HistoryScreen(vm: MainViewModel, onTicket: (String) -> Unit, onActivity: () -> Unit) {
    val tickets by vm.tickets.collectAsState()
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var sport by rememberSaveable { mutableStateOf<String?>(null) }
    var days by rememberSaveable { mutableStateOf<Int?>(null) }
    val now = LocalNow.current
    val settled = tickets.filter { it.status != TicketStatus.OPEN }
    val shown = settled.filter { t ->
        (result == null || t.status.name == result) &&
            (sport == null || sport in t.sports) &&
            (days == null || (t.settledAt ?: 0) >= now - days!! * 86_400_000L)
    }.sortedByDescending { it.settledAt }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                val pl = shown.sumOf { it.realizedProfitCents }
                LabeledValue("Realized profit/loss", Fmt.signedMoney(pl.toDouble()), Modifier.weight(1f), valueColor = if (pl >= 0) positiveColor() else Negative)
                TextButton(onClick = onActivity) { Text("Scan activity ›") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("WON" to "Won", "LOST" to "Lost", "VOID" to "Voided").forEach { (k, l) ->
                    FilterChip(selected = result == k, onClick = { result = if (result == k) null else k }, label = { Text(l) })
                }
                settled.flatMap { it.sports }.distinct().forEach { s ->
                    FilterChip(selected = sport == s, onClick = { sport = if (sport == s) null else s }, label = { Text(s) })
                }
                listOf(7, 30, 90).forEach { d ->
                    FilterChip(selected = days == d, onClick = { days = if (days == d) null else d }, label = { Text("Last $d days") })
                }
            }
            Text("Based on confirmed and manually recorded fills only. Unpurchased quotes are in Scan activity.", Modifier.padding(16.dp, 6.dp), style = MaterialTheme.typography.labelSmall)
        }
        if (shown.isEmpty()) item { EmptyState("No settled combos", "Settled purchases appear here with their result.") }
        items(shown, key = { it.id }) { t ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable { onTicket(t.id) }) {
                Column(Modifier.padding(14.dp)) {
                    Row {
                        Text("${t.legs.size}-leg · ${t.sports.joinToString(" + ")}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Pill(t.status.name.lowercase().replaceFirstChar { it.uppercase() }, if (t.status == TicketStatus.WON) positiveColor() else if (t.status == TicketStatus.LOST) Negative else Warning)
                    }
                    Text(t.settledAt?.let { Fmt.date(it) } ?: "", style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        LabeledValue("Stake", Fmt.money(t.spentCents), Modifier.weight(1f))
                        LabeledValue("Actual payout", Fmt.money(t.actualPayoutCents), Modifier.weight(1f))
                        LabeledValue("Profit/loss", Fmt.signedMoney(t.realizedProfitCents.toDouble()), Modifier.weight(1f), valueColor = if (t.realizedProfitCents >= 0) positiveColor() else Negative)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun TicketDetailScreen(vm: MainViewModel, id: String) {
    val tickets by vm.tickets.collectAsState()
    val t = tickets.firstOrNull { it.id == id }
    if (t == null) { EmptyState("Ticket not found", ""); return }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            ExpandableSection("Original recommendation", initiallyOpen = true) {
                val r = t.recommendation
                if (r == null) Text("No recommendation recorded.") else {
                    KeyRow("Stake", Fmt.money(r.stakeCents))
                    KeyRow("Total payout", Fmt.money(r.payoutCents))
                    KeyRow("Multiplier", Fmt.mult(r.multiplier))
                    KeyRow("Estimated joint win probability", Fmt.pct(t.jointFair, 1))
                    t.recommendedReturnPerDollar?.let { KeyRow("Estimated return per dollar", Fmt.signedPct(it)) }
                    KeyRow("Quote", "${if (r.source == QuoteSource.API_RFQ) "Verified RFQ quote" else "Kalshi app quote"} at ${Fmt.clock(r.timestamp)}, ${Fmt.date(r.timestamp)}")
                }
            }
        }
        item {
            ExpandableSection("Actual fills", initiallyOpen = true) {
                t.fills.forEach { f ->
                    KeyRow("${Fmt.date(f.timestamp)} ${Fmt.clock(f.timestamp)}", "${Fmt.money(f.stakeCents)} → ${Fmt.money(f.payoutCents)}")
                    Text(fillLabel(f.source), style = MaterialTheme.typography.labelSmall, color = if (f.source == FillSource.CONFIRMED) positiveColor() else Warning)
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                KeyRow("Total spent", Fmt.money(t.spentCents), bold = true)
                KeyRow("Total payout if it wins", Fmt.money(t.potentialPayoutCents), bold = true)
                if (t.status != TicketStatus.OPEN) KeyRow("Realized profit/loss", Fmt.signedMoney(t.realizedProfitCents.toDouble()), bold = true)
            }
        }
        item {
            ExpandableSection("Legs and results", initiallyOpen = true) {
                t.legs.forEachIndexed { i, l ->
                    val st = t.legStates[l.leg.id] ?: LegState.UPCOMING
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}. ${l.leg.matchup} · ${l.leg.outcome}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Pill(st.name.lowercase(), when (st) { LegState.WON -> positiveColor(); LegState.LOST -> Negative; else -> MaterialTheme.colorScheme.secondary })
                    }
                    Text("ask ${Fmt.cents(l.leg.askCents)} · fair ${Fmt.pct(l.fair.probability, 1)} · ${Fmt.start(l.startTime)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.refreshTickets() }) { Text("Refresh results") }
                TextButton(onClick = { vm.deleteTicket(t.id) }) { Text("Delete record", color = Negative) }
            }
        }
    }
}
