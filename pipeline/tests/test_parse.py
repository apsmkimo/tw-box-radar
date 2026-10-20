import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox.parse import parse_number, parse_tpex_daily, parse_twse_mi_index  # noqa: E402


class ParseTests(unittest.TestCase):
    def test_parse_number_strips_commas_and_rejects_halts(self) -> None:
        self.assertEqual(parse_number("2,430.00"), 2430)
        self.assertIsNone(parse_number("--"))
        self.assertIsNone(parse_number(""))
        self.assertIsNone(parse_number("0"))

    def test_twse_filters_common_stocks_and_detects_holiday(self) -> None:
        payload = {
            "stat": "OK",
            "tables": [
                {
                    "fields": ["證券代號", "證券名稱", "成交股數", "成交筆數", "成交金額", "開盤價", "最高價", "最低價", "收盤價"],
                    "data": [
                        ["2330", "台積電", "43,046,982", "1", "1", "2,430.00", "2,445.00", "2,420.00", "2,420.00"],
                        ["0050", "元大台灣50", "1,000", "1", "1", "100", "101", "99", "100"],
                        ["1101B", "台泥乙", "1,000", "1", "1", "10", "11", "9", "10"],
                        ["9999", "暫停", "0", "0", "0", "--", "--", "--", "--"],
                    ],
                }
            ],
        }
        status, rows = parse_twse_mi_index(payload)
        self.assertEqual(status, "ok")
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0][0], "2330")
        self.assertEqual(rows[0][3], 2430)
        self.assertEqual(rows[0][7], 43046982)
        closed, empty = parse_twse_mi_index({"stat": "很抱歉，沒有符合條件的資料!"})
        self.assertEqual(closed, "closed")
        self.assertEqual(empty, [])

    def test_tpex_holiday_and_stock_filter(self) -> None:
        payload = {
            "stat": "ok",
            "tables": [
                {
                    "listedCompanies": "893",
                    "totalCount": 2,
                    "fields": ["代號", "名稱", "收盤", "漲跌", "開盤", "最高", "最低", "均價", "成交股數"],
                    "data": [
                        ["6488", "環球晶", "540", "+1", "538", "545", "530", "540", "1,234,567"],
                        ["006201", "元大富櫃50", "20", "0", "20", "21", "19", "20", "100"],
                    ],
                }
            ],
        }
        status, rows = parse_tpex_daily(payload)
        self.assertEqual(status, "ok")
        self.assertEqual(rows[0][0], "6488")
        self.assertEqual(rows[0][2], "tpex")
        holiday = {"tables": [{"listedCompanies": "0", "totalCount": 0, "fields": [], "data": []}]}
        self.assertEqual(parse_tpex_daily(holiday)[0], "closed")


if __name__ == "__main__":
    unittest.main()
