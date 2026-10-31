"""漲跌停價。

台股普通股漲跌幅 ±10%，以「參考價」計算。平常參考價是前一日收盤價；
除權息當天改用官方除權息參考價，也就是 前收盤 × 這次的價格因子
（價格因子＝參考價／前收盤，見 adjust.py）。

漲停價＝參考價 × 1.1，依該價位的升降單位無條件捨去；
跌停價＝參考價 × 0.9，依該價位的升降單位無條件進位。
升降單位（證交所與櫃買普通股相同）：
  未滿 10 元 0.01、10–50 元 0.05、50–100 元 0.1、100–500 元 0.5、500–1000 元 1、1000 元以上 5。

判斷「漲停／跌停」用成交原價：收盤價 ≥ 漲停價記為漲停，收盤價 ≤ 跌停價記為跌停。
若升降單位規則與交易所實際算法有出入（例如價位剛好跨級），
另以「收盤＝最高（或最低）且漲跌幅達 9.5%」當備援。
新上市前五日等沒有漲跌幅限制的情形不另外處理，只會偶爾少標或多標。
Android `core/.../Limits.kt` 用同一套規則。
"""

from __future__ import annotations

import math

UP = "up"
DOWN = "down"
FALLBACK_FRACTION = 0.095
_EPS = 1e-6


def tick_size(price: float) -> float:
    if price < 10:
        return 0.01
    if price < 50:
        return 0.05
    if price < 100:
        return 0.1
    if price < 500:
        return 0.5
    if price < 1000:
        return 1.0
    return 5.0


def limit_up_price(reference: float) -> float:
    raw = reference * 1.1
    tick = tick_size(raw)
    units = math.floor(round(raw / tick, 6))
    return round(units * tick, 2)


def limit_down_price(reference: float) -> float:
    raw = reference * 0.9
    tick = tick_size(raw)
    units = math.ceil(round(raw / tick, 6))
    return round(units * tick, 2)


def limit_move(reference: float, close: float, high: float, low: float) -> str | None:
    """回傳 "up"、"down" 或 None。全部用成交原價。"""
    if reference <= 0 or close <= 0:
        return None
    change = close / reference - 1.0
    if close >= limit_up_price(reference) - _EPS:
        return UP
    if close <= limit_down_price(reference) + _EPS:
        return DOWN
    if abs(close - high) < _EPS and change >= FALLBACK_FRACTION - _EPS:
        return UP
    if abs(close - low) < _EPS and change <= -FALLBACK_FRACTION + _EPS:
        return DOWN
    return None


def reference_price(prev_close: float, same_day_factors: list[float]) -> float:
    """前收盤乘上「今天生效」的除權息價格因子，得到今天的參考價。"""
    value = prev_close
    for factor in same_day_factors:
        if factor > 0:
            value *= factor
    return round(value, 2)
