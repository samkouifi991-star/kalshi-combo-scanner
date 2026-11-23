package com.samer.comboscanner.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Supplies combo-eligible legs at their current executable asks. */
interface MarketSource {
    suspend fun eligibleLegs(sports: Set<String>): List<Leg>
}

/** Supplies fair estimates for legs; legs it cannot price are left out of the map. */
interface FairEstimator {
    suspend fun estimate(legs: List<Leg>): Map<String, FairEstimate>
}

data class ScanSettings(
    val legFilter: LegFilter = LegFilter(),
    val combo: ComboSettings = ComboSettings(),
    val stakeMinCents: Int = 500,
    val stakeMaxCents: Int = 2000,
    val stakeIncrementCents: Int = 500,
    /** Also quote the amount one dollar above each step ($5 and $6, $10 and $11). */
    val testNeighborStakes: Boolean = true,
    val quoteBudget: Int = 30,
    /** Share of the budget spent on first quotes of different groupings. */
    val groupingShare: Double = 0.45,
    val orderTestCombos: Int = 2,
    val stakeSweepCombos: Int = 3,
    /** Relative multiplier difference that counts as a real difference worth an A–B–A recheck. */
    val differenceThreshold: Double = 0.02,
    val alertMinReturnPerDollar: Double = 0.05,
    val fees: FeeModel = FeeModel(),
) {
    fun stakesToTest(): List<Int> {
        val steps = generateSequence(stakeMinCents) { it + stakeIncrementCents }.takeWhile { it <= stakeMaxCents }.toList()
        val all = if (testNeighborStakes) steps.flatMap { listOf(it, it + 100) } else steps
        return all.filter { it in stakeMinCents..stakeMaxCents }.distinct().sorted()
    }
}

@Serializable
enum class ScanPhase {
    IDLE, LOADING_MARKETS, PRICING_LEGS, BUILDING_COMBOS, QUOTING_GROUPINGS, TESTING_ORDER,
    TESTING_STAKES, RECHECKING, DONE, WAITING_FOR_LEGS, FAILED
}

@Serializable
data class ScanStatus(
    val phase: ScanPhase,
    val message: String,
    val quotesUsed: Int = 0,
    val quoteBudget: Int = 0,
    val updatedAt: EpochMs,
)

@Serializable
data class OrderFinding(
    val orderA: List<String>,
    val orderB: List<String>,
    val multiplierA: Double,
    val multiplierB: Double,
    /** True when an A–B–A sequence reproduced the difference. */
    val repeatable: Boolean,
    /** True when Kalshi treats both orders as the same combo, so order tests were skipped. */
    val identical: Boolean,
)

@Serializable
data class ComboResult(
    val candidate: ComboCandidate,
    val quotes: List<QuoteRecord>,
    val orderFinding: OrderFinding? = null,
    /** Set when the best quote was confirmed by a later quote at the same order and stake. */
    val verified: Boolean = false,
) {
    /** Latest quote for each (entry order, stake): older ones are history. */
    val currentQuotes: List<QuoteRecord>
        get() = quotes.groupBy { it.entryOrder to it.stakeCents }.values.map { g -> g.maxBy { it.timestamp } }

    val best: QuoteRecord?
        get() = currentQuotes.filter { it.payoutCents > 0 }
            .maxWithOrNull(compareBy<QuoteRecord> { it.multiplier }.thenBy { it.stakeCents })

    fun expectedReturnPerDollar(q: QuoteRecord): Double = candidate.jointFair * q.multiplier - 1
    fun expectedProfitCents(q: QuoteRecord): Double = q.stakeCents * expectedReturnPerDollar(q)
    fun conservativeReturnPerDollar(q: QuoteRecord): Double = candidate.jointLow * q.multiplier - 1
    /** How much the RFQ payout beats buying the legs separately. Null when no fair reference exists. */
    fun comboImprovement(q: QuoteRecord): Double? = candidate.referenceMultiplier?.let { q.multiplier / it - 1 }

    val bestReturnPerDollar: Double get() = best?.let { expectedReturnPerDollar(it) } ?: Double.NEGATIVE_INFINITY
}

@Serializable
data class ScanResult(
    val startedAt: EpochMs,
    val finishedAt: EpochMs,
    /** All priced legs, ranked; [qualifyingLegIds] marks the ones that pass the filters. */
    val rankedLegs: List<ScoredLeg>,
    val qualifyingLegIds: List<String>,
    val unpricedLegs: List<Leg>,
    val candidatesBuilt: Int,
    val combos: List<ComboResult>,
    val notes: List<String>,
    val finalStatus: ScanStatus,
) {
    val leader: ComboResult? get() = combos.firstOrNull { it.best != null }
}

class ScanEngine(
    private val markets: MarketSource,
    private val fair: FairEstimator,
    private val quotes: QuoteProvider,
    private val clock: () -> EpochMs = System::currentTimeMillis,
) {
    suspend fun scan(settings: ScanSettings, onStatus: (ScanStatus) -> Unit = {}): ScanResult {
        val startedAt = clock()
        val notes = mutableListOf<String>()
        var used = 0
        fun status(phase: ScanPhase, msg: String) =
            ScanStatus(phase, msg, used, settings.quoteBudget, clock()).also(onStatus)

        status(ScanPhase.LOADING_MARKETS, "Loading combo-eligible markets")
        val legs = markets.eligibleLegs(settings.legFilter.sports)

        status(ScanPhase.PRICING_LEGS, "Estimating fair probabilities for ${legs.size} legs")
        val estimates = fair.estimate(legs)
        val scored = LegScorer.rank(legs.mapNotNull { l -> estimates[l.id]?.let { LegScorer.score(l, it, settings.fees, settings.stakeMinCents) } })
        val unpriced = legs.filter { it.id !in estimates }
        val now = clock()
        val qualifying = scored.filter { LegScorer.qualifies(it, settings.legFilter, now) }

        fun finish(phase: ScanPhase, msg: String, combos: List<ComboResult>, built: Int) = ScanResult(
            startedAt, clock(), scored, qualifying.map { it.leg.id }, unpriced, built,
            combos.sortedByDescending { it.bestReturnPerDollar }, notes, status(phase, msg),
        )

        if (qualifying.size < settings.combo.minLegs) {
            return finish(
                ScanPhase.WAITING_FOR_LEGS,
                "Only ${qualifying.size} leg${if (qualifying.size == 1) "" else "s"} qualify. Need ${settings.combo.minLegs}. Waiting for new prices.",
                emptyList(), 0,
            )
        }

        status(ScanPhase.BUILDING_COMBOS, "Building ${settings.combo.minLegs}–${settings.combo.maxLegs} leg combos from ${qualifying.size} legs")
        val candidates = ComboBuilder.build(qualifying, settings.combo)
        if (candidates.isEmpty()) {
            return finish(ScanPhase.WAITING_FOR_LEGS, "No valid groupings: qualifying legs are in different combo collections or the same games.", emptyList(), 0)
        }

        val results = LinkedHashMap<String, ComboResult>()
        val baseStake = settings.stakeMinCents
        var stop = false

        // One quote is always held back so the leader can be rechecked before it is shown as verified.
        val workingBudget = settings.quoteBudget - 1
        suspend fun ask(c: ComboCandidate, order: List<ScoredLeg>, stake: Int, purpose: QuotePurpose): QuoteRecord? {
            val limit = if (purpose == QuotePurpose.LEADER_RECHECK) settings.quoteBudget else workingBudget
            if (stop || used >= limit) return null
            used++
            return when (val o = quotes.quote(c.key, order.map { it.leg }, stake, purpose)) {
                is QuoteOutcome.Quoted -> {
                    val prev = results[c.key] ?: ComboResult(c, emptyList())
                    results[c.key] = prev.copy(quotes = prev.quotes + o.record)
                    o.record
                }
                is QuoteOutcome.NoQuote -> {
                    results.getOrPut(c.key) { ComboResult(c, emptyList()) }
                    null
                }
                is QuoteOutcome.Failed -> {
                    notes += "Quote failed: ${o.message}"
                    if (!o.retryable) stop = true
                    null
                }
            }
        }

        // 1. Different groupings at the base stake, rank order as entry order.
        val groupingCount = maxOf(1, (settings.quoteBudget * settings.groupingShare).toInt())
        val toQuote = ComboBuilder.selectForQuoting(candidates, groupingCount)
        for ((i, c) in toQuote.withIndex()) {
            status(ScanPhase.QUOTING_GROUPINGS, "Quoting grouping ${i + 1} of ${toQuote.size} at ${dollars(baseStake)}")
            ask(c, c.legs, baseStake, QuotePurpose.GROUPING)
        }

        fun topQuoted(n: Int) = results.values.filter { it.best != null }.sortedByDescending { it.bestReturnPerDollar }.take(n)

        // 2. Entry order at the same stake.
        val orderSensitivity = HashMap<String, Boolean?>()
        for (r in topQuoted(settings.orderTestCombos)) {
            val c = r.candidate
            val sensitive = orderSensitivity.getOrPut(c.collectionTicker) { quotes.isOrderSensitive(c.collectionTicker) }
            if (sensitive == false) {
                results[c.key] = results.getValue(c.key).copy(orderFinding = OrderFinding(c.legs.map { it.leg.id }, alternateOrder(c.legs).map { it.leg.id }, 0.0, 0.0, false, identical = true))
                continue
            }
            status(ScanPhase.TESTING_ORDER, "Testing entry order on a ${c.size}-leg combo")
            val a = c.legs
            val b = alternateOrder(c.legs)
            val qa = results.getValue(c.key).currentQuotes.firstOrNull { it.entryOrder == a.map { l -> l.leg.id } && it.stakeCents == baseStake } ?: continue
            val qb = ask(c, b, baseStake, QuotePurpose.ORDER_TEST) ?: continue
            val differs = relDiff(qa.multiplier, qb.multiplier) > settings.differenceThreshold
            var repeatable = false
            if (differs) {
                status(ScanPhase.RECHECKING, "Rechecking entry-order difference (A–B–A)")
                val a2 = ask(c, a, baseStake, QuotePurpose.ABA_RECHECK)
                val b2 = ask(c, b, baseStake, QuotePurpose.ABA_RECHECK)
                val a3 = ask(c, a, baseStake, QuotePurpose.ABA_RECHECK)
                repeatable = a2 != null && b2 != null && a3 != null &&
                    sameSign(a2.multiplier - b2.multiplier, qa.multiplier - qb.multiplier) &&
                    sameSign(a3.multiplier - b2.multiplier, qa.multiplier - qb.multiplier)
            }
            val identical = !differs
            if (identical) orderSensitivity[c.collectionTicker] = false
            results[c.key] = results.getValue(c.key).copy(
                orderFinding = OrderFinding(a.map { it.leg.id }, b.map { it.leg.id }, qa.multiplier, qb.multiplier, repeatable, identical),
            )
        }
        if (orderSensitivity.values.any { it == false }) {
            notes += "Kalshi priced both entry orders the same, so order tests were skipped and the budget went to other groupings and stakes."
        }

        // 3. Stake sweep with the order held fixed.
        val stakes = settings.stakesToTest().filter { it != baseStake }
        for (r in topQuoted(settings.stakeSweepCombos)) {
            val c = r.candidate
            val bestOrderIds = r.best!!.entryOrder
            val order = bestOrderIds.map { id -> c.legs.first { it.leg.id == id } }
            for (s in stakes) {
                status(ScanPhase.TESTING_STAKES, "Testing ${dollars(s)} on a ${c.size}-leg combo")
                if (ask(c, order, s, QuotePurpose.STAKE_SWEEP) == null && (stop || used >= workingBudget)) break
            }
            // A–B–A on a stake that beat the base stake.
            val cur = results.getValue(c.key)
            val base = cur.currentQuotes.firstOrNull { it.entryOrder == bestOrderIds && it.stakeCents == baseStake }
            val top = cur.best
            if (base != null && top != null && top.stakeCents != baseStake && relDiff(top.multiplier, base.multiplier) > settings.differenceThreshold) {
                status(ScanPhase.RECHECKING, "Rechecking ${dollars(top.stakeCents)} vs ${dollars(baseStake)} (A–B–A)")
                ask(c, order, top.stakeCents, QuotePurpose.ABA_RECHECK)
                ask(c, order, baseStake, QuotePurpose.ABA_RECHECK)
                ask(c, order, top.stakeCents, QuotePurpose.ABA_RECHECK)
            }
        }

        // 4. Spend any leftover budget on further groupings.
        val remaining = ComboBuilder.selectForQuoting(candidates, candidates.size).filter { it.key !in results }
        for (c in remaining) {
            if (stop || used >= workingBudget) break
            status(ScanPhase.QUOTING_GROUPINGS, "Quoting extra grouping at ${dollars(baseStake)}")
            ask(c, c.legs, baseStake, QuotePurpose.GROUPING)
        }

        // 5. Recheck the leading quote before it is shown as verified or alerted.
        topQuoted(1).firstOrNull()?.let { lead ->
            val q = lead.best!!
            val order = q.entryOrder.map { id -> lead.candidate.legs.first { it.leg.id == id } }
            status(ScanPhase.RECHECKING, "Rechecking the leading quote")
            val again = ask(lead.candidate, order, q.stakeCents, QuotePurpose.LEADER_RECHECK)
            if (again != null) {
                val confirmed = relDiff(again.multiplier, q.multiplier) <= settings.differenceThreshold
                results[lead.candidate.key] = results.getValue(lead.candidate.key).copy(verified = confirmed)
                if (!confirmed) notes += "Leading quote moved on recheck: ${dollars(q.stakeCents)} paid ${dollars(q.payoutCents)}, now ${dollars(again.payoutCents)}."
            }
        }

        val combos = results.values.toList()
        val lead = combos.filter { it.best != null }.maxByOrNull { it.bestReturnPerDollar }
        val msg = if (lead == null) "No quotes returned for ${toQuote.size} groupings" else
            "Best: ${lead.candidate.size}-leg at ${dollars(lead.best!!.stakeCents)} pays ${dollars(lead.best!!.payoutCents)} (${"%+.1f".format(lead.bestReturnPerDollar * 100)}% est. return)"
        return finish(ScanPhase.DONE, msg, combos, candidates.size)
    }

    companion object {
        /** For four legs A,B,C,D this is C,A,D,B; extra legs follow in reverse. */
        fun <T> alternateOrder(legs: List<T>): List<T> {
            if (legs.size < 4) return legs.reversed()
            val head = listOf(legs[2], legs[0], legs[3], legs[1])
            return head + legs.drop(4).reversed()
        }

        fun relDiff(a: Double, b: Double): Double = if (a == 0.0 && b == 0.0) 0.0 else abs(a - b) / maxOf(abs(a), abs(b))
        private fun sameSign(a: Double, b: Double) = (a > 0 && b > 0) || (a < 0 && b < 0)
        fun dollars(cents: Int): String = if (cents % 100 == 0) "$${cents / 100}" else "$${"%.2f".format(cents / 100.0)}"
    }
}
