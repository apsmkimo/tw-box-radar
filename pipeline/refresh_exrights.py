#!/usr/bin/env python3
"""更新 data/exrights.json。可中斷，已抓過的舊區間與已完成的明細會略過。

用法：
  python3 pipeline/refresh_exrights.py --start 20231001 --end 20261019 --sleep 3
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))

from twbox.calendar_util import parse_yyyymmdd, taipei_today  # noqa: E402
from twbox.exrights import refresh_exrights  # noqa: E402
from twbox.http_client import HttpClient  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="下載上市與上櫃除權息因子")
    parser.add_argument("--start", default="20231001", help="YYYYMMDD")
    parser.add_argument("--end", default="", help="YYYYMMDD，空白為台北今天")
    parser.add_argument("--sleep", type=float, default=3.0)
    args = parser.parse_args()
    start = parse_yyyymmdd(args.start)
    end = parse_yyyymmdd(args.end) if args.end else taipei_today()
    client = HttpClient(min_interval=args.sleep)
    tpex_client = HttpClient(min_interval=args.sleep)
    refresh_exrights(ROOT, client, start, end, tpex_client=tpex_client)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
