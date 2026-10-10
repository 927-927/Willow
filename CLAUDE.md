# Willow (柳荫)

An app that protects the user's grandpa from fear-based health scam videos on **WeChat Channels (视频号)**. It mutes a new video, checks it, and if it's a scam, covers it with a calm spoken explanation. It also invites him to his hobbies (fishing, backyard crops, Chinese chess). It's part of the user's university application; the essay draft is `文书大概.txt`. Full plan: `APP_OUTLINE.md`.

## Non-negotiable product rules (from the user)
- **No monitoring.** No watch history, no logs, no reports or alerts to family, no access to his account/messages/contacts. Only on-screen text of an *unclear* video may be sent to Qwen.
- **No restricting.** Willow explains, never forbids: every interception has **继续看原视频** (keep watching). Willow never hides or removes videos. The feed changes only through his own taps.
- Questionable-but-not-selling videos get only a small tip while they play. Verified doctors/hospitals are never touched.
- Tone: respectful, "let's check together", never "you were fooled". Big text, slow voice.

## Stack & decisions
- Target: **Android** (Kotlin) AccessibilityService on top of WeChat (`com.tencent.mm`; Channels is the internal "Finder" plugin, so verify class names on the device).
- AI tier: **Qwen** via DashScope / 百炼 (OpenAI-compatible). `qwen-plus` for text, `qwen-vl-plus` for screenshots later. Prompt: `SYSTEM_PROMPT` in `prototype-web/server.mjs`.
- `shared/` holds the data the Android app reuses as-is: `rules/*.json`, `debunks/debunks.json`. `shared/classifier.js` is the reference logic to port to Kotlin.

## Layout
```
shared/            rules, debunk cards, sample feed, classifier.js (reference logic)
prototype-web/     browser prototype (Phase 1, done) - the behaviour reference for Android
android/           Phase 2 (not started)
```

## Commands
- Run prototype: `cd prototype-web && npm start` → http://localhost:5173 (must be opened through the server, not as a file)
- Qwen: copy `prototype-web/.env.example` to `.env` and set `DASHSCOPE_API_KEY`

## Behaviour spec (the prototype is the reference; Android must match)
1. **Mute first, decide second.** A new video is muted before anything else, then classified.
2. **Rules** (`shared/rules/health_scam_rules.json`): score ≥ 0.65 → intercept, ≥ 0.25 → tip, else allow. Verified medical accounts always allowed.
3. **Qwen** is called only when unsure: tip-level videos, or intercepts with no curated card. Its label overrides the rules. If it fails, fall back to the rules.
4. **Explanation card** (4 steps: 等一下 → 视频里说 → 其实 → 怎么办):
   - Autoplay advances only after the voice finishes + **2.5 s** reading pause; with voice off, **max(5 s, 300 ms × characters)**. Never cut a sentence off.
   - Manual control: **‹ 上一步 / 下一步 ›** buttons, tapping the progress dots, swiping left/right, ← → keys.
   - **Any manual navigation stops autoplay**; a ⏸ 暂停 / ▶ 自动 button toggles it. 🔊 replays the current step.
   - **继续看原视频 ›** is visible on every step. The last step shows: 换一个视频 (sends "not interested") / hobby invite / 继续看原视频.
   - Swiping up/down still changes video at any time.
5. **Tip:** a yellow banner; the video keeps playing; dismiss with 知道了.
6. **Purchase pause:** on a flagged video's product page, show a gentle note. Both 再想想 and 立即购买 work. No alerts.
7. Explanation source priority: curated card (needs ≥ 2 keyword hits) → Qwen-written (labelled "AI 解释") → generic "scare then sell" card.

## Prototype debug log
Record every behaviour change or bug found here, so the Android version gets it right first time.

| Date | Change / finding | Carry to Android? |
|---|---|---|
| 2026-10-10 | 下一个 did nothing before the start screen was tapped (silent no-op). Any navigation now starts the feed. Page shows a clear message if opened as a file. | Lesson only: no silent no-ops |
| 2026-10-10 | Explanation card autoplayed too fast (0.5 s after voice) and had no manual control. New timing and controls: see spec #4. | **Yes**: spec #4 |
| 2026-10-10 | Recommender sim tuned so results are believable (scam share 36% → ~12% with Willow, → ~75% without). | No (prototype-only model) |
| 2026-10-10 | **Open bug:** generic words like 千万别 (fear) or 限时 / 特价 (sales) can flag non-health videos, e.g. a fishing-gear sale or "钓鱼千万别用这种饵". Planned fix: require a health-topic word (血压, 血糖, 肾, 癌, 关节, 药, 保健…) before tip/intercept. | **Yes**, once fixed |

## Working notes
- Debunk cards must be reviewed by a doctor/pharmacist before real use.
- No Java/Android SDK on this PC yet. The user installs Android Studio and has an Android test phone. The first Android task: a probe that logs which text WeChat Channels exposes to accessibility.
