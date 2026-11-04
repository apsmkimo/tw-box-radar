# tw-box-radar-collector

Cloudflare Workers（免費方案）的盤後原始資料收集器，取代 GitHub Actions 每日排程。

- 平日 16:30、18:30、22:00（台北）各跑一次，抓證交所／櫃買中心的行情、三大法人、注意／處置、除權息、月營收。
- 免費方案每次排程只有 10 ms CPU，所以**不解析**官方回應，原始位元組直接存進 KV；解析與計算交給 App。
- 每份資料約 10.5 個月後自動過期，免費 KV 1 GB 夠用。

## 讀取

| 路徑 | 內容 |
| --- | --- |
| `GET /manifest` | `{"days": {"YYYYMMDD": [來源...]}, "latest": "YYYYMMDD", "updatedAt": "..."}` |
| `GET /day/YYYYMMDD` | `{"twse_quotes": <原文>, "tpex_quotes": <原文>, ...}` 一次拿整天 |
| `GET /raw/YYYYMMDD/<來源>` | 單一來源原文 |

`latest` 只指向上市、上櫃行情都齊的交易日。

## 部署

Cloudflare Dashboard → Workers & Pages → 連結 GitHub 的這個 repo，**Root directory 設為 `worker`**，
Deploy command `npx wrangler deploy`。KV 命名空間第一次部署時會自動建立。

## 測試

```
cd worker && npm test
```
