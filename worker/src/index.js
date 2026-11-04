// 台股盤後原始資料收集器（Cloudflare Workers 免費方案）。
//
// 免費方案每次排程只有 10 ms CPU，所以這裡「不解析」官方回應：原文存進 KV，
// 由 App 自己解析與計算。等待網路與 KV 不算 CPU 時間。
//
// 讀取：
//   GET /manifest          {"days": {"YYYYMMDD": ["twse_quotes", ...]}, "latest": "...", "updatedAt": "..."}
//   GET /day/YYYYMMDD      {"twse_quotes": <官方原文>, "tpex_quotes": <官方原文>, ...}
//   GET /raw/YYYYMMDD/src  單一來源原文

const UA = "Mozilla/5.0 (compatible; tw-box-radar-collector/1.0; +https://github.com/apsmkimo/tw-box-radar)";
const GAP_MS = 2500; // 證交所對連續請求會封 IP，每次間隔幾秒。
const TTL_SECONDS = 60 * 60 * 24 * 320; // 約 10.5 個月後自動過期（每天約 2.6 MB，免費 KV 只有 1 GB）。
const LOOKBACK_WEEKDAYS = 5; // 每次也補最近幾個平日缺的行情。

export const SOURCES = {
  // 行情：兩邊都有才算完整的交易日。
  twse_quotes: (d) => `https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX?date=${d}&type=ALLBUT0999&response=json`,
  tpex_quotes: (d) => `https://www.tpex.org.tw/www/zh-tw/afterTrading/dailyQuotes?date=${slash(d)}&id=&response=json`,
  // 三大法人
  twse_t86: (d) => `https://www.twse.com.tw/rwd/zh/fund/T86?date=${d}&selectType=ALLBUT0999&response=json`,
  tpex_insti: (d) => `https://www.tpex.org.tw/www/zh-tw/insti/dailyTrade?type=Daily&sect=EW&date=${slash(d)}&response=json`,
  // 注意／處置
  twse_notice: (d) => `https://www.twse.com.tw/rwd/zh/announcement/notice?startDate=${d}&endDate=${d}&response=json`,
  twse_punish: () => "https://openapi.twse.com.tw/v1/announcement/punish",
  tpex_warning: () => "https://www.tpex.org.tw/openapi/v1/tpex_trading_warning_information",
  tpex_disposal: () => "https://www.tpex.org.tw/openapi/v1/tpex_disposal_information",
  // 除權息（當天）
  twse_exrights: (d) => `https://www.twse.com.tw/exchangeReport/TWT49U?response=json&startDate=${d}&endDate=${d}`,
  tpex_exrights: (d) => `https://www.tpex.org.tw/web/stock/exright/dailyquo/exDailyQ_result.php?l=zh-tw&d=${rocSlash(d)}&ed=${rocSlash(d)}&o=json`,
  // 月營收（最新一期，每天存一份，App 取最新）
  twse_revenue: () => "https://openapi.twse.com.tw/v1/opendata/t187ap05_L",
  tpex_revenue: () => "https://www.tpex.org.tw/openapi/v1/mopsfin_t187ap05_O",
};

// 只隨「今天」抓的來源（最新快照，不能指定日期）。
const TODAY_ONLY = new Set(["twse_punish", "tpex_warning", "tpex_disposal", "twse_revenue", "tpex_revenue"]);
const QUOTES = ["twse_quotes", "tpex_quotes"];

export default {
  async scheduled(_event, env, ctx) {
    ctx.waitUntil(collect(env, new Date()));
  },

  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const parts = url.pathname.split("/").filter(Boolean);
    if (request.method !== "GET" && request.method !== "HEAD") return text("method not allowed", 405);
    if (parts.length === 0 || parts[0] === "health") return text("ok");
    if (parts[0] === "manifest") {
      const body = (await env.RAW.get("manifest")) ?? '{"days":{}}';
      return json(body, 300);
    }
    if (parts[0] === "day" && isDate(parts[1])) {
      const day = parts[1];
      const manifest = await readManifest(env);
      const sources = manifest.days[day];
      if (!sources) return text("not found", 404);
      // 原文本身就是 JSON，以串流把位元組直接接起來，不解碼、不解析（省 CPU）。
      const { readable, writable } = new TransformStream();
      const write = async () => {
        const enc = new TextEncoder();
        const writer = writable.getWriter();
        let first = true;
        await writer.write(enc.encode("{"));
        for (const s of sources) {
          const value = await env.RAW.get(key(day, s), { type: "stream" });
          if (value == null) continue;
          await writer.write(enc.encode(`${first ? "" : ","}${JSON.stringify(s)}:`));
          first = false;
          const reader = value.getReader();
          for (;;) {
            const { done, value: chunk } = await reader.read();
            if (done) break;
            await writer.write(chunk);
          }
        }
        await writer.write(enc.encode("}"));
        await writer.close();
      };
      const done = write();
      ctx?.waitUntil?.(done);
      return json(readable, day === manifest.latest ? 600 : 86400);
    }
    if (parts[0] === "raw" && isDate(parts[1]) && parts[2] in SOURCES) {
      const body = await env.RAW.get(key(parts[1], parts[2]), { type: "stream" });
      return body == null ? text("not found", 404) : json(body, 3600);
    }
    return text("not found", 404);
  },
};

export async function collect(env, now, fetcher = fetch, sleep = defaultSleep) {
  const today = taipeiDate(now);
  const manifest = await readManifest(env);
  const targets = recentWeekdays(today, LOOKBACK_WEEKDAYS).filter((d) => !hasQuotes(manifest, d));
  if (!targets.includes(today) && isWeekday(today)) targets.push(today); // 今天的快照類來源每次都更新
  let first = true;
  const pause = async () => {
    if (!first) await sleep(GAP_MS);
    first = false;
  };
  for (const day of targets) {
    const have = new Set(manifest.days[day] ?? []);
    for (const [source, build] of Object.entries(SOURCES)) {
      if (TODAY_ONLY.has(source) && day !== today) continue;
      if (have.has(source) && !TODAY_ONLY.has(source) && source !== "twse_notice") continue;
      await pause();
      const body = await download(fetcher, build(day));
      if (body == null || !looksUsable(source, body)) continue;
      await env.RAW.put(key(day, source), body, { expirationTtl: TTL_SECONDS });
      have.add(source);
    }
    if (have.size > 0) manifest.days[day] = [...have].sort();
    // 只有一邊行情時不算完整，下次排程再補。
  }
  prune(manifest, today);
  const complete = Object.keys(manifest.days).filter((d) => hasQuotes(manifest, d)).sort();
  manifest.latest = complete.at(-1) ?? null;
  manifest.updatedAt = now.toISOString();
  await env.RAW.put("manifest", JSON.stringify(manifest));
  return manifest;
}

async function download(fetcher, url) {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res = await fetcher(url, { headers: { "User-Agent": UA, Accept: "application/json,*/*" } });
      if (res.ok) {
        // 保持原始位元組，不整份解碼（省 CPU）；只去掉 UTF-8 BOM。
        const bytes = new Uint8Array(await res.arrayBuffer());
        return bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf ? bytes.subarray(3) : bytes;
      }
    } catch (_) {
      // 網路錯誤，重試一次
    }
  }
  return null;
}

// 只做字串層級的檢查，不解析 JSON（省 CPU）。
export function looksUsable(source, raw) {
  const dec = new TextDecoder();
  const body = typeof raw === "string" ? raw : dec.decode(raw.subarray(0, 2000)) + dec.decode(raw.subarray(Math.max(2000, raw.length - 400)));
  const head = body.trimStart();
  if (!(head.startsWith("{") || head.startsWith("["))) return false; // HTML 封鎖頁、錯誤頁
  if (head.startsWith("[")) return head.length > 2; // OpenAPI 陣列不可為空
  // 證交所的 stat 可能在開頭或結尾（MI_INDEX 在最後），只看頭尾一小段。
  const edges = body.slice(0, 2000) + body.slice(-400);
  if (source === "twse_notice") return edges.includes('"stat"'); // 當天沒有注意股也是有效結果
  if (/"stat"\s*:\s*"OK"/i.test(edges)) return true;
  // 櫃買：totalCount 大於 0
  if (source.startsWith("tpex")) return /"(totalCount|iTotalRecords)"\s*:\s*[1-9]/.test(edges);
  return false;
}

function hasQuotes(manifest, day) {
  const s = manifest.days[day];
  return !!s && QUOTES.every((q) => s.includes(q));
}

async function readManifest(env) {
  const raw = await env.RAW.get("manifest");
  const m = raw ? JSON.parse(raw) : {};
  m.days ??= {};
  return m;
}

function prune(manifest, today) {
  const cutoff = shiftDays(today, -Math.floor(TTL_SECONDS / 86400) + 5);
  for (const d of Object.keys(manifest.days)) if (d < cutoff) delete manifest.days[d];
}

export function taipeiDate(now) {
  const t = new Date(now.getTime() + 8 * 3600 * 1000);
  return t.toISOString().slice(0, 10).replaceAll("-", "");
}

export function recentWeekdays(today, n) {
  const out = [];
  let d = today;
  while (out.length < n) {
    d = shiftDays(d, -1);
    if (isWeekday(d)) out.unshift(d);
  }
  if (isWeekday(today)) out.push(today);
  return out;
}

function shiftDays(d, delta) {
  const t = new Date(Date.UTC(+d.slice(0, 4), +d.slice(4, 6) - 1, +d.slice(6, 8)) + delta * 86400000);
  return t.toISOString().slice(0, 10).replaceAll("-", "");
}

function isWeekday(d) {
  const w = new Date(Date.UTC(+d.slice(0, 4), +d.slice(4, 6) - 1, +d.slice(6, 8))).getUTCDay();
  return w !== 0 && w !== 6;
}

const isDate = (s) => typeof s === "string" && /^\d{8}$/.test(s);
const key = (day, source) => `d/${day}/${source}`;
const slash = (d) => `${d.slice(0, 4)}/${d.slice(4, 6)}/${d.slice(6, 8)}`;
const rocSlash = (d) => `${+d.slice(0, 4) - 1911}/${d.slice(4, 6)}/${d.slice(6, 8)}`;
const defaultSleep = (ms) => new Promise((r) => setTimeout(r, ms));

function json(body, maxAge) {
  return new Response(body, {
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${maxAge}`,
      "access-control-allow-origin": "*",
    },
  });
}

function text(body, status = 200) {
  return new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8" } });
}
