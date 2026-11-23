package com.samer.comboscanner.engine.odds

import com.samer.comboscanner.engine.Evidence
import com.samer.comboscanner.engine.FairEstimate
import com.samer.comboscanner.engine.FairEstimator
import com.samer.comboscanner.engine.FairProbability
import com.samer.comboscanner.engine.HistoricalSample
import com.samer.comboscanner.engine.Leg

fun interface HistoricalSource {
    suspend fun sampleFor(leg: Leg): HistoricalSample?
}

/**
 * Combines sportsbook consensus (margin removed) with settled-history base rates. Legs with no evidence
 * at all are left unpriced rather than guessed.
 */
class CompositeFairEstimator(
    private val odds: OddsApiClient?,
    private val history: HistoricalSource?,
    private val onWarning: (String) -> Unit = {},
) : FairEstimator {
    @Volatile var lastMatches: Map<String, MatchedLeg> = emptyMap()
        private set

    override suspend fun estimate(legs: List<Leg>): Map<String, FairEstimate> {
        val matches = HashMap<String, MatchedLeg>()
        if (odds != null) {
            for ((sport, sportLegs) in legs.groupBy { it.sport }) {
                val keys = OddsApiClient.sportKeys[sport] ?: continue
                val events = keys.flatMap { k ->
                    runCatching { odds!!.odds(k) }.getOrElse { onWarning("External odds unavailable for $k: ${it.message}"); emptyList() }
                }
                for (l in sportLegs) LegMatcher.match(l, events)?.let { matches[l.id] = it }
            }
        }
        lastMatches = matches
        val out = HashMap<String, FairEstimate>()
        for (l in legs) {
            val evidence = mutableListOf<Evidence>()
            matches[l.id]?.let { m ->
                FairProbability.externalEvidence(m.books, "Sportsbook consensus · ${m.description}")?.let { evidence += it }
            }
            // Historical lookups are only worth their API cost for legs that could plausibly qualify.
            if (history != null && l.askCents >= 60) {
                runCatching { history!!.sampleFor(l) }.getOrNull()?.let { s -> FairProbability.historicalEvidence(s)?.let { evidence += it } }
            }
            FairProbability.blend(evidence)?.let { est ->
                out[l.id] = est.copy(eventStartTime = matches[l.id]?.event?.commenceMs)
            }
        }
        return out
    }
}
