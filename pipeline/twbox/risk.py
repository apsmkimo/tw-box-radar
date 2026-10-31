"""注意股與處置股清單。

來源（2026-10 實測）：
- 上市注意：`https://www.twse.com.tw/rwd/zh/announcement/notice?startDate=YYYYMMDD&endDate=YYYYMMDD&response=json`
  可指定日期。失敗時改用 OpenAPI `https://openapi.twse.com.tw/v1/announcement/notice`
  （只有當天，非交易日回一列空白代號）。
- 上市處置：OpenAPI `https://openapi.twse.com.tw/v1/announcement/punish`，列出目前處置中的證券。
- 上櫃注意：OpenAPI `https://www.tpex.org.tw/openapi/v1/tpex_trading_warning_information`，只有最新一天。
- 上櫃處置：OpenAPI `https://www.tpex.org.tw/openapi/v1/tpex_disposal_information`，含近期公告，
  依處置迄日過濾。

每個來源各自失敗，不影響行情發佈；失敗的來源在 `sources` 標記 error，對應標籤這次不出現。
結果存成 data/risk.json，發佈時再轉成 docs/flags.json。
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .http_client import HttpClient
from .parse import is_common_stock, loads_json

TWSE_NOTICE = "https://www.twse.com.tw/rwd/zh/announcement/notice?startDate={date}&endDate={date}&response=json"
TWSE_NOTICE_OPENAPI = "https://openapi.twse.com.tw/v1/announcement/notice"
TWSE_PUNISH_OPENAPI = "https://openapi.twse.com.tw/v1/announcement/punish"
TPEX_WARNING_OPENAPI = "https://www.tpex.org.tw/openapi/v1/tpex_trading_warning_information"
TPEX_DISPOSAL_OPENAPI = "https://www.tpex.org.tw/openapi/v1/tpex_disposal_information"

RISK_NAME = "risk.json"
_ROC_DATE = re.compile(r"(\d{2,3})\s*[/.\-年]?\s*(\d{1,2})\s*[/.\-月]?\s*(\d{1,2})")


def parse_roc(text: str) -> int | None:
    """民國日期轉 YYYYMMDD。接受 1151030、115/10/30、115.10.30、115年10月30日。"""
    value = str(text or "").strip()
    if not value:
        return None
    if value.isdigit() and len(value) in (6, 7):
        year, month, day = int(value[:-4]), int(value[-4:-2]), int(value[-2:])
    else:
        match = _ROC_DATE.search(value)
        if not match:
            return None
        year, month, day = (int(part) for part in match.groups())
    if not (1 <= month <= 12 and 1 <= day <= 31) or year <= 0:
        return None
    return (year + 1911) * 10000 + month * 100 + day


def parse_period(text: str) -> tuple[int | None, int | None]:
    """處置期間「115/10/28～115/11/03」或「1151030~1151105」。"""
    value = str(text or "").replace("～", "~").replace("至", "~").replace("－", "~")
    parts = [part for part in value.split("~") if part.strip()]
    if len(parts) < 2:
        return parse_roc(value), None
    return parse_roc(parts[0]), parse_roc(parts[1])


def parse_twse_notice(payload: dict[str, Any]) -> set[str]:
    """rwd 版注意股。stat 不是 OK（例如當天沒有注意股）時回空集合。"""
    if str(payload.get("stat", "")).upper() != "OK":
        return set()
    fields = [str(field) for field in payload.get("fields") or []]
    try:
        code_i = fields.index("證券代號")
    except ValueError:
        code_i = 1
    codes = set()
    for row in payload.get("data") or []:
        if len(row) > code_i:
            code = str(row[code_i]).strip()
            if is_common_stock(code):
                codes.add(code)
    return codes


def parse_twse_notice_openapi(payload: list[dict[str, Any]], as_of: int) -> set[str] | None:
    """OpenAPI 版只有當天。日期不是 as_of 時回 None（不可用）。"""
    codes = set()
    seen_date = None
    for item in payload or []:
        code = str(item.get("Code") or "").strip()
        day = parse_roc(str(item.get("Date") or ""))
        if day:
            seen_date = day
        if is_common_stock(code) and day == as_of:
            codes.add(code)
    if codes or seen_date == as_of:
        return codes
    return None


def parse_twse_punish(payload: list[dict[str, Any]], as_of: int) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for item in payload or []:
        code = str(item.get("Code") or "").strip()
        if not is_common_stock(code):
            continue
        start, end = parse_period(str(item.get("DispositionPeriod") or ""))
        _put_punish(result, code, start, end, str(item.get("DispositionMeasures") or "").strip(), as_of)
    return result


def parse_tpex_warning(payload: list[dict[str, Any]], as_of: int) -> set[str] | None:
    codes = set()
    latest = None
    for item in payload or []:
        day = parse_roc(str(item.get("Date") or ""))
        if day and (latest is None or day > latest):
            latest = day
        code = str(item.get("SecuritiesCompanyCode") or "").strip()
        if is_common_stock(code) and day == as_of:
            codes.add(code)
    if latest is not None and latest != as_of:
        # 櫃買只給最新一天。日期對不上時不能拿來當 as_of 的注意股。
        return None
    return codes


def parse_tpex_disposal(payload: list[dict[str, Any]], as_of: int) -> dict[str, dict]:
    result: dict[str, dict] = {}
    for item in payload or []:
        code = str(item.get("SecuritiesCompanyCode") or "").strip()
        if not is_common_stock(code):
            continue
        start, end = parse_period(str(item.get("DispositionPeriod") or ""))
        _put_punish(result, code, start, end, _tpex_measure(item), as_of)
    return result


def _tpex_measure(item: dict[str, Any]) -> str:
    text = str(item.get("DisposalCondition") or "")
    match = re.search(r"第[一二三四五六七八九十]次處置", text)
    return match.group(0) if match else "處置"


def _put_punish(result: dict[str, dict], code: str, start: int | None, end: int | None, measure: str, as_of: int) -> None:
    # 已結束的處置略過。已公告、下一個交易日才開始的處置照樣保留，App 會顯示起日。
    if end is None or end < as_of:
        return
    previous = result.get(code)
    if previous and (previous.get("end") or 0) >= end:
        return
    result[code] = {"start": start, "end": end, "measure": measure or "處置"}


def fetch_risk(client: HttpClient, as_of: int) -> dict:
    """抓四個來源。任何一個失敗只標記，不丟例外。"""
    sources: dict[str, str] = {}
    notice: set[str] = set()
    punish: dict[str, dict] = {}

    twse_notice = _try(lambda: parse_twse_notice(_get(client, TWSE_NOTICE.format(date=f"{as_of:08d}"))))
    if twse_notice is None:
        twse_notice = _try(lambda: parse_twse_notice_openapi(_get(client, TWSE_NOTICE_OPENAPI), as_of))
    sources["twseNotice"] = "ok" if twse_notice is not None else "error"
    notice |= twse_notice or set()

    twse_punish = _try(lambda: parse_twse_punish(_get(client, TWSE_PUNISH_OPENAPI), as_of))
    sources["twsePunish"] = "ok" if twse_punish is not None else "error"
    punish.update(twse_punish or {})

    tpex_notice = _try(lambda: parse_tpex_warning(_get(client, TPEX_WARNING_OPENAPI), as_of))
    sources["tpexNotice"] = "ok" if tpex_notice is not None else "error"
    notice |= tpex_notice or set()

    tpex_punish = _try(lambda: parse_tpex_disposal(_get(client, TPEX_DISPOSAL_OPENAPI), as_of))
    sources["tpexPunish"] = "ok" if tpex_punish is not None else "error"
    punish.update(tpex_punish or {})

    print(f"[risk] asOf={as_of} 注意 {len(notice)} 檔、處置 {len(punish)} 檔 sources={sources}", flush=True)
    return {
        "asOf": f"{as_of:08d}",
        "fetchedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "sources": sources,
        "notice": sorted(notice),
        "punish": {code: punish[code] for code in sorted(punish)},
    }


def refresh_risk(root: Path, client: HttpClient, as_of: int) -> bool:
    """更新 data/risk.json。回傳內容（不含抓取時間）是否改變。"""
    path = root / "data" / RISK_NAME
    before = load_risk(root)
    fresh = fetch_risk(client, as_of)
    if before and before.get("asOf") == fresh["asOf"]:
        # 同一天重跑時，這次失敗的來源沿用上次成功的結果。
        _keep_previous(before, fresh)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(fresh, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return _signature(before) != _signature(fresh)


def load_risk(root: Path) -> dict:
    path = root / "data" / RISK_NAME
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def _keep_previous(before: dict, fresh: dict) -> None:
    old_sources = before.get("sources") or {}
    new_sources = fresh["sources"]
    notice = set(fresh["notice"])
    punish = dict(fresh["punish"])
    old_notice = set(before.get("notice") or [])
    old_punish = before.get("punish") or {}
    for key in ("twseNotice", "tpexNotice"):
        if new_sources.get(key) != "ok" and old_sources.get(key) == "ok":
            # 注意股不分市場存，失敗時整份沿用舊的，寧可多標也不漏標。
            notice |= old_notice
            new_sources[key] = "stale"
    for key in ("twsePunish", "tpexPunish"):
        if new_sources.get(key) != "ok" and old_sources.get(key) == "ok":
            for code, item in old_punish.items():
                punish.setdefault(code, item)
            new_sources[key] = "stale"
    fresh["notice"] = sorted(notice)
    fresh["punish"] = {code: punish[code] for code in sorted(punish)}


def _signature(payload: dict) -> str:
    if not payload:
        return ""
    return json.dumps(
        {"asOf": payload.get("asOf"), "notice": payload.get("notice"), "punish": payload.get("punish")},
        sort_keys=True,
        ensure_ascii=False,
    )


def _get(client: HttpClient, url: str) -> Any:
    return loads_json(client.get(url, timeout=60.0, retries=2))


def _try(action):
    try:
        return action()
    except Exception as exc:  # noqa: BLE001 — 標籤是附加資訊，抓不到就略過
        print(f"[risk] {type(exc).__name__}: {exc}", flush=True)
        return None
