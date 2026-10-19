import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox.adjust import ExEvent, adjust_bars  # noqa: E402
from twbox.darvas import Bar, BoxParams, Status, evaluate  # noqa: E402
from twbox.publish import publish  # noqa: E402


def bar(day: int, close: float, volume: int = 1000, high: float | None = None, low: float | None = None) -> Bar:
    return Bar(day, close, close if high is None else high, close if low is None else low, close, volume)


class AdjustTests(unittest.TestCase):
    def test_cash_dividend_scales_earlier_prices_not_volume_or_ex_date(self) -> None:
        # 華通 2024-06-03：前收 73.20、參考價 71.70、純除息。
        factor = 71.70 / 73.20
        events = [ExEvent(20240603, "2313", factor, 1.0)]
        bars = [
            bar(20240531, 73.20, volume=2000),
            bar(20240603, 71.70, volume=3000),
        ]
        adjusted = adjust_bars(bars, events)
        self.assertAlmostEqual(adjusted[0].close, 71.70)
        self.assertEqual(adjusted[0].volume, 2000)
        self.assertAlmostEqual(adjusted[1].close, 71.70)
        self.assertEqual(adjusted[1].volume, 3000)

    def test_stock_dividend_and_rights_scale_volume(self) -> None:
        # 皇昌 2024-05-30：參考價 48.42、前收 59.50、每千股無償 140、認購 202.11906001。
        stock = 140 / 1000
        cash = 202.11906001 / 1000
        events = [ExEvent(20240530, "2543", 48.42 / 59.50, 1 + stock + cash)]
        bars = [bar(20240529, 59.50, volume=1000), bar(20240530, 48.42, volume=800)]
        adjusted = adjust_bars(bars, events)
        self.assertAlmostEqual(adjusted[0].close, 48.42)
        self.assertEqual(adjusted[0].volume, 1342)
        self.assertEqual(adjusted[1].volume, 800)
        self.assertAlmostEqual(adjusted[1].close, 48.42)

    def test_event_after_the_last_bar_is_not_applied_yet(self) -> None:
        events = [ExEvent(20250612, "2330", 0.5, 2.0)]
        bars = [bar(20250401, 900, volume=1000)]
        adjusted = adjust_bars(bars, events)
        self.assertEqual(adjusted[0].close, 900)
        self.assertEqual(adjusted[0].volume, 1000)

    def test_two_events_multiply_and_ignore_non_positive_factor(self) -> None:
        events = [
            ExEvent(20240201, "2330", 0.9, 1.0),
            ExEvent(20240601, "2330", 0.8, 1.1),
            ExEvent(20240701, "2330", 0.0, 1.0),
        ]
        bars = [bar(20240101, 100, volume=1000), bar(20240301, 100, volume=1000), bar(20240702, 100, volume=1000)]
        adjusted = adjust_bars(bars, events)
        self.assertAlmostEqual(adjusted[0].close, 72.0)
        self.assertEqual(adjusted[0].volume, 1100)
        self.assertAlmostEqual(adjusted[1].close, 80.0)
        self.assertEqual(adjusted[1].volume, 1100)
        self.assertAlmostEqual(adjusted[2].close, 100.0)
        self.assertEqual(adjusted[2].volume, 1000)

    def test_ex_dividend_gap_is_not_a_breakdown_after_adjustment(self) -> None:
        params = BoxParams(lookback_days=30, confirmation_days=3, volume_average_days=3)
        raw = [
            (12, 10, 11),
            (15, 11, 14),
            (20, 14, 19),
            (19, 16, 17),
            (18, 15, 16),
            (18, 14, 15),
            (17, 15, 16),
            (17, 15, 16),
            (17, 16, 16),
            (15, 12, 13),
        ]
        bars = [Bar(20240101 + i, c, h, l, c, 1000) for i, (h, l, c) in enumerate(raw)]
        self.assertEqual(evaluate(bars, params).status, Status.BREAKDOWN)
        # 前收 16、除息參考價 13，缺口全部來自現金股利。
        adjusted = adjust_bars(bars, [ExEvent(20240110, "2330", 13 / 16, 1.0)])
        snap = evaluate(adjusted, params)
        self.assertNotEqual(snap.status, Status.BREAKDOWN)
        self.assertAlmostEqual(snap.box.bottom, 14 * 13 / 16)  # type: ignore[union-attr]
        self.assertAlmostEqual(snap.close, 13)

    def test_publish_writes_adjusted_bars_and_keeps_raw(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            month = root / "data" / "monthly"
            month.mkdir(parents=True)
            row = [20240603, "2330", "台積電", "twse", 100, 101, 99, 100, 5000]
            (month / "2024-06.jsonl").write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")
            earlier = [20240602, "2330", "台積電", "twse", 100, 102, 98, 100, 4000]
            with (month / "2024-06.jsonl").open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(earlier) + "\n")
            # 檔內日期倒序，store 會再排好。除息日 20240603，參考價／前收 = 0.95。
            cache = {
                "events": [
                    {
                        "date": 20240603,
                        "code": "2330",
                        "priceFactor": 0.95,
                        "volumeFactor": 1.0,
                        "volumeKnown": True,
                    }
                ]
            }
            (root / "data" / "exrights.json").write_text(json.dumps(cache), encoding="utf-8")
            manifest = publish(root)
            self.assertTrue(manifest["adjusted"])
            self.assertEqual(manifest["schema"], 2)
            symbol = json.loads((root / "docs" / "bars" / "2330.json").read_text(encoding="utf-8"))
            self.assertEqual(symbol["bars"][0][4], 95.0)
            self.assertEqual(symbol["bars"][0][5], 4000)
            self.assertEqual(symbol["bars"][1][4], 100.0)
            self.assertEqual(symbol["raw"][0][4], 100.0)
            window = json.loads((root / "docs" / "window.json").read_text(encoding="utf-8"))
            self.assertEqual(window["symbols"][0]["bars"][0][4], 95.0)
            self.assertNotIn("raw", window["symbols"][0])
