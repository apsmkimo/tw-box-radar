import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox import chips, revenue  # noqa: E402
from twbox.publish import build_chips  # noqa: E402

T86_FIELDS = [
    "證券代號", "證券名稱",
    "外陸資買進股數(不含外資自營商)", "外陸資賣出股數(不含外資自營商)", "外陸資買賣超股數(不含外資自營商)",
    "外資自營商買進股數", "外資自營商賣出股數", "外資自營商買賣超股數",
    "投信買進股數", "投信賣出股數", "投信買賣超股數",
    "自營商買賣超股數",
    "自營商買進股數(自行買賣)", "自營商賣出股數(自行買賣)", "自營商買賣超股數(自行買賣)",
    "自營商買進股數(避險)", "自營商賣出股數(避險)", "自營商買賣超股數(避險)",
    "三大法人買賣超股數",
]


class ChipsParseTests(unittest.TestCase):
    def test_twse_t86(self) -> None:
        payload = {
            "stat": "OK",
            "fields": T86_FIELDS,
            "data": [
                ["2330", "台積電          ", "18,503,985", "27,136,819", "-8,632,834", "0", "0", "1,000",
                 "1,863,657", "8,980", "1,854,677", "922,012", "402,050", "420,299", "-18,249",
                 "1,723,036", "782,775", "940,261", "-5,855,145"],
                ["00712", "ETF", "1", "1", "1", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "0", "1"],
            ],
        }
        self.assertEqual(chips.parse_twse_t86(payload), {"2330": [-8631834, 1854677, 922012]})
        self.assertIsNone(chips.parse_twse_t86({"stat": "很抱歉，沒有符合條件的資料!"}))

    def test_tpex_daily_trade_by_position(self) -> None:
        row = ["3324", "雙鴻", "2,377,855", "2,142,163", "235,692", "0", "0", "0", "2,377,855", "2,142,163",
               "235,692", "136,000", "0", "136,000", "134,000", "79,100", "54,900", "114,461", "98,556",
               "15,905", "248,461", "177,656", "70,805", "442,497"]
        self.assertEqual(chips.parse_tpex_insti({"tables": [{"data": [row]}]}), {"3324": [235692, 136000, 70805]})
        broken = row[:]
        broken[23] = "1"
        with self.assertRaises(ValueError):
            chips.parse_tpex_insti({"tables": [{"data": [broken]}]})

    def test_tpex_openapi_requires_date(self) -> None:
        item = {
            "Date": "1151030",
            "SecuritiesCompanyCode": "3324",
            "ForeignInvestorsInclude MainlandAreaInvestors-Difference": "-748111",
            "SecuritiesInvestmentTrustCompanies-Difference": "163167",
            "Dealers-Difference": "29998",
        }
        self.assertEqual(chips.parse_tpex_insti_openapi([item], 20261030), {"3324": [-748111, 163167, 29998]})
        self.assertIsNone(chips.parse_tpex_insti_openapi([item], 20261029))

    def test_summary_lots_and_streaks(self) -> None:
        days = [(20261001 + i, {"2330": [1000 * (i + 1), 2000 if i >= 3 else -500, 0]}) for i in range(6)]
        summary = chips.summarize(days)["2330"]
        # 外資 5 日：2..6 千股 → 20 張；20 日（6 天）21 張
        self.assertEqual(summary[0], 20)
        self.assertEqual(summary[1], 21)
        # 投信 5 日：-0.5 + 3 × 2 + ... → 第 2～6 天：-500,-500,2000,2000,2000 = 5000 股 = 5 張
        self.assertEqual(summary[2], 5)
        self.assertEqual(summary[6], 3)  # 投信連 3 天買超
        self.assertEqual(summary[7], 6)  # 外資連 6 天買超

    def test_save_and_prune(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for i in range(chips.KEEP_DAYS + 3):
                chips.save_day(root, 20260101 + i, {"2330": [1, 2, 3]})
            self.assertEqual(len(chips.stored_days(root)), chips.KEEP_DAYS)
            self.assertEqual(chips.stored_days(root)[0], 20260104)


class RevenueTests(unittest.TestCase):
    def test_shift_month(self) -> None:
        self.assertEqual(revenue.shift_month(202609, -1), 202608)
        self.assertEqual(revenue.shift_month(202601, -1), 202512)
        self.assertEqual(revenue.shift_month(202609, -12), 202509)

    def test_parse_openapi(self) -> None:
        item = {
            "資料年月": "11509",
            "公司代號": "1101",
            "營業收入-當月營收": "14321556",
            "營業收入-上月營收": "13515534",
            "營業收入-去年當月營收": "13306676",
        }
        self.assertEqual(
            revenue.parse_openapi([item, {**item, "公司代號": "0050"}]),
            {"1101": {202609: 14321556, 202608: 13515534, 202509: 13306676}},
        )

    def test_parse_mops_page(self) -> None:
        html = (
            "<table><tr><th>公司代號</th></tr>"
            "<tr><td align=center>1101</td><td>台泥</td><td align=right>13,515,534</td>"
            "<td>13,744,103</td><td>12,214,776</td><td>-1.66</td></tr>"
            "<tr><td>合計</td><td></td><td>1</td><td>1</td><td>1</td></tr></table>"
        )
        self.assertEqual(
            revenue.parse_mops_page(html, 202608),
            {"1101": {202608: 13515534, 202607: 13744103, 202508: 12214776}},
        )

    def test_summary_growth_and_high(self) -> None:
        store = {"months": {}, "pages": []}
        values = {str(revenue.shift_month(202609, -gap)): 100 for gap in range(1, 13)}
        values["202609"] = 150
        values["202608"] = 120
        store["months"]["1101"] = values
        store["months"]["2330"] = {"202609": 50, "202608": 100}
        summary = revenue.summarize(store)
        self.assertEqual(summary["1101"], [202609, 150, 0.5, 0.25, 1])
        # 前 11 個月不完整時，新高為 None
        self.assertEqual(summary["2330"], [202609, 50, None, -0.5, None])

    def test_missing_pages_skip_covered_months(self) -> None:
        store = {"months": {"1101": {"202609": 1, "202608": 1, "202509": 1}}, "pages": []}
        self.assertEqual(revenue.missing_pages(store), [202607, 202605, 202603, 202601, 202511])
        store["pages"] = [202607]
        self.assertEqual(revenue.missing_pages(store)[0], 202606)

    def test_build_chips_document(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            chips.save_day(root, 20261030, {"2330": [2000, 1000, -1000]})
            doc = build_chips(root, "20261030", {"2330", "1101"})
            self.assertEqual(doc["chipDays"], 1)
            self.assertEqual(doc["items"], {"2330": {"i": [2, 2, 1, 1, -1, -1, 1, 1]}})


if __name__ == "__main__":
    unittest.main()
