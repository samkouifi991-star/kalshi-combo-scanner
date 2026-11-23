package com.samer.comboscanner.engine

import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.random.Random

/**
 * Made-up markets and quotes for exploring the app without API keys. Everything it produces is marked
 * with the "DEMO" collection so the UI can label it.
 */
object Demo {
    const val COLLECTION = "DEMO"

    private data class Spec(val sport: String, val matchup: String, val outcome: String, val ask: Int, val fair: Double, val hoursAhead: Int)

    private val specs = listOf(
        Spec("NHL", "Rangers vs. Sabres", "Rangers win", 82, 0.86, 5),
        Spec("NHL", "Oilers vs. Sharks", "Oilers win", 88, 0.915, 7),
        Spec("Soccer", "Arsenal vs. Burnley", "Over 1.5 total goals", 85, 0.89, 26),
        Spec("Soccer", "Bayern vs. Bochum", "Bayern win", 90, 0.935, 28),
        Spec("NBA", "Celtics vs. Wizards", "Celtics win", 92, 0.95, 6),
        Spec("NFL", "Chiefs vs. Panthers", "Chiefs win", 84, 0.87, 50),
        Spec("Soccer", "Real Madrid vs. Getafe", "Over 0.5 total goals", 94, 0.965, 30),
        Spec("NBA", "Thunder vs. Jazz", "Thunder win", 91, 0.925, 8),
        Spec("NHL", "Panthers vs. Ducks", "Panthers win", 79, 0.81, 6),
        Spec("NFL", "Lions vs. Giants", "Lions win", 86, 0.85, 52),
    )

    fun markets(clock: () -> EpochMs = System::currentTimeMillis) = object : MarketSource {
        override suspend fun eligibleLegs(sports: Set<String>): List<Leg> {
            delay(400)
            val now = clock()
            return specs.mapIndexed { i, s ->
                Leg("DEMO-M$i", "DEMO-E$i", "DEMO", COLLECTION, Side.YES, s.matchup, s.outcome, s.sport,
                    now + s.hoursAhead * 3_600_000L, s.ask + Random.nextInt(-1, 2), 250, now - Random.nextLong(2_000, 40_000))
            }.filter { sports.isEmpty() || it.sport in sports }
        }
    }

    fun fair(clock: () -> EpochMs = System::currentTimeMillis) = object : FairEstimator {
        override suspend fun estimate(legs: List<Leg>): Map<String, FairEstimate> {
            delay(300)
            val now = clock()
            return legs.associate { l ->
                val s = specs[l.marketTicker.removePrefix("DEMO-M").toInt()]
                val ev = listOf(
                    Evidence(EvidenceKind.EXTERNAL_MARKET, "Sportsbook consensus (demo)", s.fair, 0.012, now - 180_000, "6 books, margin removed (demo)"),
                    Evidence(EvidenceKind.HISTORICAL, "Settled history (demo)", s.fair - 0.01, 0.03, now - 86_400_000, "41 of 45 won (demo)"),
                )
                l.id to FairProbability.blend(ev)!!
            }
        }
    }

    fun quotes(fees: FeeModel = FeeModel(), clock: () -> EpochMs = System::currentTimeMillis) = object : QuoteProvider {
        override val source = QuoteSource.API_RFQ
        override suspend fun isOrderSensitive(collectionTicker: String) = false
        override suspend fun quote(comboKey: String, legsInOrder: List<Leg>, stakeCents: Int, purpose: QuotePurpose): QuoteOutcome {
            delay(250)
            val ref = legsInOrder.fold(1.0) { a, l -> a * l.askCents / 100.0 } * 100
            // Makers sometimes improve on the individual prices; wobble a little so rechecks are realistic.
            val price = (ref * (0.93 + Random.nextDouble(0.0, 0.05))).coerceIn(1.0, 99.0).let { kotlin.math.round(it) }
            return QuoteOutcome.Quoted(
                QuoteMath.recordFor(UUID.randomUUID().toString(), comboKey, legsInOrder.map { it.id }, "DEMO-COMBO", stakeCents, price, fees, clock(), source, purpose),
            )
        }
    }
}
