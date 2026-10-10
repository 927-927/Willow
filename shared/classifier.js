// Willow rule classifier - platform-neutral ES module (runs in the browser and in Node).
// The Android app will port this logic to Kotlin and read the same JSON rule files.
//
// Input is a VideoObservation: only what is visible on screen before the audio plays.
//   { author: {name, verified}, caption, hashtags[], onScreenText, product: {title, price} | null }

export function observationText(obs) {
  return [
    obs.author?.name,
    obs.caption,
    (obs.hashtags || []).join(' '),
    obs.onScreenText,
    obs.product?.title,
  ].filter(Boolean).join(' ');
}

// Tier A: instant on-device rules.
// Returns { score, level: 'intercept' | 'tip' | 'allow', label, matched: [{id, label, label_zh, phrases}] }
export function classifyWithRules(obs, rules) {
  const text = observationText(obs);

  if (rules.allow?.verifiedTypes?.includes(obs.author?.verified)) {
    return { score: 0, level: 'allow', label: 'verified_medical', matched: [] };
  }

  const matched = [];
  let score = 0;
  for (const [id, group] of Object.entries(rules.groups)) {
    const phrases = group.phrases.filter((p) => text.includes(p));
    if (phrases.length) {
      matched.push({ id, label: group.label, label_zh: group.label_zh, phrases });
      score += group.weight;
    }
  }
  if (obs.product) {
    const f = rules.features.hasProduct;
    matched.push({ id: 'hasProduct', label: f.label, label_zh: f.label_zh, phrases: [obs.product.title] });
    score += f.weight;
  }

  const hit = new Set(matched.map((m) => m.id));
  for (const combo of rules.combos) {
    if (combo.any_of.some((g) => hit.has(g)) && combo.and_any_of.some((g) => hit.has(g))) {
      matched.push({ id: 'combo', label: combo.label, label_zh: combo.label_zh, phrases: [] });
      score += combo.bonus;
    }
  }

  score = Math.min(1, Math.round(score * 100) / 100);
  const level =
    score >= rules.thresholds.intercept ? 'intercept' :
    score >= rules.thresholds.tip ? 'tip' : 'allow';
  const label =
    level === 'intercept' ? 'predatory_health' :
    level === 'tip' ? 'dubious_health' : 'other';
  return { score, level, label, matched };
}

// Which of Grandpa's hobbies (if any) this video is about.
export function matchHobby(obs, hobbyRules) {
  const text = observationText(obs);
  let best = null;
  let bestHits = 0;
  for (const h of hobbyRules.hobbies) {
    const hits = h.phrases.filter((p) => text.includes(p)).length;
    if (hits > bestHits) { best = h; bestHits = hits; }
  }
  return best;
}

// Pick the curated debunk card that best fits. Needs >= 2 distinct keyword hits,
// so a single shared word (e.g. "降压") doesn't pull in an off-topic card.
export function pickDebunk(obs, debunks) {
  const text = observationText(obs);
  let best = null;
  let bestHits = 1;
  for (const card of debunks.cards) {
    const hits = card.match.filter((k) => text.includes(k)).length;
    if (hits > bestHits) { best = card; bestHits = hits; }
  }
  return best;
}
