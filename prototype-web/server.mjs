// Willow prototype server - zero dependencies (Node 22+).
// Serves the web prototype, the shared rules, and a /api/classify endpoint that asks the AI (DeepSeek or Qwen)
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

// The AI: DeepSeek (DEEPSEEK_API_KEY) by default, Qwen / 百炼 (DASHSCOPE_API_KEY) if only that key is set,
// or AI_PROVIDER=deepseek|qwen. The same choice as android/app/build.gradle.kts.
const env = process.env;
const PROVIDER = env.AI_PROVIDER || (!env.DEEPSEEK_API_KEY && env.DASHSCOPE_API_KEY ? 'qwen' : 'deepseek');
const AI = PROVIDER === 'qwen'
  ? { name: 'Qwen', key: env.DASHSCOPE_API_KEY, model: env.QWEN_MODEL || 'qwen-plus',
      baseUrl: env.QWEN_BASE_URL || 'https://dashscope.aliyuncs.com/compatible-mode/v1',
      noThinking: { enable_thinking: false } }
  : { name: 'DeepSeek', key: env.DEEPSEEK_API_KEY, model: env.DEEPSEEK_MODEL || 'deepseek-flash',
      baseUrl: env.DEEPSEEK_BASE_URL || 'https://api.deepseek.com',
      noThinking: { thinking: { type: 'disabled' } } };
const API_KEY = AI.key || '';
const BASE_URL = AI.baseUrl.replace(/\/$/, '');
const MODEL = AI.model;

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

// Shared with the Android app (packed as an asset there), so both ask the AI the same way.
const SYSTEM_PROMPT = (await readFile(path.join(ROOTS['/shared/'], 'prompts/ai_system.txt'), 'utf8')).trim();

const LABELS = new Set(['predatory_health', 'dubious_health', 'legit_health', 'hobby', 'other']);
const cache = new Map();

async function askAi(observation, ruleHints) {
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
      ...AI.noThinking, // no slow "thinking": he is waiting in front of a muted video
      response_format: { type: 'json_object' },
      messages: [
        { role: 'system', content: SYSTEM_PROMPT },
        { role: 'user', content: user },
      ],
    }),
    signal: AbortSignal.timeout(12000),
  });
  if (!res.ok) throw new Error(`${AI.name} HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const data = await res.json();
  const parsed = JSON.parse(data.choices?.[0]?.message?.content ?? '{}');
  if (!LABELS.has(parsed.label)) throw new Error(`Unexpected label from ${AI.name}: ${parsed.label}`);

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
      return send(res, 200, { ai: Boolean(API_KEY), provider: AI.name, model: API_KEY ? MODEL : null });
    }
    if (req.method === 'POST' && req.url === '/api/classify') {
      if (!API_KEY) return send(res, 200, { available: false });
      const { observation, ruleHints = [] } = await readJsonBody(req);
      try {
        return send(res, 200, { available: true, result: await askAi(observation, ruleHints) });
      } catch (err) {
        console.error('[ai]', err.message);
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
  console.log(API_KEY ? `AI enabled: ${AI.name} (model: ${MODEL})` : 'AI disabled - set DEEPSEEK_API_KEY in prototype-web/.env to enable it. Rules-only mode.');
});
