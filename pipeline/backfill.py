#!/usr/bin/env python3
"""回補一段日期的全市場日線。可中斷，已完成的日期記在 data/state.json。

用法：
  python3 pipeline/backfill.py --start 20231016 --end 20261016 --sleep 3
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from twbox.calendar_util import parse_yyyymmdd, weekdays_between, yyyymmdd  # noqa: E402
from twbox.fetch import fetch_day  # noqa: E402
from twbox.http_client import HttpClient  # noqa: E402
from twbox.publish import publish  # noqa: E402
from twbox.store import append_day, load_state, save_state  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="回補上市與上櫃普通股日線")
    parser.add_argument("--start", required=True, help="YYYYMMDD")
    parser.add_argument("--end", required=True, help="YYYYMMDD")
    parser.add_argument("--sleep", type=float, default=3.0, help="每次請求的最小間隔秒數")
    parser.add_argument("--no-publish", action="store_true")
    args = parser.parse_args()

    start = parse_yyyymmdd(args.start)
    end = parse_yyyymmdd(args.end)
    state = load_state(ROOT)
    completed = set(state.get("completed", []))
    closed = set(state.get("closed", []))
    client = HttpClient(min_interval=args.sleep)
    tpex_client = HttpClient(min_interval=args.sleep)
    days = weekdays_between(start, end)
    print(f"[backfill] {args.start}..{args.end} 平日 {len(days)} 天，間隔 {args.sleep}s", flush=True)
    for index, day in enumerate(days, start=1):
        key = yyyymmdd(day)
        if key in completed or key in closed:
            continue
        print(f"[backfill] ({index}/{len(days)}) {key}", flush=True)
        status, rows = fetch_day(client, key, allow_openapi_fallback=False, tpex_client=tpex_client)
        if status == "ok":
            append_day(ROOT, int(key), rows)
            completed.add(key)
            state["completed"] = sorted(completed)
            save_state(ROOT, state)
        elif status == "closed":
            closed.add(key)
            state["closed"] = sorted(closed)
            save_state(ROOT, state)
            print(f"[backfill] {key} 無行情（週末以外的休市）", flush=True)
        else:
            print(f"[backfill] {key} 失敗，停止。再次執行會從這裡繼續。", flush=True)
            return 1
    if not args.no_publish:
        publish(ROOT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
