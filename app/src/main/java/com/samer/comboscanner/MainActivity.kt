package com.samer.comboscanner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.samer.comboscanner.ui.ComboDetailScreen
import com.samer.comboscanner.ui.CombosScreen
import com.samer.comboscanner.ui.HistoryScreen
import com.samer.comboscanner.ui.LegDetailScreen
import com.samer.comboscanner.ui.LocalNow
import com.samer.comboscanner.ui.MainViewModel
import com.samer.comboscanner.ui.MarketsScreen
import com.samer.comboscanner.ui.OpenScreen
import com.samer.comboscanner.ui.ScanActivityScreen
import com.samer.comboscanner.ui.ScannerTheme
import com.samer.comboscanner.ui.SettingsScreen
import com.samer.comboscanner.ui.TicketDetailScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    /** Combo to open from an alert, consumed by the nav host. */
    private val pendingCombo = mutableStateOf<Pair<String, Boolean>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { ScannerTheme { AppRoot(vm, pendingCombo.value) { pendingCombo.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_COMBO_KEY) ?: return
        pendingCombo.value = key to intent.getBooleanExtra(EXTRA_REFRESH, false)
    }

    companion object {
        const val EXTRA_COMBO_KEY = "combo_key"
        const val EXTRA_REFRESH = "refresh"
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    Markets("markets", "Markets", Icons.Filled.ShowChart),
    Combos("combos", "Combos", Icons.Filled.Layers),
    Open("open", "Open", Icons.Filled.ReceiptLong),
    History("history", "History", Icons.Filled.History),
}

object Routes {
    fun leg(id: String) = "leg/${Uri.encode(id)}"
    fun combo(key: String, refresh: Boolean = false) = "combo/${Uri.encode(key)}?refresh=$refresh"
    fun combosForLeg(id: String) = "combos?leg=${Uri.encode(id)}"
    fun ticket(id: String) = "ticket/${Uri.encode(id)}"
    const val SETTINGS = "settings"
    const val ACTIVITY = "activity"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(vm: MainViewModel, pending: Pair<String, Boolean>?, consumePending: () -> Unit) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: Tab.Markets.route
    val tab = Tab.entries.firstOrNull { route.startsWith(it.route) }
    val isTabRoot = tab != null && (route == tab.route || route.startsWith("combos"))

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    LaunchedEffect(pending) {
        pending?.let { (key, refresh) -> nav.navigate(Routes.combo(key, refresh)); consumePending() }
    }

    CompositionLocalProvider(LocalNow provides now) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(titleFor(route)) },
                    navigationIcon = {
                        if (!isTabRoot) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    actions = {
                        if (route != Routes.SETTINGS) IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Filled.Settings, "Scan settings") }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { nav.goTab(t.route) },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(nav, startDestination = Tab.Markets.route, modifier = Modifier.padding(padding)) {
                composable(Tab.Markets.route) {
                    MarketsScreen(vm, onLeg = { nav.navigate(Routes.leg(it)) }, onActivity = { nav.navigate(Routes.ACTIVITY) })
                }
                composable(
                    "combos?leg={leg}",
                    arguments = listOf(navArgument("leg") { type = NavType.StringType; nullable = true; defaultValue = null }),
                ) { e ->
                    CombosScreen(
                        vm, legFilter = e.arguments?.getString("leg")?.let(Uri::decode),
                        onCombo = { nav.navigate(Routes.combo(it)) },
                        onActivity = { nav.navigate(Routes.ACTIVITY) },
                        onClearLeg = { nav.goTab(Tab.Combos.route) },
                    )
                }
                composable(Tab.Open.route) { OpenScreen(vm, onTicket = { nav.navigate(Routes.ticket(it)) }) }
                composable(Tab.History.route) { HistoryScreen(vm, onTicket = { nav.navigate(Routes.ticket(it)) }, onActivity = { nav.navigate(Routes.ACTIVITY) }) }
                composable("leg/{id}") { e ->
                    LegDetailScreen(vm, Uri.decode(e.arguments?.getString("id") ?: ""), onCombos = { nav.navigate(Routes.combosForLeg(it)) })
                }
                composable(
                    "combo/{key}?refresh={refresh}",
                    arguments = listOf(navArgument("refresh") { type = NavType.StringType; defaultValue = "false" }),
                ) { e ->
                    ComboDetailScreen(
                        vm, Uri.decode(e.arguments?.getString("key") ?: ""),
                        refreshOnOpen = e.arguments?.getString("refresh") == "true",
                        onOpenTicket = { nav.navigate(Routes.ticket(it)) },
                    )
                }
                composable("ticket/{id}") { e -> TicketDetailScreen(vm, Uri.decode(e.arguments?.getString("id") ?: "")) }
                composable(Routes.SETTINGS) { SettingsScreen(vm) }
                composable(Routes.ACTIVITY) { ScanActivityScreen(vm) }
            }
        }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun titleFor(route: String) = when {
    route == "markets" -> "Markets"
    route.startsWith("combos") -> "Combos"
    route == "open" -> "Open"
    route == "history" -> "History"
    route.startsWith("leg/") -> "Leg pricing"
    route.startsWith("combo/") -> "Combo details"
    route.startsWith("ticket/") -> "Ticket"
    route == Routes.SETTINGS -> "Scan settings & alerts"
    route == Routes.ACTIVITY -> "Scan activity"
    else -> "Combo Scanner"
}
