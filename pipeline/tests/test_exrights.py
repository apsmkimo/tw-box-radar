import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox.exrights import (  # noqa: E402
    iter_months,
    iter_quarters,
    parse_roc_date,
    parse_tpex_exrights,
    parse_twse_detail,
    parse_twse_summary,
    per_thousand,
)


class ExrightsParseTests(unittest.TestCase):
    def test_roc_dates_and_per_thousand(self) -> None:
        self.assertEqual(parse_roc_date("113年06月03日"), 20240603)
        self.assertEqual(parse_roc_date("113/06/04"), 20240604)
        self.assertAlmostEqual(per_thousand("140 股"), 0.14)
        self.assertAlmostEqual(per_thousand("202.11906001 股"), 0.20211906001)
        self.assertEqual(per_thousand(""), 0.0)
        self.assertEqual(per_thousand("--"), 0.0)

    def test_twse_summary_keeps_common_stock_and_marks_rights(self) -> None:
        payload = {
            "stat": "OK",
            "fields": ["資料日期", "股票代號", "股票名稱", "除權息前收盤價", "除權息參考價", "權/息", "詳細資料"],
            "data": [
                ["113年06月03日", "2313", "華通", "73.20", "71.70", "息", "2313,20240603"],
                ["113年05月30日", "2543", "皇昌", "59.50", "48.42", "權息", "2543,20240530"],
                ["113年01月30日", "2836A", "高雄銀甲特", "21.30", "20.90", "權", "2836A,20240130"],
                ["113年10月19日", "00694B", "債券", "42.53", "42.13", "息", "00694B,20241019"],
            ],
        }
        events = {item["code"]: item for item in parse_twse_summary(payload)}
        self.assertEqual(set(events), {"2313", "2543"})
        self.assertFalse(events["2313"]["needsDetail"])
        self.assertTrue(events["2313"]["volumeKnown"])
        self.assertEqual(events["2313"]["volumeFactor"], 1.0)
        self.assertAlmostEqual(events["2313"]["priceFactor"], 71.70 / 73.20)
        self.assertTrue(events["2543"]["needsDetail"])
        self.assertFalse(events["2543"]["volumeKnown"])
        self.assertEqual(events["2543"]["date"], 20240530)

    def test_twse_detail_ratios(self) -> None:
        payload = {
            "stat": "ok",
            "fields": [
                "股票代號",
                "股票名稱",
                "(每股配發現金股利)除息",
                "A. 按普通股股東持股比例每千股無償配股",
                "按股東持股比例每千股認購",
            ],
            "data": [["2543  ", "皇昌", "0.4 元／股", "140 股", "202.11906001 股"]],
        }
        stock, cash = parse_twse_detail(payload)
        self.assertAlmostEqual(stock, 0.14)
        self.assertAlmostEqual(cash, 0.20211906001)

    def test_tpex_stock_dividend_and_cash(self) -> None:
        payload = {
            "stat": "ok",
            "tables": [
                {
                    "fields": [
                        "除權息日期",
                        "代號",
                        "名稱",
                        "除權息前收盤價",
                        "除權息參考價",
                        "現金股利",
                        "每仟股無償配股",
                        "按持股比例仟股認購",
                    ],
                    "data": [
                        ["113/06/04", "6788", "華景電", "146.50", "140.50", "6.00000000", "0.00000000", "0.00000000"],
                        ["113/07/01", "1234", "測試", "110.00", "100.00", "0", "100.00000000", "50.00000000"],
                        ["113/07/01", "00694B", "債券", "10", "9", "1", "0", "0"],
                    ],
                }
            ],
        }
        events = {item["code"]: item for item in parse_tpex_exrights(payload)}
        self.assertEqual(set(events), {"6788", "1234"})
        self.assertEqual(events["6788"]["volumeFactor"], 1.0)
        self.assertTrue(events["6788"]["volumeKnown"])
        self.assertAlmostEqual(events["6788"]["priceFactor"], 140.50 / 146.50)
        self.assertAlmostEqual(events["1234"]["stockRatio"], 0.1)
        self.assertAlmostEqual(events["1234"]["cashRatio"], 0.05)
        self.assertAlmostEqual(events["1234"]["volumeFactor"], 1.15)
        self.assertEqual(events["1234"]["market"], "tpex")

    def test_spans_cover_partial_edges(self) -> None:
        quarters = list(iter_quarters(date(2023, 10, 16), date(2024, 1, 2)))
        self.assertEqual(quarters[0], (date(2023, 10, 16), date(2023, 12, 31)))
        self.assertEqual(quarters[1], (date(2024, 1, 1), date(2024, 1, 2)))
        months = list(iter_months(date(2024, 6, 4), date(2024, 7, 1)))
        self.assertEqual(months[0][0], date(2024, 6, 4))
        self.assertEqual(months[-1][1], date(2024, 7, 1))
