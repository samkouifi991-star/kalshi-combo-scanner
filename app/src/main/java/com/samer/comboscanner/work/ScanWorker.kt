package com.samer.comboscanner.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.samer.comboscanner.MainActivity
import com.samer.comboscanner.R
import com.samer.comboscanner.ScannerApp
import com.samer.comboscanner.data.AppSettings
import com.samer.comboscanner.ui.Fmt
import java.util.concurrent.TimeUnit

/** Periodic background scan that alerts on newly verified opportunities above the user's threshold. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ScannerApp
        val repo = app.repo
        val settings = repo.settings.value
        if (!settings.alertsEnabled) return Result.success()
        val result = repo.scanNow() ?: return Result.retry()
        val lead = result.leader ?: return Result.success()
        val best = lead.best ?: return Result.success()
        val ret = lead.expectedReturnPerDollar(best)
        if (!lead.verified || ret * 100 < settings.alertMinReturnPct) return Result.success()

        // Don't alert twice for the same combo, stake and payout.
        val prefs = applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val signature = "${lead.candidate.key}|${best.stakeCents}|${best.payoutCents}"
        if (prefs.getString("last", null) == signature) return Result.success()
        prefs.edit().putString("last", signature).apply()

        notify(applicationContext, lead.candidate.key, best.stakeCents, best.payoutCents, ret, lead.candidate.size)
        return Result.success()
    }

    companion object {
        private const val NAME = "background-scan"

        fun schedule(context: Context, s: AppSettings) {
            val wm = WorkManager.getInstance(context)
            if (!s.alertsEnabled) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val req = PeriodicWorkRequestBuilder<ScanWorker>(s.alertIntervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun notify(context: Context, comboKey: String, stakeCents: Int, payoutCents: Int, ret: Double, legs: Int) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                android.os.Build.VERSION.SDK_INT >= 33
            ) return
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_COMBO_KEY, comboKey)
                putExtra(MainActivity.EXTRA_REFRESH, true)
            }
            val pi = PendingIntent.getActivity(context, comboKey.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, ScannerApp.ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle("$legs-leg combo · ${Fmt.signedPct(ret)} est. return")
                .setContentText("${Fmt.money(stakeCents)} pays ${Fmt.money(payoutCents)} · verified RFQ quote. Tap to refresh.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(comboKey.hashCode(), n)
        }
    }
}
