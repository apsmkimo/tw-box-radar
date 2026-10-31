"""相對強弱（RS）百分位與箱型品質。規則與 Android `core/.../Quality.kt` 相同。

RS：N 日報酬＝最新收盤 ／ N 個交易日前收盤 − 1（還原價），全市場換成 0～100 百分位：
（比它低的檔數 ＋（同分檔數 − 1）／2）／（檔數 − 1）。K 線不足 N＋1 根沒有 RS。

箱型品質（只看今天以前的 K 線）：
- boxDays：箱頂那一根到昨天（含）的交易日數。
- boxWidth：（箱頂 − 箱底）／箱底。
- volumeContraction：箱內平均量 ÷ 箱頂前 20 個交易日平均量；箱頂前不足 20 根時為 None。
"""

from __future__ import annotations

from bisect import bisect_left, bisect_right

from .darvas import Bar, Box

RS_SHORT = 60
RS_LONG = 120
CONTRACTION_BASE_DAYS = 20


def period_return(bars: list[Bar], period: int) -> float | None:
    if period < 1 or len(bars) < period + 1:
        return None
    base = bars[-1 - period].close
    if base <= 0:
        return None
    return bars[-1].close / base - 1.0


def percentile_ranks(values: dict[str, float]) -> dict[str, int]:
    if not values:
        return {}
    if len(values) == 1:
        return {code: 100 for code in values}
    ordered = sorted(values.values())
    n = len(ordered)
    result = {}
    for code, value in values.items():
        less = bisect_left(ordered, value)
        equal = bisect_right(ordered, value) - less
        rank = (less + (equal - 1) / 2.0) / (n - 1)
        # Kotlin Math.round 是四捨五入（.5 進位），這裡不用 Python 的銀行家捨入。
        result[code] = int(rank * 100.0 + 0.5)
    return result


def box_quality(bars: list[Bar], box: Box | None) -> dict | None:
    if box is None or box.bottom <= 0 or len(bars) < 2:
        return None
    prior = bars[:-1]
    start = next((i for i, bar in enumerate(prior) if bar.date == box.top_date), -1)
    if start < 0:
        return None
    inside = prior[start:]
    contraction = None
    if start >= CONTRACTION_BASE_DAYS:
        before = prior[start - CONTRACTION_BASE_DAYS : start]
        base = sum(bar.volume for bar in before) / len(before)
        if base > 0:
            contraction = (sum(bar.volume for bar in inside) / len(inside)) / base
    return {
        "boxDays": len(inside),
        "boxWidth": (box.top - box.bottom) / box.bottom,
        "volumeContraction": contraction,
    }
