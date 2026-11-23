package com.samer.comboscanner.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.samer.comboscanner.data.Recheck
import com.samer.comboscanner.engine.ComboResult
import com.samer.comboscanner.engine.QuotePurpose
import com.samer.comboscanner.engine.QuoteRecord

@Composable
fun ComboDetailScreen(vm: MainViewModel, comboKey: String, refreshOnOpen: Boolean, onOpenTicket: (String) -> Unit) {
    val scan by vm.scan.collectAsState()
    val settings by vm.settings.collectAsState()
    val now = LocalNow.current
    var trading by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    val c = scan?.combos?.firstOrNull { it.candidate.key == comboKey }
    if (c == null) { EmptyState("Combo not in the latest scan", "Scan again to find current combos."); return }
    val best = c.best

    // Alerts open here with a refresh so the numbers shown are current.
    LaunchedEffect(comboKey, refreshOnOpen) {
        if (refreshOnOpen && best != null) vm.refreshQuote(comboKey, best.entryOrder, best.stakeCents, QuotePurpose.LEADER_RECHECK)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ComboCard(vm, c, settings.quoteFreshSeconds, onOpen = null, onTrade = { trading = true }) }
        item {
            ExpandableSection("Legs and entry order", initiallyOpen = true) {
                val order = best?.entryOrder ?: c.candidate.legs.map { it.leg.id }
                order.forEachIndexed { i, id ->
                    val l = c.candidate.legs.first { it.leg.id == id }
                    Text("${i + 1}. ${l.leg.matchup} · ${l.leg.outcome}", fontWeight = FontWeight.Medium)
                    KeyRow("Individual Kalshi price", Fmt.cents(l.leg.askCents))
                    KeyRow("Estimated fair probability", "${Fmt.pct(l.fair.probability, 1)} (${Fmt.pct(l.fair.low, 1)}–${Fmt.pct(l.fair.high, 1)})")
                    KeyRow("Starts", Fmt.start(l.startTime))
                    if (i < order.lastIndex) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        }
        item {
            ExpandableSection("Dollar amounts tested", initiallyOpen = true) {
                StakeTable(c, best, now)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (best != null) OutlinedButton(onClick = { vm.refreshQuote(comboKey, best.entryOrder, best.stakeCents) }) { Text("Recheck ${Fmt.money(best.stakeCents)}") }
                    OutlinedButton(onClick = { manualEntry = true }) { Text("Add quote from Kalshi app") }
                }
            }
        }
        item {
            ExpandableSection("Where the edge comes from", initiallyOpen = false) { EdgeBreakdown(c, best) }
        }
        item {
            ExpandableSection("Every quote for this combo") {
                c.quotes.sortedByDescending { it.timestamp }.forEach { q ->
                    Text(
                        "${Fmt.clock(q.timestamp)} · ${Fmt.money(q.stakeCents)} → ${Fmt.money(q.payoutCents)} (${Fmt.mult(q.multiplier)}) · ${q.purpose.name.lowercase().replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "   order: " + q.entryOrder.joinToString(" → ") { id -> letterFor(c, id) } + " · ${quoteLabel(q)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
    if (trading) TradeSheet(vm, comboKey, onDismiss = { trading = false })
    if (manualEntry) ManualQuoteDialog(
        defaultStake = best?.stakeCents ?: settings.stakeMinDollars * 100,
        onDismiss = { manualEntry = false },
        onSave = { stake, payout ->
            vm.addManualQuote(comboKey, best?.entryOrder ?: c.candidate.legs.map { it.leg.id }, stake, payout)
            manualEntry = false
        },
    )
}

/** Letters by rank (A = strongest leg) so entry orders read like A → B → C → D. */
fun letterFor(c: ComboResult, legId: String): String = ('A' + c.candidate.legs.indexOfFirst { it.leg.id == legId }).toString()

@Composable
private fun StakeTable(c: ComboResult, best: QuoteRecord?, now: Long) {
    val order = best?.entryOrder
    val rows = c.currentQuotes.filter { order == null || it.entryOrder == order }.sortedBy { it.stakeCents }
    if (rows.isEmpty()) { Text("No stakes tested yet."); return }
    Row(Modifier.fillMaxWidth()) {
        listOf("Stake", "Total payout", "Multiplier", "Checked").forEach {
            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    rows.forEach { q ->
        val isBest = best != null && q.stakeCents == best.stakeCents && q.entryOrder == best.entryOrder
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(if (isBest) positiveColor().copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0f))
                .padding(vertical = 4.dp),
        ) {
            val w = if (isBest) FontWeight.Bold else FontWeight.Normal
            Text(Fmt.money(q.stakeCents), Modifier.weight(1f), fontWeight = w)
            Text(Fmt.money(q.payoutCents), Modifier.weight(1f), fontWeight = w)
            Text(Fmt.mult(q.multiplier), Modifier.weight(1f), fontWeight = w, color = if (isBest) positiveColor() else MaterialTheme.colorScheme.onSurface)
            Text(Fmt.age(q.timestamp, now), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (best != null && rows.size > 1) {
        val worst = rows.minBy { it.multiplier }
        Text(
            "Best observed: ${Fmt.money(best.stakeCents)} at ${Fmt.mult(best.multiplier)} versus ${Fmt.money(worst.stakeCents)} at ${Fmt.mult(worst.multiplier)}.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun EdgeBreakdown(c: ComboResult, best: QuoteRecord?) {
    val cand = c.candidate
    KeyRow("Estimated joint win probability", "${Fmt.pct(cand.jointFair, 1)} (${Fmt.pct(cand.jointLow, 1)}–${Fmt.pct(cand.jointHigh, 1)})")
    KeyRow("Estimated fair multiplier", Fmt.mult(cand.fairMultiplier))
    val ref = cand.referenceMultiplier
    if (ref != null) {
        KeyRow("Reference multiplier from individual Kalshi prices", Fmt.mult(ref))
        KeyRow("Advantage from the legs alone", Fmt.signedPct(cand.legEdgeReturn ?: 0.0))
    } else {
        KeyRow("Reference multiplier", "Unavailable")
        Text(
            "Two or more legs come from the same game, so they are likely correlated. There's no reliable combined Kalshi price to compare against, and the joint probability assumes independence.",
            style = MaterialTheme.typography.bodySmall, color = Warning,
        )
    }
    if (best != null) {
        KeyRow("Offered multiplier (fees included)", Fmt.mult(best.multiplier), bold = true)
        c.comboImprovement(best)?.let { KeyRow("Payout improvement from the combo quote", Fmt.signedPct(it), if (it > 0) positiveColor() else Negative, bold = true) }
        KeyRow("Estimated return per dollar", Fmt.signedPct(c.expectedReturnPerDollar(best)), bold = true)
        KeyRow("At the low end of the estimate", Fmt.signedPct(c.conservativeReturnPerDollar(best)))
    }
    c.orderFinding?.let { f ->
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (f.identical) {
            Text("Entry order: Kalshi priced both orders the same, so further order tests were skipped.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Entry order made a difference" + if (f.repeatable) " that repeated in an A–B–A recheck." else ", but it didn't hold up on recheck.", style = MaterialTheme.typography.bodySmall)
            KeyRow(f.orderA.joinToString(" → ") { letterFor(c, it) }, Fmt.mult(f.multiplierA))
            KeyRow(f.orderB.joinToString(" → ") { letterFor(c, it) }, Fmt.mult(f.multiplierB))
        }
    }
}

@Composable
fun ManualQuoteDialog(defaultStake: Int, title: String = "Quote from the Kalshi app", confirm: String = "Save quote", defaultPayout: Int? = null, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var stake by remember { mutableStateOf((defaultStake / 100.0).toString()) }
    var payout by remember { mutableStateOf(defaultPayout?.let { (it / 100.0).toString() } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(stake, { stake = it }, label = { Text("Stake ($)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(payout, { payout = it }, label = { Text("Total payout if it wins ($)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
        },
        confirmButton = {
            val s = stake.toDoubleOrNull()
            val p = payout.toDoubleOrNull()
            TextButton(enabled = s != null && p != null && s > 0 && p > 0, onClick = { onSave((s!! * 100).toInt(), (p!! * 100).toInt()) }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Review & trade: rechecks the quote, shows old vs new, hands off to Kalshi, records the purchase. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradeSheet(vm: MainViewModel, comboKey: String, onDismiss: () -> Unit, stakeOverride: Int? = null) {
    val context = LocalContext.current
    val scan by vm.scan.collectAsState()
    val c = scan?.combos?.firstOrNull { it.candidate.key == comboKey } ?: return
    val original = remember(comboKey) { c.best } ?: return
    val stake = stakeOverride ?: original.stakeCents
    var recheck by remember { mutableStateOf<Recheck?>(null) }
    var checking by remember { mutableStateOf(true) }
    var marking by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(comboKey) {
        vm.refreshQuote(comboKey, original.entryOrder, stake, QuotePurpose.PRE_TRADE) { recheck = it; checking = false }
    }
    val latest = recheck?.fresh ?: original
    val order = original.entryOrder.map { id -> c.candidate.legs.first { it.leg.id == id } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Review & trade", style = MaterialTheme.typography.titleLarge)
            Text(comboTitle(c), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                LabeledValue("Dollar amount to enter", Fmt.money(stake), Modifier.weight(1f), big = true)
                LabeledValue("Total payout if it wins", Fmt.money(latest.payoutCents), Modifier.weight(1f), big = true, valueColor = positiveColor())
            }
            Spacer(Modifier.height(8.dp))
            when {
                checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("  Rechecking the quote…", style = MaterialTheme.typography.bodyMedium)
                }
                recheck?.fresh == null -> Text("Couldn't recheck: ${recheck?.message ?: "no quote"}. Numbers shown are from ${Fmt.clock(original.timestamp)}.", color = Warning)
                recheck!!.fresh!!.payoutCents != original.payoutCents -> Text(
                    "${Fmt.money(stake)} previously paid ${Fmt.money(original.payoutCents)}. It now pays ${Fmt.money(recheck!!.fresh!!.payoutCents)}.",
                    color = Warning, fontWeight = FontWeight.SemiBold,
                )
                else -> Text("Rechecked just now: still pays ${Fmt.money(latest.payoutCents)}.", color = positiveColor())
            }
            val ret = c.expectedReturnPerDollar(latest)
            KeyRow("Offered multiplier", Fmt.mult(latest.multiplier))
            KeyRow("Estimated return per dollar", Fmt.signedPct(ret), if (ret > 0) positiveColor() else Negative, bold = true)
            KeyRow("Expected profit", Fmt.signedMoney(c.expectedProfitCents(latest)))
            KeyRow("Net profit if it wins", Fmt.money(latest.netProfitIfWinsCents))
            Spacer(Modifier.height(12.dp))
            Text("Enter these picks in this order", style = MaterialTheme.typography.titleSmall)
            order.forEachIndexed { i, l ->
                Text("${i + 1}. ${l.leg.matchup} · ${l.leg.outcome} (${l.leg.side.name})", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { continueInKalshi(context, checklist(c, order.map { it.leg.matchup + " · " + it.leg.outcome + " (" + it.leg.side.name + ")" }, stake, latest)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Continue in Kalshi")
            }
            Text(
                "Kalshi has no public link that fills a combo ticket, so the picks and amount are copied to your clipboard as a checklist. You make the purchase in Kalshi.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { marking = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Mark as purchased") }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (marking) ManualQuoteDialog(
        defaultStake = stake, defaultPayout = latest.payoutCents, title = "Record your purchase", confirm = "Mark as purchased",
        onDismiss = { marking = false },
        onSave = { s, p ->
            vm.markPurchased(comboKey, original.entryOrder, s, p, latest)
            marking = false
            onDismiss()
        },
    )
}

private fun checklist(c: ComboResult, picks: List<String>, stake: Int, q: QuoteRecord) = buildString {
    appendLine("${comboTitle(c)}: enter in this order")
    picks.forEachIndexed { i, p -> appendLine("${i + 1}. $p") }
    appendLine("Amount: ${Fmt.money(stake)}")
    append("Expected payout: ${Fmt.money(q.payoutCents)} (${Fmt.mult(q.multiplier)})")
}

private fun continueInKalshi(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Combo checklist", text))
    val pm = context.packageManager
    val launch = listOf("com.kalshi", "com.kalshi.app", "com.kalshi.kalshi").firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    val intent = launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://kalshi.com"))
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) { }
}
