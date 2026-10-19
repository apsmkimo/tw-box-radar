"""達瓦斯箱型（可參數化）。

與 Android 模組 `com.twboxradar.core.Darvas` 必須保持同一規則。修改時兩邊一起改，
並讓 `pipeline/tests/test_darvas.py` 與 `core` 單元測試的手算案例同時通過。

規則摘要（由舊到新的 K 線）：

1. 評估「今天」時，箱型只使用今天以前的 K 線，避免當天新高把箱頂抬走。
2. 回看視窗為前 `lookback_days` 根（不含今天）。箱型在視窗內用狀態機重建，
   不繼承視窗之前的箱型。
3. `use_wicks=True` 時上緣＝最高價、下緣＝最低價；否則上緣＝max(開,收)、下緣＝min(開,收)。
4. 上緣創高之後，若連續 `confirmation_days` 根都沒有更高的上緣，確認箱頂。
   箱頂之後的下緣創低，同樣要連續 `confirmation_days` 根沒有更低，才確認箱底。
   箱底從箱頂的下一根開始找，不含箱頂那根的下緣。
5. 箱型完成後，若某日收盤價高於箱頂或低於箱底，箱型結束並重新尋找。
   影線突破但收盤仍在箱內，不視為突破（避免假突破）。
6. 今天的狀態一律用收盤價：
   - 收盤 > 箱頂 → breakout（突破箱頂）
   - 收盤 < 箱底 → breakdown（跌破箱底）
   - 在箱內且 (收盤-箱底)/(箱頂-箱底) ≤ near_range_fraction → near_bottom（接近箱底）
   - 其餘在箱內 → inside（整理中）
7. 量比＝今天成交量 / 前 `volume_average_days` 日（不含今天）均量。
   ≥ `volume_multiple` 視為帶量。清單仍列出所有收盤突破；提醒預設要帶量。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class Status(str, Enum):
    NONE = "none"
    INSIDE = "inside"
    NEAR_BOTTOM = "near_bottom"
    BREAKOUT = "breakout"
    BREAKDOWN = "breakdown"


@dataclass(frozen=True)
class Bar:
    date: int
    open: float
    high: float
    low: float
    close: float
    volume: int


@dataclass(frozen=True)
class BoxParams:
    lookback_days: int = 60
    confirmation_days: int = 3
    use_wicks: bool = True
    near_range_fraction: float = 0.20
    volume_multiple: float = 1.5
    volume_average_days: int = 20
    require_volume_for_entry: bool = False


@dataclass(frozen=True)
class Box:
    top: float
    bottom: float
    top_date: int
    bottom_date: int


@dataclass(frozen=True)
class Snapshot:
    status: Status
    close: float
    box: Box | None
    distance_to_top: float | None
    distance_to_bottom: float | None
    volume_ratio: float | None
    volume_confirmed: bool


def upper(bar: Bar, use_wicks: bool) -> float:
    if use_wicks:
        return bar.high
    return bar.open if bar.open >= bar.close else bar.close


def lower(bar: Bar, use_wicks: bool) -> float:
    if use_wicks:
        return bar.low
    return bar.open if bar.open <= bar.close else bar.close


def active_box(bars: list[Bar], confirmation: int, use_wicks: bool) -> Box | None:
    """回傳序列末端仍然有效的箱型；沒有則為 None。"""
    if confirmation < 1 or len(bars) < confirmation + 2:
        return None
    top: float | None = None
    bottom: float | None = None
    top_i: int | None = None
    bottom_i: int | None = None
    pending_high: float | None = None
    pending_high_i: int | None = None
    pending_low: float | None = None
    pending_low_i: int | None = None

    for i, bar in enumerate(bars):
        high = upper(bar, use_wicks)
        low = lower(bar, use_wicks)
        close = bar.close
        if top is not None and bottom is not None:
            if close > top or close < bottom:
                top = bottom = None
                top_i = bottom_i = None
                pending_high, pending_high_i = high, i
                pending_low = pending_low_i = None
            continue
        if top is None:
            if pending_high is None or high > pending_high:
                pending_high, pending_high_i = high, i
            elif pending_high_i is not None and i - pending_high_i >= confirmation:
                top, top_i = pending_high, pending_high_i
                start = top_i + 1
                pending_low_i = start
                pending_low = lower(bars[start], use_wicks)
                for k in range(start + 1, i + 1):
                    value = lower(bars[k], use_wicks)
                    if value < pending_low:
                        pending_low, pending_low_i = value, k
                if (
                    pending_low is not None
                    and pending_low_i is not None
                    and i - pending_low_i >= confirmation
                    and pending_low < top
                ):
                    bottom, bottom_i = pending_low, pending_low_i
            continue
        if high > top:
            top = top_i = None
            bottom = bottom_i = None
            pending_high, pending_high_i = high, i
            pending_low = pending_low_i = None
            continue
        if pending_low is None or low < pending_low:
            pending_low, pending_low_i = low, i
        elif (
            pending_low_i is not None
            and i - pending_low_i >= confirmation
            and pending_low < top
        ):
            bottom, bottom_i = pending_low, pending_low_i

    if (
        top is not None
        and bottom is not None
        and top_i is not None
        and bottom_i is not None
        and bottom < top
    ):
        return Box(top, bottom, bars[top_i].date, bars[bottom_i].date)
    return None


def volume_ratio(prior: list[Bar], today: Bar, days: int) -> float | None:
    if days < 1 or len(prior) < days:
        return None
    sample = prior[-days:]
    average = sum(bar.volume for bar in sample) / days
    if average <= 0:
        return None
    return today.volume / average


def evaluate(bars: list[Bar], params: BoxParams | None = None) -> Snapshot:
    params = params or BoxParams()
    if not bars:
        return Snapshot(Status.NONE, 0.0, None, None, None, None, False)
    today = bars[-1]
    minimum = params.confirmation_days * 2 + 2
    if len(bars) < minimum:
        return Snapshot(Status.NONE, today.close, None, None, None, None, False)
    prior = bars[:-1]
    window = prior[-params.lookback_days :]
    box = active_box(window, params.confirmation_days, params.use_wicks)
    ratio = volume_ratio(prior, today, params.volume_average_days)
    confirmed = ratio is not None and ratio >= params.volume_multiple
    if box is None or today.close <= 0:
        return Snapshot(Status.NONE, today.close, box, None, None, ratio, confirmed)
    distance_top = (box.top - today.close) / today.close
    distance_bottom = (today.close - box.bottom) / today.close
    if today.close > box.top:
        status = Status.BREAKOUT
    elif today.close < box.bottom:
        status = Status.BREAKDOWN
    else:
        span = box.top - box.bottom
        position = (today.close - box.bottom) / span if span > 0 else 0.0
        status = Status.NEAR_BOTTOM if position <= params.near_range_fraction else Status.INSIDE
    return Snapshot(status, today.close, box, distance_top, distance_bottom, ratio, confirmed)


@dataclass(frozen=True)
class Trade:
    entry_date: int
    entry_price: float
    exit_date: int | None
    exit_price: float | None
    return_fraction: float | None


@dataclass(frozen=True)
class BacktestResult:
    trades: list[Trade]
    total_return: float
    win_rate: float | None
    max_drawdown: float
    buy_hold_return: float
    closed_trades: int


def backtest(bars: list[Bar], params: BoxParams | None = None) -> BacktestResult:
    """收盤價進出：突破箱頂買進，收盤跌破停損（初始為突破當下的箱底，其後只上移）賣出。

    停損上移發生在持有期間又出現有效箱型、且新箱底更高時。
    不計手續費與稅，價格不還原除權息。空手部位不計報酬。
    買進持有的比較起點是回測第一個可評估日的收盤價。
    """
    params = params or BoxParams()
    start = params.confirmation_days * 2 + 2
    if len(bars) <= start:
        return BacktestResult([], 0.0, None, 0.0, 0.0, 0)
    equity = 1.0
    peak = 1.0
    max_dd = 0.0
    in_position = False
    entry_price = 0.0
    entry_date = 0
    equity_at_entry = 1.0
    stop = 0.0
    trades: list[Trade] = []

    for i in range(start, len(bars)):
        snap = evaluate(bars[: i + 1], params)
        close = bars[i].close
        if not in_position:
            volume_ok = (not params.require_volume_for_entry) or snap.volume_confirmed
            if snap.status is Status.BREAKOUT and volume_ok and snap.box is not None:
                in_position = True
                entry_price = close
                entry_date = bars[i].date
                equity_at_entry = equity
                stop = snap.box.bottom
        else:
            if snap.box is not None and snap.box.bottom > stop:
                stop = snap.box.bottom
            if close < stop:
                ret = close / entry_price - 1.0
                equity = equity_at_entry * (close / entry_price)
                trades.append(Trade(entry_date, entry_price, bars[i].date, close, ret))
                in_position = False
        marked = equity_at_entry * (close / entry_price) if in_position else equity
        if marked > peak:
            peak = marked
        if peak > 0:
            drawdown = (peak - marked) / peak
            if drawdown > max_dd:
                max_dd = drawdown

    if in_position:
        close = bars[-1].close
        trades.append(Trade(entry_date, entry_price, None, close, close / entry_price - 1.0))
        equity = equity_at_entry * (close / entry_price)

    closed = [trade for trade in trades if trade.exit_date is not None]
    wins = sum(1 for trade in closed if (trade.return_fraction or 0.0) > 0)
    win_rate = (wins / len(closed)) if closed else None
    buy_hold = bars[-1].close / bars[start].close - 1.0
    return BacktestResult(trades, equity - 1.0, win_rate, max_dd, buy_hold, len(closed))
