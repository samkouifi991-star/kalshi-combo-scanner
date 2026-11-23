package com.samer.comboscanner.engine.odds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
data class OddsOutcome(val name: String, val price: Double, val point: Double? = null, val description: String? = null)

@Serializable
data class OddsMarket(val key: String, val last_update: String? = null, val outcomes: List<OddsOutcome> = emptyList())

@Serializable
data class OddsBookmaker(val key: String, val title: String = "", val last_update: String? = null, val markets: List<OddsMarket> = emptyList())

@Serializable
data class OddsEvent(
    val id: String,
    val sport_key: String,
    val sport_title: String = "",
    val commence_time: String,
    val home_team: String,
    val away_team: String,
    val bookmakers: List<OddsBookmaker> = emptyList(),
) {
    val commenceMs: Long get() = Instant.parse(commence_time).toEpochMilli()
}

/**
 * Client for The Odds API v4 (the-odds-api.com), which aggregates prices from many sportsbooks and
 * exchanges. Needs the user's own API key. Responses are cached so repeated scans don't burn credits.
 */
class OddsApiClient(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build(),
    private val cacheMs: Long = 5 * 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, Pair<Long, List<OddsEvent>>>()
    @Volatile var remainingCredits: Int? = null
        private set

    suspend fun odds(sportKey: String, markets: String = "h2h,totals,spreads", regions: String = "us,eu,uk"): List<OddsEvent> {
        val cacheKey = "$sportKey|$markets|$regions"
        cache[cacheKey]?.let { (t, v) -> if (clock() - t < cacheMs) return v }
        val url = "https://api.the-odds-api.com/v4/sports/$sportKey/odds?apiKey=$apiKey&regions=$regions&markets=$markets&oddsFormat=decimal&dateFormat=iso"
        val events = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                r.header("x-requests-remaining")?.toDoubleOrNull()?.let { remainingCredits = it.toInt() }
                if (r.code == 404 || r.code == 422) return@use emptyList()
                if (!r.isSuccessful) throw IllegalStateException("Odds API $sportKey → ${r.code}")
                json.decodeFromString<List<OddsEvent>>(r.body!!.string())
            }
        }
        cache[cacheKey] = clock() to events
        return events
    }

    companion object {
        /** Odds API sport keys to query for each sport name the app uses. */
        val sportKeys: Map<String, List<String>> = mapOf(
            "NFL" to listOf("americanfootball_nfl"),
            "College football" to listOf("americanfootball_ncaaf"),
            "NBA" to listOf("basketball_nba"),
            "WNBA" to listOf("basketball_wnba"),
            "College basketball" to listOf("basketball_ncaab"),
            "NHL" to listOf("icehockey_nhl"),
            "MLB" to listOf("baseball_mlb"),
            "Soccer" to listOf(
                "soccer_epl", "soccer_spain_la_liga", "soccer_italy_serie_a", "soccer_germany_bundesliga",
                "soccer_france_ligue_one", "soccer_uefa_champs_league", "soccer_uefa_europa_league", "soccer_usa_mls",
                "soccer_efl_champ", "soccer_netherlands_eredivisie", "soccer_mexico_ligamx",
            ),
            "MMA" to listOf("mma_mixed_martial_arts"),
        )
    }
}
