package com.samer.comboscanner.data

import com.samer.comboscanner.engine.ComboSettings
import com.samer.comboscanner.engine.EpochMs
import com.samer.comboscanner.engine.FeeModel
import com.samer.comboscanner.engine.LegFilter
import com.samer.comboscanner.engine.QuoteRecord
import com.samer.comboscanner.engine.ScanSettings
import com.samer.comboscanner.engine.ScoredLeg
import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val minProbability: Double = 0.80,
    val minEdgePp: Double = 0.0,
    /** Empty means every sport. */
    val sports: Set<String> = emptySet(),
    val minLegs: Int = 4,
    val maxLegs: Int = 7,
    val stakeMinDollars: Int = 5,
    val stakeMaxDollars: Int = 20,
    val stakeIncrementDollars: Int = 5,
    val testNeighborStakes: Boolean = true,
    val quoteBudget: Int = 30,
    val poolSize: Int = 10,
    val allowRelatedLegs: Boolean = false,
    val alertsEnabled: Boolean = false,
    val alertMinReturnPct: Double = 5.0,
    val alertIntervalMinutes: Int = 30,
    val takerFeeRate: Double = 0.07,
    val useHistorical: Boolean = true,
    val demoMode: Boolean = true,
    val useDemoExchange: Boolean = false,
    /** A quote older than this is shown as expired with a Refresh button. */
    val quoteFreshSeconds: Int = 90,
    // Long tab: very long combos of heavy favourites.
    val longMinAskCents: Int = 94,
    val longMinLegs: Int = 100,
    val longMaxLegs: Int = 150,
    val longMinEdgePp: Double = 0.0,
    val longQuoteBudget: Int = 10,
) {
    fun toLongSettings() = com.samer.comboscanner.engine.LongComboSettings(
        minAskCents = longMinAskCents,
        minFairProbability = longMinAskCents / 100.0,
        minEdgePp = longMinEdgePp,
        minLegs = longMinLegs,
        maxLegs = longMaxLegs,
        sports = sports,
        stakesCents = listOf(stakeMinDollars, stakeMinDollars + 1, stakeMinDollars * 2).map { it * 100 }.distinct(),
        quoteBudget = longQuoteBudget,
    )

    fun toScanSettings() = ScanSettings(
        legFilter = LegFilter(minProbability, minEdgePp, sports),
        combo = ComboSettings(minLegs, maxLegs, poolSize, allowRelatedLegs),
        stakeMinCents = stakeMinDollars * 100,
        stakeMaxCents = stakeMaxDollars * 100,
        stakeIncrementCents = stakeIncrementDollars * 100,
        testNeighborStakes = testNeighborStakes,
        quoteBudget = quoteBudget,
        alertMinReturnPerDollar = alertMinReturnPct / 100,
        fees = FeeModel(takerFeeRate),
    )
}

@Serializable
enum class FillSource {
    /** Found in the Kalshi account's fills through the API. */
    CONFIRMED,
    /** Recorded by hand with Mark as purchased. */
    MANUAL,
}

@Serializable
data class Fill(
    val id: String,
    val stakeCents: Int,
    val payoutCents: Int,
    val timestamp: EpochMs,
    val source: FillSource,
)

@Serializable
enum class LegState { UPCOMING, LIVE, WON, LOST, VOID }

@Serializable
enum class TicketStatus { OPEN, WON, LOST, VOID }

/** A combo the user bought, possibly several times. Each fill keeps its own price. */
@Serializable
data class Ticket(
    val id: String,
    val comboKey: String,
    val comboMarketTicker: String? = null,
    /** Legs in the entry order used. */
    val legs: List<ScoredLeg>,
    val recommendation: QuoteRecord?,
    val recommendedReturnPerDollar: Double?,
    val jointFair: Double,
    val fills: List<Fill>,
    val legStates: Map<String, LegState> = emptyMap(),
    val status: TicketStatus = TicketStatus.OPEN,
    val settledAt: EpochMs? = null,
    val sports: List<String> = legs.map { it.leg.sport }.distinct(),
) {
    val spentCents: Int get() = fills.sumOf { it.stakeCents }
    val potentialPayoutCents: Int get() = fills.sumOf { it.payoutCents }
    val actualPayoutCents: Int
        get() = when (status) {
            TicketStatus.WON -> potentialPayoutCents
            TicketStatus.VOID -> spentCents
            else -> 0
        }
    val realizedProfitCents: Int get() = actualPayoutCents - spentCents
    fun count(s: LegState) = legs.count { (legStates[it.leg.id] ?: LegState.UPCOMING) == s }
    /** Earliest upcoming start among legs still to play, for sorting Open by what happens next. */
    val nextEventTime: Long
        get() = legs.filter { (legStates[it.leg.id] ?: LegState.UPCOMING) in setOf(LegState.UPCOMING, LegState.LIVE) }
            .mapNotNull { it.startTime }.minOrNull() ?: Long.MAX_VALUE
}
