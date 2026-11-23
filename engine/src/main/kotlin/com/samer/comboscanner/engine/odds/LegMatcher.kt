package com.samer.comboscanner.engine.odds

import com.samer.comboscanner.engine.BookPrices
import com.samer.comboscanner.engine.Leg
import java.time.Instant

/** What a Kalshi leg is betting on, parsed from its title and outcome text. */
sealed class LegKind {
    data class Winner(val team: String) : LegKind()
    data object Draw : LegKind()
    data class Total(val over: Boolean, val line: Double) : LegKind()
    data class Spread(val team: String, val winsByMoreThan: Double) : LegKind()
}

data class MatchedLeg(val event: OddsEvent, val books: List<BookPrices>, val description: String)

/**
 * Finds the sportsbook market matching a Kalshi leg. Handles game winner (2- and 3-way), totals and
 * spreads. Anything else is left unmatched and gets only historical evidence.
 */
object LegMatcher {
    private val stop = setOf("the", "fc", "cf", "sc", "afc", "united", "city", "club", "de", "real", "state", "university", "vs", "at")

    /**
     * Parses the outcome text of the side being bought. Kalshi gives each side its own label; when a NO side
     * has no label of its own the source labels it "Not: <yes label>", which is handled as a negation.
     * [threeWay] is true when the game can end in a draw, so "not Team A" has no single matching outcome.
     */
    fun parse(leg: Leg, event: OddsEvent, threeWay: Boolean = false): LegKind? {
        val negated = leg.outcome.startsWith("Not: ")
        val text = leg.outcome.removePrefix("Not: ")
        val lower = text.lowercase()
        Regex("""\b(over|under)\s+(\d+(?:\.\d+)?)""").find(lower)?.let { m ->
            val over = (m.groupValues[1] == "over") != negated
            return LegKind.Total(over, m.groupValues[2].toDouble())
        }
        Regex("""(.+?)\s+wins?\s+by\s+(?:over|more than)\s+(\d+(?:\.\d+)?)""").find(lower)?.let { m ->
            if (negated) return null
            val team = teamIn(m.groupValues[1], event) ?: return null
            return LegKind.Spread(team, m.groupValues[2].toDouble())
        }
        if (Regex("""\b(tie|draw)\b""").containsMatchIn(lower)) return if (negated) null else LegKind.Draw
        val team = teamIn(text, event) ?: return null
        if (!negated) return LegKind.Winner(team)
        if (threeWay) return null
        return LegKind.Winner(if (team == event.home_team) event.away_team else event.home_team)
    }

    fun match(leg: Leg, events: List<OddsEvent>): MatchedLeg? {
        val candidates = events.filter { sameGame(leg, it) }
        val event = candidates.minByOrNull { e -> leg.startTime?.let { kotlin.math.abs(e.commenceMs - it) } ?: 0L } ?: return null
        val threeWay = event.bookmakers.any { b -> b.markets.any { m -> m.key == "h2h" && m.outcomes.size == 3 } }
        val kind = parse(leg, event, threeWay) ?: return null
        val books = event.bookmakers.mapNotNull { b -> prices(b, event, kind) }
        if (books.isEmpty()) return null
        val label = when (kind) {
            is LegKind.Winner -> "${kind.team} to win"
            LegKind.Draw -> "Draw"
            is LegKind.Total -> "${if (kind.over) "Over" else "Under"} ${kind.line}"
            is LegKind.Spread -> "${kind.team} −${kind.winsByMoreThan}"
        }
        return MatchedLeg(event, books, "${event.away_team} @ ${event.home_team} · $label")
    }

    private fun prices(b: OddsBookmaker, event: OddsEvent, kind: LegKind): BookPrices? {
        val updated = b.last_update?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        fun mk(odds: List<Double>, idx: Int) = if (idx < 0) null else BookPrices(b.key, odds, idx, updated)
        return when (kind) {
            is LegKind.Winner, LegKind.Draw -> {
                val m = b.markets.firstOrNull { it.key == "h2h" } ?: return null
                val target = if (kind is LegKind.Winner) kind.team else "Draw"
                mk(m.outcomes.map { it.price }, m.outcomes.indexOfFirst { it.name == target })
            }
            is LegKind.Total -> {
                val m = b.markets.firstOrNull { it.key == "totals" } ?: return null
                val pair = m.outcomes.filter { it.point == kind.line }
                if (pair.size != 2) return null
                mk(pair.map { it.price }, pair.indexOfFirst { it.name.equals(if (kind.over) "Over" else "Under", true) })
            }
            is LegKind.Spread -> {
                val m = b.markets.firstOrNull { it.key == "spreads" } ?: return null
                val fav = m.outcomes.firstOrNull { it.name == kind.team && it.point == -kind.winsByMoreThan } ?: return null
                val dog = m.outcomes.firstOrNull { it.name != kind.team && it.point == kind.winsByMoreThan } ?: return null
                mk(listOf(fav.price, dog.price), 0)
            }
        }
    }

    fun sameGame(leg: Leg, e: OddsEvent): Boolean {
        val text = (leg.matchup + " " + leg.outcome).lowercase()
        val ok = teamMentioned(e.home_team, text) && teamMentioned(e.away_team, text)
        val timeOk = leg.startTime?.let { kotlin.math.abs(e.commenceMs - it) < 36 * 3600_000L } ?: true
        return ok && timeOk
    }

    private fun tokens(name: String) = name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }

    private fun teamMentioned(team: String, text: String): Boolean {
        val t = tokens(team)
        val words = tokens(text).toSet()
        return t.any { it in words } || text.contains(team.lowercase())
    }

    private fun teamIn(text: String, event: OddsEvent): String? {
        val lower = text.lowercase()
        val home = teamMentioned(event.home_team, lower)
        val away = teamMentioned(event.away_team, lower)
        return when {
            home && !away -> event.home_team
            away && !home -> event.away_team
            else -> null
        }
    }
}
