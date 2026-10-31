#!/usr/bin/env python3
"""每日收盤後補抓尚未入庫的交易日，並重新發佈靜態 JSON。

排程應在台北時間 16:30 與 18:30 左右各跑一次。若收盤資料還沒出來，
今天不會被記成休市，下一次排程會再試。過去的平日若官方明確無資料，記為休市。
"""

from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from twbox.calendar_util import parse_yyyymmdd, recent_weekdays, taipei_today, yyyymmdd  # noqa: E402
from twbox.exrights import refresh_exrights  # noqa: E402
from twbox.fetch import fetch_day  # noqa: E402
from twbox.http_client import HttpClient  # noqa: E402
from twbox.publish import publish  # noqa: E402
from twbox.risk import refresh_risk  # noqa: E402
from twbox.store import append_day, load_state, save_state  # noqa: E402


def main() -> int:
    today = taipei_today()
    state = load_state(ROOT)
    completed = set(state.get("completed", []))
    closed = set(state.get("closed", []))
    pending = []
    for day in recent_weekdays(today, 10):
        key = yyyymmdd(day)
        if key not in completed and key not in closed:
            pending.append(day)
    # SMCPKG_SUPPORT>>>CursorXXX
    # 以前只在有新行情時重發佈，除權息日不會回頭改舊 K 線。
    factors_changed = _refresh_factors()
    # SMCPKG_SUPPORT<<<CursorYYY
    if not pending:
        print("[daily] 沒有待抓日期", flush=True)
        _refresh_risk(state)
        publish(ROOT)
        return 0

    client = HttpClient(min_interval=3.0)
    tpex_client = HttpClient(min_interval=3.0)
    today_key = yyyymmdd(today)
    changed = False
    for day in pending:
        key = yyyymmdd(day)
        print(f"[daily] 抓取 {key}", flush=True)
        status, rows = fetch_day(client, key, tpex_client=tpex_client)
        if status == "ok":
            append_day(ROOT, int(key), rows)
            completed.add(key)
            changed = True
            state["completed"] = sorted(completed)
            save_state(ROOT, state)
        elif status == "closed" and key != today_key:
            closed.add(key)
            state["closed"] = sorted(closed)
            save_state(ROOT, state)
            print(f"[daily] {key} 休市或無行情", flush=True)
        elif status == "closed":
            print(f"[daily] {key} 尚無收盤資料，稍後再試", flush=True)
        else:
            print(f"[daily] {key} 下載失敗，稍後再試", flush=True)
            return 1
    # SMCPKG_SUPPORT>>>CursorXXX
    # if changed or not (ROOT / "docs" / "manifest.json").exists():
    risk_changed = _refresh_risk(state)
    if changed or factors_changed or risk_changed or not (ROOT / "docs" / "manifest.json").exists():
        # SMCPKG_SUPPORT<<<CursorYYY
        publish(ROOT)
    return 0


def _refresh_risk(state: dict) -> bool:
    """注意／處置清單以最新入庫的交易日為準。失敗不影響行情發佈。"""
    completed = state.get("completed") or []
    if not completed:
        return False
    try:
        return refresh_risk(ROOT, HttpClient(min_interval=3.0), int(max(completed)))
    except Exception as exc:  # noqa: BLE001 — 標籤是附加資訊
        print(f"[daily] 注意／處置清單更新失敗，這次不標：{exc}", flush=True)
        return False


def _refresh_factors() -> bool:
    try:
        return refresh_exrights(
            ROOT,
            HttpClient(min_interval=3.0),
            parse_yyyymmdd("20231001"),
            taipei_today(),
            tpex_client=HttpClient(min_interval=3.0),
        )
    except Exception as exc:  # noqa: BLE001 — 行情仍要發佈，因子留待下次
        print(f"[daily] 除權息更新失敗，沿用已有因子：{exc}", flush=True)
        return False


if __name__ == "__main__":
    raise SystemExit(main())
