package com.samer.comboscanner.engine

import kotlinx.serialization.Serializable

@Serializable
data class ComboCandidate(
    /** Legs in ranking order (strongest first). Entry order for quotes is chosen separately. */
    val legs: List<ScoredLeg>,
) {
    val collectionTicker: String get() = legs.first().leg.collectionTicker
    /** Order-independent identity of the grouping. */
    val key: String get() = legs.map { it.leg.id }.sorted().joinToString("|")
    val size: Int get() = legs.size
    val sports: List<String> get() = legs.map { it.leg.sport }.distinct()

    /** True when two legs come from the same game, so they are probably correlated. */
    val hasRelatedLegs: Boolean get() = legs.groupBy { it.leg.eventTicker }.any { it.value.size > 1 }

    /** Joint probability assuming independence. Shown with a warning when [hasRelatedLegs]. */
    val jointFair: Double get() = legs.fold(1.0) { acc, l -> acc * l.fair.probability }
    val jointLow: Double get() = legs.fold(1.0) { acc, l -> acc * l.fair.low }
    val jointHigh: Double get() = legs.fold(1.0) { acc, l -> acc * l.fair.high }
    val fairMultiplier: Double get() = 1.0 / jointFair

    /**
     * Payout multiplier implied by buying each leg separately at its Kalshi ask, before fees.
     * Only meaningful for independent legs; null (comparison unavailable) when legs are related,
     * because there is no correlation-aware Kalshi reference price for them.
     */
    val referenceMultiplier: Double?
        get() = if (hasRelatedLegs) null else legs.fold(1.0) { acc, l -> acc * 100.0 / l.leg.askCents }

    /** Advantage from the legs alone: expected return if the combo paid exactly the reference multiplier. */
    val legEdgeReturn: Double?
        get() = referenceMultiplier?.let { jointFair * it - 1 }

    /** Score used to pick which groupings to quote first. */
    val preQuoteScore: Double
        get() = legs.fold(1.0) { acc, l -> acc * (1 + l.returnPerDollar) } - 1
}

data class ComboSettings(
    val minLegs: Int = 4,
    val maxLegs: Int = 7,
    /** How many of the top-ranked legs to combine. */
    val poolSize: Int = 10,
    val allowRelatedLegs: Boolean = false,
    /** Cap on generated candidates per length, to keep enumeration fast on a phone. */
    val maxCandidatesPerLength: Int = 4000,
)

object ComboBuilder {
    /**
     * Enumerates groupings of the strongest qualifying legs in rank order (A+B+C+D, A+B+C+E, A+B+D+E, ...)
     * for every allowed length, within a single combo collection.
     */
    fun build(rankedLegs: List<ScoredLeg>, settings: ComboSettings): List<ComboCandidate> {
        val out = mutableListOf<ComboCandidate>()
        for ((_, legsInCollection) in rankedLegs.groupBy { it.leg.collectionTicker }) {
            val pool = dedupeMarkets(legsInCollection).take(settings.poolSize)
            for (k in settings.minLegs..settings.maxLegs) {
                if (pool.size < k) break
                var produced = 0
                combinations(pool.size, k) { idx ->
                    val legs = idx.map { pool[it] }
                    if (!settings.allowRelatedLegs && legs.groupBy { it.leg.eventTicker }.any { it.value.size > 1 }) return@combinations true
                    out += ComboCandidate(legs)
                    produced++
                    produced < settings.maxCandidatesPerLength
                }
            }
        }
        return out
    }

    /** Never put both sides of one market in a pool. Keeps the stronger side. */
    private fun dedupeMarkets(legs: List<ScoredLeg>): List<ScoredLeg> {
        val seen = HashSet<String>()
        return legs.filter { seen.add(it.leg.marketTicker) }
    }

    /** Lexicographic k-combinations of 0 until n. [visit] returns false to stop. */
    fun combinations(n: Int, k: Int, visit: (IntArray) -> Boolean) {
        if (k > n || k <= 0) return
        val idx = IntArray(k) { it }
        while (true) {
            if (!visit(idx.copyOf())) return
            var i = k - 1
            while (i >= 0 && idx[i] == n - k + i) i--
            if (i < 0) return
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }

    /**
     * Picks which groupings to spend quotes on: best pre-quote score first, but limits how often the same
     * leg set repeats so different groupings and lengths both get tested.
     */
    fun selectForQuoting(candidates: List<ComboCandidate>, count: Int): List<ComboCandidate> {
        val sorted = candidates.sortedByDescending { it.preQuoteScore }
        val chosen = mutableListOf<ComboCandidate>()
        val perLength = HashMap<Int, Int>()
        val lengths = sorted.map { it.size }.distinct().size.coerceAtLeast(1)
        val perLengthCap = maxOf(1, (count + lengths - 1) / lengths)
        for (c in sorted) {
            if (chosen.size >= count) break
            if ((perLength[c.size] ?: 0) >= perLengthCap) continue
            chosen += c
            perLength[c.size] = (perLength[c.size] ?: 0) + 1
        }
        // Fill any leftover slots in plain score order.
        for (c in sorted) {
            if (chosen.size >= count) break
            if (c !in chosen) chosen += c
        }
        return chosen
    }
}
