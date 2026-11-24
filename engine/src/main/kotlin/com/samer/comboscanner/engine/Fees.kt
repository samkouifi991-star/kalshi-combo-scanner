package com.samer.comboscanner.engine

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Kalshi's published general trading fee: ceil_to_cent(rate × C × P × (1 − P)), P in dollars.
 * The standard taker rate is 0.07. Kalshi can override fees per series or event, so the rate is a setting.
 */
data class FeeModel(val takerRate: Double = 0.07) {
    /** Total fee in cents for [contracts] at [priceCents]. */
    fun feeCents(contracts: Int, priceCents: Double): Int = feeCents(contracts.toDouble(), priceCents)

    /** Combo RFQs can fill fractional contracts (0.01 steps), so fees are defined on a Double count too. */
    fun feeCents(contracts: Double, priceCents: Double): Int {
        if (contracts <= 0) return 0
        val p = priceCents / 100.0
        val dollars = takerRate * contracts * p * (1 - p)
        // Round up to the next whole cent; subtract a tiny epsilon so exact cents don't bump up from float noise.
        return ceil(dollars * 100 - 1e-9).toInt().coerceAtLeast(0)
    }

    fun feePerContractCents(contracts: Int, priceCents: Double): Double =
        if (contracts <= 0) 0.0 else feeCents(contracts, priceCents).toDouble() / contracts

    /**
     * Largest whole number of contracts whose price plus fee fits in [stakeCents]. A $1 payout per contract,
     * so payout in cents is 100 × the result. Stake rounding is why neighbouring amounts ($5 vs $6) can
     * give different multipliers.
     */
    fun contractsForStake(stakeCents: Int, priceCents: Double): Int {
        if (priceCents <= 0) return 0
        var c = floor(stakeCents / priceCents).toInt()
        while (c > 0 && c * priceCents + feeCents(c, priceCents) > stakeCents + 1e-9) c--
        return c
    }
}
