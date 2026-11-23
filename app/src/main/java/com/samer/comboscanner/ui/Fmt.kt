package com.samer.comboscanner.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** Number and time formatting shared across screens, so labels stay consistent. */
object Fmt {
    fun money(cents: Int): String = money(cents.toDouble())
    fun money(cents: Double): String = "$" + String.format(Locale.US, "%,.2f", cents / 100.0)
    fun signedMoney(cents: Double): String = (if (cents >= 0) "+" else "−") + money(abs(cents))
    fun pct(p: Double, digits: Int = 0): String = String.format(Locale.US, "%.${digits}f%%", p * 100)
    fun signedPct(r: Double, digits: Int = 1): String = (if (r >= 0) "+" else "−") + String.format(Locale.US, "%.${digits}f%%", abs(r) * 100)
    fun pp(v: Double): String = (if (v >= 0) "+" else "−") + String.format(Locale.US, "%.1f", abs(v)) + " pp"
    fun mult(m: Double): String = String.format(Locale.US, "%.2fx", m)
    fun cents(c: Int): String = "$c¢"
    fun cents(c: Double): String = String.format(Locale.US, "%.1f¢", c)

    fun age(ms: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - ms) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s seconds ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ago"
            else -> "${s / 86_400} d ago"
        }
    }

    private val timeFmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val clockFmt = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
    fun start(ms: Long?): String = ms?.let { timeFmt.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: "Start time unknown"
    fun date(ms: Long): String = dateFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
    fun clock(ms: Long): String = clockFmt.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
}
