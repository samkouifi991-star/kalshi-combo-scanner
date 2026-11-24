package com.samer.comboscanner.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.samer.comboscanner.data.AppSettings

private val allSports = listOf("NFL", "NBA", "NHL", "Soccer", "MLB", "College football", "College basketball", "WNBA", "Tennis", "MMA", "Golf", "Other")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    fun set(f: (AppSettings) -> AppSettings) = vm.updateSettings(f)
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section("Scan")
        Text("Minimum leg probability: ${Fmt.pct(s.minProbability)}")
        Slider(value = s.minProbability.toFloat(), onValueChange = { v -> set { it.copy(minProbability = (v * 100).toInt() / 100.0) } }, valueRange = 0.5f..0.99f)
        Stepper("Minimum edge after fees", Fmt.pp(s.minEdgePp), { set { it.copy(minEdgePp = (it.minEdgePp - 0.5).coerceAtLeast(0.0)) } }, { set { it.copy(minEdgePp = it.minEdgePp + 0.5) } })
        Text("Sports", Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = s.sports.isEmpty(), onClick = { set { it.copy(sports = emptySet()) } }, label = { Text("All") })
            allSports.forEach { sp ->
                FilterChip(selected = sp in s.sports, onClick = { set { it.copy(sports = if (sp in it.sports) it.sports - sp else it.sports + sp) } }, label = { Text(sp) })
            }
        }
        Text("Combo length: ${s.minLegs}–${s.maxLegs} legs", Modifier.padding(top = 8.dp))
        RangeSlider(
            value = s.minLegs.toFloat()..s.maxLegs.toFloat(),
            onValueChange = { r -> set { it.copy(minLegs = r.start.toInt(), maxLegs = r.endInclusive.toInt().coerceAtLeast(r.start.toInt())) } },
            valueRange = 4f..7f, steps = 2,
        )
        Stepper("Lowest stake", "$${s.stakeMinDollars}", { set { it.copy(stakeMinDollars = (it.stakeMinDollars - 1).coerceAtLeast(1)) } }, { set { it.copy(stakeMinDollars = (it.stakeMinDollars + 1).coerceAtMost(it.stakeMaxDollars)) } })
        Stepper("Highest stake", "$${s.stakeMaxDollars}", { set { it.copy(stakeMaxDollars = (it.stakeMaxDollars - 1).coerceAtLeast(it.stakeMinDollars)) } }, { set { it.copy(stakeMaxDollars = it.stakeMaxDollars + 1) } })
        Stepper("Dollar increment", "$${s.stakeIncrementDollars}", { set { it.copy(stakeIncrementDollars = (it.stakeIncrementDollars - 1).coerceAtLeast(1)) } }, { set { it.copy(stakeIncrementDollars = it.stakeIncrementDollars + 1) } })
        Toggle("Also test $1 above each step ($5 and $6)", s.testNeighborStakes) { v -> set { it.copy(testNeighborStakes = v) } }
        Stepper("Quote budget per scan", "${s.quoteBudget}", { set { it.copy(quoteBudget = (it.quoteBudget - 5).coerceAtLeast(5)) } }, { set { it.copy(quoteBudget = (it.quoteBudget + 5).coerceAtMost(90)) } })
        Text("Kalshi allows 100 open RFQs at once. The app closes each RFQ after reading it.", style = MaterialTheme.typography.labelSmall)
        Text("Stakes tested: " + s.toScanSettings().stakesToTest().joinToString { Fmt.money(it).removeSuffix(".00") }, style = MaterialTheme.typography.labelSmall)

        Section("Long tab")
        Stepper("Lowest leg price", "${s.longMinAskCents}¢", { set { it.copy(longMinAskCents = (it.longMinAskCents - 1).coerceAtLeast(80)) } }, { set { it.copy(longMinAskCents = (it.longMinAskCents + 1).coerceAtMost(99)) } })
        Stepper("Fewest legs", "${s.longMinLegs}", { set { it.copy(longMinLegs = (it.longMinLegs - 10).coerceAtLeast(20)) } }, { set { it.copy(longMinLegs = (it.longMinLegs + 10).coerceAtMost(it.longMaxLegs)) } })
        Stepper("Most legs", "${s.longMaxLegs}", { set { it.copy(longMaxLegs = (it.longMaxLegs - 10).coerceAtLeast(it.longMinLegs)) } }, { set { it.copy(longMaxLegs = it.longMaxLegs + 10) } })
        Stepper("Minimum leg edge", Fmt.pp(s.longMinEdgePp), { set { it.copy(longMinEdgePp = it.longMinEdgePp - 0.5) } }, { set { it.copy(longMinEdgePp = it.longMinEdgePp + 0.5) } })
        if (s.longMinEdgePp < 0) Text("Legs with negative estimated edge make the whole combo worse.", style = MaterialTheme.typography.labelSmall, color = Warning)
        Stepper("Quote budget per long scan", "${s.longQuoteBudget}", { set { it.copy(longQuoteBudget = (it.longQuoteBudget - 2).coerceAtLeast(4)) } }, { set { it.copy(longQuoteBudget = (it.longQuoteBudget + 2).coerceAtMost(40)) } })
        Text("Kalshi caps how many legs a combo can hold; the app uses each collection's limit.", style = MaterialTheme.typography.labelSmall)

        Section("Alerts")
        Toggle("Alert me about newly verified opportunities", s.alertsEnabled) { v ->
            if (v && Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            set { it.copy(alertsEnabled = v) }
        }
        Stepper("Minimum estimated return", Fmt.signedPct(s.alertMinReturnPct / 100, 0), { set { it.copy(alertMinReturnPct = (it.alertMinReturnPct - 1).coerceAtLeast(0.0)) } }, { set { it.copy(alertMinReturnPct = it.alertMinReturnPct + 1) } })
        Stepper("Background scan every", "${s.alertIntervalMinutes} min", { set { it.copy(alertIntervalMinutes = (it.alertIntervalMinutes - 15).coerceAtLeast(15)) } }, { set { it.copy(alertIntervalMinutes = it.alertIntervalMinutes + 15) } })
        Text("Only rechecked quotes trigger an alert. Each alert opens that combo and refreshes its quote.", style = MaterialTheme.typography.labelSmall)

        Section("Data sources")
        Toggle("Demo data (no keys needed)", s.demoMode) { v -> set { it.copy(demoMode = v) } }
        Toggle("Use Kalshi demo exchange", s.useDemoExchange) { v -> set { it.copy(useDemoExchange = v) } }
        Toggle("Historical calibration from settled Kalshi markets", s.useHistorical) { v -> set { it.copy(useHistorical = v) } }
        Stepper("Taker fee rate", String.format("%.3f", s.takerFeeRate), { set { it.copy(takerFeeRate = (it.takerFeeRate - 0.005).coerceAtLeast(0.0)) } }, { set { it.copy(takerFeeRate = it.takerFeeRate + 0.005) } })
        Stepper("Quote counts as fresh for", "${s.quoteFreshSeconds} s", { set { it.copy(quoteFreshSeconds = (it.quoteFreshSeconds - 30).coerceAtLeast(30)) } }, { set { it.copy(quoteFreshSeconds = it.quoteFreshSeconds + 30) } })
        Keys(vm)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Keys(vm: MainViewModel) {
    var keyId by remember { mutableStateOf("") }
    var pem by remember { mutableStateOf("") }
    var odds by remember { mutableStateOf("") }
    Text(
        "Kalshi key: " + (if (vm.repo.hasKalshiKey()) "saved" else "not set") + " · Odds key: " + (if (vm.repo.hasOddsKey()) "saved" else "not set"),
        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(keyId, { keyId = it }, label = { Text("Kalshi API key ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(pem, { pem = it }, label = { Text("Kalshi private key (PEM)") }, modifier = Modifier.fillMaxWidth().height(120.dp), visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(odds, { odds = it }, label = { Text("The Odds API key (external prices)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
    Button(
        onClick = {
            vm.saveKeys(keyId.ifBlank { null }, pem.ifBlank { null }, odds.ifBlank { null })
            keyId = ""; pem = ""; odds = ""
        },
        modifier = Modifier.padding(top = 8.dp),
    ) { Text("Save keys") }
    Text(
        "Keys stay encrypted on this phone and are only sent to Kalshi and The Odds API. Create a Kalshi key under Account → API keys. Turn off Demo data to scan real markets.",
        style = MaterialTheme.typography.labelSmall,
    )
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, "Less") }
        Text(value, style = MaterialTheme.typography.titleSmall)
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, "More") }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
