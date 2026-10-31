import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox.darvas import Bar, Box, evaluate  # noqa: E402
from twbox.quality import box_quality, percentile_ranks, period_return  # noqa: E402


def bar(i: int, close: float, volume: int = 1000) -> Bar:
    return Bar(20240101 + i, close, close, close, close, volume)


class QualityTests(unittest.TestCase):
    """與 core/src/test/.../QualityTest.kt 相同的手算案例。"""

    def test_period_return(self) -> None:
        bars = [bar(i, 100.0 + i) for i in range(61)]
        self.assertAlmostEqual(period_return(bars, 60), 0.6)
        self.assertIsNone(period_return(bars, 61))

    def test_percentile_ties(self) -> None:
        ranks = percentile_ranks({"a": 0.1, "b": 0.2, "c": 0.2, "d": 0.5, "e": -0.3})
        self.assertEqual(ranks, {"a": 25, "b": 63, "c": 63, "d": 100, "e": 0})
        self.assertEqual(percentile_ranks({"x": 0.0}), {"x": 100})

    def test_box_quality(self) -> None:
        bars = [bar(i, 10.0, 1000) for i in range(20)] + [bar(i, 10.0, 500) for i in range(20, 30)] + [bar(30, 10.0, 3000)]
        box = Box(top=11.0, bottom=10.0, top_date=20240121, bottom_date=20240126)
        quality = box_quality(bars, box)
        self.assertEqual(quality["boxDays"], 10)
        self.assertAlmostEqual(quality["boxWidth"], 0.1)
        self.assertAlmostEqual(quality["volumeContraction"], 0.5)
        self.assertIsNone(box_quality(bars[5:], box)["volumeContraction"])
        self.assertIsNone(box_quality(bars, None))

    def test_hand_case_box_days(self) -> None:
        rows = [(12, 10, 11), (15, 11, 14), (20, 14, 19), (19, 16, 17), (18, 15, 16),
                (18, 16, 17), (17, 16, 16.5), (18, 16, 17), (19, 17, 18)]
        bars = [Bar(20240101 + i, c, h, low, c, 1000) for i, (h, low, c) in enumerate(rows)]
        snap = evaluate(bars)
        quality = box_quality(bars, snap.box)
        self.assertEqual(quality["boxDays"], 6)
        self.assertAlmostEqual(quality["boxWidth"], 1 / 3)
        self.assertIsNone(quality["volumeContraction"])


if __name__ == "__main__":
    unittest.main()
