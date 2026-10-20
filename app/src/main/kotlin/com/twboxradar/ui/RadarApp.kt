package com.twboxradar.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.twboxradar.BuildConfig
import com.twboxradar.R
import com.twboxradar.ads.AdsController
import com.twboxradar.ads.BannerAd
import com.twboxradar.core.DISCLAIMER

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadarApp(viewModel: RadarViewModel, ads: AdsController, onRequestNotifications: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val canRequest by ads.canRequestAds.collectAsStateWithLifecycle()
    val owned by ads.owned.collectAsStateWithLifecycle()
    val debugRemoved by ads.debugRemoved.collectAsStateWithLifecycle()
    val showAds = canRequest && !owned && !(BuildConfig.DEBUG && debugRemoved)
    RadarTheme {
        if (!state.disclaimerAccepted) {
            DisclaimerScreen(onAccept = {
                viewModel.acceptDisclaimer()
                ads.onDisclaimerAccepted()
            })
            return@RadarTheme
        }
        val route = state.route
        val canBack = route is Route.Detail || route is Route.Backtest || route is Route.RemoveAds
        BackHandler(enabled = canBack) { viewModel.back() }
        val title = when (route) {
            Route.Lists -> "選股"
            Route.Screener -> "條件"
            Route.Watchlist -> "自選"
            Route.Settings -> "設定"
            is Route.Detail -> {
                val symbol = state.symbols.firstOrNull { it.code == route.code }
                if (symbol == null) "個股" else "${symbol.name} ${symbol.code}"
            }
            is Route.Backtest -> "回測"
            Route.RemoveAds -> "移除廣告"
        }
        val showNav = route is Route.Lists ||
            route is Route.Screener ||
            route is Route.Watchlist ||
            route is Route.Settings
        val activity = LocalContext.current as Activity
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            // SMCPKG_SUPPORT>>>CursorXXX
                            // 返回以前是底部的文字按鈕。子畫面改到左上角的箭頭。
                            if (canBack) {
                                IconButton(onClick = viewModel::back) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                            }
                            // SMCPKG_SUPPORT<<<CursorYYY
                        },
                        actions = {
                            IconButton(onClick = viewModel::openRemoveAds) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_remove_ads),
                                    contentDescription = "移除廣告",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        },
                    )
                    BannerAd(enabled = showAds)
                }
            },
            bottomBar = {
                Column {
                    Text(
                        DISCLAIMER,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (showNav) {
                        NavigationBar {
                            // SMCPKG_SUPPORT>>>CursorXXX
                            // BarItem("選股", state.route is Route.Lists) { viewModel.openTab(Route.Lists) }
                            // 四個項目以前共用啟動圖示。那個向量是整面色塊，被導覽列著色後變成大矩形。
                            BarItem("選股", R.drawable.ic_nav_lists, route is Route.Lists) { viewModel.openTab(Route.Lists) }
                            BarItem("條件", R.drawable.ic_nav_conditions, route is Route.Screener) { viewModel.openTab(Route.Screener) }
                            BarItem("自選", R.drawable.ic_nav_star, route is Route.Watchlist) { viewModel.openTab(Route.Watchlist) }
                            BarItem("設定", R.drawable.ic_nav_settings, route is Route.Settings) { viewModel.openTab(Route.Settings) }
                            // SMCPKG_SUPPORT<<<CursorYYY
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (route) {
                    Route.Lists -> ListScreen(
                        state,
                        onOpen = viewModel::openDetail,
                        onRetry = { viewModel.refresh(true) },
                        showAds = showAds,
                    )
                    Route.Screener -> ConditionScreen(
                        state,
                        viewModel::toggleCondition,
                        viewModel::runConditions,
                        viewModel::openDetail,
                        onRetry = { viewModel.refresh(true) },
                        showAds = showAds,
                    )
                    Route.Watchlist -> WatchScreen(
                        state,
                        viewModel::openDetail,
                        onRetry = { viewModel.refresh(true) },
                        showAds = showAds,
                    )
                    Route.Settings -> SettingsScreen(
                        state,
                        viewModel::updateParams,
                        viewModel::setAlerts,
                        { viewModel.refresh(true) },
                        onRequestNotifications,
                        viewModel::setDataBranch,
                    )
                    is Route.Detail -> DetailScreen(
                        state,
                        route.code,
                        { viewModel.toggleWatch(route.code) },
                        { viewModel.openBacktest(route.code) },
                    )
                    is Route.Backtest -> BacktestScreen(state, route.code)
                    Route.RemoveAds -> RemoveAdsScreen(ads, activity)
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
