package com.samer.comboscanner.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_700_000_000_000L

fun leg(n: String, ask: Int, event: String = "EV$n", collection: String = "COL", sport: String = "NHL") =
    Leg("MKT$n", event, "SER", collection, Side.YES, "Team $n vs. Other $n", "Outcome $n", sport, NOW + 3_600_000, ask, 100, NOW)

fun fair(p: Double, se: Double = 0.01) = FairProbability.blend(listOf(Evidence(EvidenceKind.EXTERNAL_MARKET, "test", p, se)))!!

class FeesTest {
    private val fees = FeeModel()

    @Test fun feeRoundsUpToCent() {
        // 0.07 × 10 × 0.92 × 0.08 = $0.05152 → 6¢
        assertEquals(6, fees.feeCents(10, 92.0))
        assertEquals(0, fees.feeCents(0, 50.0))
        // 0.07 × 100 × 0.5 × 0.5 = $1.75 exactly
        assertEquals(175, fees.feeCents(100, 50.0))
    }

    @Test fun stakeRoundingMakesNeighbouringStakesDiffer() {
        // A 45¢ combo: $5 buys 10 contracts ($4.50 + 18¢ fee), $6 buys 12.
        val five = fees.contractsForStake(500, 45.0)
        val six = fees.contractsForStake(600, 45.0)
        assertEquals(10, five)
        assertEquals(12, six)
        assertTrue(five * 45 + fees.feeCents(five, 45.0) <= 500)
    }
}

class FairProbabilityTest {
    @Test fun devigRemovesMargin() {
        val p = FairProbability.devig(BookPrices("book", listOf(1.10, 7.0), 0))!!
        assertEquals((1 / 1.10) / (1 / 1.10 + 1 / 7.0), p, 1e-9)
    }

    @Test fun sharpBooksWeighMore() {
        val books = listOf(
            BookPrices("pinnacle", listOf(1.05, 15.0), 0),
            BookPrices("draftkings", listOf(1.12, 6.5), 0),
        )
        val e = FairProbability.externalEvidence(books)!!
        val pin = FairProbability.devig(books[0])!!
        val dk = FairProbability.devig(books[1])!!
        assertTrue(e.probability > (pin + dk) / 2)
    }

    @Test fun blendNarrowsWithMoreEvidence() {
        val one = FairProbability.blend(listOf(Evidence(EvidenceKind.EXTERNAL_MARKET, "a", 0.9, 0.03)))!!
        val two = FairProbability.blend(
            listOf(Evidence(EvidenceKind.EXTERNAL_MARKET, "a", 0.9, 0.03), Evidence(EvidenceKind.HISTORICAL, "h", 0.9, 0.03)),
        )!!
        assertTrue(two.high - two.low < one.high - one.low)
        assertTrue(one.low < 0.9 && one.high > 0.9)
    }

    @Test fun kalshiPriceIsNeverEvidence() {
        assertNull(FairProbability.blend(listOf(Evidence(EvidenceKind.KALSHI_REFERENCE, "k", 0.9, 0.01))))
    }
}

class LegAndComboTest {
    private val fees = FeeModel()

    @Test fun qualifyingNeedsProbabilityAndPositiveEdgeAfterFees() {
        val good = LegScorer.score(leg("A", 92), fair(0.97), fees)
        val noEdge = LegScorer.score(leg("B", 92), fair(0.925), fees)
        val lowProb = LegScorer.score(leg("C", 60), fair(0.75), fees)
        val f = LegFilter()
        assertTrue(LegScorer.qualifies(good, f, NOW))
        assertFalse(LegScorer.qualifies(noEdge, f, NOW))
        assertFalse(LegScorer.qualifies(lowProb, f, NOW))
        assertEquals(5.0, good.edgeBeforeFeesPp, 0.6)
    }

    @Test fun startedGamesAreExcluded() {
        val s = LegScorer.score(leg("A", 92).copy(startTime = NOW - 1), fair(0.97), fees)
        assertFalse(LegScorer.qualifies(s, LegFilter(), NOW))
    }

    @Test fun combinationsFollowRankOrder() {
        val seen = mutableListOf<String>()
        ComboBuilder.combinations(5, 4) { seen += it.joinToString(""); true }
        assertEquals(listOf("0123", "0124", "0134", "0234", "1234"), seen)
    }

    @Test fun buildsAllLengthsAndSkipsSameGameByDefault() {
        val legs = (1..8).map { LegScorer.score(leg("$it", 90), fair(0.95), fees) } +
            LegScorer.score(leg("X", 90, event = "EV1"), fair(0.95), fees)
        val combos = ComboBuilder.build(LegScorer.rank(legs), ComboSettings(poolSize = 9))
        assertEquals(setOf(4, 5, 6, 7), combos.map { it.size }.toSet())
        assertTrue(combos.none { it.hasRelatedLegs })
    }

    @Test fun referenceMultiplierAndImprovementMatchSpecExample() {
        // Four independent legs whose prices imply 1.60x; RFQ offers 1.85x → +15.6%.
        val c = ComboCandidate((1..4).map { LegScorer.score(leg("$it", 89), fair(0.95), fees) })
        val ref = c.referenceMultiplier!!
        val q = QuoteRecord("q", c.key, emptyList(), null, 1000, (ref * 1000 * 1.85 / 1.60).toInt(), 0, 0.0, 0, 0, NOW, QuoteSource.API_RFQ, QuotePurpose.GROUPING)
        val r = ComboResult(c, listOf(q))
        assertEquals(1.85 / 1.60 - 1, r.comboImprovement(q)!!, 0.002)
    }

    @Test fun relatedLegsHaveNoReference() {
        val c = ComboCandidate(listOf(leg("A", 90), leg("B", 90, event = "EVA"), leg("C", 90), leg("D", 90)).map { LegScorer.score(it, fair(0.95), fees) })
        assertTrue(c.hasRelatedLegs)
        assertNull(c.referenceMultiplier)
    }

    @Test fun alternateOrderMatchesSpec() {
        assertEquals(listOf("C", "A", "D", "B"), ScanEngine.alternateOrder(listOf("A", "B", "C", "D")))
    }

    @Test fun stakeListIncludesNeighbours() {
        assertEquals(listOf(500, 600, 1000, 1100), ScanSettings(stakeMinCents = 500, stakeMaxCents = 1100, stakeIncrementCents = 500).stakesToTest())
    }

    @Test fun bestStakeIsHighestMultiplier() {
        val c = ComboCandidate((1..4).map { LegScorer.score(leg("$it", 90), fair(0.95), fees) })
        fun q(stake: Int, payout: Int) = QuoteRecord("$stake", c.key, listOf("x"), null, stake, payout, payout / 100, 0.0, 0, 0, NOW, QuoteSource.API_RFQ, QuotePurpose.STAKE_SWEEP)
        val r = ComboResult(c, listOf(q(500, 1000), q(1000, 1300)))
        assertEquals(500, r.best!!.stakeCents)
        assertEquals(2.0, r.best!!.multiplier, 1e-9)
    }
}

/** Fake RFQ: a fixed per-contract price per grouping, optionally depending on entry order. */
class FakeQuotes(
    private val orderSensitive: Boolean?,
    private val priceFor: (List<Leg>) -> Double,
) : QuoteProvider {
    override val source = QuoteSource.API_RFQ
    val calls = mutableListOf<Pair<List<String>, Int>>()
    private val fees = FeeModel()
    override suspend fun isOrderSensitive(collectionTicker: String) = orderSensitive
    override suspend fun quote(comboKey: String, legsInOrder: List<Leg>, stakeCents: Int, purpose: QuotePurpose): QuoteOutcome {
        calls += legsInOrder.map { it.id } to stakeCents
        return QuoteOutcome.Quoted(
            QuoteMath.recordFor("${calls.size}", comboKey, legsInOrder.map { it.id }, "COMBO", stakeCents, priceFor(legsInOrder), fees, NOW + calls.size, source, purpose),
        )
    }
}

class ScanEngineTest {
    private fun engine(legs: List<Leg>, probs: Map<String, Double>, quotes: QuoteProvider) = ScanEngine(
        markets = object : MarketSource { override suspend fun eligibleLegs(sports: Set<String>) = legs },
        fair = object : FairEstimator {
            override suspend fun estimate(legs: List<Leg>) = legs.filter { it.id in probs }.associate { it.id to fair(probs.getValue(it.id)) }
        },
        quotes = quotes,
        clock = { NOW },
    )

    @Test fun waitsWhenFewerThanFourLegsQualify() = runTest {
        val legs = (1..3).map { leg("$it", 90) }
        val r = engine(legs, legs.associate { it.id to 0.96 }, FakeQuotes(false) { 50.0 }).scan(ScanSettings())
        assertEquals(ScanPhase.WAITING_FOR_LEGS, r.finalStatus.phase)
        assertTrue(r.finalStatus.message.contains("Only 3 legs qualify"))
        assertTrue(r.combos.isEmpty())
    }

    @Test fun orderInsensitiveCollectionsSkipOrderTests() = runTest {
        val legs = (1..6).map { leg("$it", 90) }
        val fake = FakeQuotes(orderSensitive = false) { 55.0 }
        val r = engine(legs, legs.associate { it.id to 0.96 }, fake).scan(ScanSettings(quoteBudget = 20))
        assertEquals(ScanPhase.DONE, r.finalStatus.phase)
        assertTrue(r.combos.flatMap { it.quotes }.none { it.purpose == QuotePurpose.ORDER_TEST })
        assertTrue(r.notes.any { it.contains("skipped") })
        assertTrue(fake.calls.size <= 20)
        assertNotNull(r.leader?.best)
    }

    @Test fun repeatableOrderDifferenceIsRecorded() = runTest {
        val legs = (1..4).map { leg("$it", 90) }
        // Entry order starting with leg 3 gets a cheaper price.
        val fake = FakeQuotes(orderSensitive = true) { order -> if (order.first().marketTicker == "MKT3") 50.0 else 60.0 }
        val r = engine(legs, legs.associate { it.id to 0.96 }, fake).scan(ScanSettings(quoteBudget = 30, combo = ComboSettings(maxLegs = 4)))
        val finding = r.combos.first().orderFinding!!
        assertFalse(finding.identical)
        assertTrue(finding.repeatable)
        // The sweep then uses the better order.
        assertTrue(r.leader!!.best!!.entryOrder.first() == "MKT3:YES")
        assertTrue(r.leader!!.verified)
    }

    @Test fun identicalOrdersAreDeduplicated() = runTest {
        val legs = (1..4).map { leg("$it", 90) }
        val fake = FakeQuotes(orderSensitive = null) { 50.0 }
        val r = engine(legs, legs.associate { it.id to 0.96 }, fake).scan(ScanSettings(quoteBudget = 12, combo = ComboSettings(maxLegs = 4)))
        val finding = r.combos.first().orderFinding!!
        assertTrue(finding.identical)
        assertEquals(1, r.combos.flatMap { it.quotes }.count { it.purpose == QuotePurpose.ORDER_TEST })
        assertTrue(r.combos.flatMap { it.quotes }.any { it.purpose == QuotePurpose.STAKE_SWEEP && it.stakeCents == 600 })
    }

    @Test fun budgetIsRespected() = runTest {
        val legs = (1..10).map { leg("$it", 90) }
        val fake = FakeQuotes(orderSensitive = true) { 50.0 }
        engine(legs, legs.associate { it.id to 0.96 }, fake).scan(ScanSettings(quoteBudget = 15))
        assertTrue(fake.calls.size <= 15, "used ${fake.calls.size}")
    }
}
