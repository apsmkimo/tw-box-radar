#!/usr/bin/env python3
"""由 data/ 產生 App 的初始資料（APK 內建 assets/seed/）。

格式與 App 本機資料相同（core/.../local/LocalStore.kt）：
  q/YYYYMMDD.gz   每行「代號\\t市場(t/o)\\t開\\t高\\t低\\t收\\t股數」，成交原價、只有普通股
  c/YYYYMMDD.gz   每行「代號\\t外資\\t投信\\t自營商」，單位股
  names.tsv       第一行 #YYYYMMDD，之後「代號\\t名稱\\t市場」
  exrights.tsv    「代號\\t日期\\t價格因子\\t成交量因子\\t待查明細(0/1)」
  revenue.tsv     「代號\\tYYYYMM\\t營收(千元)」

用法：
  python3 pipeline/make_seed.py --out app/src/main/assets/seed --days 250

目前 APK 還沒有打包這份資料（約 8 MB）；沒有 assets/seed/ 時，App 第一次啟動改從 GitHub data/ 匯入。
"""

from __future__ import annotations

import argparse
import gzip
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from twbox.parse import is_common_stock  # noqa: E402
from twbox.store import load_state  # noqa: E402


def _num(value: float) -> str:
    return str(int(value)) if float(value).is_integer() else repr(float(value))


def build(out: Path, days_count: int) -> None:
    state = load_state(ROOT)
    days = sorted(int(day) for day in state.get("completed", []))[-days_count:]
    if not days:
        raise SystemExit("data/state.json 沒有交易日")
    wanted = set(days)
    if out.exists():
        shutil.rmtree(out)
    (out / "q").mkdir(parents=True)
    (out / "c").mkdir(parents=True)

    by_day: dict[int, dict[str, list]] = {}
    for month in sorted({day // 100 for day in days}):
        path = ROOT / "data" / "monthly" / f"{month // 100}-{month % 100:02d}.jsonl"
        if not path.exists():
            continue
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                if not line.strip():
                    continue
                row = json.loads(line)
                day = int(row[0])
                if day in wanted and is_common_stock(str(row[1])):
                    by_day.setdefault(day, {})[row[1]] = row
    names: dict[str, tuple[str, str]] = {}
    for day in sorted(by_day):
        lines = []
        for code in sorted(by_day[day]):
            _, _, name, market, open_, high, low, close, volume = by_day[day][code]
            names[code] = (str(name).replace("\t", " "), market)
            flag = "o" if market == "tpex" else "t"
            lines.append(f"{code}\t{flag}\t{_num(open_)}\t{_num(high)}\t{_num(low)}\t{_num(close)}\t{int(volume)}\n")
        with gzip.open(out / "q" / f"{day:08d}.gz", "wt", encoding="utf-8") as handle:
            handle.writelines(lines)
    latest = max(by_day)
    with (out / "names.tsv").open("w", encoding="utf-8") as handle:
        handle.write(f"#{latest}\n")
        for code in sorted(names):
            handle.write(f"{code}\t{names[code][0]}\t{names[code][1]}\n")

    oldest = min(by_day)
    events = json.loads((ROOT / "data" / "exrights.json").read_text(encoding="utf-8")).get("events", [])
    with (out / "exrights.tsv").open("w", encoding="utf-8") as handle:
        for item in sorted(events, key=lambda e: (e["code"], int(e["date"]))):
            price = float(item.get("priceFactor") or 0)
            volume = float(item.get("volumeFactor") or 0)
            if int(item["date"]) <= oldest or price <= 0 or volume <= 0:
                continue
            pending = 1 if item.get("needsDetail") and not item.get("volumeKnown") else 0
            handle.write(f"{item['code']}\t{int(item['date'])}\t{price!r}\t{volume!r}\t{pending}\n")

    revenue_path = ROOT / "data" / "revenue.json"
    if revenue_path.exists():
        months = json.loads(revenue_path.read_text(encoding="utf-8")).get("months", {})
        with (out / "revenue.tsv").open("w", encoding="utf-8") as handle:
            for code in sorted(months):
                for month in sorted(months[code], key=int):
                    handle.write(f"{code}\t{int(month)}\t{int(months[code][month])}\n")

    for path in sorted((ROOT / "data" / "chips").glob("*.json")):
        if not path.stem.isdigit() or int(path.stem) not in wanted:
            continue
        rows = json.loads(path.read_text(encoding="utf-8"))
        with gzip.open(out / "c" / f"{int(path.stem):08d}.gz", "wt", encoding="utf-8") as handle:
            for code in sorted(rows):
                foreign, trust, dealer = rows[code]
                handle.write(f"{code}\t{int(foreign)}\t{int(trust)}\t{int(dealer)}\n")

    size = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    print(f"[seed] {len(by_day)} 天 {oldest}～{latest}，{len(names)} 檔，{size / 1024 / 1024:.1f} MB → {out}")


def main() -> int:
    parser = argparse.ArgumentParser(description="產生 App 初始資料")
    parser.add_argument("--out", default=str(ROOT / "build" / "seed"))
    parser.add_argument("--days", type=int, default=250)
    args = parser.parse_args()
    build(Path(args.out), args.days)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
