import { classifyWithRules, matchHobby, pickDebunk } from '/shared/classifier.js';
import { Recommender, mulberry32 } from './recommender.js';
import { renderVideo, esc } from './scenes.js';
import { renderShareChart, renderShareTable } from './chart.js';
import { voice } from './voice.js';

const $ = (id) => document.getElementById(id);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const getJson = (url) => fetch(url).then((r) => { if (!r.ok) throw new Error(url); return r.json(); });

const [rules, hobbyRules, debunks, feedData, status] = await Promise.all([
  getJson('/shared/rules/health_scam_rules.json'),
  getJson('/shared/rules/hobby_rules.json'),
  getJson('/shared/debunks/debunks.json'),
  getJson('/shared/samples/feed.json'),
  getJson('/api/status').catch(() => ({ qwen: false, model: null })),
]);

const recommender = new Recommender(feedData.videos);
const settings = { willow: true, qwen: status.qwen, english: false };
const WATCHED_MS = 4000;
let simSeed = 7;

const state = {
  shown: [],     // videos in the order they were shown (for ↑ previous)
  index: -1,
  token: 0,      // bumps on every video change so stale async work can bail out
  current: null, // { video, el, decision, playingSince, watchedMs, signalSent }
};

// ---------- What Willow can see ----------

// Only what the accessibility service could read before audio plays.
// Deliberately NOT included: category (ground truth), voiceover (the audio itself).
function observe(video) {
  const { author, caption, hashtags, onScreenText, product } = video;
  return { author, caption, hashtags, onScreenText, product };
}

// ---------- Decision: rules first, Qwen only when unsure ----------

async function decide(obs) {
  const r = classifyWithRules(obs, rules);
  const card = pickDebunk(obs, debunks);
  const decision = { ...r, tier: 'rules', card, cardSource: card ? 'library' : null, ai: null, latencyMs: 0 };

  const unsure = r.level === 'tip' || (r.level === 'intercept' && !card);
  if (!unsure || r.label === 'verified_medical') return finishDecision(decision, obs);

  if (!settings.qwen) {
    decision.tierNote = status.qwen ? 'Qwen switched off' : 'Qwen not configured';
    return finishDecision(decision, obs);
  }

  const t0 = performance.now();
  try {
    const res = await fetch('/api/classify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ observation: obs, ruleHints: r.matched.map((m) => m.label_zh) }),
    }).then((x) => x.json());
    decision.latencyMs = Math.round(performance.now() - t0);
    if (res.result) {
      const ai = res.result;
      decision.tier = 'qwen';
      decision.ai = ai;
      decision.label = ai.label;
      decision.level = ai.label === 'predatory_health' ? 'intercept' : ai.label === 'dubious_health' ? 'tip' : 'allow';
    } else {
      decision.tierNote = res.error ? `Qwen error, fell back to rules: ${res.error}` : 'Qwen not configured';
    }
  } catch (err) {
    decision.tierNote = `Qwen unreachable, fell back to rules`;
  }
  return finishDecision(decision, obs);
}

function finishDecision(d, obs) {
  d.hobby = matchHobby(obs, hobbyRules);
  if (!d.card && d.level !== 'allow') {
    if (d.ai?.claim && d.ai?.truth) {
      d.cardSource = 'ai';
      d.card = {
        icon: '💬',
        myth_zh: d.ai.claim, myth_en: d.ai.claim_en,
        truth_zh: d.ai.truth, truth_en: d.ai.truth_en,
        action_zh: d.ai.action, action_en: d.ai.action_en,
        source_zh: '这段解释由 AI（通义千问）生成，仅供参考。身体的事，请问医生。',
      };
    } else if (d.level === 'intercept') {
      d.cardSource = 'generic';
      d.card = debunks.generic;
    }
  }
  return d;
}

// ---------- Feed navigation ----------

function leaveCurrent() {
  const cur = state.current;
  if (!cur) return;
  pausePlayback();
  if (!cur.signalSent) {
    recommender.signal(cur.video.category, cur.watchedMs >= WATCHED_MS ? 'watched' : 'skipped');
    cur.signalSent = true;
    renderFeedPanel();
  }
}

function goNext(forceCategory) {
  if (!forceCategory && state.index < state.shown.length - 1) {
    state.index++;
  } else {
    state.shown = state.shown.slice(0, state.index + 1);
    state.shown.push(recommender.next(forceCategory));
    state.index = state.shown.length - 1;
  }
  showVideo(state.shown[state.index], 'up');
}

function goPrev() {
  if (state.index <= 0) return toast('已经是第一个了');
  state.index--;
  showVideo(state.shown[state.index], 'down');
}

async function showVideo(video, direction) {
  leaveCurrent();
  const token = ++state.token;
  voice.stop();
  closeOverlays();

  const feed = $('feed');
  const el = renderVideo(video);
  el.classList.add(direction === 'up' ? 'enter-up' : 'enter-down');
  for (const old of feed.querySelectorAll('.video')) {
    old.classList.add(direction === 'up' ? 'leave-up' : 'leave-down');
    setTimeout(() => old.remove(), 400);
  }
  feed.append(el);

  state.current = { video, el, decision: null, playingSince: null, watchedMs: 0, signalSent: false };
  const obs = observe(video);
  renderObservation(obs);

  if (!settings.willow) {
    state.current.decision = { level: 'allow', tier: 'off' };
    renderDecision(state.current.decision);
    play();
    return;
  }

  // Mute first, decide second.
  setMuted(true);
  $('checking').hidden = false;
  renderDecision(null);
  await sleep(250); // stands in for reading the screen
  const decision = await decide(obs);
  if (token !== state.token) return;

  $('checking').hidden = true;
  state.current.decision = decision;
  renderDecision(decision);

  if (decision.level === 'intercept') {
    openDebunk(decision, token);
  } else {
    setMuted(false);
    if (decision.level === 'tip') showTip(decision);
    play();
  }
}

function play() {
  const cur = state.current;
  if (!cur) return;
  cur.playingSince = performance.now();
  cur.el.classList.add('playing');
  voice.say(cur.video.voiceover, { role: 'video', dramatic: cur.video.scene.style === 'alarm' });
}

function pausePlayback() {
  const cur = state.current;
  if (cur?.playingSince != null) {
    cur.watchedMs += performance.now() - cur.playingSince;
    cur.playingSince = null;
  }
}

function setMuted(on) {
  const icon = state.current?.el.querySelector('.muted-icon');
  if (icon) icon.hidden = !on;
  if (on) voice.stop();
}

function closeOverlays() {
  $('checking').hidden = true;
  $('tip').hidden = true;
  closeDebunk();
  closeSheet();
}

// ---------- Willow's debunk screen ----------
// 4 steps (beats). Autoplay moves on only after the voice finishes plus a reading pause.
// Grandpa can go back/forward at any time (buttons, dots, swipe left/right, ← → keys);
// as soon as he does, autoplay stops so the screen never runs away from him.

const AUTO_PAUSE_AFTER_VOICE_MS = 2500;           // reading time after the voice finishes
const AUTO_MS_PER_CHAR_NO_VOICE = 300;            // when voice is off: ~3 characters/second
const AUTO_MIN_MS_NO_VOICE = 5000;
const RESUME_DELAY_MS = 1500;

const debunk = { beats: [], index: 0, auto: true, run: 0, timer: null, token: 0 };

function hobbyInvite() {
  const top = recommender.shares().topHobby;
  return hobbyRules.hobbies.find((h) => h.id === top) || hobbyRules.hobbies[0];
}

function openDebunk(decision, token) {
  const card = decision.card;
  const hobby = hobbyInvite();
  debunk.beats = [
    { icon: '🍵', title: '等一下', body: '我们先看看这个说法对不对', en: "Hold on - let's check this claim together.",
      speech: '等一下，我们先看看这个说法对不对。' },
    { icon: '❓', title: '视频里说', body: card.myth_zh, en: card.myth_en,
      speech: `视频里说：${card.myth_zh}。` },
    { icon: '✅', title: '其实', body: card.truth_zh, en: card.truth_en, source: card.source_zh,
      speech: `其实，${card.truth_zh}` },
    { icon: card.icon || '👨‍⚕️', title: '怎么办', body: card.action_zh, en: card.action_en,
      speech: `所以，${card.action_zh}` },
  ];
  debunk.index = 0;
  debunk.auto = true;
  debunk.token = token;

  const box = $('debunk');
  box.innerHTML = `
    <div class="debunk-top">
      <div class="dots">${debunk.beats.map((b, i) => `<button type="button" data-beat="${i}" aria-label="第 ${i + 1} 步：${esc(b.title)}"><i></i></button>`).join('')}</div>
      <button type="button" class="link" data-act="continue">继续看原视频 ›</button>
    </div>
    <div class="beat" id="beat"></div>
    <div class="beat-nav">
      <button type="button" class="nav-btn" data-act="prevBeat" id="prevBeat">‹ 上一步</button>
      <button type="button" class="nav-btn auto" data-act="toggleAuto" id="autoBtn"></button>
      <button type="button" class="nav-btn" data-act="nextBeat" id="nextBeat">下一步 ›</button>
    </div>
    <div class="debunk-actions" id="debunkActions" hidden>
      <button type="button" class="big primary" data-act="next">换一个视频</button>
      <button type="button" class="big secondary" data-act="invite">${hobby.icon} ${esc(hobby.invite_zh)}</button>
      <button type="button" class="big outline" data-act="continue">继续看原视频 ▶</button>
      <p class="en">Next video · ${esc(hobby.label)} · Keep watching this one</p>
    </div>
    <div class="debunk-foot">🌳 Willow ${decision.cardSource === 'ai' ? '· AI 解释' : ''}</div>`;
  box.dataset.hobby = hobby.id;
  box.hidden = false;
  showBeat(0);
}

function closeDebunk() {
  debunk.run++;
  clearTimeout(debunk.timer);
  $('debunk').hidden = true;
}

const debunkOpen = () => !$('debunk').hidden;

function showBeat(i, { manual = false } = {}) {
  const last = debunk.beats.length - 1;
  i = Math.max(0, Math.min(last, i));
  if (manual) debunk.auto = false;
  debunk.index = i;
  const b = debunk.beats[i];

  $('debunk').querySelectorAll('.dots i').forEach((d, k) => d.classList.toggle('on', k <= i));
  $('beat').innerHTML = `
    <div class="beat-in">
      <div class="beat-icon">${esc(b.icon)}</div>
      <div class="beat-title">${esc(b.title)} <button type="button" class="replay" data-act="replay" aria-label="再听一遍">🔊</button></div>
      <div class="beat-body">${esc(b.body)}</div>
      ${b.en ? `<div class="en">${esc(b.en)}</div>` : ''}
      ${b.source ? `<div class="beat-source">${esc(b.source)}</div>` : ''}
    </div>`;
  $('prevBeat').disabled = i === 0;
  $('nextBeat').disabled = i === last;
  $('debunkActions').hidden = i !== last;
  renderAutoButton();
  speakBeat();
}

// Speak the current beat, then (if autoplay is on) schedule the next one.
async function speakBeat() {
  const run = ++debunk.run;
  clearTimeout(debunk.timer);
  voice.stop();
  const b = debunk.beats[debunk.index];
  const spoke = await voice.say(b.speech);
  if (run !== debunk.run) return; // he navigated, paused, or left in the meantime
  scheduleNextBeat(spoke ? AUTO_PAUSE_AFTER_VOICE_MS : Math.max(AUTO_MIN_MS_NO_VOICE, b.body.length * AUTO_MS_PER_CHAR_NO_VOICE));
}

function scheduleNextBeat(delay) {
  clearTimeout(debunk.timer);
  if (!debunk.auto || debunk.index >= debunk.beats.length - 1) return;
  const run = debunk.run;
  const tick = () => {
    if (run !== debunk.run || !debunk.auto || debunk.token !== state.token || !debunkOpen()) return;
    if (voice.speaking) { debunk.timer = setTimeout(tick, 500); return; } // never cut a sentence off
    showBeat(debunk.index + 1);
  };
  debunk.timer = setTimeout(tick, delay);
}

function renderAutoButton() {
  const atEnd = debunk.index >= debunk.beats.length - 1;
  const btn = $('autoBtn');
  btn.disabled = atEnd;
  btn.textContent = debunk.auto && !atEnd ? '⏸ 暂停' : '▶ 自动';
  btn.setAttribute('aria-label', debunk.auto ? '暂停自动播放' : '继续自动播放');
}

function toggleAuto() {
  debunk.auto = !debunk.auto;
  renderAutoButton();
  if (debunk.auto) {
    debunk.run++; // the pending speakBeat() must not schedule a second advance
    scheduleNextBeat(RESUME_DELAY_MS);
  } else {
    clearTimeout(debunk.timer);
  }
}

$('debunk').addEventListener('click', (e) => {
  const cur = state.current;
  if (!cur) return;
  const beatBtn = e.target.closest('[data-beat]');
  if (beatBtn) return showBeat(Number(beatBtn.dataset.beat), { manual: true });
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (!act) return;
  if (act === 'prevBeat') {
    showBeat(debunk.index - 1, { manual: true });
  } else if (act === 'nextBeat') {
    showBeat(debunk.index + 1, { manual: true });
  } else if (act === 'toggleAuto') {
    toggleAuto();
  } else if (act === 'replay') {
    debunk.auto = false;
    renderAutoButton();
    speakBeat();
  } else if (act === 'continue') {
    voice.stop();
    closeDebunk();
    setMuted(false);
    toast('好的，继续播放');
    play();
  } else if (act === 'next') {
    recommender.signal(cur.video.category, 'notInterested');
    cur.signalSent = true;
    renderFeedPanel();
    toast('好的，以后少推这种');
    goNext();
  } else if (act === 'invite') {
    const hobbyId = $('debunk').dataset.hobby;
    recommender.signal(hobbyId, 'invited', false);
    recommender.signal(cur.video.category, 'notInterested');
    cur.signalSent = true;
    renderFeedPanel();
    goNext(hobbyId);
  }
});

// ---------- Gentle tip (video keeps playing) ----------

function showTip(decision) {
  const text = decision.card?.truth_zh || debunks.tip_generic.text_zh;
  const en = decision.card?.truth_en || debunks.tip_generic.text_en;
  const tip = $('tip');
  tip.innerHTML = `
    <div class="tip-head">💡 Willow 小提示</div>
    <div class="tip-body">${esc(text)}</div>
    <div class="en">${esc(en)}</div>
    <button type="button" class="tip-close">知道了</button>`;
  tip.hidden = false;
  tip.querySelector('.tip-close').onclick = () => { tip.hidden = true; };
}

// ---------- Product sheet (a pause, never a block) ----------

function openSheet() {
  const cur = state.current;
  if (!cur?.video.product) return;
  const p = cur.video.product;
  const flagged = settings.willow && cur.decision && cur.decision.level !== 'allow';
  $('sheet').innerHTML = `
    <div class="sheet-grip"></div>
    <div class="sheet-product">
      <div class="sheet-img">${esc(cur.video.scene.emoji)}</div>
      <div><div class="sheet-title">${esc(p.title)}</div><div class="sheet-price">¥${p.price}</div><div class="sub">已售 1.2万 · 包邮</div></div>
    </div>
    ${flagged ? `<div class="sheet-note">💡 保健食品不是药物，不能代替药物治疗疾病。买之前可以再想想，或者问问医生。<div class="en">Supplements are not medicine. Maybe think it over, or ask a doctor first.</div></div>` : ''}
    <div class="sheet-actions">
      <button type="button" class="big outline" data-sheet="close">再想想</button>
      <button type="button" class="big primary buy" data-sheet="buy">立即购买</button>
    </div>`;
  $('sheet').hidden = false;
  $('sheetBackdrop').hidden = false;
}

function closeSheet() {
  $('sheet').hidden = true;
  $('sheetBackdrop').hidden = true;
}

$('sheet').addEventListener('click', (e) => {
  const act = e.target.closest('[data-sheet]')?.dataset.sheet;
  if (act === 'close') closeSheet();
  if (act === 'buy') { closeSheet(); toast('演示：不会真的下单 (demo - no real order)'); }
});
$('sheetBackdrop').addEventListener('click', closeSheet);
$('feed').addEventListener('click', (e) => {
  if (e.target.closest('[data-action="product"]')) openSheet();
});

// ---------- Small UI helpers ----------

let toastTimer;
function toast(text) {
  const t = $('toast');
  t.textContent = text;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.hidden = true; }, 2200);
}

// ---------- Behind-the-scenes panel ----------

function renderObservation(obs) {
  const rows = [
    ['Author', `${esc(obs.author.name)}${obs.author.verified ? ` <span class="chip">${obs.author.verified === 'medical' ? 'verified doctor' : 'verified creator'}</span>` : ''}`],
    ['Caption', esc(obs.caption)],
    ['Hashtags', esc(obs.hashtags.join(' ')) || '—'],
    ['On-screen text', `${esc(obs.onScreenText)} <span class="sub">(OCR, phase 3)</span>`],
    ['Product link', obs.product ? `${esc(obs.product.title)} · ¥${obs.product.price}` : '—'],
  ];
  $('observation').innerHTML = rows.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join('');
}

const LEVELS = {
  intercept: { icon: '⛔', text: 'Intercepted · 拦截并解释', cls: 'critical' },
  tip: { icon: '💡', text: 'Gentle tip · 小提示, video plays', cls: 'warning' },
  allow: { icon: '✓', text: 'Allowed · 正常播放', cls: 'good' },
  off: { icon: '○', text: 'Willow is off · 未保护', cls: 'neutral' },
};

function renderDecision(d) {
  const box = $('decision');
  if (!d) {
    box.innerHTML = '<p class="status neutral"><span>🔇</span> Muted while checking…</p>';
    return;
  }
  if (d.tier === 'off') {
    box.innerHTML = `<p class="status neutral"><span>${LEVELS.off.icon}</span> ${LEVELS.off.text}</p><p class="sub">The video plays as-is, sound and all.</p>`;
    return;
  }
  const lv = LEVELS[d.level];
  const tierText = d.tier === 'qwen'
    ? `Rules were unsure → asked Qwen (${esc(d.ai.model)}) · ${d.latencyMs} ms${d.ai.cached ? ' (cached)' : ''}`
    : `Rules only · instant${d.tierNote ? ` · ${esc(d.tierNote)}` : ''}`;
  const chips = d.matched.length
    ? d.matched.map((m) => `<span class="chip" title="${esc(m.phrases.join('、'))}">${esc(m.label_zh)} · ${esc(m.label)}${m.phrases.length ? `: ${esc(m.phrases.slice(0, 3).join('、'))}` : ''}</span>`).join('')
    : `<span class="sub">${d.label === 'verified_medical' ? 'Verified medical account - never intercepted.' : 'No warning signals.'}</span>`;
  const t = rules.thresholds;
  box.innerHTML = `
    <p class="status ${lv.cls}"><span>${lv.icon}</span> ${lv.text}</p>
    <div class="meter" role="img" aria-label="Rule score ${d.score} of 1">
      <div class="meter-fill ${lv.cls}" style="width:${Math.max(2, d.score * 100)}%"></div>
      <i style="left:${t.tip * 100}%"></i><i style="left:${t.intercept * 100}%"></i>
    </div>
    <div class="meter-scale"><span>Rule score <b>${d.score.toFixed(2)}</b></span><span>tip ≥ ${t.tip} · intercept ≥ ${t.intercept}</span></div>
    <div class="chips">${chips}</div>
    <p class="sub">${tierText}</p>
    ${d.ai ? `<p class="ai-note"><b>Qwen:</b> ${esc(d.ai.label)} (${Math.round((d.ai.confidence ?? 0) * 100)}%) - ${esc(d.ai.reason || '')}</p>` : ''}
    ${d.card && d.level !== 'allow' ? `<p class="sub">Explanation: ${d.cardSource === 'library' ? `curated card <code>${esc(d.card.id)}</code>` : d.cardSource === 'ai' ? 'written by Qwen for this video' : 'generic “scare then sell” card'}</p>` : ''}
    ${d.hobby ? `<p class="sub">Hobby video: ${d.hobby.icon} ${esc(d.hobby.label)}</p>` : ''}`;
}

function renderFeedPanel() {
  const h = recommender.history;
  const first = h[0];
  const last = h.at(-1);
  const tile = (label, now, start, upIsGood) => {
    const diff = Math.round((now - start) * 100);
    const good = diff === 0 ? null : (diff > 0) === upIsGood;
    return `<div class="tile">
      <div class="tile-label">${label}</div>
      <div class="tile-value">${Math.round(now * 100)}%</div>
      <div class="tile-delta ${good === null ? '' : good ? 'good' : 'bad'}">${diff === 0 ? 'no change' : `${diff > 0 ? '▲' : '▼'} ${Math.abs(diff)} pts`} vs start</div>
    </div>`;
  };
  $('tiles').innerHTML =
    tile('Scam-health share of feed', last.scam, first.scam, false) +
    tile('Hobby share of feed', last.hobby, first.hobby, true);
  renderShareChart($('chart'), h);
  renderShareTable($('chartTable'), h);
}

// A simple model of Grandpa, so a week of viewing can be simulated in one click.
function simulate(steps = 150) {
  const rand = mulberry32(simSeed++);
  for (let i = 0; i < steps; i++) {
    const video = recommender.next();
    const level = settings.willow ? classifyWithRules(observe(video), rules).level : 'off';
    const r = rand();
    if (level === 'intercept') {
      if (r < 0.85) {
        if (r >= 0.7) recommender.signal(recommender.shares().topHobby, 'invited', false);
        recommender.signal(video.category, 'notInterested');
      } else {
        recommender.signal(video.category, 'watched'); // he chose to keep watching
      }
      continue;
    }
    // Fear hooks hold attention: unprotected, he watches most scare videos to the end.
    const watchChance = {
      scam: 0.85, dubious: level === 'tip' ? 0.5 : 0.7, realHealth: 0.6,
      fishing: 0.85, garden: 0.85, chess: 0.85, general: 0.5,
    }[video.category];
    recommender.signal(video.category, r < watchChance ? 'watched' : 'skipped');
  }
  renderFeedPanel();
  $('simNote').textContent = settings.willow
    ? 'Simulated with Willow ON: he usually taps 换一个 on debunks, sometimes keeps watching.'
    : 'Simulated with Willow OFF: scare videos hold his attention, so the platform sends more.';
}

// ---------- Wiring ----------

function bindSettings() {
  $('optWillow').onchange = (e) => {
    settings.willow = e.target.checked;
    $('willowBadge').hidden = !settings.willow;
    if (state.current) showVideo(state.current.video, 'up');
  };
  $('optQwen').checked = settings.qwen;
  $('optQwen').disabled = !status.qwen;
  $('optQwen').onchange = (e) => { settings.qwen = e.target.checked; };
  $('qwenStatus').innerHTML = status.qwen
    ? `Connected · model <code>${esc(status.model)}</code>. Only used for unclear videos.`
    : 'Not configured: rules only. Add <code>DASHSCOPE_API_KEY</code> to <code>prototype-web/.env</code> and restart.';
  $('optVoice').disabled = !voice.available;
  $('optVoice').onchange = (e) => { voice.enabled = e.target.checked; };
  $('optEnglish').onchange = (e) => { document.body.classList.toggle('show-en', e.target.checked); };
  $('hobbyList').textContent = hobbyRules.hobbies.map((h) => `${h.icon} ${h.label}`).join(' · ');
  $('simBtn').onclick = () => simulate();
  $('resetBtn').onclick = () => {
    recommender.reset();
    if (state.current) state.current.signalSent = true; // don't count the current video into the fresh start
    $('simNote').textContent = 'Feed reset to the “polluted” starting point.';
    renderFeedPanel();
  };
}

function bindNavigation() {
  const screen = $('screen');
  const started = () => $('start').hidden;
  // Any navigation before the start screen is tapped simply starts the feed.
  const begin = () => {
    if (started()) return false;
    $('start').hidden = true;
    goNext();
    return true;
  };
  $('nextBtn').onclick = () => begin() || goNext();
  $('prevBtn').onclick = () => begin() || goPrev();

  let lastWheel = 0;
  screen.addEventListener('wheel', (e) => {
    if (e.target.closest('.sheet')) return;
    e.preventDefault();
    const now = Date.now();
    if (Math.abs(e.deltaY) < 20 || now - lastWheel < 700) return;
    lastWheel = now;
    if (begin()) return;
    e.deltaY > 0 ? goNext() : goPrev();
  }, { passive: false });

  let start = null;
  screen.addEventListener('pointerdown', (e) => {
    start = e.target.closest('button, .sheet, .tip') ? null : { x: e.clientX, y: e.clientY };
  });
  screen.addEventListener('pointerup', (e) => {
    if (start == null || !started()) return;
    const dx = e.clientX - start.x;
    const dy = e.clientY - start.y;
    start = null;
    // Sideways swipe on the explanation card = previous / next step.
    if (debunkOpen() && Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
      showBeat(debunk.index + (dx < 0 ? 1 : -1), { manual: true });
      return;
    }
    if (dy < -50) goNext();
    else if (dy > 50) goPrev();
  });

  document.addEventListener('keydown', (e) => {
    if (e.target.matches('input, textarea')) return;
    if (debunkOpen() && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      e.preventDefault();
      showBeat(debunk.index + (e.key === 'ArrowRight' ? 1 : -1), { manual: true });
      return;
    }
    if (e.key === 'ArrowDown') { e.preventDefault(); begin() || goNext(); }
    if (e.key === 'ArrowUp') { e.preventDefault(); begin() || goPrev(); }
  });

  $('start').onclick = begin;
}

let resizeTimer;
window.addEventListener('resize', () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(renderFeedPanel, 150);
});

bindSettings();
bindNavigation();
renderFeedPanel();
window.willowReady = true;
