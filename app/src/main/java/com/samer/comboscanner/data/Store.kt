package com.samer.comboscanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.samer.comboscanner.engine.QuoteRecord
import com.samer.comboscanner.engine.ScanResult
import com.samer.comboscanner.engine.kalshi.KalshiCredentials
import com.samer.comboscanner.engine.kalshi.SettledPoint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** JSON files in app-private storage, plus encrypted preferences for API keys. */
class Store(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; allowSpecialFloatingPointValues = true }

    private fun <T> read(name: String, ser: KSerializer<T>): T? = runCatching {
        val f = File(context.filesDir, name)
        if (f.exists()) json.decodeFromString(ser, f.readText()) else null
    }.getOrNull()

    private fun <T> write(name: String, ser: KSerializer<T>, value: T) {
        val f = File(context.filesDir, "$name.tmp")
        f.writeText(json.encodeToString(ser, value))
        f.renameTo(File(context.filesDir, name))
    }

    fun loadSettings() = read("settings.json", AppSettings.serializer()) ?: AppSettings()
    fun saveSettings(s: AppSettings) = write("settings.json", AppSettings.serializer(), s)

    fun loadScan() = read("last_scan.json", ScanResult.serializer())
    fun saveScan(r: ScanResult) = write("last_scan.json", ScanResult.serializer(), r)

    fun loadTickets() = read("tickets.json", ListSerializer(Ticket.serializer())) ?: emptyList()
    fun saveTickets(t: List<Ticket>) = write("tickets.json", ListSerializer(Ticket.serializer()), t)

    /** Every quote seen, purchased or not, newest last. Capped so the file stays small. */
    fun loadActivity() = read("scan_activity.json", ListSerializer(QuoteRecord.serializer())) ?: emptyList()
    fun saveActivity(q: List<QuoteRecord>) = write("scan_activity.json", ListSerializer(QuoteRecord.serializer()), q.takeLast(3000))

    private val calSer = MapSerializer(String.serializer(), PairSerializer(Long.serializer(), ListSerializer(SettledPoint.serializer())))
    fun loadCalibration() = read("calibration.json", calSer) ?: emptyMap()
    fun saveCalibration(m: Map<String, Pair<Long, List<SettledPoint>>>) = write("calibration.json", calSer, m)

    private val secrets: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secrets", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun kalshiCredentials(): KalshiCredentials? {
        val id = secrets.getString("kalshi_key_id", null)
        val pem = secrets.getString("kalshi_pem", null)
        return if (id.isNullOrBlank() || pem.isNullOrBlank()) null else KalshiCredentials(id, pem)
    }

    fun oddsApiKey(): String? = secrets.getString("odds_key", null)?.takeIf { it.isNotBlank() }

    fun saveSecrets(kalshiKeyId: String?, kalshiPem: String?, oddsKey: String?) {
        secrets.edit().apply {
            kalshiKeyId?.let { putString("kalshi_key_id", it.trim()) }
            kalshiPem?.let { putString("kalshi_pem", it.trim()) }
            oddsKey?.let { putString("odds_key", it.trim()) }
        }.apply()
    }
}
