# Willow (柳荫)

An app that protects the user's grandpa from fear-based health scam videos on **WeChat Channels (视频号)**. It mutes a new video, checks it, and if it's a scam, covers it with a calm spoken explanation. It also invites him to his hobbies (fishing, backyard crops, Chinese chess). It's part of the user's university application; the essay draft is `文书大概.txt`. Full plan: `APP_OUTLINE.md`.

## Non-negotiable product rules (from the user)
- **No monitoring.** No watch history, no logs, no reports or alerts to family, no access to his account/messages/contacts. Only on-screen text of an *unclear* video may be sent to the AI (DeepSeek by default).
- **No restricting.** Willow explains, never forbids: every interception has **继续看原视频** (keep watching). Willow never hides or removes videos. The feed changes only through his own taps.
- Questionable-but-not-selling videos get only a small tip while they play. Verified doctors/hospitals are never touched.
- Tone: respectful, "let's check together", never "you were fooled". Big text, slow voice.

## Stack & decisions
- Target: **Android** (Kotlin) AccessibilityService on top of WeChat (`com.tencent.mm`; Channels is the internal "Finder" plugin, so verify class names on the device).
- **Reading the screen (v1.0):** WeChat hides its view tree from accessibility services, so Willow takes a screenshot (`takeScreenshotOfWindow` on Android 14+, so notifications are never in it), reads it with **on-phone Chinese OCR** (Google ML Kit, bundled model, works without Google Play services) and sorts the lines into author / caption / labels / burned-in text with `FeedParser.kt` (anchored on the bottom bar's counts row). Screenshots are dropped right after OCR. Only Channels *feed* screens are ever photographed (allowlist in `WillowService`).
- AI tier (v2.0): **DeepSeek** `deepseek-flash` (V4.1 Flash, the cheap model) by default, or **Qwen** via 百炼 (`AI_PROVIDER=qwen`). Both are OpenAI-compatible and called from the phone (`Ai.kt`) and the prototype (`server.mjs`). Thinking mode must be off (DeepSeek: `"thinking": {"type": "disabled"}`; Qwen: `enable_thinking: false`), or he waits seconds in front of a muted video. Prompt: `shared/prompts/ai_system.txt`, used by both. The key is baked into the APK at build time from `prototype-web/.env`; `aiOn = false` in `android/app/build.gradle.kts` builds v1.0 (rules + cards only, no key in the APK).
- `shared/` holds the data the Android app reuses as-is: `rules/*.json`, `debunks/debunks.json`, `prompts/`. `shared/classifier.js` is the reference logic to port to Kotlin.

## Layout
```
shared/            rules, debunk cards, sample feed, classifier.js (reference logic)
prototype-web/     browser prototype (Phase 1, done) - the behaviour reference for Android
android/app/       Willow v1.0: AccessibilityService on WeChat Channels, screenshot + OCR reader, rules + AI (Gradle)
android/probe/     test-phone tool that logs what Channels exposes; results in probe/FINDINGS.md (build-offline.sh)
```

## Commands
- Run prototype: `cd prototype-web && npm start` → http://localhost:5173 (must be opened through the server, not as a file)
- AI key: copy `prototype-web/.env.example` to `.env` and set `DEEPSEEK_API_KEY` (or `DASHSCOPE_API_KEY` for Qwen). The same file feeds the Android build (rebuild after changing it).
- Build + install Willow (Gradle, Git Bash): `android/willow.sh install` (debug, test phone) or `android/willow.sh release` (Grandpa: no logs, no test hooks). It packs `shared/` rules, cards and prompt as assets. Parser tests on the PC: `android/willow.sh unittest`.
- Build the probe (no Gradle): `android/build-offline.sh probe --install`.
- The Honor test phone needs `ADB_LIBUSB=1` for every adb command (and `MSYS_NO_PATHCONV=1` in Git Bash for `/sdcard/...` paths); `willow.sh` sets both.
- Willow dev hooks (debug build only, `DEV = BuildConfig.DEBUG`): `willow.sh log`; `willow.sh ocr` (what the screen reads as); `willow.sh ai "降压药千万别吃了"` (check the key); `willow.sh test intercept bp_stop` (force a decision); `willow.sh selftest` (Kotlin vs JS rules).

## Behaviour spec (the prototype is the reference; Android must match)
1. **Mute first, decide second.** A new video is muted before anything else, then classified. On the phone, the screen is read when the swipe settles and again at about 2, 5 and 9 s (subtitles carry the scariest words); later reads can only make the decision stricter.
2. **Rules** (`shared/rules/health_scam_rules.json`): score ≥ 0.65 → intercept, ≥ 0.25 → tip, else allow. Verified medical accounts always allowed.
3. **The AI** is called only when unsure: tip-level videos, or intercepts with no curated card. Its label overrides the rules. If it fails, fall back to the rules.
4. **Explanation card** (4 steps: 等一下 → 视频里说 → 其实 → 怎么办):
   - Autoplay advances only after the voice finishes + **2.5 s** reading pause; with voice off, **max(5 s, 300 ms × characters)**. Never cut a sentence off.
   - Manual control: **‹ 上一步 / 下一步 ›** buttons, tapping the progress dots, swiping left/right, ← → keys.
   - **Any manual navigation stops autoplay**; a ⏸ 暂停 / ▶ 自动 button toggles it. 🔊 replays the current step.
   - **继续看原视频 ›** is visible on every step. The last step shows: 换一个视频 (sends "not interested") / hobby invite / 继续看原视频.
   - Swiping up/down still changes video at any time.
5. **Tip:** a yellow banner; the video keeps playing; dismiss with 知道了.
6. **Purchase pause:** on a flagged video's product page, show a gentle note. Both 再想想 and 立即购买 work. No alerts.
7. Explanation source priority: curated card (needs ≥ 2 keyword hits) → AI-written (labelled "AI 解释") → generic "scare then sell" card.

## Prototype debug log
Record every behaviour change or bug found here, so the Android version gets it right first time.

| Date | Change / finding | Carry to Android? |
|---|---|---|
| 2026-10-10 | 下一个 did nothing before the start screen was tapped (silent no-op). Any navigation now starts the feed. Page shows a clear message if opened as a file. | Lesson only: no silent no-ops |
| 2026-10-10 | Explanation card autoplayed too fast (0.5 s after voice) and had no manual control. New timing and controls: see spec #4. | **Yes**: spec #4 |
| 2026-10-10 | Recommender sim tuned so results are believable (scam share 36% → ~12% with Willow, → ~75% without). | No (prototype-only model) |
| 2026-10-10 | Generic words like 千万别 (fear) or 限时 / 特价 (sales) flagged non-health videos, e.g. a fishing-gear sale or "钓鱼千万别用这种饵". | See 2026-10-12 |
| 2026-10-12 | **Fixed:** health-topic gate (rules v2, `topic` in `health_scam_rules.json`). No health word → `allow` with label `off_topic`, whatever the score. The fishing-gear sale went intercept 0.80 → allow; all 25 samples kept their level. | **Yes**: done in `Rules.kt` |
| 2026-10-12 | Real phone: WeChat swaps the caption for the top comment (热评) after a few seconds, and preloaded offscreen cards can show the previous video's text. | **Yes**: same-video check tolerates a missing caption; only on-screen nodes are read |
| 2026-10-22 | v1.0 reads screenshots with OCR. Same-video check now compares the like/share/favourite/comment numbers first (stable for one video, different between videos), then author, then caption, all fuzzy. 热评 lines are left out of the text. | **Yes**: done in `FeedParser.kt` / `Rules.kt` |
| 2026-10-22 | Qwen prompt moved to `shared/prompts/qwen_system.txt` (prototype reads it too) and now says the text may come from OCR with typos, and lists 平台标签 (AI label / 合集). | **Yes**: done |
| 2026-10-24 | Phone, WeChat 8.0.79: OCR reads neighbouring counts as one line (`1.7万 1.8万 9773`), so every screen was "not a video". Also: labels misread (`作者堤示`, `含A生成内容`, `D合集`), `8条回复` under 热评, play time glued to 🎧/弹 (`11:37 6) 弹`). | **Fixed** in `FeedParser.kt` (+2 tests from the real screen) |
| 2026-10-24 | Phone: 抢购 book ad (sales + product card, no fear) → tip 0.45, banner shown. Tapping 抢购 opened the mini-program shop but no purchase pause appeared (shop loads slower than the 1.2 s + 2.5 s checks?). | **Open** |
| 2026-10-24 | Phone: ads show words instead of most counts (`1 1`), so two ads could pass the same-video check; the author can be read from text inside the avatar (`文兴` for 东方文兴图书). | **Open** |
| 2026-10-24 | Real 养生 feed: nothing reached intercept by rules alone (e.g. 「院长亲荐 白毛乌鸡甜酒汤，效果很好」 = 0). AI tier switched from Qwen to DeepSeek `deepseek-flash`; prompt renamed `ai_system.txt`. | **Yes**: done (`Ai.kt`, `server.mjs`) |

## Working notes
- Debunk cards must be reviewed by a doctor/pharmacist before real use.
- Android Studio is at `E:\Android_Studio` (bundled JDK 25 + kotlinc 2.3). The app builds with Gradle 9.8.1 + AGP 9.4.1 (built-in Kotlin) + ML Kit `text-recognition-chinese`; the wrapper downloads from the Tencent mirror and libraries come from Aliyun mirrors first (`android/settings.gradle.kts`). Internet speed varies (80 KB/s to 600 KB/s); everything is cached in `~/.gradle` now.
- Test phone: Honor Magic5 Pro, MagicOS 10 / Android 16, WeChat 8.0.79 (was 8.0.69 until 2026-10; layout unchanged, OCR grouping differs). MagicOS asks on the phone to confirm every `adb install`.
- WeChat view ids (`a8p`, `o45`, …) are obfuscated and change with WeChat updates. v1.0 only uses one (the search button, `r0l` in `WeChatActions.kt`); reading is by OCR. If WeChat changes its layout, check `willow.sh ocr` output and adjust `FeedParser.kt` + its test. Details in `android/probe/FINDINGS.md`.
