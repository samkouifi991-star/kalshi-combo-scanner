package com.samer.comboscanner.engine

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/** One bookmaker's prices for every outcome of one market, so the margin can be removed. */
@Serializable
data class BookPrices(
    val bookmaker: String,
    /** Decimal odds per outcome; [targetIndex] is the outcome matching the Kalshi leg. */
    val decimalOdds: List<Double>,
    val targetIndex: Int,
    val updatedAt: EpochMs? = null,
)

/** Settled history used as a base rate: how often comparable legs won. */
@Serializable
data class HistoricalSample(
    val description: String,
    val wins: Int,
    val total: Int,
    val updatedAt: EpochMs? = null,
)

object FairProbability {
    /** Books whose lines are commonly treated as sharp get extra weight in the consensus. */
    private val sharpBooks = setOf("pinnacle", "betfair_ex_eu", "betfair_ex_uk", "matchbook", "circasports", "lowvig", "betonlineag")

    /** Removes the bookmaker margin proportionally (multiplicative method). Returns null if odds are unusable. */
    fun devig(prices: BookPrices): Double? {
        if (prices.decimalOdds.size < 2 || prices.targetIndex !in prices.decimalOdds.indices) return null
        if (prices.decimalOdds.any { it <= 1.0 }) return null
        val implied = prices.decimalOdds.map { 1.0 / it }
        val total = implied.sum()
        return implied[prices.targetIndex] / total
    }

    /** Consensus of devigged book probabilities, as one piece of evidence. */
    fun externalEvidence(books: List<BookPrices>, sourceLabel: String = "Sportsbook consensus"): Evidence? {
        val probs = books.mapNotNull { b -> devig(b)?.let { b to it } }
        if (probs.isEmpty()) return null
        val weights = probs.map { (b, _) -> if (b.bookmaker.lowercase() in sharpBooks) 3.0 else 1.0 }
        val wSum = weights.sum()
        val mean = probs.indices.sumOf { probs[it].second * weights[it] } / wSum
        val variance = probs.indices.sumOf { weights[it] * (probs[it].second - mean).let { d -> d * d } } / wSum
        val n = probs.size
        // Disagreement between books, plus a floor because even a tight consensus is not the truth.
        val se = max(sqrt(variance / n), 0.012) + if (n < 3) 0.015 else 0.0
        val newest = probs.mapNotNull { it.first.updatedAt }.maxOrNull()
        val sharp = probs.filter { it.first.bookmaker.lowercase() in sharpBooks }.joinToString { it.first.bookmaker }
        val detail = buildString {
            append("$n book${if (n == 1) "" else "s"}, margin removed")
            append(", range ${pct(probs.minOf { it.second })}–${pct(probs.maxOf { it.second })}")
            if (sharp.isNotEmpty()) append(", sharp: $sharp")
        }
        return Evidence(EvidenceKind.EXTERNAL_MARKET, sourceLabel, mean, se, newest, detail)
    }

    /** Beta(1,1) posterior mean of a historical win rate. */
    fun historicalEvidence(sample: HistoricalSample): Evidence? {
        if (sample.total <= 0) return null
        val p = (sample.wins + 1.0) / (sample.total + 2.0)
        val se = sqrt(p * (1 - p) / (sample.total + 3.0))
        return Evidence(
            EvidenceKind.HISTORICAL, sample.description, p, se, sample.updatedAt,
            "${sample.wins} of ${sample.total} won",
        )
    }

    /**
     * Inverse-variance blend in log-odds space. The Kalshi price itself is never used as evidence,
     * otherwise every leg would look fairly priced.
     */
    fun blend(evidence: List<Evidence>): FairEstimate? {
        val usable = evidence.filter { it.kind != EvidenceKind.KALSHI_REFERENCE && it.probability in 0.001..0.999 }
        if (usable.isEmpty()) return null
        var wSum = 0.0
        var acc = 0.0
        for (e in usable) {
            val p = e.probability
            val seLogit = max(e.stdError, 1e-4) / (p * (1 - p))
            val w = 1.0 / (seLogit * seLogit)
            acc += w * logit(p)
            wSum += w
        }
        val center = acc / wSum
        val seLogit = 1.0 / sqrt(wSum)
        val z = 1.645
        val p = sigmoid(center)
        val low = sigmoid(center - z * seLogit)
        val high = sigmoid(center + z * seLogit)
        val hasExternal = usable.any { it.kind == EvidenceKind.EXTERNAL_MARKET }
        val width = high - low
        val confidence = when {
            hasExternal && usable.size >= 2 && width < 0.06 -> FairEstimate.Confidence.HIGH
            hasExternal && width < 0.12 -> FairEstimate.Confidence.MEDIUM
            else -> FairEstimate.Confidence.LOW
        }
        return FairEstimate(p, low, high, evidence, confidence)
    }

    fun logit(p: Double) = ln(p / (1 - p))
    fun sigmoid(x: Double) = 1.0 / (1.0 + exp(-x))
    private fun pct(p: Double) = "${"%.1f".format(p * 100)}%"
}
