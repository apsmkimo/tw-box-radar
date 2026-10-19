"""把證交所、櫃買中心的每日行情回應轉成統一列。

只保留普通股：代號為 4 碼數字且不以 0 開頭（排除 ETF、權證、特別股）。
價格未還原除權息，與官方成交價一致。
"""

from __future__ import annotations

import json
import re
from typing import Any

STOCK_CODE = re.compile(r"^[1-9]\d{3}$")


def parse_number(value: Any) -> float | None:
    if value is None:
        return None
    text = str(value).strip().replace(",", "")
    if text in {"", "--", "---", "----", "X", "null", "None", "N/A"}:
        return None
    # 櫃買漲跌欄可能帶正負號與空白，價格欄不應走到這裡；保險起見去掉非數字尾巴以外的空白。
    text = text.replace(" ", "")
    try:
        number = float(text)
    except ValueError:
        return None
    if number <= 0:
        return None
    return number


def is_common_stock(code: str) -> bool:
    return STOCK_CODE.fullmatch(code.strip()) is not None


def _field_index(fields: list[str], *candidates: str) -> int | None:
    normalized = [str(f).replace(" ", "").replace("\n", "") for f in fields]
    for candidate in candidates:
        key = candidate.replace(" ", "")
        for index, field in enumerate(normalized):
            if field == key or field.startswith(key):
                return index
    return None


def rows_from_table(fields: list[str], data: list[list[Any]], market: str) -> list[list[Any]]:
    code_i = _field_index(fields, "證券代號", "代號")
    name_i = _field_index(fields, "證券名稱", "名稱")
    open_i = _field_index(fields, "開盤價", "開盤")
    high_i = _field_index(fields, "最高價", "最高")
    low_i = _field_index(fields, "最低價", "最低")
    close_i = _field_index(fields, "收盤價", "收盤")
    volume_i = _field_index(fields, "成交股數")
    if None in {code_i, name_i, open_i, high_i, low_i, close_i, volume_i}:
        raise ValueError(f"無法辨識欄位：{fields}")
    rows: list[list[Any]] = []
    for raw in data:
        if not raw or len(raw) <= max(code_i, name_i, open_i, high_i, low_i, close_i, volume_i):  # type: ignore[type-var]
            continue
        code = str(raw[code_i]).strip()  # type: ignore[index]
        if not is_common_stock(code):
            continue
        prices = [parse_number(raw[i]) for i in (open_i, high_i, low_i, close_i)]  # type: ignore[index]
        volume = parse_number(raw[volume_i])  # type: ignore[index]
        if any(p is None for p in prices) or volume is None:
            continue
        open_, high, low, close = prices  # type: ignore[misc]
        if high < low:
            continue
        rows.append(
            [
                code,
                str(raw[name_i]).strip(),  # type: ignore[index]
                market,
                round(float(open_), 4),
                round(float(high), 4),
                round(float(low), 4),
                round(float(close), 4),
                int(volume),
            ]
        )
    return rows


def parse_twse_mi_index(payload: dict[str, Any]) -> tuple[str, list[list[Any]]]:
    """回傳 (狀態, rows)。狀態為 ok / closed / error。"""
    stat = str(payload.get("stat", ""))
    if stat != "OK":
        if "沒有符合條件" in stat or stat.lower() in {"no data", "nodata"}:
            return "closed", []
        return "error", []
    tables = payload.get("tables") or []
    for table in tables:
        fields = table.get("fields") or []
        joined = "".join(str(f) for f in fields)
        if "證券代號" in joined and "收盤價" in joined:
            return "ok", rows_from_table(fields, table.get("data") or [], "twse")
    return "error", []


def parse_twse_openapi(payload: list[dict[str, Any]]) -> tuple[str, list[list[Any]], str | None]:
    """OpenAPI STOCK_DAY_ALL。只含最新交易日，無法指定歷史日期。"""
    if not payload:
        return "closed", [], None
    trade_date = None
    raw_rows: list[list[Any]] = []
    fields = [
        "Code",
        "Name",
        "OpeningPrice",
        "HighestPrice",
        "LowestPrice",
        "ClosingPrice",
        "TradeVolume",
    ]
    for item in payload:
        if trade_date is None and item.get("Date"):
            trade_date = str(item["Date"])
        raw_rows.append([item.get(name, "") for name in fields])
    rows = rows_from_table(fields, raw_rows, "twse")
    return ("ok" if rows else "closed"), rows, trade_date


def parse_tpex_daily(payload: dict[str, Any]) -> tuple[str, list[list[Any]]]:
    tables = payload.get("tables") or []
    if not tables:
        stat = str(payload.get("stat", ""))
        if stat and stat not in {"ok", "OK"}:
            return "closed", []
        return "error", []
    table = tables[0]
    total = table.get("totalCount")
    listed = str(table.get("listedCompanies") or "")
    if total == 0 or listed in {"0", "0家"}:
        return "closed", []
    fields = table.get("fields") or []
    rows = rows_from_table(fields, table.get("data") or [], "tpex")
    if not rows:
        return "closed", []
    return "ok", rows


def parse_tpex_openapi(payload: list[dict[str, Any]]) -> tuple[str, list[list[Any]], str | None]:
    if not payload:
        return "closed", [], None
    trade_date = None
    raw_rows: list[list[Any]] = []
    fields = [
        "SecuritiesCompanyCode",
        "CompanyName",
        "Open",
        "High",
        "Low",
        "Close",
        "TradingShares",
    ]
    for item in payload:
        if trade_date is None and item.get("Date"):
            trade_date = str(item["Date"])
        raw_rows.append(
            [
                item.get("SecuritiesCompanyCode", ""),
                item.get("CompanyName", ""),
                item.get("Open", ""),
                item.get("High", ""),
                item.get("Low", ""),
                item.get("Close", ""),
                item.get("TradingShares", ""),
            ]
        )
    rows = rows_from_table(fields, raw_rows, "tpex")
    return ("ok" if rows else "closed"), rows, trade_date


def loads_json(body: bytes) -> Any:
    text = body.decode("utf-8-sig")
    return json.loads(text)
