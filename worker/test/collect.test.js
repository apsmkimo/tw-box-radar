import { test } from "node:test";
import assert from "node:assert/strict";
import worker, { collect, looksUsable, taipeiDate, recentWeekdays } from "../src/index.js";

class MemKV {
  constructor() { this.map = new Map(); }
  async get(k, opts) {
    if (!this.map.has(k)) return null;
    const v = this.map.get(k);
    if (opts?.type === "stream") return new Response(v).body;
    return typeof v === "string" ? v : new TextDecoder().decode(v);
  }
  async put(k, v) { this.map.set(k, v); }
}

const okTwse = '{"stat":"OK","date":"20261104","tables":[]}';
const okTpex = '{"tables":[{"totalCount":900,"data":[["1101"]]}],"date":"20261104","stat":"ok"}';
const okArr = '[{"Code":"1101"}]';

function fakeFetch(urls) {
  return async (url) => {
    urls.push(url);
    let body = okTwse;
    if (url.includes("openapi")) body = okArr;
    else if (url.includes("tpex")) body = okTpex;
    return new Response(body, { status: 200 });
  };
}

test("taipei date and weekdays", () => {
  assert.equal(taipeiDate(new Date("2026-11-04T08:30:00Z")), "20261104");
  assert.equal(taipeiDate(new Date("2026-11-03T17:00:00Z")), "20261104");
  assert.deepEqual(recentWeekdays("20261104", 3), ["20261030", "20261102", "20261103", "20261104"]);
});

test("usable check rejects html and empty", () => {
  assert.equal(looksUsable("twse_quotes", "<html>blocked</html>"), false);
  assert.equal(looksUsable("twse_quotes", '{"stat":"很抱歉，沒有符合條件的資料!"}'), false);
  assert.equal(looksUsable("twse_quotes", okTwse), true);
  assert.equal(looksUsable("tpex_quotes", '{"tables":[{"totalCount":0,"data":[]}]}'), false);
  assert.equal(looksUsable("tpex_quotes", okTpex), true);
  assert.equal(looksUsable("twse_punish", "[]"), false);
  assert.equal(looksUsable("twse_quotes", '{"tables":[' + "x".repeat(5000) + '],"stat":"OK","date":"20261103"}'), true);
});

test("collect stores raw, manifest, and serves day bundle", async () => {
  const env = { RAW: new MemKV() };
  const urls = [];
  const m = await collect(env, new Date("2026-11-04T08:30:00Z"), fakeFetch(urls), async () => {});
  assert.equal(m.latest, "20261104");
  assert.ok(m.days["20261104"].includes("twse_revenue"));
  assert.ok(!m.days["20261103"].includes("twse_revenue"));
  const res = await worker.fetch(new Request("https://x/day/20261104"), env);
  const bundle = await res.json();
  assert.equal(bundle.twse_quotes.stat, "OK");
  assert.equal(bundle.tpex_quotes.tables[0].totalCount, 900);

  // 第二次只重抓今天的快照與注意股，不重抓已完整的歷史行情
  urls.length = 0;
  await collect(env, new Date("2026-11-04T10:30:00Z"), fakeFetch(urls), async () => {});
  assert.ok(urls.every((u) => !u.includes("date=20261103")));
  assert.ok(!urls.some((u) => u.includes("MI_INDEX")));
});
