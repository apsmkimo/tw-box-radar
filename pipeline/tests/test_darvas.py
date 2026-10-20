import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from twbox.darvas import Bar, BoxParams, Status, backtest, evaluate  # noqa: E402


def bar(index: int, high: float, low: float, close: float, volume: int = 1000, open_: float | None = None) -> Bar:
    return Bar(20240101 + index, close if open_ is None else open_, high, low, close, volume)


def series_box() -> list[Bar]:
    """箱頂 20（index 2）確認後，箱底 14（index 5）在 index 8 確認。"""
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
    ]
    return [bar(i, h, l, c) for i, (h, l, c) in enumerate(raw)]


class DarvasTests(unittest.TestCase):
    def test_inside_near_bottom_breakout_and_breakdown(self) -> None:
        params = BoxParams(lookback_days=30, confirmation_days=3, volume_average_days=3, volume_multiple=1.5)
        base = series_box()
        inside = evaluate(base + [bar(9, 17, 15, 16, volume=1000)], params)
        self.assertEqual(inside.status, Status.INSIDE)
        self.assertEqual(inside.box.top, 20)  # type: ignore[union-attr]
        self.assertEqual(inside.box.bottom, 14)  # type: ignore[union-attr]

        near = evaluate(base + [bar(9, 16, 14, 15, volume=1000)], params)
        self.assertEqual(near.status, Status.NEAR_BOTTOM)

        breakout = evaluate(base + [bar(9, 22, 18, 21, volume=5000)], params)
        self.assertEqual(breakout.status, Status.BREAKOUT)
        self.assertTrue(breakout.volume_confirmed)
        self.assertAlmostEqual(breakout.distance_to_top, (20 - 21) / 21)

        quiet = evaluate(base + [bar(9, 22, 18, 21, volume=1000)], params)
        self.assertEqual(quiet.status, Status.BREAKOUT)
        self.assertFalse(quiet.volume_confirmed)

        broken = evaluate(base + [bar(9, 15, 12, 13, volume=1000)], params)
        self.assertEqual(broken.status, Status.BREAKDOWN)
        self.assertEqual(broken.box.bottom, 14)  # type: ignore[union-attr]

    def test_breakdown_does_not_expand_the_box(self) -> None:
        params = BoxParams(lookback_days=30, confirmation_days=3)
        bars = series_box() + [bar(9, 15, 10, 13)]
        snap = evaluate(bars, params)
        self.assertEqual(snap.status, Status.BREAKDOWN)
        self.assertEqual(snap.box.top, 20)  # type: ignore[union-attr]
        self.assertEqual(snap.box.bottom, 14)  # type: ignore[union-attr]

    def test_body_mode_ignores_wicks(self) -> None:
        params = BoxParams(lookback_days=30, confirmation_days=3, use_wicks=False)
        # 實體高點在 12，影線刺到 40；箱頂應落在實體，而不是影線。
        specs = [
            (10, 11, 9, 11),
            (11, 12, 10, 11.5),
            (12, 40, 1, 11),
            (11, 13, 9, 10.5),
            (10.5, 13, 9, 10.2),
            (10.2, 13, 9, 10.4),
            (10.4, 13, 9, 10.6),
            (10.6, 13, 9, 10.5),
            (10.5, 13, 9, 10.8),
            (10.8, 13, 9, 11),
            (11, 13, 9, 10.7),
            (10.7, 13, 9, 11),
        ]
        bars = [Bar(20240101 + i, o, h, l, c, 1000) for i, (o, h, l, c) in enumerate(specs)]
        snap = evaluate(bars, params)
        self.assertIsNotNone(snap.box)
        self.assertEqual(snap.box.top, 12)  # type: ignore[union-attr]
        self.assertGreater(snap.box.bottom, 5)  # type: ignore[union-attr]
        self.assertEqual(snap.status, Status.INSIDE)

    def test_false_breakout_backtest_loses_to_range(self) -> None:
        params = BoxParams(lookback_days=30, confirmation_days=3, volume_average_days=2)
        bars = series_box() + [
            bar(9, 22, 20, 21, volume=1000),
            bar(10, 22, 19, 20, volume=1000),
            bar(11, 16, 12, 13, volume=1000),
        ]
        result = backtest(bars, params)
        self.assertEqual(result.closed_trades, 1)
        trade = result.trades[0]
        self.assertEqual(trade.entry_price, 21)
        self.assertEqual(trade.exit_price, 13)
        self.assertAlmostEqual(trade.return_fraction, 13 / 21 - 1)
        self.assertAlmostEqual(result.total_return, 13 / 21 - 1)
        self.assertEqual(result.win_rate, 0)
        self.assertGreater(result.max_drawdown, 0.3)
        self.assertLess(result.total_return, result.buy_hold_return)

    def test_stop_ratchet_locks_a_winner(self) -> None:
        params = BoxParams(lookback_days=40, confirmation_days=3, volume_average_days=2)
        # 第一段箱型突破後，在更高的位置形成新箱型，再跌破新箱底停利。
        bars = series_box() + [
            bar(9, 22, 20, 21),
            bar(10, 30, 26, 29),
            bar(11, 29, 25, 26),
            bar(12, 28, 25, 26),
            bar(13, 28, 24, 25),
            bar(14, 27, 25, 26),
            bar(15, 27, 25, 26),
            bar(16, 27, 26, 26),
            bar(17, 25, 23, 23),
        ]
        result = backtest(bars, params)
        self.assertEqual(result.closed_trades, 1)
        trade = result.trades[0]
        self.assertEqual(trade.entry_price, 21)
        self.assertEqual(trade.exit_price, 23)
        self.assertGreater(trade.exit_price, trade.entry_price)
        self.assertAlmostEqual(result.total_return, 23 / 21 - 1)
        self.assertEqual(result.win_rate, 1)


if __name__ == "__main__":
    unittest.main()
