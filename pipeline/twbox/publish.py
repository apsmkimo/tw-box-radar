"""由月檔產生 App 下載的靜態 JSON。"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from .darvas import BoxParams, Status, evaluate
from .store import SymbolSeries, load_symbols

WINDOW_BARS = 180
SCHEMA = 1


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

    for code in sorted(symbols):
        series = symbols[code]
        if not series.bars:
            continue
        as_of = max(as_of, series.bars[-1].date)
        history_start = min(history_start, series.bars[0].date)
        _write_symbol(bars_dir / f"{code}.json", series)
        universe.append({"c": code, "n": series.name, "m": series.market})
        window = series.bars[-window_bars:]
        window_symbols.append(_symbol_json(series, window))
        row = _screen_row(series, params)
        if row is None:
            continue
        status = row["status"]
        if status == Status.BREAKOUT.value:
            breakout.append(row)
        elif status == Status.INSIDE.value:
            inside.append(row)
        elif status == Status.NEAR_BOTTOM.value:
            near_bottom.append(row)

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
        "adjusted": False,
        "universe": "4-digit-common-stock",
        "markets": ["twse", "tpex"],
        "priceNote": "成交價未還原除權息。跨越除權息日的箱型與報酬會失真。",
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
    print(
        f"[publish] asOf={manifest['asOf']} symbols={len(universe)} "
        f"breakout={len(breakout)} inside={len(inside)} nearBottom={len(near_bottom)}",
        flush=True,
    )
    return manifest


def _screen_row(series: SymbolSeries, params: BoxParams) -> dict | None:
    snap = evaluate(series.bars, params)
    if snap.box is None or snap.status in {Status.NONE, Status.BREAKDOWN}:
        return None
    span = snap.box.top - snap.box.bottom
    position = (snap.close - snap.box.bottom) / span if span else 0.0
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
    }


def _symbol_json(series: SymbolSeries, bars) -> dict:
    return {
        "c": series.code,
        "n": series.name,
        "m": series.market,
        "bars": [[bar.date, bar.open, bar.high, bar.low, bar.close, bar.volume] for bar in bars],
    }


def _write_symbol(path: Path, series: SymbolSeries) -> None:
    _dump(path, _symbol_json(series, series.bars))


def _dump(path: Path, payload) -> None:
    path.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
