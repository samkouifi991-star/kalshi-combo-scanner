package com.samer.comboscanner.engine

import kotlinx.coroutines.test.runTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongCombosTest {
    private val fees = FeeModel()

    @Test fun mathMatchesScreenshotExample() {
        // 113 legs, $48.98 pays $7,434.76 → 151.8x. Break-even average leg probability is 151.8^(−1/113).
        val legs = (1..113).map { LegScorer.score(leg("$it", 96), fair(0.96), fees) }
        val c = ComboCandidate(legs)
        val m = LongCombos.math(c, 7434.76 / 48.98)
        assertEquals((48.98 / 7434.76).pow(1.0 / 113), m.breakEvenLegProbability, 1e-9)
        assertEquals(0.96, m.averageLegFair, 1e-6)
        // 0.96^113 ≈ 1.0% joint × 151.8x.
        assertEquals(0.96.pow(113) * 7434.76 / 48.98 - 1, m.expectedReturnPerDollar, 1e-9)
        assertTrue(m.returnIfLegsOnePpWorse < m.expectedReturnPerDollar)
    }

    @Test fun buildsVariantsWithinCap() {
        val pool = (1..140).map { LegScorer.score(leg("$it", 95), fair(0.97), fees) }
        val combos = LongCombos.build(pool, LongComboSettings(), maxLegsFor = { 120 })
        assertTrue(combos.isNotEmpty())
        assertTrue(combos.all { it.size in 100..120 })
        assertTrue(combos.map { it.size }.distinct().size > 1)
    }

    @Test fun waitsWhenTooFewLegs() = runTest {
        val legs = (1..50).map { leg("$it", 95) }
        val r = LongComboScanner(
            object : MarketSource { override suspend fun eligibleLegs(sports: Set<String>) = legs },
            object : FairEstimator { override suspend fun estimate(legs: List<Leg>) = legs.associate { it.id to fair(0.97) } },
            FakeQuotes(false) { 1.0 }, clock = { 1_700_000_000_000L },
        ).scan(LongComboSettings(), fees)
        assertEquals(ScanPhase.WAITING_FOR_LEGS, r.finalStatus.phase)
    }

    @Test fun demoLongScanQuotesAndRechecks() = runTest {
        val r = LongComboScanner(DemoLong.markets(), DemoLong.fair(), Demo.quotes(fees)).scan(LongComboSettings(), fees)
        assertEquals(ScanPhase.DONE, r.finalStatus.phase, r.finalStatus.message)
        val lead = r.leader!!
        assertTrue(lead.candidate.size >= 100)
        assertTrue(lead.best!!.multiplier > 10)
    }
}
