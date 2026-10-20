"""月檔 JSONL 是歷史的唯一來源；發佈檔由它重生。"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

from .darvas import Bar


@dataclass
class SymbolSeries:
    code: str
    name: str
    market: str
    bars: list[Bar] = field(default_factory=list)

    def add(self, bar: Bar, name: str, market: str) -> None:
        self.name = name or self.name
        self.market = market or self.market
        if self.bars and self.bars[-1].date == bar.date:
            self.bars[-1] = bar
            return
        if self.bars and bar.date < self.bars[-1].date:
            # 回補若亂序，插入後排序去重。
            self.bars = [item for item in self.bars if item.date != bar.date]
            self.bars.append(bar)
            self.bars.sort(key=lambda item: item.date)
            return
        self.bars.append(bar)


def monthly_path(root: Path, yyyymmdd: int) -> Path:
    text = f"{yyyymmdd:08d}"
    return root / "data" / "monthly" / f"{text[:4]}-{text[4:6]}.jsonl"


def append_day(root: Path, yyyymmdd: int, rows: list[list]) -> None:
    path = monthly_path(root, yyyymmdd)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps([yyyymmdd, *row], ensure_ascii=False, separators=(",", ":")) + "\n")


def load_symbols(root: Path) -> dict[str, SymbolSeries]:
    symbols: dict[str, SymbolSeries] = {}
    folder = root / "data" / "monthly"
    if not folder.exists():
        return symbols
    for path in sorted(folder.glob("*.jsonl")):
        with path.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                date, code, name, market, open_, high, low, close, volume = json.loads(line)
                series = symbols.get(code)
                if series is None:
                    series = SymbolSeries(code, name, market)
                    symbols[code] = series
                series.add(
                    Bar(int(date), float(open_), float(high), float(low), float(close), int(volume)),
                    name,
                    market,
                )
    return symbols


def load_state(root: Path) -> dict:
    path = root / "data" / "state.json"
    if not path.exists():
        return {"completed": [], "closed": []}
    return json.loads(path.read_text(encoding="utf-8"))


def save_state(root: Path, state: dict) -> None:
    path = root / "data" / "state.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    state["completed"] = sorted(set(state.get("completed", [])))
    state["closed"] = sorted(set(state.get("closed", [])))
    path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
