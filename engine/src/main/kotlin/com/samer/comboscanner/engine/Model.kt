package com.samer.comboscanner.engine

import kotlinx.serialization.Serializable

/** Times are epoch milliseconds throughout so models serialize and work on Android without extra adapters. */
typealias EpochMs = Long

@Serializable
enum class Side { YES, NO }

/** One selectable outcome from a combo-eligible Kalshi market, priced at its executable ask. */
@Serializable
data class Leg(
    val marketTicker: String,
    val eventTicker: String,
    val seriesTicker: String? = null,
    val collectionTicker: String,
    val side: Side,
    /** e.g. "Team A vs. Team B" */
    val matchup: String,
    /** e.g. "Over 1.5 total goals" */
    val outcome: String,
    val sport: String,
    val startTime: EpochMs? = null,
    /** Executable ask for [side] in cents (1..99). */
    val askCents: Int,
    val askSize: Int? = null,
    val quoteTime: EpochMs,
) {
    val id: String get() = "$marketTicker:${side.name}"
}

@Serializable
enum class EvidenceKind { EXTERNAL_MARKET, HISTORICAL, KALSHI_REFERENCE }

/** One input to a fair-probability estimate, kept so the app can show how a leg was priced. */
@Serializable
data class Evidence(
    val kind: EvidenceKind,
    val source: String,
    val probability: Double,
    /** Standard error of this source's probability, used for inverse-variance blending. */
    val stdError: Double,
    val updatedAt: EpochMs? = null,
    val detail: String = "",
)

@Serializable
data class FairEstimate(
    val probability: Double,
    /** Lower and upper bounds of an approximate 90% interval. */
    val low: Double,
    val high: Double,
    val evidence: List<Evidence>,
    val confidence: Confidence,
    /** Game start time learned from an external source, when Kalshi does not provide one. */
    val eventStartTime: EpochMs? = null,
) {
    @Serializable
    enum class Confidence { HIGH, MEDIUM, LOW }
}

/** A leg with its fair estimate and fee-adjusted economics at the executable ask. */
@Serializable
data class ScoredLeg(
    val leg: Leg,
    val fair: FairEstimate,
    /** Fee per contract in cents at the reference stake, fractional because fees round per order. */
    val feePerContractCents: Double,
) {
    val startTime: EpochMs? get() = leg.startTime ?: fair.eventStartTime
    val effectiveCostCents: Double get() = leg.askCents + feePerContractCents
    /** Percentage points, before fees. */
    val edgeBeforeFeesPp: Double get() = fair.probability * 100 - leg.askCents
    /** Percentage points, after fees. */
    val edgeAfterFeesPp: Double get() = fair.probability * 100 - effectiveCostCents
    /** Expected return per dollar at the executable price after fees. */
    val returnPerDollar: Double get() = fair.probability * 100 / effectiveCostCents - 1
    /** Same, using the low end of the fair interval. */
    val conservativeReturnPerDollar: Double get() = fair.low * 100 / effectiveCostCents - 1
}
