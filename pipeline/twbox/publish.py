"""由月檔產生 App 下載的靜態 JSON。"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from .adjust import adjust_bars
from .darvas import BoxParams, Status, evaluate
from .exrights import load_event_map
from .limits import limit_move, reference_price
from .quality import RS_LONG, RS_SHORT, box_quality, percentile_ranks, period_return
from .risk import load_risk
from . import chips as chips_mod
from . import revenue as revenue_mod
from .store import SymbolSeries, load_symbols

WINDOW_BARS = 180
# SMCPKG_SUPPORT>>>CursorXXX
# SCHEMA = 1
SCHEMA = 2
PRICE_NOTE = (
    "向後還原：除權息日以前的開高低收，乘上其後各次（除權息參考價／前收盤價）。"
    "成交量只在有無償配股或按持股比例現金增資時乘上（1＋配股率＋認購率），現金股利不調整成交量。"
    "最新一根維持成交價；還沒有行情的除權息日先不套，等那天入庫再回頭調整。"
    "因子來自證交所 TWT49U（含明細）與櫃買除權息計算表。"
    "新的除權息日會在下次發佈時回頭調整更早的歷史。"
)
# SMCPKG_SUPPORT<<<CursorYYY


def publish(root: Path, window_bars: int = WINDOW_BARS) -> dict:
    symbols = load_symbols(root)
    if not symbols:
        raise SystemExit("沒有歷史資料可發佈。請先執行 backfill.py 或 daily.py。")
    docs = root / "docs"
    bars_dir = docs / "bars"
    bars_dir.mkdir(parents=True, exist_ok=True)
    # 清掉已下市、這次沒有的舊檔，避免 App 讀到過期代號。
    keep = set(symbols)
    for old in bars_dir.glob("*.json"):
        if old.stem not in keep:
            old.unlink()

    as_of = 0
    history_start = 99999999
    universe = []
    window_symbols = []
    breakout = []
    inside = []
    near_bottom = []
    params = BoxParams()
    # SMCPKG_SUPPORT>>>CursorXXX
    # 篩選與視窗以前直接用月檔成交價。
    events = load_event_map(root)
    adjusted_on = bool(events)
    adjusted_count = 0
    # SMCPKG_SUPPORT<<<CursorYYY
    risk = load_risk(root)
    returns_short: dict[str, float] = {}
    returns_long: dict[str, float] = {}
    limits: dict[str, tuple[str, int]] = {}

    for code in sorted(symbols):
        series = symbols[code]
        if not series.bars:
            continue
        # SMCPKG_SUPPORT>>>CursorXXX
        # as_of = max(as_of, series.bars[-1].date)
        # history_start = min(history_start, series.bars[0].date)
        # _write_symbol(bars_dir / f"{code}.json", series)
        # universe.append({"c": code, "n": series.name, "m": series.market})
        # window = series.bars[-window_bars:]
        # window_symbols.append(_symbol_json(series, window))
        # row = _screen_row(series, params)
        adjusted = adjust_bars(series.bars, events.get(code, []))
        if _bars_differ(series.bars, adjusted):
            adjusted_count += 1
        as_of = max(as_of, adjusted[-1].date)
        history_start = min(history_start, adjusted[0].date)
        _write_symbol(bars_dir / f"{code}.json", series, adjusted)
        universe.append({"c": code, "n": series.name, "m": series.market})
        window = adjusted[-window_bars:]
        window_symbols.append(_symbol_json(series, window))
        for period, target in ((RS_SHORT, returns_short), (RS_LONG, returns_long)):
            value = period_return(adjusted, period)
            if value is not None:
                target[code] = value
        row = _screen_row(series, adjusted, params)
        # SMCPKG_SUPPORT<<<CursorYYY
        move = _limit_today(series, events.get(code, []))
        if move:
            limits[code] = (move, series.bars[-1].date)
        if row is None:
            continue
        if move:
            row["limit"] = move
        status = row["status"]
        if status == Status.BREAKOUT.value:
            breakout.append(row)
        elif status == Status.INSIDE.value:
            inside.append(row)
        elif status == Status.NEAR_BOTTOM.value:
            near_bottom.append(row)

    rs_short = percentile_ranks(returns_short)
    rs_long = percentile_ranks(returns_long)
    for row in (*breakout, *inside, *near_bottom):
        row["rs60"] = rs_short.get(row["c"])
        row["rs120"] = rs_long.get(row["c"])
    breakout.sort(key=lambda item: item.get("volumeRatio") or 0, reverse=True)
    inside.sort(key=lambda item: (item["top"] - item["bottom"]) / item["bottom"])
    near_bottom.sort(key=lambda item: item["rangePosition"])

    manifest = {
        "schema": SCHEMA,
        "asOf": f"{as_of:08d}",
        "generatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "symbolCount": len(universe),
        "historyStart": f"{history_start:08d}",
        "windowBars": window_bars,
        # SMCPKG_SUPPORT>>>CursorXXX
        # "adjusted": False,
        # "priceNote": "成交價未還原除權息。跨越除權息日的箱型與報酬會失真。",
        "adjusted": adjusted_on,
        "adjustedSymbolCount": adjusted_count,
        "adjustMethod": "backward-official-ref" if adjusted_on else "none",
        "universe": "4-digit-common-stock",
        "markets": ["twse", "tpex"],
        "priceNote": PRICE_NOTE if adjusted_on else "尚未取得除權息因子，這次發佈仍是成交價。",
        # SMCPKG_SUPPORT<<<CursorYYY
        "params": {
            "lookbackDays": params.lookback_days,
            "confirmationDays": params.confirmation_days,
            "useWicks": params.use_wicks,
            "nearRangeFraction": params.near_range_fraction,
            "volumeMultiple": params.volume_multiple,
            "volumeAverageDays": params.volume_average_days,
        },
    }
    _dump(docs / "manifest.json", manifest)
    _dump(docs / "universe.json", {"asOf": manifest["asOf"], "symbols": universe})
    _dump(docs / "window.json", {"asOf": manifest["asOf"], "symbols": window_symbols})
    _dump(
        docs / "screens.json",
        {
            "asOf": manifest["asOf"],
            "params": manifest["params"],
            "breakout": breakout,
            "inside": inside,
            "nearBottom": near_bottom,
        },
    )
    # 停牌而最後一根不是資料日的股票，不標漲跌停。
    today_limits = {code: move for code, (move, day) in limits.items() if day == as_of}
    flags = build_flags(manifest["asOf"], risk, today_limits)
    _dump(docs / "flags.json", flags)
    _dump(docs / "chips.json", build_chips(root, manifest["asOf"], keep))
    print(
        f"[publish] asOf={manifest['asOf']} symbols={len(universe)} "
        f"breakout={len(breakout)} inside={len(inside)} nearBottom={len(near_bottom)} "
        f"adjusted={adjusted_on} adjustedSymbols={adjusted_count}",
        flush=True,
    )
    return manifest


def _limit_today(series: SymbolSeries, events) -> str | None:
    """最後一根是否漲停／跌停。用成交原價，除權息當天的參考價乘上當天的價格因子。"""
    if len(series.bars) < 2:
        return None
    prev, today = series.bars[-2], series.bars[-1]
    factors = [event.price_factor for event in events if event.date == today.date]
    reference = reference_price(prev.close, factors)
    return limit_move(reference, today.close, today.high, today.low)


def build_flags(as_of: str, risk: dict, limits: dict[str, str]) -> dict:
    """docs/flags.json：只列出有標籤的代號。

    n＝當天公布注意交易資訊；p＝[處置起日, 處置迄日]，pm＝處置措施；l＝1 漲停、-1 跌停。
    注意／處置清單的日期若和行情資料日不同，仍照實寫在 riskAsOf，App 依此顯示。
    """
    flags: dict[str, dict] = {}
    for code in risk.get("notice") or []:
        flags.setdefault(code, {})["n"] = 1
    for code, item in (risk.get("punish") or {}).items():
        entry = flags.setdefault(code, {})
        entry["p"] = [item.get("start"), item.get("end")]
        entry["pm"] = item.get("measure") or "處置"
    for code, move in limits.items():
        flags.setdefault(code, {})["l"] = 1 if move == "up" else -1
    return {
        "asOf": as_of,
        "riskAsOf": risk.get("asOf") or "",
        "sources": risk.get("sources") or {},
        "limitNote": "漲跌停以成交原價判斷：收盤價達到依升降單位計算的漲停價（跌停價）。除權息當天以官方參考價為基準。",
        "flags": {code: flags[code] for code in sorted(flags)},
    }


def build_chips(root: Path, as_of: str, codes: set[str]) -> dict:
    """docs/chips.json：三大法人近 5／20 日買賣超（張）、連買天數與最新月營收。

    i＝[外資5日, 外資20日, 投信5日, 投信20日, 自營5日, 自營20日, 投信連買天數, 外資連買天數]
    r＝[資料月 YYYYMM, 營收(千元), 年增率, 月增率, 創 12 個月新高 1／0／null]
    chipDays 是目前累積的交易日數；不足 20 天時，20 日欄位只是已累積天數的合計。
    """
    days = chips_mod.load_days(root)
    insti = chips_mod.summarize(days)
    revenue = revenue_mod.summarize(revenue_mod.load_revenue(root))
    items: dict[str, dict] = {}
    for code in sorted(codes):
        entry: dict = {}
        if code in insti:
            entry["i"] = insti[code]
        if code in revenue:
            entry["r"] = revenue[code]
        if entry:
            items[code] = entry
    return {
        "asOf": as_of,
        "chipDays": len(days),
        "chipStart": f"{days[0][0]:08d}" if days else "",
        "chipEnd": f"{days[-1][0]:08d}" if days else "",
        "unit": "張",
        "revenueUnit": "千元",
        "items": items,
    }


def _screen_row(series: SymbolSeries, bars, params: BoxParams) -> dict | None:
    # SMCPKG_SUPPORT>>>CursorXXX
    # snap = evaluate(series.bars, params)
    snap = evaluate(bars, params)
    # SMCPKG_SUPPORT<<<CursorYYY
    if snap.box is None or snap.status in {Status.NONE, Status.BREAKDOWN}:
        return None
    span = snap.box.top - snap.box.bottom
    position = (snap.close - snap.box.bottom) / span if span else 0.0
    quality = box_quality(bars, snap.box) or {}
    contraction = quality.get("volumeContraction")
    return {
        "c": series.code,
        "n": series.name,
        "m": series.market,
        "status": snap.status.value,
        "close": snap.close,
        "top": snap.box.top,
        "bottom": snap.box.bottom,
        "topDate": snap.box.top_date,
        "bottomDate": snap.box.bottom_date,
        "distanceToTop": None if snap.distance_to_top is None else round(snap.distance_to_top, 6),
        "distanceToBottom": None if snap.distance_to_bottom is None else round(snap.distance_to_bottom, 6),
        "volumeRatio": None if snap.volume_ratio is None else round(snap.volume_ratio, 4),
        "volumeConfirmed": snap.volume_confirmed,
        "rangePosition": round(position, 6),
        "boxDays": quality.get("boxDays"),
        "boxWidth": None if "boxWidth" not in quality else round(quality["boxWidth"], 6),
        "volumeContraction": None if contraction is None else round(contraction, 4),
    }


def _symbol_json(series: SymbolSeries, bars, raw=None) -> dict:
    payload = {
        "c": series.code,
        "n": series.name,
        "m": series.market,
        "bars": [[bar.date, bar.open, bar.high, bar.low, bar.close, bar.volume] for bar in bars],
    }
    # SMCPKG_SUPPORT>>>CursorXXX
    # 以前個股檔只有成交價 bars。有除權息差異時另存 raw，視窗檔不帶 raw。
    if raw is not None:
        payload["raw"] = [[bar.date, bar.open, bar.high, bar.low, bar.close, bar.volume] for bar in raw]
    # SMCPKG_SUPPORT<<<CursorYYY
    return payload


def _write_symbol(path: Path, series: SymbolSeries, adjusted) -> None:
    # SMCPKG_SUPPORT>>>CursorXXX
    # _dump(path, _symbol_json(series, series.bars))
    raw = series.bars if _bars_differ(series.bars, adjusted) else None
    _dump(path, _symbol_json(series, adjusted, raw))
    # SMCPKG_SUPPORT<<<CursorYYY


def _bars_differ(raw, adjusted) -> bool:
    if len(raw) != len(adjusted):
        return True
    for left, right in zip(raw, adjusted):
        if (
            left.date != right.date
            or left.open != right.open
            or left.high != right.high
            or left.low != right.low
            or left.close != right.close
            or left.volume != right.volume
        ):
            return True
    return False


def _dump(path: Path, payload) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
