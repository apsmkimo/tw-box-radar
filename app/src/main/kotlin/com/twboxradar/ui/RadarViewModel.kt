package com.twboxradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.twboxradar.core.BacktestResult
import com.twboxradar.core.BoxParams
import com.twboxradar.core.BoxStatus
import com.twboxradar.core.ScreenRow
import com.twboxradar.core.SymbolHistory
import com.twboxradar.core.analyze
import com.twboxradar.core.backtest
import com.twboxradar.core.conditionCatalog
import com.twboxradar.core.matchesAll
import com.twboxradar.data.MarketRepository
import com.twboxradar.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Route {
    data object Lists : Route
    data object Screener : Route
    data object Watchlist : Route
    data object Settings : Route
    data class Detail(val code: String) : Route
    data class Backtest(val code: String) : Route
}

data class UiState(
    val loading: Boolean = true,
    val message: String? = null,
    val sample: Boolean = false,
    val asOf: String = "",
    val symbols: List<SymbolHistory> = emptyList(),
    val rows: List<ScreenRow> = emptyList(),
    val params: BoxParams = BoxParams(),
    val watchlist: Set<String> = emptySet(),
    val disclaimerAccepted: Boolean = false,
    val alertsEnabled: Boolean = true,
    val route: Route = Route.Lists,
    val selectedConditions: Set<String> = emptySet(),
    val conditionHits: List<ScreenRow>? = null,
    val detailLoading: Boolean = false,
    val backtest: BacktestResult? = null,
    val backtestLoading: Boolean = false,
    val dataBranch: String = "",
)

class RadarViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val repository = MarketRepository(app)
    private val backStack = ArrayDeque<Route>()
    private val _state = MutableStateFlow(
        UiState(
            params = prefs.boxParams(),
            watchlist = prefs.watchlist,
            disclaimerAccepted = prefs.disclaimerAccepted,
            alertsEnabled = prefs.alertsEnabled,
            dataBranch = prefs.dataBranch,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // SMCPKG_SUPPORT>>>CursorXXX
            // val loaded = repository.loadMarket(force)
            val loaded = repository.loadMarket(force, _state.value.dataBranch)
            // SMCPKG_SUPPORT<<<CursorYYY
            val params = _state.value.params
            val rows = withContext(Dispatchers.Default) { loaded.symbols.map { analyze(it, params) } }
            _state.update {
                it.copy(
                    loading = false,
                    message = loaded.message,
                    sample = loaded.sample,
                    asOf = loaded.asOf,
                    symbols = loaded.symbols,
                    rows = rows,
                    conditionHits = null,
                )
            }
        }
    }

    fun acceptDisclaimer() {
        prefs.disclaimerAccepted = true
        _state.update { it.copy(disclaimerAccepted = true) }
    }

    fun openTab(route: Route) {
        backStack.clear()
        _state.update { it.copy(route = route, backtest = null) }
    }

    fun openDetail(code: String) {
        push(Route.Detail(code))
        viewModelScope.launch {
            _state.update { it.copy(detailLoading = true) }
            val current = _state.value.symbols.firstOrNull { it.code == code }
            // SMCPKG_SUPPORT>>>CursorXXX
            // val full = repository.loadHistory(code, current)
            val full = repository.loadHistory(code, current, _state.value.dataBranch)
            // SMCPKG_SUPPORT<<<CursorYYY
            if (full != null) {
                val params = _state.value.params
                val replaced = _state.value.symbols.map { if (it.code == code) full else it }
                val rows = withContext(Dispatchers.Default) {
                    replaced.map { symbol -> analyze(symbol, params) }
                }
                _state.update { it.copy(symbols = replaced, rows = rows, detailLoading = false) }
            } else {
                _state.update { it.copy(detailLoading = false) }
            }
        }
    }

    fun openBacktest(code: String) {
        push(Route.Backtest(code))
        viewModelScope.launch {
            _state.update { it.copy(backtestLoading = true, backtest = null) }
            val symbol = _state.value.symbols.firstOrNull { it.code == code } ?: return@launch
            val result = withContext(Dispatchers.Default) { backtest(symbol.bars, _state.value.params) }
            _state.update { it.copy(backtestLoading = false, backtest = result) }
        }
    }

    fun back() {
        val previous = if (backStack.isEmpty()) Route.Lists else backStack.removeLast()
        _state.update { it.copy(route = previous) }
    }

    fun toggleWatch(code: String) {
        val next = _state.value.watchlist.toMutableSet()
        if (!next.add(code)) next.remove(code)
        prefs.watchlist = next
        _state.update { it.copy(watchlist = next) }
    }

    fun updateParams(params: BoxParams) {
        prefs.saveParams(params)
        viewModelScope.launch {
            val rows = withContext(Dispatchers.Default) {
                _state.value.symbols.map { analyze(it, params) }
            }
            _state.update { it.copy(params = params, rows = rows, conditionHits = null, backtest = null) }
        }
    }

    fun setAlerts(enabled: Boolean) {
        prefs.alertsEnabled = enabled
        _state.update { it.copy(alertsEnabled = enabled) }
    }

    fun setDataBranch(branch: String) {
        prefs.dataBranch = branch.trim()
        _state.update { it.copy(dataBranch = prefs.dataBranch) }
        refresh(force = true)
    }

    fun toggleCondition(id: String) {
        val next = _state.value.selectedConditions.toMutableSet()
        if (!next.add(id)) next.remove(id)
        _state.update { it.copy(selectedConditions = next, conditionHits = null) }
    }

    fun runConditions() {
        viewModelScope.launch {
            val selected = _state.value.selectedConditions
            val conditions = conditionCatalog().filter { it.id in selected }.map { it.condition }
            val params = _state.value.params
            val hits = withContext(Dispatchers.Default) {
                _state.value.symbols.mapNotNull { symbol ->
                    if (matchesAll(symbol, conditions, params)) analyze(symbol, params) else null
                }
            }
            _state.update { it.copy(conditionHits = hits) }
        }
    }

    fun symbol(code: String): SymbolHistory? = _state.value.symbols.firstOrNull { it.code == code }

    fun row(code: String): ScreenRow? = _state.value.rows.firstOrNull { it.code == code }

    private fun push(route: Route) {
        backStack.addLast(_state.value.route)
        _state.update { it.copy(route = route) }
    }
}

fun List<ScreenRow>.byStatus(status: BoxStatus): List<ScreenRow> = filter { it.status == status }
