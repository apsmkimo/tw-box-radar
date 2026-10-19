#!/usr/bin/env python3
"""產生 App 離線範例。有全市場資料時改放幾檔真實個股，否則用手算案例。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from twbox.store import load_symbols  # noqa: E402

OUT = ROOT / "app" / "src" / "main" / "assets" / "sample" / "window.json"
PREFERRED = ["2330", "2317", "2454", "2412", "2881", "2308", "1301", "6488", "3105", "5483"]


def synthetic() -> dict:
    def bars_from(rows: list[tuple[float, float, float]]) -> list[list]:
        out = []
        for i, (high, low, close) in enumerate(rows):
            out.append([20240101 + i, close, high, low, close, 1000])
        return out

    base = [
        (12, 10, 11),
        (15, 11, 14),
        (20, 14, 19),
        (19, 16, 17),
        (18, 15, 16),
        (18, 14, 15),
        (17, 15, 16),
        (17, 15, 16),
        (17, 16, 16),
    ]
    return {
        "asOf": "20240110",
        "symbols": [
            {"c": "2330", "n": "範例突破", "m": "twse", "bars": bars_from(base + [(22, 18, 21)])},
            {"c": "2317", "n": "範例整理", "m": "twse", "bars": bars_from(base + [(17, 15, 16)])},
            {"c": "6488", "n": "範例箱底", "m": "tpex", "bars": bars_from(base + [(16, 14, 15)])},
        ],
    }


def from_history() -> dict | None:
    symbols = load_symbols(ROOT)
    if len(symbols) < 30:
        return None
    picked = [symbols[code] for code in PREFERRED if code in symbols and len(symbols[code].bars) >= 40]
    if len(picked) < 3:
        return None
    as_of = max(series.bars[-1].date for series in picked)
    payload = []
    for series in picked:
        window = series.bars[-180:]
        payload.append(
            {
                "c": series.code,
                "n": series.name,
                "m": series.market,
                "bars": [[bar.date, bar.open, bar.high, bar.low, bar.close, bar.volume] for bar in window],
            }
        )
    return {"asOf": f"{as_of:08d}", "symbols": payload}


def main() -> None:
    payload = from_history() or synthetic()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"[sample] {OUT} symbols={len(payload['symbols'])} asOf={payload['asOf']}")


if __name__ == "__main__":
    main()
