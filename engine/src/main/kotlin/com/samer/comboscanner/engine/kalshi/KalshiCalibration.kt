package com.samer.comboscanner.engine.kalshi

import com.samer.comboscanner.engine.HistoricalSample
import com.samer.comboscanner.engine.Leg
import com.samer.comboscanner.engine.Side
import com.samer.comboscanner.engine.odds.HistoricalSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

/** A settled Kalshi market with its pregame prices and result. */
@Serializable
data class SettledPoint(val ticker: String, val yesAskCents: Double?, val noAskCents: Double?, val yesWon: Boolean, val settledAt: Long?)

/**
 * Historical base rate from Kalshi itself: of recently settled markets in the same series, how often did a
 * side priced near this leg's ask (±[bandCents]) win? Prices are read from hourly candlesticks a few hours
 * before expected settlement, which for game markets is roughly pregame.
 */
class KalshiCalibration(
    private val client: KalshiClient,
    private val samplesPerSeries: Int = 30,
    private val bandCents: Int = 4,
    private val cacheMs: Long = 24 * 3600_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Lets the app persist and restore the cache between launches. */
    initial: Map<String, Pair<Long, List<SettledPoint>>> = emptyMap(),
) : HistoricalSource {
    private val cache = HashMap(initial)
    private val locks = HashMap<String, Mutex>()
    private val gate = Semaphore(3)

    fun snapshot(): Map<String, Pair<Long, List<SettledPoint>>> = synchronized(cache) { HashMap(cache) }

    override suspend fun sampleFor(leg: Leg): HistoricalSample? {
        val series = leg.seriesTicker ?: leg.eventTicker.substringBefore('-')
        val points = points(series)
        val matching = points.mapNotNull { p ->
            val price = if (leg.side == Side.YES) p.yesAskCents else p.noAskCents
            if (price == null || kotlin.math.abs(price - leg.askCents) > bandCents) null
            else (if (leg.side == Side.YES) p.yesWon else !p.yesWon)
        }
        if (matching.size < 5) return null
        return HistoricalSample(
            description = "Kalshi $series sides priced ${leg.askCents - bandCents}–${leg.askCents + bandCents}¢ pregame",
            wins = matching.count { it }, total = matching.size,
            updatedAt = synchronized(cache) { cache[series]?.first },
        )
    }

    private suspend fun points(series: String): List<SettledPoint> {
        val lock = synchronized(locks) { locks.getOrPut(series) { Mutex() } }
        return lock.withLock {
            synchronized(cache) { cache[series] }?.let { (t, v) -> if (clock() - t < cacheMs) return@withLock v }
            val fresh = runCatching { load(series) }.getOrElse { emptyList() }
            synchronized(cache) { cache[series] = clock() to fresh }
            fresh
        }
    }

    private suspend fun load(series: String): List<SettledPoint> = coroutineScope {
        val page = client.get("/markets", mapOf("series_ticker" to series, "status" to "settled", "limit" to "200"))
        val settled = page.arr("markets").filter { it.str("result") == "yes" || it.str("result") == "no" }
            .sortedByDescending { it.timeMs("close_time") ?: 0 }.take(samplesPerSeries)
        settled.map { m ->
            async {
                gate.withPermit {
                    val ticker = m.str("ticker")!!
                    val settleAt = m.timeMs("expected_expiration_time") ?: m.timeMs("close_time") ?: return@withPermit null
                    val end = settleAt - 3 * 3600_000L
                    val start = end - 6 * 3600_000L
                    val candles = runCatching {
                        client.get(
                            "/series/$series/markets/$ticker/candlesticks",
                            mapOf("start_ts" to (start / 1000).toString(), "end_ts" to (end / 1000).toString(), "period_interval" to "60"),
                        ).arr("candlesticks")
                    }.getOrElse { emptyList() }
                    val last = candles.lastOrNull { it.obj("yes_ask")?.cents("close") != null } ?: return@withPermit null
                    val yesAsk = last.obj("yes_ask")?.cents("close")
                    val yesBid = last.obj("yes_bid")?.cents("close")
                    SettledPoint(ticker, yesAsk, yesBid?.let { 100 - it }, m.str("result") == "yes", m.timeMs("close_time"))
                }
            }
        }.mapNotNull { it.await() }
    }
}
