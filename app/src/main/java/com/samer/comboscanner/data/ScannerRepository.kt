package com.samer.comboscanner.data

import android.content.Context
import com.samer.comboscanner.engine.ComboResult
import com.samer.comboscanner.engine.Demo
import com.samer.comboscanner.engine.DemoLong
import com.samer.comboscanner.engine.LongComboScanner
import com.samer.comboscanner.engine.FairEstimator
import com.samer.comboscanner.engine.FeeModel
import com.samer.comboscanner.engine.Leg
import com.samer.comboscanner.engine.MarketSource
import com.samer.comboscanner.engine.QuoteOutcome
import com.samer.comboscanner.engine.QuoteProvider
import com.samer.comboscanner.engine.QuotePurpose
import com.samer.comboscanner.engine.QuoteRecord
import com.samer.comboscanner.engine.QuoteSource
import com.samer.comboscanner.engine.ScanEngine
import com.samer.comboscanner.engine.ScanPhase
import com.samer.comboscanner.engine.ScanResult
import com.samer.comboscanner.engine.ScanStatus
import com.samer.comboscanner.engine.kalshi.KalshiCalibration
import com.samer.comboscanner.engine.kalshi.KalshiClient
import com.samer.comboscanner.engine.kalshi.KalshiMarketSource
import com.samer.comboscanner.engine.kalshi.KalshiRfqQuoteProvider
import com.samer.comboscanner.engine.kalshi.KalshiApiException
import com.samer.comboscanner.engine.odds.CompositeFairEstimator
import com.samer.comboscanner.engine.odds.OddsApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Result of rechecking a quote: the previous number and the fresh one. */
data class Recheck(val previous: QuoteRecord?, val fresh: QuoteRecord?, val message: String?)

/**
 * Single source of truth for the app and the background worker: settings, the last scan, quotes,
 * and purchased tickets. All network work goes through the engine.
 */
class ScannerRepository(private val context: Context) {
    val store = Store(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanLock = Mutex()

    private val _settings = MutableStateFlow(store.loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus(ScanPhase.IDLE, "Not scanned yet", updatedAt = System.currentTimeMillis()))
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private val _scan = MutableStateFlow(store.loadScan())
    val scan: StateFlow<ScanResult?> = _scan.asStateFlow()

    private val _longStatus = MutableStateFlow(ScanStatus(ScanPhase.IDLE, "Not scanned yet", updatedAt = System.currentTimeMillis()))
    val longStatus: StateFlow<ScanStatus> = _longStatus.asStateFlow()

    private val _longScan = MutableStateFlow(store.loadLongScan())
    val longScan: StateFlow<ScanResult?> = _longScan.asStateFlow()

    private val _tickets = MutableStateFlow(store.loadTickets())
    val tickets: StateFlow<List<Ticket>> = _tickets.asStateFlow()

    private val _activity = MutableStateFlow(store.loadActivity())
    val activity: StateFlow<List<QuoteRecord>> = _activity.asStateFlow()

    private val _warnings = MutableStateFlow<List<String>>(emptyList())
    val warnings: StateFlow<List<String>> = _warnings.asStateFlow()

    init {
        _scan.value?.let { _status.value = it.finalStatus }
        _longScan.value?.let { _longStatus.value = it.finalStatus }
    }

    fun hasKalshiKey() = store.kalshiCredentials() != null
    fun hasOddsKey() = store.oddsApiKey() != null

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        _settings.update(f)
        store.saveSettings(_settings.value)
    }

    private val fees get() = FeeModel(_settings.value.takerFeeRate)

    private fun client(): KalshiClient = KalshiClient(
        store.kalshiCredentials(),
        if (_settings.value.useDemoExchange) KalshiClient.DEMO_BASE else KalshiClient.PROD_BASE,
    )

    private var calibration: KalshiCalibration? = null

    private data class Parts(
        val markets: MarketSource,
        val fair: FairEstimator,
        val quotes: QuoteProvider,
        val maxLegsFor: (String) -> Int? = { null },
    )

    private fun parts(): Parts {
        val s = _settings.value
        if (s.demoMode) return Parts(Demo.markets(), Demo.fair(), Demo.quotes(fees))
        val client = client()
        val markets = KalshiMarketSource(client)
        val cal = if (s.useHistorical) (calibration ?: KalshiCalibration(client, initial = store.loadCalibration()).also { calibration = it }) else null
        val odds = store.oddsApiKey()?.let { OddsApiClient(it) }
        val fair = CompositeFairEstimator(odds, cal) { w -> _warnings.update { (it + w).takeLast(20) } }
        val quotes: QuoteProvider = if (store.kalshiCredentials() != null) {
            KalshiRfqQuoteProvider(client, { markets.collections }, fees)
        } else NoKeyQuotes
        return Parts(markets, fair, quotes) { t -> markets.collections[t]?.sizeMax }
    }

    private fun longParts(): Parts =
        if (_settings.value.demoMode) Parts(DemoLong.markets(), DemoLong.fair(), Demo.quotes(fees)) else parts()

    /** Long tab scan: 100+ leg combos from legs priced at the floor (94¢) or above. */
    suspend fun scanLongNow(): ScanResult? = scanLock.withLock {
        val p = longParts()
        lastQuotes = p.quotes
        val result = try {
            LongComboScanner(p.markets, p.fair, p.quotes, p.maxLegsFor).scan(_settings.value.toLongSettings(), fees) { _longStatus.value = it }
        } catch (e: Exception) {
            _longStatus.value = ScanStatus(ScanPhase.FAILED, "Scan failed: ${friendly(e)}", updatedAt = System.currentTimeMillis())
            return null
        }
        _longScan.value = result
        store.saveLongScan(result)
        calibration?.let { store.saveCalibration(it.snapshot()) }
        recordActivity(result.combos.flatMap { it.quotes })
        _longStatus.value = result.finalStatus
        result
    }

    fun scanLongInBackground() {
        scope.launch { runCatching { scanLongNow() } }
    }

    private var lastQuotes: QuoteProvider? = null

    /** Runs a full scan: legs → combos → groupings → entry order → stakes → leader recheck. */
    suspend fun scanNow(): ScanResult? = scanLock.withLock {
        _warnings.value = emptyList()
        val p = parts()
        lastQuotes = p.quotes
        val result = try {
            ScanEngine(p.markets, p.fair, p.quotes).scan(_settings.value.toScanSettings()) { _status.value = it }
        } catch (e: Exception) {
            _status.value = ScanStatus(ScanPhase.FAILED, "Scan failed: ${friendly(e)}", updatedAt = System.currentTimeMillis())
            return null
        }
        if (store.oddsApiKey() == null && !_settings.value.demoMode) {
            _warnings.update { it + "No external odds key set: fair probabilities use settled history only." }
        }
        _scan.value = result
        store.saveScan(result)
        calibration?.let { store.saveCalibration(it.snapshot()) }
        recordActivity(result.combos.flatMap { it.quotes })
        _status.value = result.finalStatus
        result
    }

    fun scanInBackground() {
        scope.launch { runCatching { scanNow() } }
    }

    private fun recordActivity(quotes: List<QuoteRecord>) {
        if (quotes.isEmpty()) return
        _activity.update { (it + quotes).distinctBy { q -> q.id }.sortedBy { q -> q.timestamp } }
        store.saveActivity(_activity.value)
    }

    fun combo(key: String): ComboResult? =
        _scan.value?.combos?.firstOrNull { it.candidate.key == key } ?: _longScan.value?.combos?.firstOrNull { it.candidate.key == key }

    /** Gets a fresh quote for the same legs, order and stake, and stores it with the combo. */
    suspend fun recheck(comboKey: String, entryOrder: List<String>, stakeCents: Int, purpose: QuotePurpose): Recheck {
        val legs = legsFor(comboKey, entryOrder) ?: return Recheck(null, null, "This combo is no longer in the latest scan.")
        val previous = latestQuote(comboKey, entryOrder, stakeCents)
        val provider = lastQuotes ?: parts().quotes.also { lastQuotes = it }
        return when (val o = provider.quote(comboKey, legs, stakeCents, purpose)) {
            is QuoteOutcome.Quoted -> {
                addQuote(o.record)
                Recheck(previous, o.record, null)
            }
            is QuoteOutcome.NoQuote -> Recheck(previous, null, o.reason)
            is QuoteOutcome.Failed -> Recheck(previous, null, o.message)
        }
    }

    /** Records a payout the user saw in the Kalshi app for a stake. */
    fun addManualQuote(comboKey: String, entryOrder: List<String>, stakeCents: Int, payoutCents: Int) {
        val contracts = payoutCents / 100.0
        val rec = QuoteRecord(
            id = UUID.randomUUID().toString(), comboKey = comboKey, entryOrder = entryOrder, comboMarketTicker = null,
            stakeCents = stakeCents, payoutCents = payoutCents, contracts = contracts,
            priceCents = if (contracts > 0) stakeCents.toDouble() / contracts else 0.0, feeCents = 0, costCents = stakeCents,
            timestamp = System.currentTimeMillis(), source = QuoteSource.MANUAL_APP, purpose = QuotePurpose.SAME_STAKE_RECHECK,
        )
        addQuote(rec)
    }

    private fun addQuote(q: QuoteRecord) {
        _scan.update { r ->
            r?.copy(combos = r.combos.map { c -> if (c.candidate.key == q.comboKey) c.copy(quotes = c.quotes + q) else c })
        }
        _scan.value?.let { store.saveScan(it) }
        _longScan.update { r ->
            r?.copy(combos = r.combos.map { c -> if (c.candidate.key == q.comboKey) c.copy(quotes = c.quotes + q) else c })
        }
        _longScan.value?.let { store.saveLongScan(it) }
        recordActivity(listOf(q))
    }

    fun latestQuote(comboKey: String, entryOrder: List<String>, stakeCents: Int): QuoteRecord? =
        combo(comboKey)?.quotes?.filter { it.entryOrder == entryOrder && it.stakeCents == stakeCents }?.maxByOrNull { it.timestamp }

    private fun legsFor(comboKey: String, order: List<String>): List<Leg>? {
        val c = combo(comboKey) ?: _tickets.value.firstOrNull { it.comboKey == comboKey }?.let { t ->
            return order.mapNotNull { id -> t.legs.firstOrNull { it.leg.id == id }?.leg }
        } ?: return null
        return order.map { id -> c.candidate.legs.first { it.leg.id == id }.leg }
    }

    /** Records a purchase. Repeat purchases of the same combo and order join the existing ticket. */
    fun markPurchased(comboKey: String, entryOrder: List<String>, stakeCents: Int, payoutCents: Int, source: FillSource, quote: QuoteRecord?) {
        val fill = Fill(UUID.randomUUID().toString(), stakeCents, payoutCents, System.currentTimeMillis(), source)
        _tickets.update { list ->
            val existing = list.firstOrNull { it.comboKey == comboKey && it.status == TicketStatus.OPEN && it.legs.map { l -> l.leg.id } == entryOrder }
            if (existing != null) list.map { if (it.id == existing.id) it.copy(fills = it.fills + fill) else it }
            else {
                val c = combo(comboKey) ?: return@update list
                val legs = entryOrder.map { id -> c.candidate.legs.first { it.leg.id == id } }
                list + Ticket(
                    id = UUID.randomUUID().toString(), comboKey = comboKey, comboMarketTicker = quote?.comboMarketTicker,
                    legs = legs, recommendation = quote, recommendedReturnPerDollar = quote?.let { c.expectedReturnPerDollar(it) },
                    jointFair = c.candidate.jointFair, fills = listOf(fill),
                )
            }
        }
        store.saveTickets(_tickets.value)
    }

    /**
     * Looks for confirmed fills on each open ticket's combo market and updates leg progress and settlement
     * from Kalshi's market results.
     */
    suspend fun refreshTickets(): String? {
        if (_settings.value.demoMode) return "Demo mode: ticket progress isn't checked against Kalshi."
        val client = client()
        val hasKey = store.kalshiCredentials() != null
        val updated = _tickets.value.map { t ->
            if (t.status != TicketStatus.OPEN) return@map t
            var ticket = t
            if (hasKey && t.comboMarketTicker != null) ticket = runCatching { confirmFills(client, ticket) }.getOrDefault(ticket)
            runCatching { updateLegs(client, ticket) }.getOrDefault(ticket)
        }
        _tickets.value = updated
        store.saveTickets(updated)
        return null
    }

    private suspend fun confirmFills(client: KalshiClient, t: Ticket): Ticket {
        val resp = client.get("/portfolio/fills", mapOf("ticker" to t.comboMarketTicker, "limit" to "100"))
        val known = t.fills.map { it.id }.toSet()
        val since = t.fills.minOf { it.timestamp } - 30 * 60_000
        val fills = (resp["fills"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val newFills = fills.mapNotNull { f ->
            val id = f.s("fill_id") ?: f.s("trade_id") ?: return@mapNotNull null
            if (id in known) return@mapNotNull null
            val time = f.s("created_time")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return@mapNotNull null
            if (time < since) return@mapNotNull null
            val count = (f.s("count_fp") ?: f.s("count"))?.toDoubleOrNull() ?: return@mapNotNull null
            val price = f.s("yes_price_dollars")?.toDoubleOrNull()?.times(100) ?: f.s("yes_price")?.toDoubleOrNull() ?: return@mapNotNull null
            val cost = kotlin.math.ceil(count * price).toInt() + fees.feeCents(count, price)
            Fill(id, cost, kotlin.math.round(count * 100).toInt(), time, FillSource.CONFIRMED)
        }
        if (newFills.isEmpty()) return t
        // A confirmed fill replaces the manual record it most likely corresponds to.
        val manual = t.fills.filter { it.source == FillSource.MANUAL }.toMutableList()
        repeat(minOf(manual.size, newFills.size)) { manual.removeAt(0) }
        return t.copy(fills = t.fills.filter { it.source == FillSource.CONFIRMED } + manual + newFills)
    }

    private suspend fun updateLegs(client: KalshiClient, t: Ticket): Ticket {
        val now = System.currentTimeMillis()
        val states = t.legs.associate { sl ->
            val m = runCatching { client.get("/markets/${sl.leg.marketTicker}")["market"] as? JsonObject }.getOrNull()
            val result = m?.s("result")
            val state = when {
                result == "yes" -> if (sl.leg.side.name == "YES") LegState.WON else LegState.LOST
                result == "no" -> if (sl.leg.side.name == "NO") LegState.WON else LegState.LOST
                result == "void" -> LegState.VOID
                (sl.startTime ?: Long.MAX_VALUE) <= now -> LegState.LIVE
                else -> LegState.UPCOMING
            }
            sl.leg.id to state
        }
        // Prefer the combo market's own result when Kalshi has settled it.
        val comboResult = t.comboMarketTicker?.let { ticker ->
            runCatching { (client.get("/markets/$ticker")["market"] as? JsonObject)?.s("result") }.getOrNull()
        }
        val status = when {
            comboResult == "yes" -> TicketStatus.WON
            comboResult == "no" -> TicketStatus.LOST
            comboResult == "void" -> TicketStatus.VOID
            states.values.any { it == LegState.LOST } -> TicketStatus.LOST
            states.values.all { it == LegState.VOID } -> TicketStatus.VOID
            states.values.all { it == LegState.WON || it == LegState.VOID } -> TicketStatus.WON
            else -> TicketStatus.OPEN
        }
        return t.copy(legStates = states, status = status, settledAt = if (status != TicketStatus.OPEN) (t.settledAt ?: now) else null)
    }

    fun deleteTicket(id: String) {
        _tickets.update { it.filterNot { t -> t.id == id } }
        store.saveTickets(_tickets.value)
    }

    fun saveKeys(kalshiKeyId: String?, kalshiPem: String?, oddsKey: String?) {
        store.saveSecrets(kalshiKeyId, kalshiPem, oddsKey)
        lastQuotes = null
        calibration = null
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it != "null" }

    companion object {
        fun friendly(e: Throwable): String = when (e) {
            is KalshiApiException -> when (e.status) {
                401, 403 -> "Kalshi rejected the API key. Check it in Settings."
                429 -> "Kalshi rate limit reached. Try again in a minute."
                else -> e.message ?: "Kalshi error ${e.status}"
            }
            is java.net.UnknownHostException -> "No internet connection."
            else -> e.message ?: e.javaClass.simpleName
        }
    }
}

/** Used when no Kalshi API key is saved: legs and combos still rank, quotes explain what's missing. */
private object NoKeyQuotes : QuoteProvider {
    override val source = QuoteSource.API_RFQ
    override suspend fun isOrderSensitive(collectionTicker: String): Boolean? = null
    override suspend fun quote(comboKey: String, legsInOrder: List<Leg>, stakeCents: Int, purpose: QuotePurpose) =
        QuoteOutcome.Failed("Add your Kalshi API key in Settings to get RFQ quotes, or enter quotes from the Kalshi app by hand.", retryable = false)
}
