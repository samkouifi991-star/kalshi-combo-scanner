package com.samer.comboscanner.engine

data class LegFilter(
    val minProbability: Double = 0.80,
    /** Minimum edge after fees, in percentage points. Must be positive to qualify. */
    val minEdgeAfterFeesPp: Double = 0.0,
    val sports: Set<String> = emptySet(),
    /** Only legs whose game has not started (pregame). */
    val pregameOnly: Boolean = true,
)

object LegScorer {
    /** Fee rounding depends on order size, so legs are scored at a representative stake. */
    fun score(leg: Leg, fair: FairEstimate, fees: FeeModel, referenceStakeCents: Int = 1000): ScoredLeg {
        val contracts = fees.contractsForStake(referenceStakeCents, leg.askCents.toDouble()).coerceAtLeast(1)
        return ScoredLeg(leg, fair, fees.feePerContractCents(contracts, leg.askCents.toDouble()))
    }

    fun qualifies(s: ScoredLeg, filter: LegFilter, now: EpochMs): Boolean {
        if (filter.sports.isNotEmpty() && s.leg.sport !in filter.sports) return false
        if (filter.pregameOnly && s.startTime != null && s.startTime!! <= now) return false
        if (s.fair.probability < filter.minProbability) return false
        val minEdge = maxOf(filter.minEdgeAfterFeesPp, 0.0)
        return s.edgeAfterFeesPp > minEdge
    }

    /** Ranked by expected return per dollar at the executable price after fees; ties broken by the conservative return. */
    fun rank(legs: List<ScoredLeg>): List<ScoredLeg> =
        legs.sortedWith(compareByDescending<ScoredLeg> { it.returnPerDollar }.thenByDescending { it.conservativeReturnPerDollar })
}
