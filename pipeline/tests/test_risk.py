import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox.darvas import Bar  # noqa: E402
from twbox.limits import (  # noqa: E402
    limit_down_price,
    limit_move,
    limit_up_price,
    reference_price,
    tick_size,
)
from twbox.publish import _limit_today, build_flags  # noqa: E402
from twbox.risk import (  # noqa: E402
    _keep_previous,
    parse_period,
    parse_roc,
    parse_tpex_disposal,
    parse_tpex_warning,
    parse_twse_notice,
    parse_twse_notice_openapi,
    parse_twse_punish,
)
from twbox.store import SymbolSeries  # noqa: E402
from twbox.adjust import ExEvent  # noqa: E402


class LimitPriceTests(unittest.TestCase):
    def test_tick_sizes(self) -> None:
        self.assertEqual(tick_size(9.99), 0.01)
        self.assertEqual(tick_size(10), 0.05)
        self.assertEqual(tick_size(50), 0.1)
        self.assertEqual(tick_size(100), 0.5)
        self.assertEqual(tick_size(500), 1.0)
        self.assertEqual(tick_size(1000), 5.0)

    def test_limit_prices_round_to_tick(self) -> None:
        # 47 × 1.1 = 51.7，50 元以上升降單位 0.1
        self.assertEqual(limit_up_price(47.0), 51.7)
        self.assertEqual(limit_down_price(47.0), 42.3)
        # 455.5 × 1.1 = 501.05，500 元以上單位 1，無條件捨去
        self.assertEqual(limit_up_price(455.5), 501.0)
        # 45.5 × 1.1 = 50.05 → 50.0；45.5 × 0.9 = 40.95
        self.assertEqual(limit_up_price(45.5), 50.0)
        self.assertEqual(limit_down_price(45.5), 40.95)
        # 2430 × 1.1 = 2673 → 2670；× 0.9 = 2187 → 2190（無條件進位到 5）
        self.assertEqual(limit_up_price(2430), 2670.0)
        self.assertEqual(limit_down_price(2430), 2190.0)
        # 9.5 × 1.1 = 10.45，已是 10 元以上的 0.05 檔位
        self.assertEqual(limit_up_price(9.5), 10.45)

    def test_limit_move(self) -> None:
        self.assertEqual(limit_move(47.0, 51.7, 51.7, 48.0), "up")
        self.assertEqual(limit_move(47.0, 42.3, 45.0, 42.3), "down")
        self.assertIsNone(limit_move(47.0, 51.6, 51.7, 48.0))
        # 備援：收在最高且漲幅 ≥ 9.5%
        self.assertEqual(limit_move(100.0, 109.5, 109.5, 100.0), "up")
        self.assertIsNone(limit_move(100.0, 109.5, 110.0, 100.0))

    def test_reference_price_on_ex_day(self) -> None:
        self.assertEqual(reference_price(100.0, []), 100.0)
        self.assertEqual(reference_price(100.0, [0.97]), 97.0)

    def test_limit_today_uses_ex_rights_reference(self) -> None:
        series = SymbolSeries("1234", "測試", "twse")
        series.bars = [Bar(20261029, 100, 100, 100, 100.0, 1000), Bar(20261030, 98, 106.5, 98, 106.5, 1000)]
        # 除息參考價 97 → 漲停 106.5（106.7 依 0.5 檔位捨去）
        self.assertEqual(_limit_today(series, [ExEvent(20261030, "1234", 0.97, 1.0)]), "up")
        self.assertIsNone(_limit_today(series, []))


class RiskParseTests(unittest.TestCase):
    def test_roc_dates(self) -> None:
        self.assertEqual(parse_roc("1151030"), 20261030)
        self.assertEqual(parse_roc("115/10/28"), 20261028)
        self.assertEqual(parse_roc("115.10.30"), 20261030)
        self.assertIsNone(parse_roc(""))
        self.assertEqual(parse_period("115/10/28～115/11/03"), (20261028, 20261103))
        self.assertEqual(parse_period("1151030~1151105"), (20261030, 20261105))

    def test_twse_notice_rwd(self) -> None:
        payload = {
            "stat": "OK",
            "fields": ["編號", "證券代號", "證券名稱", "累計次數", "注意交易資訊", "日期", "收盤價", "本益比"],
            "data": [
                [1, "046401", "世芯凱基5B購02", "1", "跌幅", "115.10.30", "0.27", "-----"],
                [2, "2426", "鼎元", "1", "當沖", "115.10.30", "87.40", "20"],
            ],
        }
        self.assertEqual(parse_twse_notice(payload), {"2426"})
        self.assertEqual(parse_twse_notice({"stat": "很抱歉，沒有符合條件的資料!"}), set())

    def test_twse_notice_openapi_placeholder_row(self) -> None:
        placeholder = [{"Number": "0", "Code": "", "Name": "", "Date": "", "ClosingPrice": "0"}]
        self.assertIsNone(parse_twse_notice_openapi(placeholder, 20261030))
        rows = [{"Code": "2426", "Date": "1151030"}]
        self.assertEqual(parse_twse_notice_openapi(rows, 20261030), {"2426"})

    def test_twse_punish_keeps_active_common_stocks(self) -> None:
        payload = [
            {"Code": "071804", "DispositionPeriod": "115/10/28～115/11/03", "DispositionMeasures": "第二次處置"},
            {"Code": "2466", "DispositionPeriod": "115/10/30～115/11/05", "DispositionMeasures": "第二次處置"},
            {"Code": "1111", "DispositionPeriod": "115/10/01～115/10/15", "DispositionMeasures": "第一次處置"},
        ]
        result = parse_twse_punish(payload, 20261030)
        self.assertEqual(list(result), ["2466"])
        self.assertEqual(result["2466"], {"start": 20261030, "end": 20261105, "measure": "第二次處置"})

    def test_tpex_warning_requires_matching_date(self) -> None:
        rows = [{"Date": "1151030", "SecuritiesCompanyCode": "1569"}, {"Date": "1151030", "SecuritiesCompanyCode": "00679B"}]
        self.assertEqual(parse_tpex_warning(rows, 20261030), {"1569"})
        self.assertIsNone(parse_tpex_warning(rows, 20261029))

    def test_tpex_disposal_filters_ended(self) -> None:
        rows = [
            {"SecuritiesCompanyCode": "4577", "DispositionPeriod": "1151030~1151105", "DisposalCondition": "* 處置措施：第一次處置"},
            {"SecuritiesCompanyCode": "5205", "DispositionPeriod": "1151023~1151029", "DisposalCondition": ""},
        ]
        result = parse_tpex_disposal(rows, 20261030)
        self.assertEqual(result, {"4577": {"start": 20261030, "end": 20261105, "measure": "第一次處置"}})

    def test_keep_previous_on_same_day_failure(self) -> None:
        before = {"asOf": "20261030", "sources": {"twseNotice": "ok", "twsePunish": "ok"}, "notice": ["2426"], "punish": {"2466": {"end": 20261105}}}
        fresh = {"asOf": "20261030", "sources": {"twseNotice": "error", "twsePunish": "error"}, "notice": [], "punish": {}}
        _keep_previous(before, fresh)
        self.assertEqual(fresh["notice"], ["2426"])
        self.assertIn("2466", fresh["punish"])
        self.assertEqual(fresh["sources"]["twseNotice"], "stale")

    def test_build_flags(self) -> None:
        risk = {"asOf": "20261030", "sources": {"twseNotice": "ok"}, "notice": ["2426"], "punish": {"2466": {"start": 20261030, "end": 20261105, "measure": "第二次處置"}}}
        flags = build_flags("20261030", risk, {"2466": "up", "2308": "down"})
        self.assertEqual(flags["flags"]["2426"], {"n": 1})
        self.assertEqual(flags["flags"]["2466"], {"p": [20261030, 20261105], "pm": "第二次處置", "l": 1})
        self.assertEqual(flags["flags"]["2308"], {"l": -1})
        empty = build_flags("20261030", {}, {})
        self.assertEqual(empty["flags"], {})


if __name__ == "__main__":
    unittest.main()
