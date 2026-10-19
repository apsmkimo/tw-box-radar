"""對 TWSE / TPEx 的節流 HTTP 客戶端。

證交所對過快的連續請求會封鎖 IP。所有官方請求都要走這個客戶端，
預設每次請求至少間隔數秒，失敗時指數退避。
"""

from __future__ import annotations

import gzip
import time
import urllib.error
import urllib.request

USER_AGENT = (
    "Mozilla/5.0 (compatible; tw-box-radar/0.1; +https://github.com/apsmkimo/tw-box-radar)"
)


class HttpClient:
    def __init__(self, min_interval: float = 3.0) -> None:
        self.min_interval = min_interval
        self._last = 0.0

    def get(self, url: str, timeout: float = 90.0, retries: int = 6) -> bytes:
        delay = 4.0
        last_error: Exception | None = None
        for attempt in range(retries):
            self._wait()
            request = urllib.request.Request(
                url,
                headers={
                    "User-Agent": USER_AGENT,
                    "Accept": "*/*",
                    "Accept-Encoding": "gzip",
                    "Connection": "close",
                },
            )
            try:
                with urllib.request.urlopen(request, timeout=timeout) as response:
                    body = response.read()
                    encoding = (response.headers.get("Content-Encoding") or "").lower()
                self._last = time.monotonic()
                if encoding == "gzip" or body[:2] == b"\x1f\x8b":
                    body = gzip.decompress(body)
                return body
            except urllib.error.HTTPError as exc:
                last_error = exc
                self._last = time.monotonic()
                if exc.code not in (403, 429, 500, 502, 503, 504):
                    raise
                wait = 30.0 if exc.code in (403, 429) else delay
                print(f"[http] {exc.code} {url}，{wait:.0f}s 後重試 ({attempt + 1}/{retries})", flush=True)
                time.sleep(wait)
                delay = min(delay * 2, 60.0)
                continue
            except Exception as exc:  # noqa: BLE001 — 斷線與讀取不完整都立即短退避
                last_error = exc
                self._last = time.monotonic()
                print(f"[http] {type(exc).__name__}: {exc}，{delay:.0f}s 後重試 ({attempt + 1}/{retries})", flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 30.0)
        raise RuntimeError(f"下載失敗：{url}") from last_error

    def _wait(self) -> None:
        elapsed = time.monotonic() - self._last
        if self._last and elapsed < self.min_interval:
            time.sleep(self.min_interval - elapsed)
