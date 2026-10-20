"""下載證交所 TWT49U 與櫃買除權息計算表，存成 data/exrights.json。

價格因子用官方「除權息參考價／除權息前收盤價」，不自行重算公式。
上市普通股若當次含「權」，再打 TWT49U 明細，讀
「每千股無償配股」與「按股東持股比例每千股認購」。
上櫃這兩欄就在計算表裡。純除息不打明細，成交量因子為 1。
"""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from .adjust import ExEvent
from .http_client import HttpClient
from .parse import is_common_stock, parse_number

CACHE_NAME = "exrights.json"
REFERER = "https://www.twse.com.tw/zh/announcement/ex-right/twt49u.html"
# 已結束超過這個天數的區間沿用快取；較近的區間每次重抓，新的除權息日才補得進來。
RECENT_DAYS = 120


def load_event_map(root: Path) -> dict[str, list[ExEvent]]:
    payload = _read_cache(root)
    grouped: dict[str, list[ExEvent]] = {}
    for item in payload.get("events", []):
        price = float(item.get("priceFactor") or 0)
        volume = float(item.get("volumeFactor") or 0)
        code = str(item.get("code") or "")
        if not code or price <= 0 or volume <= 0:
            continue
        grouped.setdefault(code, []).append(
            ExEvent(date=int(item["date"]), code=code, price_factor=price, volume_factor=volume)
        )
    for events in grouped.values():
        events.sort(key=lambda event: event.date)
    return grouped


def refresh_exrights(
    root: Path,
    client: HttpClient,
    start: date,
    end: date,
    tpex_client: HttpClient | None = None,
) -> bool:
    """合併官方除權息。回傳事件內容是否和快取不同。"""
    if end < start:
        raise ValueError("除權息結束日早於開始日")
    tpex = tpex_client or client
    payload = _read_cache(root)
    store = {(item["code"], int(item["date"])): item for item in payload.get("events", [])}
    done = set(payload.get("summaryDone", []))
    before = _event_signature(store)
    cutoff = (end - timedelta(days=RECENT_DAYS)).strftime("%Y%m%d")

    for span_start, span_end in iter_quarters(start, end):
        key = f"twse:{span_start:%Y%m%d}-{span_end:%Y%m%d}"
        if key in done and span_end.strftime("%Y%m%d") < cutoff:
            continue
        print(f"[exrights] 上市 {span_start:%Y%m%d}-{span_end:%Y%m%d}", flush=True)
        body = fetch_json(client, twse_summary_url(span_start, span_end), {"Referer": REFERER})
        if not stat_ok(body):
            raise RuntimeError(f"證交所除權息表失敗：{body.get('stat')}")
        for event in parse_twse_summary(body):
            _upsert(store, event)
        done.add(key)
        _write_cache(root, store, done)

    for span_start, span_end in iter_months(start, end):
        key = f"tpex:{span_start:%Y%m%d}-{span_end:%Y%m%d}"
        if key in done and span_end.strftime("%Y%m%d") < cutoff:
            continue
        print(f"[exrights] 上櫃 {span_start:%Y%m%d}-{span_end:%Y%m%d}", flush=True)
        body = fetch_json(tpex, tpex_url(span_start, span_end))
        if not stat_ok(body):
            raise RuntimeError(f"櫃買除權息表失敗：{body.get('stat')}")
        for event in parse_tpex_exrights(body):
            _upsert(store, event)
        done.add(key)
        _write_cache(root, store, done)

    pending = [item for item in store.values() if item.get("needsDetail") and not item.get("volumeKnown")]
    print(f"[exrights] 上市明細 {len(pending)} 筆", flush=True)
    for index, item in enumerate(pending, start=1):
        url = twse_detail_url(item["code"], f"{int(item['date']):08d}")
        try:
            body = fetch_json(client, url, {"Referer": REFERER})
        except Exception as exc:  # noqa: BLE001 — 單筆失敗留到下次
            print(f"[exrights] 明細 {item['code']} {item['date']} 失敗：{exc}", flush=True)
            continue
        if not stat_ok(body):
            print(f"[exrights] 明細 {item['code']} {item['date']}：{body.get('stat')}", flush=True)
            continue
        stock_ratio, cash_ratio = parse_twse_detail(body)
        item["stockRatio"] = stock_ratio
        item["cashRatio"] = cash_ratio
        item["volumeFactor"] = 1.0 + stock_ratio + cash_ratio
        item["volumeKnown"] = True
        if index % 10 == 0:
            print(f"[exrights] 明細 {index}/{len(pending)}", flush=True)
            _write_cache(root, store, done)
    _write_cache(root, store, done)
    changed = _event_signature(store) != before
    print(f"[exrights] 事件 {len(store)} 筆，變更={changed}", flush=True)
    return changed


def parse_twse_summary(payload: dict) -> list[dict]:
    events: list[dict] = []
    for table in _tables(payload):
        fields = table.get("fields") or []
        date_i = _field_index(fields, "資料日期")
        code_i = _field_index(fields, "股票代號")
        prev_i = _field_index(fields, "除權息前收盤價")
        ref_i = _field_index(fields, "除權息參考價")
        kind_i = _field_index(fields, "權/息")
        if None in {date_i, code_i, prev_i, ref_i, kind_i}:
            continue
        for raw in table.get("data") or []:
            code = str(raw[code_i]).strip()
            if not is_common_stock(code):
                continue
            ex_date = parse_roc_date(str(raw[date_i]))
            prev = parse_number(raw[prev_i])
            ref = parse_number(raw[ref_i])
            if ex_date is None or prev is None or ref is None:
                continue
            kind = str(raw[kind_i]).strip()
            needs_detail = "權" in kind
            events.append(
                _event(
                    ex_date,
                    code,
                    "twse",
                    prev,
                    ref,
                    stock_ratio=0.0,
                    cash_ratio=0.0,
                    volume_known=not needs_detail,
                    needs_detail=needs_detail,
                )
            )
    return events


def parse_twse_detail(payload: dict) -> tuple[float, float]:
    """回傳（無償配股率, 現金增資認購率），都是每股比率，不是每千股。"""
    for table in _tables(payload):
        fields = table.get("fields") or []
        data = table.get("data") or []
        if not data:
            continue
        row = data[0]
        stock_i = _field_index(fields, "每千股無償配股", "每仟股無償配股")
        cash_i = _field_index(fields, "按股東持股比例每千股認購", "按持股比例每千股認購", "按持股比例仟股認購")
        stock = per_thousand(row[stock_i]) if stock_i is not None and stock_i < len(row) else 0.0
        cash = per_thousand(row[cash_i]) if cash_i is not None and cash_i < len(row) else 0.0
        return max(stock, 0.0), max(cash, 0.0)
    return 0.0, 0.0


def parse_tpex_exrights(payload: dict) -> list[dict]:
    events: list[dict] = []
    for table in _tables(payload):
        fields = table.get("fields") or []
        date_i = _field_index(fields, "除權息日期")
        code_i = _field_index(fields, "代號", "股票代號")
        prev_i = _field_index(fields, "除權息前收盤價")
        ref_i = _field_index(fields, "除權息參考價")
        stock_i = _field_index(fields, "每仟股無償配股", "每千股無償配股")
        cash_i = _field_index(fields, "按持股比例仟股認購", "按持股比例每千股認購")
        if None in {date_i, code_i, prev_i, ref_i}:
            continue
        for raw in table.get("data") or []:
            code = str(raw[code_i]).strip()
            if not is_common_stock(code):
                continue
            ex_date = parse_roc_date(str(raw[date_i]))
            prev = parse_number(raw[prev_i])
            ref = parse_number(raw[ref_i])
            if ex_date is None or prev is None or ref is None:
                continue
            stock = per_thousand(raw[stock_i]) if stock_i is not None else 0.0
            cash = per_thousand(raw[cash_i]) if cash_i is not None else 0.0
            events.append(
                _event(
                    ex_date,
                    code,
                    "tpex",
                    prev,
                    ref,
                    stock_ratio=max(stock, 0.0),
                    cash_ratio=max(cash, 0.0),
                    volume_known=True,
                    needs_detail=False,
                )
            )
    return events


def parse_roc_date(text: str) -> int | None:
    digits = []
    number = ""
    for char in text:
        if char.isdigit():
            number += char
        elif number:
            digits.append(int(number))
            number = ""
    if number:
        digits.append(int(number))
    if len(digits) < 3:
        return None
    year, month, day = digits[0], digits[1], digits[2]
    if year < 1911:
        year += 1911
    if not (1 <= month <= 12 and 1 <= day <= 31):
        return None
    return year * 10000 + month * 100 + day


def per_thousand(value: object) -> float:
    text = (
        str(value)
        .replace(",", "")
        .replace("股", "")
        .replace("元／股", "")
        .replace("元/股", "")
        .strip()
    )
    if text in {"", "-", "--", "---", "----"}:
        return 0.0
    try:
        return float(text) / 1000.0
    except ValueError:
        return 0.0


def iter_quarters(start: date, end: date):
    month = ((start.month - 1) // 3) * 3 + 1
    cursor = date(start.year, month, 1)
    while cursor <= end:
        month_index = cursor.month + 3
        year = cursor.year + (month_index - 1) // 12
        month_index = (month_index - 1) % 12 + 1
        nxt = date(year, month_index, 1)
        last = nxt - timedelta(days=1)
        yield max(cursor, start), min(last, end)
        cursor = nxt


def iter_months(start: date, end: date):
    cursor = date(start.year, start.month, 1)
    while cursor <= end:
        if cursor.month == 12:
            nxt = date(cursor.year + 1, 1, 1)
        else:
            nxt = date(cursor.year, cursor.month + 1, 1)
        last = nxt - timedelta(days=1)
        yield max(cursor, start), min(last, end)
        cursor = nxt


def twse_summary_url(start: date, end: date) -> str:
    return (
        "https://www.twse.com.tw/exchangeReport/TWT49U"
        f"?response=json&startDate={start:%Y%m%d}&endDate={end:%Y%m%d}"
    )


def twse_detail_url(code: str, yyyymmdd: str) -> str:
    return (
        "https://www.twse.com.tw/rwd/zh/exRight/TWT49UDetail"
        f"?response=json&STK_NO={code}&T1={yyyymmdd}"
    )


def tpex_url(start: date, end: date) -> str:
    return (
        "https://www.tpex.org.tw/web/stock/exright/dailyquo/exDailyQ_result.php"
        f"?l=zh-tw&d={_roc_slash(start)}&ed={_roc_slash(end)}&o=json"
    )


def fetch_json(client: HttpClient, url: str, headers: dict[str, str] | None = None) -> dict:
    body = client.get(url, headers=headers)
    return json.loads(body.decode("utf-8-sig"))


def stat_ok(payload: dict) -> bool:
    return str(payload.get("stat") or payload.get("status") or "").lower() == "ok"


def _event(
    ex_date: int,
    code: str,
    market: str,
    prev: float,
    ref: float,
    stock_ratio: float,
    cash_ratio: float,
    volume_known: bool,
    needs_detail: bool,
) -> dict:
    return {
        "date": ex_date,
        "code": code,
        "market": market,
        "prev": prev,
        "ref": ref,
        "priceFactor": ref / prev,
        "volumeFactor": 1.0 + stock_ratio + cash_ratio,
        "stockRatio": stock_ratio,
        "cashRatio": cash_ratio,
        "volumeKnown": volume_known,
        "needsDetail": needs_detail,
    }


def _upsert(store: dict[tuple[str, int], dict], event: dict) -> None:
    key = (event["code"], int(event["date"]))
    old = store.get(key)
    if old and old.get("volumeKnown") and not event.get("volumeKnown"):
        event["stockRatio"] = old.get("stockRatio", 0.0)
        event["cashRatio"] = old.get("cashRatio", 0.0)
        event["volumeFactor"] = old.get("volumeFactor", 1.0)
        event["volumeKnown"] = True
    store[key] = event


def _tables(payload: dict) -> list[dict]:
    tables = payload.get("tables")
    if isinstance(tables, list) and tables:
        return tables
    return [payload]


def _field_index(fields: list, *names: str) -> int | None:
    normalized = [str(field).replace(" ", "").replace("\n", "") for field in fields]
    for name in names:
        key = name.replace(" ", "")
        for index, field in enumerate(normalized):
            if field == key or key in field:
                return index
    return None


def _roc_slash(day: date) -> str:
    return f"{day.year - 1911}/{day.month:02d}/{day.day:02d}"


def _cache_path(root: Path) -> Path:
    return root / "data" / CACHE_NAME


def _read_cache(root: Path) -> dict:
    path = _cache_path(root)
    if not path.exists():
        return {"events": [], "summaryDone": []}
    return json.loads(path.read_text(encoding="utf-8"))


def _write_cache(root: Path, store: dict[tuple[str, int], dict], done: set[str]) -> None:
    path = _cache_path(root)
    path.parent.mkdir(parents=True, exist_ok=True)
    events = [store[key] for key in sorted(store)]
    payload = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "summaryDone": sorted(done),
        "events": events,
    }
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    temporary.replace(path)


def _event_signature(store: dict[tuple[str, int], dict]) -> list:
    signature = []
    for key in sorted(store):
        item = store[key]
        signature.append(
            (
                key[0],
                key[1],
                round(float(item.get("priceFactor") or 0), 8),
                round(float(item.get("volumeFactor") or 0), 8),
                bool(item.get("volumeKnown")),
            )
        )
    return signature
