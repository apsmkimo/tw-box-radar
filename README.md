# 台股箱型雷達

給台股散戶看「箱型整理」與「收盤突破／跌破」的 Android App。計算在手機上完成，不呼叫付費 API，也沒有伺服器。行情由 GitHub Actions 在收盤後抓取，編成靜態 JSON 放在這個倉庫的 `docs/`，App 只下載這些檔案。

資料僅供參考，不構成投資建議。介面上只用「突破箱頂」「跌破箱底」「距離箱頂還有多少」這類客觀描述，不出現買賣建議或目標價。

## 架構

```
上市 MI_INDEX、上櫃 dailyQuotes
        │  每個交易日各 1 次，間隔至少 2.5–3 秒
        ▼
pipeline/            Python 3，只使用標準庫
data/monthly/*.jsonl 歷史來源（一個月一個檔，只追加，存成交原價）
data/state.json      已入庫與休市日期
data/exrights.json   證交所、櫃買的除權息因子
data/risk.json       最新交易日的注意股、處置股（每天覆寫，不留歷史）
data/chips/*.json    三大法人每日買賣超（一天一檔，只留最近 60 個交易日）
data/revenue.json    月營收（每檔最近 15 個月，千元）
        │
        ▼
docs/                App 下載的靜態檔（同時可當 GitHub Pages 根目錄）
  manifest.json      資料日、檔數、是否還原權值
  universe.json      代號、名稱、市場
  window.json        近 180 個交易日，全市場，給清單／條件／自選
  screens.json       預設參數下的三份清單（伺服器先算一份，列上有 limit 時表示收盤漲停／跌停；另含 rs60、rs120、boxDays、boxWidth、volumeContraction）
  flags.json         注意、處置（含起迄日）、漲停／跌停標籤，只列有標籤的代號
  chips.json         三大法人 5／20 日買賣超（張）、連買天數，以及最新月營收、年增、月增、是否創 12 個月新高
  bars/{代號}.json   單檔完整歷史（還原價；有除權息時另附 raw 成交原價）
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
| 證交所除權息 | `https://www.twse.com.tw/exchangeReport/TWT49U?response=json&startDate=YYYYMMDD&endDate=YYYYMMDD` | 計算結果表。參數是 `startDate`，不是 `strDate`。含「權」的列再查 `TWT49UDetail` |
| 證交所注意股 | `https://www.twse.com.tw/rwd/zh/announcement/notice?startDate=YYYYMMDD&endDate=YYYYMMDD&response=json` | 可指定日期。失敗時改用 OpenAPI `/v1/announcement/notice`（只有當天，非交易日回一列空白代號） |
| 證交所處置股 | `https://openapi.twse.com.tw/v1/announcement/punish` | 目前處置中的證券，`DispositionPeriod` 形如 `115/10/28～115/11/03` |
| 櫃買注意股 | `https://www.tpex.org.tw/openapi/v1/tpex_trading_warning_information` | 只有最新一天；日期和行情資料日不同時不採用 |
| 櫃買處置股 | `https://www.tpex.org.tw/openapi/v1/tpex_disposal_information` | 近期公告，`DispositionPeriod` 形如 `1151030~1151105`，依迄日過濾。沒有「第幾次處置」欄位，只標「處置」 |
| 證交所三大法人 | `https://www.twse.com.tw/rwd/zh/fund/T86?date=YYYYMMDD&selectType=ALLBUT0999&response=json` | 可指定日期。外資＝外陸資（不含外資自營商）＋外資自營商 |
| 櫃買三大法人 | `https://www.tpex.org.tw/www/zh-tw/insti/dailyTrade?type=Daily&sect=EW&date=YYYY/MM/DD&response=json` | 可指定日期。欄名重複，依位置讀（第 10 欄外資合計、13 投信、22 自營合計、23 三大法人合計），並檢查三者相加＝合計。備援 OpenAPI `tpex_3insti_daily_trading`（只有最新一天） |
| 上市月營收 | `https://openapi.twse.com.tw/v1/opendata/t187ap05_L` | 只有最新月。欄位：資料年月、公司代號、營業收入-當月營收／上月營收／去年當月營收 |
| 上櫃月營收 | `https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O` | 欄位同上市 |
| 觀測站月營收彙總表 | `https://mopsov.twse.com.tw/nas/t21/{sii,otc}/t21sc03_民國年_月_{0,1}.html` | Big5 靜態頁，0 國內、1 外國（KY）公司。只用來補最近 12 個月 |
| 櫃買除權息 | `https://www.tpex.org.tw/web/stock/exright/dailyquo/exDailyQ_result.php?l=zh-tw&d=ROC/MM/DD&ed=ROC/MM/DD&o=json` | 歷史區間要走這支舊網址。新的 `bulletin/exDailyQ` 會忽略日期 |

其他限制：

- **速率**：證交所會封鎖太頻繁的 IP。預設每次請求至少間隔 3 秒（回補可調）。讀取被截斷時會短暫停再重試，遇到 403／429 會等更久。請不要把間隔調到 1 秒以下。
- **休市**：週末不請求。平日若官方明確回「沒有符合條件的資料」（上市）或上櫃家數為 0，而且日期已過，記成休市。若是「今天」還沒出資料，不記休市，讓 18:30 那次排程再試。
- **範圍**：只留 4 碼、不以 0 開頭的普通股。ETF、權證、特別股（代號有英文字）排除。不含興櫃，也不含已下市股票，回測有存活者偏差。
- **除權息向後還原**。月檔 `data/monthly` 永遠存官方成交價。發佈 `docs/` 時才還原，所以新的除權息日會在下一次 `daily.py` 或 `refresh_exrights.py` 之後，回頭改寫更早的 K 線。箱型、指標、清單、提醒、回測都吃還原價。K 線預設畫還原價，個股檔若有差異會多一個 `raw`，App 可切回原始價；切回原始價時不畫箱線，因為箱線是用還原價算的。
- **歷史長度**：OpenAPI 補不出過去。三年資料要靠 `backfill.py` 一天一天抓，上櫃每天的回應約 1.5MB，而且連線常被截斷。中斷後再執行會從 `data/state.json` 續跑。`--max-days` 可以分段，結束碼 2 表示這段還沒抓完。

## 目前已入庫的歷史

這份資料是 **2023-10-16 到 2026-10-19**，731 個有行情的平日、55 個休市平日、2011 檔普通股。其中 1690 檔因除權息而與成交價不同。2026-10-19、預設參數：16 檔突破箱頂、529 檔整理、299 檔接近箱底。`manifest.json` 的 `historyStart` / `asOf` 是實際範圍。

之後的交易日由每日排程補上。若要手動把缺的日期補齊：

```bash
python3 pipeline/backfill.py --start 20231016 --end 20261019 --sleep 3 --max-days 20 --no-publish
```

結束碼 2 表示這段還沒抓完，再執行同一行會從 `data/state.json` 續跑。
- **成交量單位**是股，不是張。

## 除權息怎麼還原

演算法在 `pipeline/twbox/adjust.py`，因子下載在 `pipeline/twbox/exrights.py`。

1. 價格用官方除權息參考價，不自己套公式。某次除權息的價格因子＝參考價／前收盤價。
2. 向後還原：只有日期早於除權息日的 K 線才乘這次因子。除權息當天與之後保持原價，所以最新一根仍是成交價。多次除權息的因子連乘。若除權息日晚於這檔目前最後一根 K 線，先不套，等那天的行情入庫後再回頭調整。
3. 成交量：現金股利不調。有無償配股或按持股比例現金增資時，成交量因子＝1＋（每千股無償配股／1000）＋（按持股比例每千股認購／1000）。同樣只乘在除權息日之前的成交量，讓舊的股數跟現在的股數可比。
4. 上市的配股率、認購率在 TWT49U 明細；上櫃在計算表的「每仟股無償配股」「按持股比例仟股認購」。ETF、特別股不算。前收或參考價不是正數的列略過。
5. 員工分紅轉增資、公開承銷沒有「按持股比例」的比率，不進成交量因子。
6. `data/exrights.json` 可中斷續傳。已結束超過 120 天的區間不再重抓；比較近的區間每次重抓。明細抓過就留著。每日管線會先更新因子再發佈，因此新除權息日會改寫更早的 `docs/`。

## 注意股、處置股與漲跌停

- `daily.py` 入庫之後，以最新一個已入庫的交易日抓上面四個清單，存 `data/risk.json`。每個來源各自失敗都不會中斷發佈，只在 `sources` 記 `error`，那部分標籤這次不出現。同一天重跑時，失敗的來源沿用上次成功的結果（記 `stale`）。
- 只標 4 碼普通股。處置只留迄日不早於資料日的；已公告、隔天才開始的也會列出，App 顯示起迄日。
- 漲跌停在發佈時用**成交原價**計算（`pipeline/twbox/limits.py`，Android 同一份在 `core/.../Limits.kt`）：
  1. 參考價＝前一日收盤；除權息當天改用官方參考價（前收盤 × 當天的價格因子）。
  2. 漲停價＝參考價 × 1.1 依升降單位無條件捨去；跌停價＝參考價 × 0.9 無條件進位。升降單位：未滿 10 元 0.01、10–50 元 0.05、50–100 元 0.1、100–500 元 0.5、500–1000 元 1、1000 元以上 5。
  3. 收盤價 ≥ 漲停價記漲停，≤ 跌停價記跌停。另以「收盤＝最高（最低）且漲跌幅達 9.5%」當備援。新上市前幾天沒有漲跌幅限制，可能誤標。
- `window.json` 是還原價。除權息日以外相鄰兩根的比值和成交價相同；除權息當天，還原後的前收盤正好是官方參考價。App 自己算時用「今天原價收盤 × 前一根還原收盤 ／ 今天還原收盤」當參考價。視窗檔沒有原價，升降單位可能對不上，靠 9.5% 備援。`flags.json` 的資料日和行情相同時，App 直接用它的漲跌停。
- 回測設定可開「略過收盤漲停的突破」，預設關閉，原本的回測結果不變。

## 相對強弱與箱型品質

純計算，在 `core/.../Quality.kt`，管線的 `pipeline/twbox/quality.py` 同一套規則（`screens.json` 先算一份）。全部用還原價。

- **RS 百分位**：每檔 60 日與 120 日報酬（最新收盤 ／ N 個交易日前收盤 − 1），在全市場排成 0～100。公式是（比它低的檔數＋（同分檔數−1）／2）／（檔數−1），四捨五入，最強為 100。K 線不足 N＋1 根沒有 RS。`window.json` 有 180 根，兩個週期都算得出來。
- **箱型天數**：箱頂那一根到昨天（含）的交易日數。跟箱型判斷一樣不含今天。
- **箱寬**：（箱頂−箱底）／箱底。
- **箱內量**：箱頂以來的平均成交量 ÷ 箱頂前 20 個交易日平均量。小於 1 表示箱內量縮。箱頂前不足 20 根時不顯示。
- 每日清單可依「代號」「RS 60 日」「RS 120 日」「箱寬（窄到寬）」「箱型天數（長到短）」排序；搜尋時仍以符合程度排序。沒有該欄位的股票排在最後。

## 三大法人與月營收

- **三大法人不回補歷史**，從這個功能上線那天開始累積。`daily.py` 只抓這次入庫的交易日與最新交易日，上市、上櫃都拿到才存檔，否則下次排程再試。只留最近 60 個交易日。
- `chips.json` 的 `chipDays` 是目前累積的天數。不足 20 天時，「20 日」其實是已累積天數的合計，App 會照實寫成「N 日」。
- 買賣超單位是張（股數 ÷ 1000 四捨五入）。當天不在名單上的股票視為 0。連買天數是從最新一天往回數淨買超 > 0 的連續天數。
- 月營收每次排程抓最新月（OpenAPI 同時給上月與去年同月），再用觀測站彙總表一次最多補兩個月份頁，幾天內補滿近 12 個月。年增、月增用存下的數字自己算；「創 12 個月新高」是當月 ≥ 前 11 個月最大值，前 11 個月缺資料時顯示「資料不足」。
- 全部 fail-soft：任何來源失敗都不會中斷行情發佈。

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
- 不計手續費與證交稅。回測價格是向後還原價。

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

# 收盤後補最近缺少的交易日，更新除權息因子，並重新發佈 docs/
python3 pipeline/daily.py

# 只更新除權息因子（可中斷；較舊的區間有快取，近 120 天每次重抓）
python3 pipeline/refresh_exrights.py --start 20231001 --end 20261019 --sleep 3

# 回補時每次最多抓 20 個新的平日。結束碼 2 表示還沒到結束日。
python3 pipeline/backfill.py --start 20231016 --end 20261019 --sleep 3 --max-days 20 --no-publish

# 依目前歷史重寫 App 內建範例（歷史夠長才會放真實個股）
python3 pipeline/make_sample.py
```

GitHub Actions：

- `.github/workflows/data.yml`：週一到週五 16:30 與 18:30（台北時間）執行 `daily.py`（含除權息更新），有新資料就 commit。週一到週五 02:00 若歷史還沒補齊，再抓 25 個平日並提交。手動指定起迄日時，會以每 20 個平日為一段抓完、提交、再繼續，中斷後重跑會從 `data/state.json` 接上。排程只在合併進 `main` 之後生效。
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
2. 個股 K 線，畫出箱頂、箱底與成交量。紅漲綠跌。預設是還原價，有原價資料時可切換。
3. 自選股，顯示例如「距離箱頂還有 3.2%」。
4. WorkManager 約每 6 小時在有網路時檢查。自選股收盤突破且帶量會通知「可能進入新箱型」；跌破箱底通知「箱型被破壞」。
5. 設定可調回看天數、確認天數、是否用影線、接近箱底的區間比例、帶量倍數。
6. 條件頁可把箱型、均線、KD、MACD、RSI、量能、布林通道用「而且」疊在一起。
7. 單檔回測：交易清單、總報酬、勝率、最大回撤、對照買進持有。
8. 清單列與個股頁顯示「注意」「處置至 MM/DD」「漲停」「跌停」小標籤；個股頁另列處置起迄日與清單日期。`flags.json` 讀不到時只是不顯示注意／處置，漲跌停改由 K 線自己算。
9. 個股頁顯示外資／投信／自營 5 日與 20 日買賣超（張）、連買天數，以及月營收年增、月增、是否創 12 個月新高。條件頁可再疊「投信連買 3／5 天」「外資 5 日買超」「營收年增 > 20%／50%」「營收創 12 個月新高」，沒有資料的股票不列入。
10. 清單列與個股頁顯示 RS60、RS120、箱型天數、箱寬與箱內量比，清單可依這些欄位排序。

第一次開啟要先看免責聲明。每個主畫面底部都有「資料僅供參考，不構成投資建議」。

## 測試

- `pipeline/tests`：解析、箱型、假突破虧損、停損上移後的獲利、除權息還原與官方欄位解析、注意／處置清單解析、漲跌停價與升降單位、T86／櫃買法人與月營收解析、連買天數、年增月增與 12 個月新高、RS 百分位與箱型品質（與 core 同一組手算案例）。
- `core/src/test`：同一組箱型案例、RSI／均線／MACD／布林、條件的 AND、漲跌停判斷（含除權息日參考價）與回測略過漲停、法人與營收條件、RS 百分位（含同分）、箱型天數／寬度／量縮比與排序，以及用詞不含「建議買／建議賣」。

```bash
cd pipeline && python3 -m unittest discover -s tests -v
./gradlew :core:test
```
