"""三大法人每日買賣超。

只從開始執行的那天往後累積，不回補多年歷史。每個交易日存一個小檔
data/chips/YYYYMMDD.json：{代號: [外資, 投信, 自營商]}，單位是股、買賣超淨額。
只留最近 KEEP_DAYS 個交易日，較舊的檔案刪掉。

來源（2026-10 實測）：
- 上市 T86：`https://www.twse.com.tw/rwd/zh/fund/T86?date=YYYYMMDD&selectType=ALLBUT0999&response=json`
  外資＝「外陸資買賣超股數(不含外資自營商)」＋「外資自營商買賣超股數」；
  投信＝「投信買賣超股數」；自營商＝「自營商買賣超股數」（自行買賣＋避險）。
- 上櫃：`https://www.tpex.org.tw/www/zh-tw/insti/dailyTrade?type=Daily&sect=EW&date=YYYY/MM/DD&response=json`
  欄名重複，只能看位置：第 10 欄外資及陸資合計、第 13 欄投信、第 22 欄自營商合計、第 23 欄三大法人合計。
  解析時用「三者相加＝合計」檢查欄位沒有位移。
  備援 OpenAPI `https://www.tpex.org.tw/openapi/v1/tpex_3insti_daily_trading`（只有最新一天）。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .http_client import HttpClient
from .parse import is_common_stock, loads_json

TWSE_T86 = "https://www.twse.com.tw/rwd/zh/fund/T86?date={date}&selectType=ALLBUT0999&response=json"
TPEX_INSTI = "https://www.tpex.org.tw/www/zh-tw/insti/dailyTrade?type=Daily&sect=EW&date={iso}&response=json"
TPEX_INSTI_OPENAPI = "https://www.tpex.org.tw/openapi/v1/tpex_3insti_daily_trading"
KEEP_DAYS = 60


def _int(value: Any) -> int | None:
    text = str(value if value is not None else "").strip().replace(",", "")
    if text in {"", "--", "-"}:
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def parse_twse_t86(payload: dict[str, Any]) -> dict[str, list[int]] | None:
    """回傳 {代號: [外資, 投信, 自營商]}。stat 不是 OK 時回 None。"""
    if str(payload.get("stat", "")).upper() != "OK":
        return None
    fields = [str(field).replace(" ", "") for field in payload.get("fields") or []]

    def index(name: str) -> int | None:
        return fields.index(name) if name in fields else None

    code_i = index("證券代號")
    foreign_i = index("外陸資買賣超股數(不含外資自營商)")
    foreign_dealer_i = index("外資自營商買賣超股數")
    trust_i = index("投信買賣超股數")
    dealer_i = index("自營商買賣超股數")
    if None in (code_i, foreign_i, trust_i, dealer_i):
        raise ValueError(f"T86 欄位無法辨識：{fields}")
    result: dict[str, list[int]] = {}
    for row in payload.get("data") or []:
        code = str(row[code_i]).strip()  # type: ignore[index]
        if not is_common_stock(code):
            continue
        foreign = _int(row[foreign_i]) or 0  # type: ignore[index]
        if foreign_dealer_i is not None:
            foreign += _int(row[foreign_dealer_i]) or 0
        result[code] = [foreign, _int(row[trust_i]) or 0, _int(row[dealer_i]) or 0]  # type: ignore[index]
    return result


def parse_tpex_insti(payload: dict[str, Any]) -> dict[str, list[int]] | None:
    tables = payload.get("tables") or []
    if not tables:
        return None
    table = tables[0]
    data = table.get("data") or []
    if not data:
        return None
    result: dict[str, list[int]] = {}
    for row in data:
        if len(row) < 24:
            continue
        code = str(row[0]).strip()
        if not is_common_stock(code):
            continue
        foreign, trust, dealer, total = (_int(row[i]) for i in (10, 13, 22, 23))
        if None in (foreign, trust, dealer, total):
            continue
        if foreign + trust + dealer != total:  # type: ignore[operator]
            raise ValueError(f"櫃買三大法人欄位位移：{row}")
        result[code] = [foreign, trust, dealer]  # type: ignore[list-item]
    return result


def parse_tpex_insti_openapi(payload: list[dict[str, Any]], yyyymmdd: int) -> dict[str, list[int]] | None:
    from .risk import parse_roc

    result: dict[str, list[int]] = {}
    for item in payload or []:
        if parse_roc(str(item.get("Date") or "")) != yyyymmdd:
            return None
        code = str(item.get("SecuritiesCompanyCode") or "").strip()
        if not is_common_stock(code):
            continue
        foreign = _int(item.get("ForeignInvestorsInclude MainlandAreaInvestors-Difference"))
        trust = _int(item.get("SecuritiesInvestmentTrustCompanies-Difference"))
        dealer = _int(item.get("Dealers-Difference"))
        if None in (foreign, trust, dealer):
            continue
        result[code] = [foreign, trust, dealer]  # type: ignore[list-item]
    return result or None


def fetch_chips(client: HttpClient, yyyymmdd: int) -> dict[str, list[int]] | None:
    """上市與上櫃都拿到才回傳；任何一邊失敗回 None，下次排程再試。"""
    day = f"{yyyymmdd:08d}"
    try:
        twse = parse_twse_t86(loads_json(client.get(TWSE_T86.format(date=day), timeout=60.0, retries=2)))
    except Exception as exc:  # noqa: BLE001
        print(f"[chips] T86 {day} 失敗：{exc}", flush=True)
        twse = None
    tpex = None
    try:
        iso = f"{day[:4]}/{day[4:6]}/{day[6:]}"
        tpex = parse_tpex_insti(loads_json(client.get(TPEX_INSTI.format(iso=iso), timeout=60.0, retries=2)))
    except Exception as exc:  # noqa: BLE001
        print(f"[chips] 櫃買三大法人 {day} 失敗：{exc}", flush=True)
    if tpex is None:
        try:
            tpex = parse_tpex_insti_openapi(loads_json(client.get(TPEX_INSTI_OPENAPI, timeout=60.0, retries=2)), yyyymmdd)
        except Exception as exc:  # noqa: BLE001
            print(f"[chips] 櫃買 OpenAPI 失敗：{exc}", flush=True)
    if not twse or not tpex:
        print(f"[chips] {day} 不完整 twse={bool(twse)} tpex={bool(tpex)}，這次不存", flush=True)
        return None
    print(f"[chips] {day} 上市 {len(twse)} 檔、上櫃 {len(tpex)} 檔", flush=True)
    return {**twse, **tpex}


def chips_dir(root: Path) -> Path:
    return root / "data" / "chips"


def save_day(root: Path, yyyymmdd: int, rows: dict[str, list[int]]) -> None:
    folder = chips_dir(root)
    folder.mkdir(parents=True, exist_ok=True)
    payload = {code: rows[code] for code in sorted(rows)}
    (folder / f"{yyyymmdd:08d}.json").write_text(
        json.dumps(payload, separators=(",", ":")) + "\n", encoding="utf-8"
    )
    prune(root)


def prune(root: Path, keep: int = KEEP_DAYS) -> None:
    files = sorted(chips_dir(root).glob("*.json"))
    for old in files[:-keep] if len(files) > keep else []:
        old.unlink()


def stored_days(root: Path) -> list[int]:
    folder = chips_dir(root)
    if not folder.exists():
        return []
    return sorted(int(path.stem) for path in folder.glob("*.json") if path.stem.isdigit())


def load_days(root: Path) -> list[tuple[int, dict[str, list[int]]]]:
    out = []
    for day in stored_days(root)[-KEEP_DAYS:]:
        path = chips_dir(root) / f"{day:08d}.json"
        try:
            out.append((day, json.loads(path.read_text(encoding="utf-8"))))
        except (OSError, ValueError):
            continue
    return out


def refresh_chips(root: Path, client: HttpClient, trading_days: list[int]) -> bool:
    """補抓 trading_days 裡還沒有檔案的日期（呼叫端只傳最近幾個交易日）。"""
    have = set(stored_days(root))
    changed = False
    for day in trading_days:
        if day in have:
            continue
        rows = fetch_chips(client, day)
        if rows:
            save_day(root, day, rows)
            changed = True
    return changed


def summarize(days: list[tuple[int, dict[str, list[int]]]]) -> dict[str, list[int]]:
    """每檔 [外資5日, 外資20日, 投信5日, 投信20日, 自營5日, 自營20日, 投信連買天數, 外資連買天數]。

    買賣超換算成張（股 ÷ 1000，四捨五入）。當天沒出現在名單的股票視為 0。
    連買天數是從最新一天往回數，淨買超（> 0）連續幾天。
    """
    if not days:
        return {}
    codes: set[str] = set()
    for _, rows in days:
        codes.update(rows)
    last5 = days[-5:]
    last20 = days[-20:]
    result: dict[str, list[int]] = {}
    for code in sorted(codes):
        values = []
        for column in range(3):
            values.append(_lots(sum(rows.get(code, [0, 0, 0])[column] for _, rows in last5)))
            values.append(_lots(sum(rows.get(code, [0, 0, 0])[column] for _, rows in last20)))
        values.append(_streak(days, code, 1))
        values.append(_streak(days, code, 0))
        result[code] = values
    return result


def _streak(days: list[tuple[int, dict[str, list[int]]]], code: str, column: int) -> int:
    count = 0
    for _, rows in reversed(days):
        if rows.get(code, [0, 0, 0])[column] > 0:
            count += 1
        else:
            break
    return count


def _lots(shares: int) -> int:
    return int(round(shares / 1000.0))
