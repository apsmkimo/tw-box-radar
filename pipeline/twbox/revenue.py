"""每月營收（單位：千元）。

存 data/revenue.json：{"months": {代號: {"YYYYMM": 營收}}, "pages": [已抓過的 MOPS 月份]}。
每檔只留最近 KEEP_MONTHS 個月，足夠算年增、月增與「創 12 個月新高」。

來源（2026-10 實測）：
- 上市 OpenAPI `https://openapi.twse.com.tw/v1/opendata/t187ap05_L`
- 上櫃 OpenAPI `https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O`
  兩者欄位相同：資料年月（民國 YYYMM）、公司代號、營業收入-當月營收、營業收入-上月營收、
  營業收入-去年當月營收。只有最新一個月，所以每次把三個數字都存進去。
- 補過去 12 個月用公開資訊觀測站靜態彙總表
  `https://mopsov.twse.com.tw/nas/t21/{sii|otc}/t21sc03_{民國年}_{月}_{0|1}.html`
  （0 國內公司、1 外國公司 KY，Big5）。每頁同樣有當月、上月、去年當月，
  缺哪個月就抓那個月的頁（同時帶上月）。每次排程最多補一頁（4 個請求），幾天後補齊。
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from .http_client import HttpClient
from .parse import is_common_stock, loads_json

TWSE_REVENUE = "https://openapi.twse.com.tw/v1/opendata/t187ap05_L"
TPEX_REVENUE = "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O"
MOPS_PAGE = "https://mopsov.twse.com.tw/nas/t21/{market}/t21sc03_{roc}_{month}_{kind}.html"
KEEP_MONTHS = 15
NAME = "revenue.json"


def shift_month(yyyymm: int, delta: int) -> int:
    year, month = divmod(yyyymm, 100)
    index = year * 12 + (month - 1) + delta
    return (index // 12) * 100 + index % 12 + 1


def _roc_month(text: str) -> int | None:
    value = str(text or "").strip()
    if not value.isdigit() or len(value) not in (4, 5):
        return None
    year, month = int(value[:-2]) + 1911, int(value[-2:])
    if not 1 <= month <= 12:
        return None
    return year * 100 + month


def _number(value: Any) -> int | None:
    text = str(value if value is not None else "").strip().replace(",", "")
    if not text or text in {"-", "--"}:
        return None
    try:
        return int(round(float(text)))
    except ValueError:
        return None


def parse_openapi(payload: list[dict[str, Any]]) -> dict[str, dict[int, int]]:
    """回傳 {代號: {YYYYMM: 營收}}，含當月、上月、去年當月。"""
    result: dict[str, dict[int, int]] = {}
    for item in payload or []:
        code = str(item.get("公司代號") or "").strip()
        month = _roc_month(str(item.get("資料年月") or ""))
        if not is_common_stock(code) or month is None:
            continue
        values = {
            month: _number(item.get("營業收入-當月營收")),
            shift_month(month, -1): _number(item.get("營業收入-上月營收")),
            shift_month(month, -12): _number(item.get("營業收入-去年當月營收")),
        }
        result[code] = {key: value for key, value in values.items() if value is not None}
    return result


_ROW = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S | re.I)
_CELL = re.compile(r"<td[^>]*>(.*?)</td>", re.S | re.I)
_TAG = re.compile(r"<[^>]+>")


def parse_mops_page(html: str, month: int) -> dict[str, dict[int, int]]:
    """觀測站彙總表：每列 代號、名稱、當月、上月、去年當月、……。"""
    result: dict[str, dict[int, int]] = {}
    for row in _ROW.findall(html):
        cells = [_TAG.sub("", cell).replace("&nbsp;", "").strip() for cell in _CELL.findall(row)]
        if len(cells) < 5 or not is_common_stock(cells[0]):
            continue
        values = {
            month: _number(cells[2]),
            shift_month(month, -1): _number(cells[3]),
            shift_month(month, -12): _number(cells[4]),
        }
        result[cells[0]] = {key: value for key, value in values.items() if value is not None}
    return result


def load_revenue(root: Path) -> dict:
    path = root / "data" / NAME
    if not path.exists():
        return {"months": {}, "pages": []}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {"months": {}, "pages": []}
    payload.setdefault("months", {})
    payload.setdefault("pages", [])
    return payload


def merge(store: dict, parsed: dict[str, dict[int, int]]) -> None:
    months = store["months"]
    for code, values in parsed.items():
        target = months.setdefault(code, {})
        for month, value in values.items():
            target[str(month)] = value


def trim(store: dict) -> None:
    for code, values in store["months"].items():
        keys = sorted(values, key=int)[-KEEP_MONTHS:]
        store["months"][code] = {key: values[key] for key in keys}


def latest_month(store: dict) -> int | None:
    best = None
    for values in store["months"].values():
        for key in values:
            month = int(key)
            if best is None or month > best:
                best = month
    return best


def missing_pages(store: dict) -> list[int]:
    """還要補的觀測站月份頁，由新到舊。

    看最新月往前 11 個月，若有該月資料的公司不到有最新月公司的一半，就排這個月的頁
    （每頁同時帶上月，所以補一頁通常補到兩個月）。抓過的頁記在 pages，不重抓。
    """
    latest = latest_month(store)
    if latest is None:
        return []
    key = str(latest)
    base = [values for values in store["months"].values() if key in values]
    done = {int(page) for page in store.get("pages", [])}
    pages: list[int] = []
    covered: set[int] = set()
    for gap in range(1, 12):
        month = shift_month(latest, -gap)
        if month in covered:
            continue
        have = sum(1 for values in base if str(month) in values)
        if have * 2 < len(base) and month not in done:
            pages.append(month)
            covered.update({month, shift_month(month, -1)})
    return pages


def refresh_revenue(root: Path, client: HttpClient, seed_pages: int = 1) -> bool:
    """抓最新月營收，並最多補 seed_pages 頁歷史。任何失敗只略過。"""
    store = load_revenue(root)
    before = json.dumps(store["months"], sort_keys=True)
    for url in (TWSE_REVENUE, TPEX_REVENUE):
        try:
            merge(store, parse_openapi(loads_json(client.get(url, timeout=90.0, retries=2))))
        except Exception as exc:  # noqa: BLE001 — 營收是附加資訊
            print(f"[revenue] {url} 失敗：{exc}", flush=True)
    for month in missing_pages(store)[:seed_pages]:
        if _fetch_mops_month(client, store, month):
            store["pages"] = sorted({*store.get("pages", []), month})
    trim(store)
    path = root / "data" / NAME
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(store, separators=(",", ":"), sort_keys=True) + "\n", encoding="utf-8")
    return json.dumps(store["months"], sort_keys=True) != before


def _fetch_mops_month(client: HttpClient, store: dict, month: int) -> bool:
    year, mm = divmod(month, 100)
    ok = True
    for market in ("sii", "otc"):
        for kind in (0, 1):
            url = MOPS_PAGE.format(market=market, roc=year - 1911, month=mm, kind=kind)
            try:
                html = client.get(url, timeout=60.0, retries=2).decode("big5", errors="replace")
                merge(store, parse_mops_page(html, month))
            except Exception as exc:  # noqa: BLE001
                print(f"[revenue] {url} 失敗：{exc}", flush=True)
                ok = False
    print(f"[revenue] 補 {month} {'完成' if ok else '部分失敗，下次再試'}", flush=True)
    return ok


def summarize(store: dict) -> dict[str, list]:
    """每檔 [資料月 YYYYMM, 當月營收(千元), 年增率, 月增率, 創 12 個月新高(1/0/None)]。

    年增率、月增率是小數（0.12 表示 12%），基期為 0 或缺資料時為 None。
    創 12 個月新高：當月 ≥ 前 11 個月的最大值；前 11 個月缺任何一個月時為 None（資料不足）。
    """
    result: dict[str, list] = {}
    for code, values in store["months"].items():
        if not values:
            continue
        month = max(int(key) for key in values)
        current = values.get(str(month))
        if current is None:
            continue
        previous = values.get(str(shift_month(month, -1)))
        last_year = values.get(str(shift_month(month, -12)))
        window = [values.get(str(shift_month(month, -gap))) for gap in range(1, 12)]
        if any(value is None for value in window):
            high = None
        else:
            high = 1 if current >= max(window) else 0  # type: ignore[type-var]
        result[code] = [month, current, _growth(current, last_year), _growth(current, previous), high]
    return result


def _growth(current: int, base: int | None) -> float | None:
    if base is None or base <= 0:
        return None
    return round(current / base - 1.0, 4)
