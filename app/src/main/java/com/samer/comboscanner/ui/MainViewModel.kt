package com.samer.comboscanner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.samer.comboscanner.ScannerApp
import com.samer.comboscanner.data.AppSettings
import com.samer.comboscanner.data.FillSource
import com.samer.comboscanner.data.Recheck
import com.samer.comboscanner.engine.QuotePurpose
import com.samer.comboscanner.engine.QuoteRecord
import com.samer.comboscanner.work.ScanWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val repo = (app as ScannerApp).repo
    val settings = repo.settings
    val status = repo.status
    val scan = repo.scan
    val tickets = repo.tickets
    val activity = repo.activity
    val warnings = repo.warnings

    /** Keys of quotes being refreshed right now, so cards can show a spinner. */
    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun scanNow() = repo.scanInBackground()

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        repo.updateSettings(f)
        ScanWorker.schedule(getApplication(), repo.settings.value)
    }

    fun refreshQuote(comboKey: String, order: List<String>, stakeCents: Int, purpose: QuotePurpose = QuotePurpose.SAME_STAKE_RECHECK, onDone: (Recheck) -> Unit = {}) {
        val k = "$comboKey@$stakeCents"
        _refreshing.value = _refreshing.value + k
        viewModelScope.launch {
            val r = runCatching { repo.recheck(comboKey, order, stakeCents, purpose) }
                .getOrElse { com.samer.comboscanner.data.Recheck(null, null, com.samer.comboscanner.data.ScannerRepository.friendly(it)) }
            _refreshing.value = _refreshing.value - k
            r.message?.let { _message.value = it }
            onDone(r)
        }
    }

    fun addManualQuote(comboKey: String, order: List<String>, stakeCents: Int, payoutCents: Int) =
        repo.addManualQuote(comboKey, order, stakeCents, payoutCents)

    fun markPurchased(comboKey: String, order: List<String>, stakeCents: Int, payoutCents: Int, quote: QuoteRecord?) {
        repo.markPurchased(comboKey, order, stakeCents, payoutCents, FillSource.MANUAL, quote)
        _message.value = "Recorded as a manual fill. It will switch to Confirmed fill once Kalshi shows it in your fills."
    }

    fun refreshTickets() = viewModelScope.launch { repo.refreshTickets()?.let { _message.value = it } }

    fun deleteTicket(id: String) = repo.deleteTicket(id)

    fun saveKeys(kalshiKeyId: String?, pem: String?, odds: String?) {
        repo.saveKeys(kalshiKeyId, pem, odds)
        _message.value = "Keys saved on this phone (encrypted)."
    }
}
