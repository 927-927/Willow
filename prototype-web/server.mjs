// Willow prototype server - zero dependencies (Node 22+).
// Serves the web prototype, the shared rules, and a /api/classify endpoint that asks Qwen
// about videos the rules aren't sure about. The API key stays here, never in the browser.

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const ROOTS = {
  '/shared/': path.resolve(here, '../shared'),
  '/': path.resolve(here, 'public'),
};
const PORT = Number(process.env.PORT || 5173);
const API_KEY = process.env.DASHSCOPE_API_KEY || '';
const BASE_URL = (process.env.QWEN_BASE_URL || 'https://dashscope.aliyuncs.com/compatible-mode/v1').replace(/\/$/, '');
const MODEL = process.env.QWEN_MODEL || 'qwen-plus';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const SYSTEM_PROMPT = `你是 Willow 的内容判断助手。Willow 帮助一位不太识字的中国老人分辨短视频里的健康骗局。
你只会看到视频在声音播放前屏幕上能看到的文字：作者名、标题、话题标签、画面大字、商品链接。

请判断这个视频属于哪一类：
- predatory_health：用恐吓或“神效”说法推销没有科学依据的产品或疗法（例如保健品治病、劝人停药、偏方治癌）
- dubious_health：健康说法不准确或夸大，但没有在卖东西
- legit_health：说法基本正确的健康科普（即使措辞有点夸张也算）
- hobby：钓鱼、种菜、下象棋等兴趣内容
- other：其他内容

原则：
1. 尊重老人看视频的自由。只有明确的骗局才判 predatory_health；正确的健康知识不要误伤。
2. claim、truth、action 写给老人看：口语、简单、温和，不要责备，不要说“你被骗了”。
3. truth 要准确，只说有共识的医学常识；不确定就建议问医生或药师。

只输出 JSON，格式：
{"label":"...","confidence":0到1之间的小数,"claim":"视频的核心说法，不超过30字","truth":"真相，不超过45字","action":"建议怎么做，不超过20字","claim_en":"...","truth_en":"...","action_en":"...","reason":"一句话说明判断理由"}`;

const LABELS = new Set(['predatory_health', 'dubious_health', 'legit_health', 'hobby', 'other']);
const cache = new Map();

async function askQwen(observation, ruleHints) {
  const key = JSON.stringify(observation);
  if (cache.has(key)) return { ...cache.get(key), cached: true };

  const user = [
    `作者：${observation.author?.name ?? ''}${observation.author?.verified ? `（认证：${observation.author.verified}）` : ''}`,
    `标题：${observation.caption ?? ''}`,
    `话题：${(observation.hashtags || []).join(' ')}`,
    `画面大字：${observation.onScreenText ?? ''}`,
    `商品：${observation.product ? `${observation.product.title} ¥${observation.product.price}` : '无'}`,
    `规则引擎命中的信号（仅供参考）：${ruleHints.length ? ruleHints.join('、') : '无'}`,
  ].join('\n');

  const started = Date.now();
  const res = await fetch(`${BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${API_KEY}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      model: MODEL,
      temperature: 0.1,
      enable_thinking: false,
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: user },
      ],
    }),
    signal: AbortSignal.timeout(12000),
  });
  if (!res.ok) throw new Error(`Qwen HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const parsed = JSON.parse(data.choices?.[0]?.message?.content ?? '{}');
  if (!LABELS.has(parsed.label)) throw new Error(`Unexpected label from Qwen: ${parsed.label}`);

  const result = { ...parsed, model: MODEL, latencyMs: Date.now() - started };
  cache.set(key, result);
  return result;
}

function send(res, status, body, type = 'application/json; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

async function readJsonBody(req, limit = 32 * 1024) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new Error('Body too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
}

async function serveStatic(req, res) {
  const url = new URL(req.url, 'http://localhost');
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/') pathname = '/index.html';

  const prefix = Object.keys(ROOTS).find((p) => pathname.startsWith(p));
  const root = ROOTS[prefix];
  const file = path.resolve(root, '.' + pathname.slice(prefix.length - 1));
  if (!file.startsWith(root + path.sep)) return send(res, 403, { error: 'Forbidden' });

  try {
    const body = await readFile(file);
    send(res, 200, body, MIME[path.extname(file)] || 'application/octet-stream');
  } catch {
    send(res, 404, { error: 'Not found' });
  }
}

const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/api/status') {
      return send(res, 200, { qwen: Boolean(API_KEY), model: API_KEY ? MODEL : null });
    }
    if (req.method === 'POST' && req.url === '/api/classify') {
      if (!API_KEY) return send(res, 200, { available: false });
      const { observation, ruleHints = [] } = await readJsonBody(req);
      try {
        return send(res, 200, { available: true, result: await askQwen(observation, ruleHints) });
      } catch (err) {
        console.error('[qwen]', err.message);
        return send(res, 200, { available: true, error: err.message });
      }
    }
    if (req.method === 'GET') return serveStatic(req, res);
    send(res, 405, { error: 'Method not allowed' });
  } catch (err) {
    console.error(err);
    send(res, 500, { error: 'Server error' });
  }
});

server.listen(PORT, () => {
  console.log(`Willow prototype → http://localhost:${PORT}`);
  console.log(API_KEY ? `Qwen enabled (model: ${MODEL})` : 'Qwen disabled - set DASHSCOPE_API_KEY in prototype-web/.env to enable it. Rules-only mode.');
});
