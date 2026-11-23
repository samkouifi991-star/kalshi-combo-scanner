package com.samer.comboscanner.engine.kalshi

import com.samer.comboscanner.engine.Leg
import com.samer.comboscanner.engine.MarketSource
import com.samer.comboscanner.engine.Side
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A combo collection as Kalshi describes it. */
data class ComboCollection(
    val ticker: String,
    val seriesTicker: String?,
    val title: String,
    val isOrdered: Boolean,
    val sizeMin: Int?,
    val sizeMax: Int?,
    /** Event ticker → whether only YES may be selected for markets in it. */
    val events: Map<String, Boolean>,
)

/**
 * Lists every open combo collection, then every market in their eligible events, and turns each
 * tradable side into a [Leg] priced at its current ask.
 */
class KalshiMarketSource(
    private val client: KalshiClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEventsPerCall: Int = 10,
) : MarketSource {
    @Volatile var collections: Map<String, ComboCollection> = emptyMap()
        private set

    suspend fun loadCollections(): List<ComboCollection> {
        val out = mutableListOf<ComboCollection>()
        var cursor: String? = null
        do {
            val page = client.get("/multivariate_event_collections", mapOf("status" to "open", "limit" to "200", "cursor" to cursor))
            for (c in page.arr("multivariate_contracts")) {
                val events = c.arr("associated_events").associate { (it.str("ticker") ?: "") to (it.bool("is_yes_only") ?: false) }
                    .ifEmpty {
                        // Older field: plain list of event tickers.
                        (c["associated_event_tickers"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            ?.associateWith { c.bool("is_all_yes") ?: false } ?: emptyMap()
                    }
                out += ComboCollection(
                    ticker = c.str("collection_ticker") ?: continue,
                    seriesTicker = c.str("series_ticker"),
                    title = c.str("title") ?: "",
                    isOrdered = c.bool("is_ordered") ?: false,
                    sizeMin = c.int("size_min"),
                    sizeMax = c.int("size_max"),
                    events = events.filterKeys { it.isNotEmpty() },
                )
            }
            cursor = page.str("cursor")
        } while (!cursor.isNullOrEmpty())
        collections = out.associateBy { it.ticker }
        return out
    }

    override suspend fun eligibleLegs(sports: Set<String>): List<Leg> = coroutineScope {
        val cols = loadCollections()
        // An event can sit in several collections; keep the first so each leg has one home.
        val eventToCollection = LinkedHashMap<String, ComboCollection>()
        for (c in cols) for (e in c.events.keys) eventToCollection.putIfAbsent(e, c)
        val gate = Semaphore(4)
        eventToCollection.keys.chunked(maxEventsPerCall).map { chunk ->
            async { gate.withPermit { fetchMarkets(chunk) } }
        }.flatMap { it.await() }.flatMap { m -> toLegs(m, eventToCollection) }
            .filter { sports.isEmpty() || it.sport in sports }
    }

    private suspend fun fetchMarkets(eventTickers: List<String>): List<kotlinx.serialization.json.JsonObject> {
        val out = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var cursor: String? = null
        do {
            val page = client.get(
                "/markets",
                mapOf("event_ticker" to eventTickers.joinToString(","), "status" to "open", "limit" to "1000", "cursor" to cursor),
            )
            out += page.arr("markets")
            cursor = page.str("cursor")
        } while (!cursor.isNullOrEmpty())
        return out
    }

    private fun toLegs(m: kotlinx.serialization.json.JsonObject, eventToCollection: Map<String, ComboCollection>): List<Leg> {
        val ticker = m.str("ticker") ?: return emptyList()
        val eventTicker = m.str("event_ticker") ?: return emptyList()
        val col = eventToCollection[eventTicker] ?: return emptyList()
        val yesOnly = col.events[eventTicker] ?: false
        val now = clock()
        val title = m.str("title") ?: ticker
        val matchup = SportClassifier.matchup(title)
        val sport = SportClassifier.sport(eventTicker, m.str("series_ticker") ?: col.seriesTicker, title)
        val yesLabel = m.str("yes_sub_title") ?: title
        val noLabel = m.str("no_sub_title")?.takeIf { it != yesLabel } ?: "Not: $yesLabel"
        val start = m.timeMs("occurrence_datetime") ?: m.timeMs("expected_start_time")
        val legs = mutableListOf<Leg>()
        m.cents("yes_ask")?.takeIf { it in 1.0..99.0 }?.let { ask ->
            legs += Leg(ticker, eventTicker, m.str("series_ticker"), col.ticker, Side.YES, matchup, yesLabel, sport, start,
                kotlin.math.ceil(ask).toInt(), m.int("yes_ask_size"), now)
        }
        if (!yesOnly) m.cents("no_ask")?.takeIf { it in 1.0..99.0 }?.let { ask ->
            legs += Leg(ticker, eventTicker, m.str("series_ticker"), col.ticker, Side.NO, matchup, noLabel, sport, start,
                kotlin.math.ceil(ask).toInt(), m.int("no_ask_size"), now)
        }
        return legs
    }
}

/** Maps Kalshi tickers and titles to the sport names used in filters. */
object SportClassifier {
    private val rules = listOf(
        "NFL" to listOf("NFL"),
        "College football" to listOf("NCAAF", "CFB"),
        "NBA" to listOf("NBA"),
        "WNBA" to listOf("WNBA"),
        "College basketball" to listOf("NCAAB", "NCAAMB", "NCAAWB", "CBB"),
        "NHL" to listOf("NHL"),
        "MLB" to listOf("MLB"),
        "Soccer" to listOf("EPL", "LALIGA", "SERIEA", "BUNDESLIGA", "LIGUE1", "UCL", "UEL", "MLS", "SOCCER", "EFL", "FACUP", "UECL", "LIGAMX", "EREDIVISIE"),
        "Tennis" to listOf("ATP", "WTA", "TENNIS"),
        "MMA" to listOf("UFC", "MMA"),
        "Golf" to listOf("PGA", "GOLF", "LIV"),
        "Esports" to listOf("LOL", "CS2", "VALORANT", "DOTA"),
    )

    fun sport(eventTicker: String, seriesTicker: String?, title: String): String {
        val hay = ((seriesTicker ?: "") + " " + eventTicker).uppercase()
        // WNBA must win over NBA, so check longer keys first.
        for ((name, keys) in rules.sortedByDescending { r -> r.second.maxOf { it.length } }) {
            if (keys.any { hay.contains(it) }) return name
        }
        return "Other"
    }

    fun matchup(title: String): String {
        // "Team A vs Team B: Total goals" → "Team A vs. Team B"
        val m = Regex("""(.+?)\s+(?:vs\.?|at|@)\s+(.+?)(?:[:?]|\s+-\s+|\s+winner|\s+total|\s+spread|$)""", RegexOption.IGNORE_CASE).find(title)
        return m?.let { "${it.groupValues[1].trim()} vs. ${it.groupValues[2].trim()}" } ?: title.trimEnd('?')
    }
}
