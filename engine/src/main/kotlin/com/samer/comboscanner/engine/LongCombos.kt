package com.samer.comboscanner.engine

import kotlin.math.pow

/** Settings for very long combos built only from heavy favourites (e.g. 100+ legs at 94¢ or more). */
data class LongComboSettings(
    val minAskCents: Int = 94,
    /** Estimated fair probability floor; defaults to the price floor. */
    val minFairProbability: Double = 0.94,
    /**
     * Minimum leg edge in percentage points, before fees. Fees are charged once on the combo, so the
     * leg filter compares fair probability with the ask and the combo quote carries the fee.
     */
    val minEdgePp: Double = 0.0,
    val minLegs: Int = 100,
    val maxLegs: Int = 150,
    /** Number of different lengths and groupings to quote. */
    val variants: Int = 5,
    val sports: Set<String> = emptySet(),
    val stakesCents: List<Int> = listOf(500, 600, 1000),
    val quoteBudget: Int = 12,
    val differenceThreshold: Double = 0.02,
)

/** Honest summary numbers for a long combo at one quote. */
data class LongComboMath(
    val legs: Int,
    val jointFair: Double,
    val jointLow: Double,
    /** Geometric mean of the legs' estimated fair probabilities. */
    val averageLegFair: Double,
    /** Average leg probability needed for the quote to break even: multiplier^(−1/n). */
    val breakEvenLegProbability: Double,
    val expectedReturnPerDollar: Double,
    val conservativeReturnPerDollar: Double,
    /** Expected return if every leg were 1 percentage point less likely than estimated. */
    val returnIfLegsOnePpWorse: Double,
)

object LongCombos {
    fun math(c: ComboCandidate, multiplier: Double): LongComboMath {
        val n = c.size
        val worse = c.legs.fold(1.0) { a, l -> a * (l.fair.probability - 0.01).coerceAtLeast(0.0) }
        return LongComboMath(
            legs = n,
            jointFair = c.jointFair,
            jointLow = c.jointLow,
            averageLegFair = c.jointFair.pow(1.0 / n),
            breakEvenLegProbability = (1.0 / multiplier).pow(1.0 / n),
            expectedReturnPerDollar = c.jointFair * multiplier - 1,
            conservativeReturnPerDollar = c.jointLow * multiplier - 1,
            returnIfLegsOnePpWorse = worse * multiplier - 1,
        )
    }

    fun qualifies(s: ScoredLeg, settings: LongComboSettings, now: EpochMs): Boolean {
        if (settings.sports.isNotEmpty() && s.leg.sport !in settings.sports) return false
        if (s.startTime != null && s.startTime!! <= now) return false
        if (s.leg.askCents < settings.minAskCents) return false
        if (s.fair.probability < settings.minFairProbability) return false
        return s.edgeBeforeFeesPp > settings.minEdgePp || (settings.minEdgePp < 0 && s.edgeBeforeFeesPp >= settings.minEdgePp)
    }

    /**
     * Builds long combos inside each combo collection. Legs are ranked by fair ÷ price (how much each
     * leg adds to expected value per unit of price). Variants differ in length, and one variant ranks by
     * the low end of the estimate instead, to favour legs the app is most sure of.
     */
    fun build(pool: List<ScoredLeg>, settings: LongComboSettings, maxLegsFor: (String) -> Int?): List<ComboCandidate> {
        val out = mutableListOf<ComboCandidate>()
        for ((collection, legs) in pool.groupBy { it.leg.collectionTicker }) {
            val unique = legs.distinctBy { it.leg.marketTicker }
            val cap = minOf(settings.maxLegs, maxLegsFor(collection) ?: settings.maxLegs, unique.size)
            if (cap < settings.minLegs) continue
            val byValue = unique.sortedByDescending { it.fair.probability / it.leg.askCents }
            val bySafety = unique.sortedByDescending { it.fair.low / it.leg.askCents }
            val lengths = lengths(settings.minLegs, cap, settings.variants - 1)
            for (n in lengths) out += ComboCandidate(byValue.take(n))
            out += ComboCandidate(bySafety.take(settings.minLegs))
        }
        return out.distinctBy { it.key }
    }

    private fun lengths(min: Int, max: Int, count: Int): List<Int> {
        if (count <= 1 || max == min) return listOf(min)
        val step = (max - min).toDouble() / (count - 1)
        return (0 until count).map { (min + it * step).toInt() }.distinct()
    }
}

/** Scans for long combos: legs → long groupings → quotes at a few stakes → leader recheck. */
class LongComboScanner(
    private val markets: MarketSource,
    private val fair: FairEstimator,
    private val quotes: QuoteProvider,
    private val maxLegsFor: (String) -> Int? = { null },
    private val clock: () -> EpochMs = System::currentTimeMillis,
) {
    suspend fun scan(settings: LongComboSettings, fees: FeeModel, onStatus: (ScanStatus) -> Unit = {}): ScanResult {
        val startedAt = clock()
        val notes = mutableListOf<String>()
        var used = 0
        fun status(phase: ScanPhase, msg: String) = ScanStatus(phase, msg, used, settings.quoteBudget, clock()).also(onStatus)

        status(ScanPhase.LOADING_MARKETS, "Loading combo-eligible markets")
        val all = markets.eligibleLegs(settings.sports)
        // Only heavy favourites matter here, so skip pricing everything else.
        val candidates = all.filter { it.askCents >= settings.minAskCents }
        status(ScanPhase.PRICING_LEGS, "Estimating fair probabilities for ${candidates.size} legs priced ${settings.minAskCents}¢+")
        val estimates = fair.estimate(candidates)
        val scored = LegScorer.rank(candidates.mapNotNull { l -> estimates[l.id]?.let { LegScorer.score(l, it, fees) } })
            .sortedByDescending { it.fair.probability / it.leg.askCents }
        val now = clock()
        val qualifying = scored.filter { LongCombos.qualifies(it, settings, now) }
        val unpriced = candidates.filter { it.id !in estimates }
        if (unpriced.isNotEmpty()) notes += "${unpriced.size} legs priced ${settings.minAskCents}¢+ had no outside evidence and were left out."

        fun finish(phase: ScanPhase, msg: String, combos: List<ComboResult>, built: Int) = ScanResult(
            startedAt, clock(), scored, qualifying.map { it.leg.id }, unpriced, built,
            combos.sortedByDescending { it.bestReturnPerDollar }, notes, status(phase, msg),
        )

        val groupings = LongCombos.build(qualifying, settings, maxLegsFor)
        if (groupings.isEmpty()) {
            return finish(
                ScanPhase.WAITING_FOR_LEGS,
                "Only ${qualifying.size} legs priced ${settings.minAskCents}¢+ have a positive estimated edge. Need ${settings.minLegs} in one combo collection.",
                emptyList(), 0,
            )
        }

        val results = LinkedHashMap<String, ComboResult>()
        val working = settings.quoteBudget - 1
        var stop = false
        suspend fun ask(c: ComboCandidate, stake: Int, purpose: QuotePurpose, order: List<ScoredLeg> = c.legs): QuoteRecord? {
            val limit = if (purpose == QuotePurpose.LEADER_RECHECK) settings.quoteBudget else working
            if (stop || used >= limit) return null
            used++
            return when (val o = quotes.quote(c.key, order.map { it.leg }, stake, purpose)) {
                is QuoteOutcome.Quoted -> {
                    val prev = results[c.key] ?: ComboResult(c, emptyList())
                    results[c.key] = prev.copy(quotes = prev.quotes + o.record)
                    o.record
                }
                is QuoteOutcome.NoQuote -> { results.getOrPut(c.key) { ComboResult(c, emptyList()) }; notes += "${c.size}-leg grouping: ${o.reason}"; null }
                is QuoteOutcome.Failed -> { notes += "Quote failed: ${o.message}"; if (!o.retryable) stop = true; null }
            }
        }

        val base = settings.stakesCents.minOrNull() ?: 500
        for ((i, c) in groupings.withIndex()) {
            status(ScanPhase.QUOTING_GROUPINGS, "Quoting ${c.size}-leg grouping (${i + 1} of ${groupings.size})")
            ask(c, base, QuotePurpose.GROUPING)
        }
        val lead = results.values.filter { it.best != null }.maxByOrNull { it.bestReturnPerDollar }
        if (lead != null) {
            for (s in settings.stakesCents.filter { it != base }) {
                status(ScanPhase.TESTING_STAKES, "Testing ${ScanEngine.dollars(s)} on the ${lead.candidate.size}-leg combo")
                ask(lead.candidate, s, QuotePurpose.STAKE_SWEEP)
            }
            val cur = results.getValue(lead.candidate.key).best!!
            status(ScanPhase.RECHECKING, "Rechecking the leading quote")
            ask(lead.candidate, cur.stakeCents, QuotePurpose.LEADER_RECHECK)?.let { again ->
                val ok = ScanEngine.relDiff(again.multiplier, cur.multiplier) <= settings.differenceThreshold
                results[lead.candidate.key] = results.getValue(lead.candidate.key).copy(verified = ok)
            }
        }
        notes += "Joint probabilities assume legs are independent. Several props from the same game often move together, so treat these numbers as optimistic."
        val best = results.values.filter { it.best != null }.maxByOrNull { it.bestReturnPerDollar }
        val msg = best?.let {
            val q = it.best!!
            "Best: ${it.candidate.size} legs, ${ScanEngine.dollars(q.stakeCents)} pays ${ScanEngine.dollars(q.payoutCents)} · joint win ${"%.1f".format(it.candidate.jointFair * 100)}% · ${"%+.1f".format(it.bestReturnPerDollar * 100)}% est. return"
        } ?: "No quotes returned for ${groupings.size} long groupings"
        return finish(ScanPhase.DONE, msg, results.values.toList(), groupings.size)
    }
}
