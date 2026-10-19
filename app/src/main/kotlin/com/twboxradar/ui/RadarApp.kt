package com.twboxradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twboxradar.R
import com.twboxradar.core.DISCLAIMER

@Composable
fun RadarApp(viewModel: RadarViewModel, onRequestNotifications: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RadarTheme {
        if (!state.disclaimerAccepted) {
            DisclaimerScreen(onAccept = viewModel::acceptDisclaimer)
            return@RadarTheme
        }
        val showNav = state.route is Route.Lists ||
            state.route is Route.Screener ||
            state.route is Route.Watchlist ||
            state.route is Route.Settings
        Scaffold(
            bottomBar = {
                Column {
                    Text(
                        DISCLAIMER,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    )
                    if (showNav) {
                        NavigationBar {
                            BarItem("選股", state.route is Route.Lists) { viewModel.openTab(Route.Lists) }
                            BarItem("條件", state.route is Route.Screener) { viewModel.openTab(Route.Screener) }
                            BarItem("自選", state.route is Route.Watchlist) { viewModel.openTab(Route.Watchlist) }
                            BarItem("設定", state.route is Route.Settings) { viewModel.openTab(Route.Settings) }
                        }
                    } else {
                        TextButton(onClick = viewModel::back, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text("返回")
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (val route = state.route) {
                    Route.Lists -> ListScreen(state, onOpen = viewModel::openDetail)
                    Route.Screener -> ConditionScreen(state, viewModel::toggleCondition, viewModel::runConditions, viewModel::openDetail)
                    Route.Watchlist -> WatchScreen(state, viewModel::openDetail)
                    Route.Settings -> SettingsScreen(state, viewModel::updateParams, viewModel::setAlerts, { viewModel.refresh(true) }, onRequestNotifications)
                    is Route.Detail -> DetailScreen(state, route.code, { viewModel.toggleWatch(route.code) }, { viewModel.openBacktest(route.code) })
                    is Route.Backtest -> BacktestScreen(state, route.code)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(imageVector = ImageVector.vectorResource(R.drawable.ic_launcher), contentDescription = label) },
        label = { Text(label) },
    )
}
