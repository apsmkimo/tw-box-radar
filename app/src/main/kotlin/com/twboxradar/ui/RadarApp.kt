package com.twboxradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
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
                            // SMCPKG_SUPPORT>>>CursorXXX
                            // BarItem("選股", state.route is Route.Lists) { viewModel.openTab(Route.Lists) }
                            // 四個項目以前共用啟動圖示。那個向量是整面色塊，被導覽列著色後變成大矩形。
                            BarItem("選股", R.drawable.ic_nav_lists, state.route is Route.Lists) { viewModel.openTab(Route.Lists) }
                            BarItem("條件", R.drawable.ic_nav_conditions, state.route is Route.Screener) { viewModel.openTab(Route.Screener) }
                            BarItem("自選", R.drawable.ic_nav_star, state.route is Route.Watchlist) { viewModel.openTab(Route.Watchlist) }
                            BarItem("設定", R.drawable.ic_nav_settings, state.route is Route.Settings) { viewModel.openTab(Route.Settings) }
                            // SMCPKG_SUPPORT<<<CursorYYY
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
                    Route.Lists -> ListScreen(state, onOpen = viewModel::openDetail, onRetry = { viewModel.refresh(true) })
                    Route.Screener -> ConditionScreen(
                        state,
                        viewModel::toggleCondition,
                        viewModel::runConditions,
                        viewModel::openDetail,
                        onRetry = { viewModel.refresh(true) },
                    )
                    Route.Watchlist -> WatchScreen(state, viewModel::openDetail, onRetry = { viewModel.refresh(true) })
                    Route.Settings -> SettingsScreen(
                        state,
                        viewModel::updateParams,
                        viewModel::setAlerts,
                        { viewModel.refresh(true) },
                        onRequestNotifications,
                        viewModel::setDataBranch,
                    )
                    is Route.Detail -> DetailScreen(state, route.code, { viewModel.toggleWatch(route.code) }, { viewModel.openBacktest(route.code) })
                    is Route.Backtest -> BacktestScreen(state, route.code)
                }
            }
        }
    }
}

@Composable
private fun RowScope.BarItem(label: String, icon: Int, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                painter = painterResource(icon),
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        },
        label = { Text(label) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Color(0xFF1565C0),
            selectedTextColor = Color(0xFF1565C0),
            unselectedIconColor = Color(0xFF546E7A),
            unselectedTextColor = Color(0xFF546E7A),
            indicatorColor = Color(0xFFD6E6F8),
        ),
    )
}
