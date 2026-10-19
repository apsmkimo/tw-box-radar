# 台股箱型雷達

給台股散戶看「箱型整理」與「收盤突破／跌破」的 Android App。計算在手機上完成，不呼叫付費 API，也沒有伺服器。行情由 GitHub Actions 在收盤後抓取，編成靜態 JSON 放在這個倉庫的 `docs/`，App 只下載這些檔案。

資料僅供參考，不構成投資建議。介面上只用「突破箱頂」「跌破箱底」「距離箱頂還有多少」這類客觀描述，不出現買賣建議或目標價。

## 架構

```
上市 MI_INDEX、上櫃 dailyQuotes
        │  每個交易日各 1 次，間隔至少 2.5–3 秒
        ▼
pipeline/            Python 3，只使用標準庫
data/monthly/*.jsonl 歷史來源（一個月一個檔，只追加）
data/state.json      已入庫與休市日期
        │
        ▼
docs/                App 下載的靜態檔（同時可當 GitHub Pages 根目錄）
  manifest.json      資料日、檔數、是否還原權值
  universe.json      代號、名稱、市場
  window.json        近 180 個交易日，全市場，給清單／條件／自選
  screens.json       預設參數下的三份清單（伺服器先算一份）
  bars/{代號}.json   單檔完整歷史，點進 K 線或回測時才下載
        │
        ▼
Android App          Kotlin + Jetpack Compose
  :core              純 JVM：箱型、指標、條件、回測（單元測試不需要 Android）
  :app               畫面、下載、WorkManager 提醒
```

App 的預設網址是 `https://raw.githubusercontent.com/apsmkimo/tw-box-radar/main/docs`。合併到 `main` 之後才抓得到遠端全市場資料；沒網路或網址還沒發佈時，改用 APK 內建範例。

沒有另開資料分支。雲端代理的分支名稱有固定格式，資料就跟程式放在同一個 `main`。每日工作流程會把更新 commit 回目前分支。

## 資料來源與限制

實測（2026-10）結果：

| 來源 | 網址 | 用途 |
| --- | --- | --- |
| 證交所當日全市場 | `https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=YYYYMMDD&type=ALLBUT0999&response=json` | 上市歷史。可指定日期，一天一次拿到全部證券 |
| 證交所 OpenAPI | `https://openapi.twse.com.tw/v1/exchangeReport/STOCK_DAY_ALL` | 只有最新一個交易日。`date` 參數會被忽略，只在當日 MI_INDEX 失敗時當備援 |
| 櫃買每日收盤 | `https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?date=YYYY/MM/DD&response=json` | 上櫃歷史。可指定日期。回應很大，因為含權證，程式只留下普通股 |
| 櫃買 OpenAPI | `https://www.tpex.org.tw/openapi/v1/tpex_mainboard_daily_close_quotes` | 同樣只有最新交易日 |

其他限制：

- **速率**：證交所會封鎖太頻繁的 IP。預設每次請求至少間隔 3 秒（回補可調）。讀取被截斷時會短暫停再重試，遇到 403／429 會等更久。請不要把間隔調到 1 秒以下。
- **休市**：週末不請求。平日若官方明確回「沒有符合條件的資料」（上市）或上櫃家數為 0，而且日期已過，記成休市。若是「今天」還沒出資料，不記休市，讓 18:30 那次排程再試。
- **範圍**：只留 4 碼、不以 0 開頭的普通股。ETF、權證、特別股（代號有英文字）排除。不含興櫃，也不含已下市股票，回測有存活者偏差。
- **價格未還原除權息**。官方每日行情是成交價。除權息日可能出現假跌破。`manifest.json` 的 `adjusted` 是 `false`。這次沒有把除權息因子併進價格；若要做，證交所 `TWT49U`、櫃買除權息計算表可以另接，但不在這版的價格裡。
- **歷史長度**：OpenAPI 補不出過去。三年資料要靠 `backfill.py` 一天一天抓，上櫃每天的回應約 1.5MB，而且連線常被截斷。中斷後再執行會從 `data/state.json` 續跑。

## 目前已入庫的歷史

這份資料是 **2023-10-16 到 2024-12-05**，279 個有行情的平日、約 1877 檔普通股。箱型與大約十四個月的回測已經夠用。還沒到三年：上櫃每天的檔案約 1.5MB，連線常被截斷，同一輪抓完到 2026-10 會拖很久。已完成的日期會略過，接下去跑：

```bash
python3 pipeline/backfill.py --start 20231016 --end 20261016 --sleep 2
```

抓完後 `publish` 會重寫 `docs/`。`manifest.json` 的 `historyStart` / `asOf` 是實際範圍。
- **成交量單位**是股，不是張。

## 箱型演算法

程式在 `pipeline/twbox/darvas.py`，Android 的同一份在 `core/.../Darvas.kt`。兩邊用手算案例鎖在一起。

預設：回看 60 個交易日、確認 3 天、用最高價與最低價（含影線）。這是經典達瓦斯「高點三日不過、低點三日不破」的可調版本。

1. 判斷「今天」時，箱型只用今天以前的 K 線，避免當天新高把箱頂抬走，導致永遠看不到突破。
2. 只在回看視窗內重建箱型，不繼承更早的狀態。
3. `useWicks = true` 時上緣是最高價、下緣是最低價。關閉時上緣是 `max(開, 收)`、下緣是 `min(開, 收)`。
4. 上緣創高之後，連續 `confirmationDays` 根沒有更高，確認箱頂。箱頂的下一根起算的下緣創低，同樣要連續那麼多根沒有更低，才確認箱底。
5. 箱型完成後，收盤價高於箱頂或低於箱底，這口箱結束，重新找。影線穿出但收盤仍在箱內，不算突破。
6. 今天的狀態只看收盤價：
   - 高於箱頂：突破箱頂
   - 低於箱底：跌破箱底
   - 在箱內，且位於區間下緣 `nearRangeFraction`（預設 20%）：接近箱底
   - 其餘在箱內：整理中
7. 量比是今天成交量除以前 20 日（不含今天）均量。預設 1.5 倍算帶量。每日清單仍列出所有收盤突破，並標「帶量／未帶量」。自選提醒的突破要帶量才通知。

回測（同一套箱型，在手機上跑）：

- 空手且收盤突破箱頂就以收盤價買進，停損先放在該箱箱底。
- 持有期間若出現新的有效箱型且箱底更高，停損只上移。
- 收盤跌破停損就以收盤價賣出。
- 預設不要求帶量，區間盤的假突破會留在交易清單裡。設定裡可以改成突破必須帶量。
- 總報酬是期末權益除以期初再減 1，空手不加不減。勝率只算已平倉且報酬為正的比例。最大回撤是權益曲線從高點下來的最大幅度。買進持有是回測第一個可評估日的收盤到最後一根收盤。
- 不計手續費與證交稅，價格不還原除權息。

指標（手機上，可同時勾選，全部是「而且」）：

- 5／20／60 日簡單移動平均，以及 5 日與 20 日的黃金、死亡交叉
- KD：9 日 RSV，K、D 以 1/3 平滑，初值 50
- MACD：12、26、9，訊號線是 MACD 的 EMA
- RSI：Wilder 14
- 成交量大於設定的 20 日均量倍數
- 布林通道：20 日均線 ± 2 倍母體標準差

## 怎麼跑管線

需要 Python 3.11 以上，不必安裝套件。

```bash
# 單元測試
cd pipeline && python3 -m unittest discover -s tests -v

# 回補約三年（可中斷，再執行同一行會續跑）
python3 pipeline/backfill.py --start 20231016 --end 20261016 --sleep 3

# 收盤後補最近缺少的交易日，並重新發佈 docs/
python3 pipeline/daily.py

# 依目前歷史重寫 App 內建範例（歷史夠長才會放真實個股）
python3 pipeline/make_sample.py
```

GitHub Actions：

- `.github/workflows/data.yml`：週一到週五 16:30 與 18:30（台北時間）執行 `daily.py`，有新資料就 commit。也可以手動指定起迄日做回補。
- `.github/workflows/ci.yml`：Python 測試，以及 `./gradlew :core:test :app:assembleDebug`，並上傳 debug APK。

排程只在預設分支生效。合併到 `main` 之後才會每天自己跑。

## 怎麼建置與安裝 APK

```bash
./gradlew :core:test :app:assembleDebug
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。Android 13 以上安裝時允許「不明的應用程式」即可。CI 的 artifact 名稱是 `tw-box-radar-debug`。

本機需要 JDK 17 或 21，以及 Android SDK（compileSdk 35）。`local.properties` 的 `sdk.dir` 不要提交。

App 內容：

1. 每日三份清單：今日突破箱頂、箱型內整理中、接近箱底。可篩上市／上櫃與代號。
2. 個股 K 線，畫出箱頂、箱底與成交量。紅漲綠跌。
3. 自選股，顯示例如「距離箱頂還有 3.2%」。
4. WorkManager 約每 6 小時在有網路時檢查。自選股收盤突破且帶量會通知「可能進入新箱型」；跌破箱底通知「箱型被破壞」。
5. 設定可調回看天數、確認天數、是否用影線、接近箱底的區間比例、帶量倍數。
6. 條件頁可把箱型、均線、KD、MACD、RSI、量能、布林通道用「而且」疊在一起。
7. 單檔回測：交易清單、總報酬、勝率、最大回撤、對照買進持有。

第一次開啟要先看免責聲明。每個主畫面底部都有「資料僅供參考，不構成投資建議」。

## 測試

- `pipeline/tests`：解析、箱型、假突破虧損、停損上移後的獲利。
- `core/src/test`：同一組箱型案例、RSI／均線／MACD／布林、條件的 AND，以及用詞不含「建議買／建議賣」。

```bash
cd pipeline && python3 -m unittest discover -s tests -v
./gradlew :core:test
```
