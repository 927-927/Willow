// A toy stand-in for the video platform's recommendation algorithm.
// Willow can't reorder WeChat Channels' feed - it only influences the signals the platform
// already listens to (how long a video is watched, "not interested"). This class lets the
// prototype show how those signals shift the feed over time.

export const HOBBY_CATEGORIES = ['fishing', 'garden', 'chess'];

// A "polluted" starting feed, like Grandpa's after the fall: scare videos dominate.
const INITIAL_WEIGHTS = {
  scam: 5, dubious: 2, realHealth: 1, fishing: 1.5, garden: 1, chess: 1, general: 2.5,
};

const SIGNAL_FACTORS = {
  watched: 1.12,      // watched most of the video
  skipped: 0.93,      // swiped away quickly
  notInterested: 0.6, // explicit "不感兴趣"
  invited: 1.25,      // tapped a hobby invitation
};

// Real platforms keep exploring, so interests never fully vanish: each signal pulls every
// weight a little back toward the starting mix, and the total is kept constant.
const EXPLORE = 0.03;
const TOTAL = Object.values(INITIAL_WEIGHTS).reduce((a, b) => a + b, 0);
const MIN_WEIGHT = 0.05;
const MAX_HISTORY = 600;

export class Recommender {
  constructor(videos, random = Math.random) {
    this.videos = videos;
    this.random = random;
    this.reset();
  }

  reset() {
    this.weights = { ...INITIAL_WEIGHTS };
    this.recent = [];
    this.history = [this.shares()];
  }

  shares() {
    const total = Object.values(this.weights).reduce((a, b) => a + b, 0);
    const hobby = HOBBY_CATEGORIES.reduce((sum, c) => sum + this.weights[c], 0);
    return {
      scam: this.weights.scam / total,
      hobby: hobby / total,
      topHobby: HOBBY_CATEGORIES.reduce((a, b) => (this.weights[b] > this.weights[a] ? b : a)),
    };
  }

  pickCategory() {
    const entries = Object.entries(this.weights);
    let r = this.random() * entries.reduce((sum, [, w]) => sum + w, 0);
    for (const [cat, w] of entries) {
      r -= w;
      if (r <= 0) return cat;
    }
    return entries.at(-1)[0];
  }

  next(forceCategory) {
    const category = forceCategory || this.pickCategory();
    const inCategory = this.videos.filter((v) => v.category === category);
    const fresh = inCategory.filter((v) => !this.recent.includes(v.id));
    const pool = fresh.length ? fresh : inCategory;
    const video = pool[Math.floor(this.random() * pool.length)];
    this.recent = [...this.recent, video.id].slice(-6);
    return video;
  }

  // One history point per video; pass record=false for a second signal about the same video.
  signal(category, kind, record = true) {
    const factor = SIGNAL_FACTORS[kind];
    if (!factor || !(category in this.weights)) return;
    const w = this.weights;
    for (const c in w) w[c] = w[c] * (1 - EXPLORE) + INITIAL_WEIGHTS[c] * EXPLORE;
    w[category] = Math.max(MIN_WEIGHT, w[category] * factor);
    const sum = Object.values(w).reduce((a, b) => a + b, 0);
    for (const c in w) w[c] *= TOTAL / sum;
    if (!record) return;
    this.history.push(this.shares());
    if (this.history.length > MAX_HISTORY) this.history.shift();
  }
}

// Seeded random for repeatable simulations.
export function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
