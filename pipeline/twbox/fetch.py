"""下載單一交易日的上市 + 上櫃普通股行情。"""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from .http_client import HttpClient
from .parse import (
    loads_json,
    parse_tpex_daily,
    parse_tpex_openapi,
    parse_twse_mi_index,
    parse_twse_openapi,
)

TWSE_MI = "https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date={date}&type=ALLBUT0999&response=json"
TWSE_OPENAPI = "https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL"
TPEX_DAILY = "https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?date={iso}&id=&response=json"
TPEX_OPENAPI = "https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes"


def _iso(yyyymmdd: str) -> str:
    return f"{yyyymmdd[:4]}/{yyyymmdd[4:6]}/{yyyymmdd[6:8]}"


def fetch_day(
    client: HttpClient,
    yyyymmdd: str,
    allow_openapi_fallback: bool = True,
    tpex_client: HttpClient | None = None,
) -> tuple[str, list[list]]:
    """回傳 (ok|closed|error, rows)。

    歷史日只用可指定日期的端點。OpenAPI 只在「最新快照的日期正好是這一天」時當備援，
    因為它忽略 date 參數。傳入兩個客戶端時，上市與上櫃同時下載，各自節流。
    """
    other = tpex_client or client
    if other is client:
        twse_status, twse_rows = _fetch_twse(client, yyyymmdd, allow_openapi_fallback)
        tpex_status, tpex_rows = _fetch_tpex(other, yyyymmdd, allow_openapi_fallback)
    else:
        with ThreadPoolExecutor(max_workers=2) as pool:
            twse_future = pool.submit(_fetch_twse, client, yyyymmdd, allow_openapi_fallback)
            tpex_future = pool.submit(_fetch_tpex, other, yyyymmdd, allow_openapi_fallback)
            twse_status, twse_rows = twse_future.result()
            tpex_status, tpex_rows = tpex_future.result()
    if twse_status == "error" or tpex_status == "error":
        return "error", []
    if twse_status == "closed" and tpex_status == "closed":
        return "closed", []
    if twse_status != "ok" or tpex_status != "ok":
        # 只有一邊有資料時不當休市，讓下一次排程重試，避免漏抓。
        print(f"[fetch] {yyyymmdd} 不完整 twse={twse_status} tpex={tpex_status}", flush=True)
        return "error", []
    print(f"[fetch] {yyyymmdd} 上市 {len(twse_rows)} 檔、上櫃 {len(tpex_rows)} 檔", flush=True)
    return "ok", twse_rows + tpex_rows


def _fetch_twse(client: HttpClient, yyyymmdd: str, allow_openapi_fallback: bool) -> tuple[str, list[list]]:
    body = client.get(TWSE_MI.format(date=yyyymmdd))
    status, rows = parse_twse_mi_index(loads_json(body))
    if status == "ok" or not allow_openapi_fallback:
        return status, rows
    if status == "closed":
        return status, rows
    print(f"[fetch] TWSE MI_INDEX {yyyymmdd} 失敗，改試 OpenAPI 最新快照", flush=True)
    payload = loads_json(client.get(TWSE_OPENAPI))
    open_status, open_rows, roc = parse_twse_openapi(payload)
    if open_status == "ok" and roc and _roc_to_yyyymmdd(roc) == yyyymmdd:
        return "ok", open_rows
    return status, rows


def _fetch_tpex(client: HttpClient, yyyymmdd: str, allow_openapi_fallback: bool) -> tuple[str, list[list]]:
    body = client.get(TPEX_DAILY.format(iso=_iso(yyyymmdd)))
    status, rows = parse_tpex_daily(loads_json(body))
    if status == "ok" or status == "closed" or not allow_openapi_fallback:
        return status, rows
    print(f"[fetch] TPEx dailyQuotes {yyyymmdd} 失敗，改試 OpenAPI 最新快照", flush=True)
    payload = loads_json(client.get(TPEX_OPENAPI))
    open_status, open_rows, roc = parse_tpex_openapi(payload)
    if open_status == "ok" and roc and _roc_to_yyyymmdd(roc) == yyyymmdd:
        return "ok", open_rows
    return status, rows


def _roc_to_yyyymmdd(value: str) -> str:
    text = value.strip()
    if len(text) == 7 and text.isdigit():
        return f"{int(text[:3]) + 1911:04d}{text[3:5]}{text[5:7]}"
    if len(text) == 6 and text.isdigit():
        return f"{int(text[:2]) + 1911:04d}{text[2:4]}{text[4:6]}"
    return text
