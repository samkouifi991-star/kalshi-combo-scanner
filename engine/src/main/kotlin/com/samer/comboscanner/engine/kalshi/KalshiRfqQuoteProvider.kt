package com.samer.comboscanner.engine.kalshi

import com.samer.comboscanner.engine.FeeModel
import com.samer.comboscanner.engine.Leg
import com.samer.comboscanner.engine.QuoteMath
import com.samer.comboscanner.engine.QuoteOutcome
import com.samer.comboscanner.engine.QuoteProvider
import com.samer.comboscanner.engine.QuotePurpose
import com.samer.comboscanner.engine.QuoteSource
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Gets combo quotes from Kalshi's RFQ system through the official API. Never accepts a quote:
 * every RFQ is deleted after reading, and purchases stay manual in the Kalshi app.
 *
 * Flow per quote:
 *  1. POST /multivariate_event_collections/{collection} with the legs in entry order → combo market ticker.
 *  2. POST /communications/rfqs with target_cost_dollars = stake (fees included).
 *  3. Poll GET /communications/quotes?rfq_id=… until makers respond or [waitMs] passes.
 *  4. DELETE /communications/rfqs/{id}.
 */
class KalshiRfqQuoteProvider(
    private val client: KalshiClient,
    private val collections: () -> Map<String, ComboCollection>,
    private val fees: FeeModel = FeeModel(),
    private val waitMs: Long = 8_000,
    private val pollMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : QuoteProvider {
    override val source = QuoteSource.API_RFQ
    private val tickerCache = HashMap<String, String>()

    override suspend fun isOrderSensitive(collectionTicker: String): Boolean? =
        collections()[collectionTicker]?.isOrdered

    suspend fun comboTicker(legsInOrder: List<Leg>): String {
        val collection = legsInOrder.first().collectionTicker
        val ordered = collections()[collection]?.isOrdered ?: true
        val cacheKey = collection + "/" + (if (ordered) legsInOrder.map { it.id } else legsInOrder.map { it.id }.sorted()).joinToString(",")
        tickerCache[cacheKey]?.let { return it }
        val body = buildJsonObject {
            put("selected_markets", buildJsonArray {
                for (l in legsInOrder) add(buildJsonObject {
                    put("market_ticker", l.marketTicker)
                    put("event_ticker", l.eventTicker)
                    put("side", l.side.name.lowercase())
                })
            })
        }
        val resp = client.post("/multivariate_event_collections/$collection", body)
        val ticker = resp.str("market_ticker") ?: resp.obj("market")?.str("ticker")
            ?: throw KalshiApiException(200, "no_ticker", "Kalshi did not return a combo ticker")
        tickerCache[cacheKey] = ticker
        return ticker
    }

    override suspend fun quote(comboKey: String, legsInOrder: List<Leg>, stakeCents: Int, purpose: QuotePurpose): QuoteOutcome {
        val ticker = try {
            comboTicker(legsInOrder)
        } catch (e: KalshiApiException) {
            return if (e.status == 400) QuoteOutcome.NoQuote("Kalshi rejected this leg combination (${e.code ?: e.status})")
            else QuoteOutcome.Failed(e.message ?: "combo lookup failed", retryable = e.status != 401 && e.code != "too_many_requests")
        }
        val rfqId = try {
            createRfq(ticker, stakeCents)
        } catch (e: KalshiApiException) {
            return when {
                e.code == "open_rfq_limit_exceeded" -> QuoteOutcome.Failed("100 open RFQ limit reached", retryable = false)
                e.status == 401 || e.status == 403 -> QuoteOutcome.Failed("API key rejected", retryable = false)
                e.status == 400 -> QuoteOutcome.NoQuote("Market not open for RFQ (${e.code ?: 400})")
                else -> QuoteOutcome.Failed(e.message ?: "RFQ failed", retryable = true)
            }
        }
        try {
            val deadline = clock() + waitMs
            var best: Pair<String, Double>? = null
            while (clock() < deadline) {
                delay(pollMs)
                best = bestYesPrice(rfqId) ?: continue
                // Give other makers one more poll to beat the first quote, then stop.
                delay(pollMs)
                best = bestYesPrice(rfqId) ?: best
                break
            }
            val (quoteId, priceCents) = best ?: return QuoteOutcome.NoQuote("No maker quoted within ${waitMs / 1000}s")
            return QuoteOutcome.Quoted(
                QuoteMath.recordFor(
                    id = UUID.randomUUID().toString(), comboKey = comboKey, entryOrder = legsInOrder.map { it.id },
                    comboMarketTicker = ticker, stakeCents = stakeCents, priceCents = priceCents, fees = fees,
                    timestamp = clock(), source = source, purpose = purpose, rfqId = rfqId, quoteId = quoteId,
                ),
            )
        } finally {
            runCatching { client.delete("/communications/rfqs/$rfqId") }
        }
    }

    private suspend fun createRfq(ticker: String, stakeCents: Int): String {
        val body = buildJsonObject {
            put("market_ticker", ticker)
            put("rest_remainder", false)
            put("target_cost_dollars", JsonPrimitive("%.2f".format(stakeCents / 100.0)))
            put("target_cost_excludes_fees", false)
        }
        return try {
            client.post("/communications/rfqs", body).str("id")!!
        } catch (e: KalshiApiException) {
            if (e.status != 409) throw e
            // An RFQ of ours is still open on this combo: close it and try once more.
            closeMyOpenRfqs(ticker)
            client.post("/communications/rfqs", body).str("id")!!
        }
    }

    private suspend fun closeMyOpenRfqs(ticker: String) {
        val open = client.get("/communications/rfqs", mapOf("market_ticker" to ticker, "status" to "open", "user_filter" to "self"))
        for (r in open.arr("rfqs")) r.str("id")?.let { runCatching { client.delete("/communications/rfqs/$it") } }
    }

    /**
     * Best price for us to buy YES on the combo, in cents. A maker's no_bid is what they pay for NO,
     * so they are selling YES at 1 − no_bid.
     */
    private suspend fun bestYesPrice(rfqId: String): Pair<String, Double>? {
        val resp = client.get("/communications/quotes", mapOf("rfq_id" to rfqId, "status" to "open", "limit" to "100"))
        return resp.arr("quotes").mapNotNull { q ->
            val noBid = q.cents("no_bid") ?: return@mapNotNull null
            if (noBid <= 0) return@mapNotNull null
            (q.str("id") ?: return@mapNotNull null) to (100.0 - noBid)
        }.minByOrNull { it.second }
    }
}
