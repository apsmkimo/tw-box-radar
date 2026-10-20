"""交易日曆只排除週六日。國定假日由「官方回應沒有行情」判定，不內建假日表。"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

TAIPEI = ZoneInfo("Asia/Taipei")


def taipei_today() -> date:
    return datetime.now(TAIPEI).date()


def yyyymmdd(day: date) -> str:
    return day.strftime("%Y%m%d")


def parse_yyyymmdd(text: str) -> date:
    return datetime.strptime(text, "%Y%m%d").date()


def weekdays_between(start: date, end: date) -> list[date]:
    days = []
    cursor = start
    while cursor <= end:
        if cursor.weekday() < 5:
            days.append(cursor)
        cursor += timedelta(days=1)
    return days


def recent_weekdays(today: date, count: int) -> list[date]:
    days: list[date] = []
    cursor = today
    while len(days) < count:
        if cursor.weekday() < 5:
            days.append(cursor)
        cursor -= timedelta(days=1)
    days.reverse()
    return days
