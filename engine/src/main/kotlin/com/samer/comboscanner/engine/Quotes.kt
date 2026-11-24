package com.samer.comboscanner.engine

import kotlinx.serialization.Serializable

@Serializable
enum class QuoteSource {
    /** Quote returned by Kalshi's RFQ system through the official API. Labelled "Verified mobile quote" in the app UI only when it comes from the RFQ system. */
    API_RFQ,
    /** Payout the user read in the Kalshi app and typed in. */
    MANUAL_APP,
}

@Serializable
enum class QuotePurpose { GROUPING, ORDER_TEST, STAKE_SWEEP, ABA_RECHECK, LEADER_RECHECK, PRE_TRADE, SAME_STAKE_RECHECK }

/** Every quote the app sees is recorded with its exact legs, entry order, stake, payout and time. */
@Serializable
data class QuoteRecord(
    val id: String,
    val comboKey: String,
    /** Leg ids in the exact entry order used for this quote. */
    val entryOrder: List<String>,
    val comboMarketTicker: String? = null,
    val stakeCents: Int,
    val payoutCents: Int,
    /** Can be fractional: combo RFQs size in 0.01 contract steps. */
    val contracts: Double,
    /** Price per contract in cents, before fees. */
    val priceCents: Double,
    val feeCents: Int,
    /** What the contracts actually cost including fees; can be a few cents under the stake. */
    val costCents: Int = 0,
    val timestamp: EpochMs,
    val source: QuoteSource,
    val purpose: QuotePurpose,
    val rfqId: String? = null,
    val quoteId: String? = null,
) {
    /** Total payout ÷ stake, with known fees already inside the stake. */
    val multiplier: Double get() = if (stakeCents == 0) 0.0 else payoutCents.toDouble() / stakeCents
    val netProfitIfWinsCents: Int get() = payoutCents - stakeCents
}

sealed class QuoteOutcome {
    data class Quoted(val record: QuoteRecord) : QuoteOutcome()
    data class NoQuote(val reason: String) : QuoteOutcome()
    data class Failed(val message: String, val retryable: Boolean) : QuoteOutcome()
}

interface QuoteProvider {
    val source: QuoteSource

    /** Whether entry order can change the quote for a collection. Null when unknown. */
    suspend fun isOrderSensitive(collectionTicker: String): Boolean?

    suspend fun quote(
        comboKey: String,
        legsInOrder: List<Leg>,
        stakeCents: Int,
        purpose: QuotePurpose,
    ): QuoteOutcome
}

/** Converts a maker's per-contract price into stake → contracts → payout using Kalshi's fee rules. */
object QuoteMath {
    fun recordFor(
        id: String,
        comboKey: String,
        entryOrder: List<String>,
        comboMarketTicker: String?,
        stakeCents: Int,
        priceCents: Double,
        fees: FeeModel,
        timestamp: EpochMs,
        source: QuoteSource,
        purpose: QuotePurpose,
        rfqId: String? = null,
        quoteId: String? = null,
        contractsOverride: Double? = null,
    ): QuoteRecord {
        val contracts = contractsOverride ?: fees.contractsForStake(stakeCents, priceCents).toDouble()
        val fee = fees.feeCents(contracts, priceCents)
        return QuoteRecord(
            id = id, comboKey = comboKey, entryOrder = entryOrder, comboMarketTicker = comboMarketTicker,
            stakeCents = stakeCents, payoutCents = kotlin.math.round(contracts * 100).toInt(), contracts = contracts, priceCents = priceCents,
            feeCents = fee, costCents = (contracts * priceCents).let { kotlin.math.ceil(it - 1e-9).toInt() } + fee, timestamp = timestamp, source = source, purpose = purpose, rfqId = rfqId, quoteId = quoteId,
        )
    }
}
