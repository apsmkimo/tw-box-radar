"""除權息向後還原。

最新一根 K 線維持官方成交價。比較早的開高低收，乘上「之後每一次」
除權息參考價／除權息前收盤價。除權息當天已是新價格，不再乘該次因子。
還沒有進到這段 K 線裡的除權息日先不套用，避免歷史還沒補到那天就把收盤價縮掉。

成交量只在股數變多時調整：乘上之後各次的
（1 ＋ 每仟股無償配股／1000 ＋ 按持股比例仟股認購／1000）。
現金股利不改成交量。沒有配股與現金增資認購時，成交量因子是 1。
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .darvas import Bar


@dataclass(frozen=True)
class ExEvent:
    date: int
    code: str
    price_factor: float
    volume_factor: float


def adjust_bars(bars: list[Bar], events: list[ExEvent]) -> list[Bar]:
    usable = [
        event
        for event in events
        if event.price_factor > 0
        and event.volume_factor > 0
        and math.isfinite(event.price_factor)
        and math.isfinite(event.volume_factor)
    ]
    if not bars or not usable:
        return list(bars)
    # K 線需依日期排序。晚於最後一根的除權息日，等那天的行情入庫再套。
    last_date = bars[-1].date
    usable = [event for event in usable if event.date <= last_date]
    if not usable:
        return list(bars)
    usable.sort(key=lambda event: event.date)
    price_factor = 1.0
    volume_factor = 1.0
    index = len(usable) - 1
    adjusted: list[Bar] = []
    for bar in reversed(bars):
        while index >= 0 and usable[index].date > bar.date:
            price_factor *= usable[index].price_factor
            volume_factor *= usable[index].volume_factor
            index -= 1
        adjusted.append(
            Bar(
                bar.date,
                round(bar.open * price_factor, 4),
                round(bar.high * price_factor, 4),
                round(bar.low * price_factor, 4),
                round(bar.close * price_factor, 4),
                int(round(bar.volume * volume_factor)),
            )
        )
    adjusted.reverse()
    return adjusted
