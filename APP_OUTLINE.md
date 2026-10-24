# Willow (柳荫) — App Outline

> Working name from the essay: the willow tree my great-grandparents sat under. The app's job is to give Grandpa that same carefree shade back from his phone.

**Decisions so far:** Android · WeChat Channels (视频号) · DeepSeek (`deepseek-flash`) for the AI tier, Qwen as a switchable alternative (decided 2026-10-24: the user already has a DeepSeek account) · no monitoring, no restricting what he watches.

**Status:** Phase 1 browser prototype is built → [prototype-web/README.md](prototype-web/README.md). Android **v1.0** (screenshot + on-phone OCR, rules + Qwen, purchase pause, hobby search) is built in [android/app](android/app); next is testing it on the phone.

## 1. Problem (from the application draft)

- After his fall on New Year's Eve, Grandpa became anxious about his health ("will I even reach eighty?").
- Short-video algorithms noticed and fed him **fear-based health videos**: synthetic AI voices warning of organ failure, then selling expensive herbal "miracle cures".
- He struggles to read small characters, so he trusts the dramatic animations and voices, and orders overpriced products.

## 2. What the app does

| # | Feature | From the draft | Status |
|---|---------|----------------|--------|
| F1 | **Detect** predatory health videos as soon as they appear | "whenever a predatory video loaded" | Prototype ✓ |
| F2 | **Intercept** before the audio plays: mute instantly, then decide | "intercepted the stream before the audio played" | Prototype ✓ |
| F3 | **Explain**: a calm 4-step animation, big text, read aloud | "custom animation that visually debunked the pseudoscience" | Prototype ✓ |
| F4 | **Hobbies**: invite him to fishing, crops, Chinese chess | "let grandpa see more video related to his hobbies" | Prototype ✓ |
| F5 | **Purchase pause**: a gentle note on flagged product pages, both buttons still work | implied by "tapped buttons to order" | Prototype ✓ |
| F6 | **Positive replacement**: real tips (balance exercises, fall prevention) | ties back to the fall | Later |

### Design principles
1. **No monitoring.** No watch history, no reports to the family, no access to his account, messages or contacts. Only the on-screen text of an *unclear* video is sent to Qwen, with nothing identifying him.
2. **Explain, never forbid.** Every interception has **继续看原视频** (keep watching). Questionable videos that aren't selling anything only get a small tip while they play. Real doctors (verified accounts) are never touched.
3. **Willow never hides videos.** The feed changes only through what *he* does: tapping 换一个 (next) tells the platform "not interested", and the hobby button invites him to something he enjoys.
4. **Zero setup for Grandpa.** The family installs it; he keeps using WeChat as usual. A small "🌳 Willow 保护中" badge tells him it's there.
5. **Respect, not scolding.** He snaps when treated as fragile. The tone is "let's check this together", never "you were fooled".
6. **Readable without reading.** Huge text, icons, slow Mandarin voice. Stretch: my own recorded voice.
7. **Mute first, decide second.** A scary AI voice playing for two seconds already does damage.

## 3. How it works on the phone (Android)

WeChat doesn't let other apps tap into its video stream, so Willow sits **on top of** it using Android's Accessibility framework (the same system screen readers use).

```
 WeChat Channels on screen
          │  accessibility event: new video swiped in
          ▼
 1. Observer (AccessibilityService)
    - reads author, caption, #hashtags, product card
    - screenshot + OCR for big text burned into the video
    - MUTES media volume immediately
          ▼
 2. Classifier
    a. rules (instant, on phone)          ── shared/rules/*.json
    b. Qwen, only when the rules are unsure
          ▼
   ┌──────────────┬────────────────┬──────────────────┐
   intercept       tip              allow
   explanation     small banner,    unmute, nothing
   overlay + voice video plays      else happens
   [换一个] [钓鱼] [继续看]
```

### 3.1 Observer — WeChat Channels specifics
- WeChat package: `com.tencent.mm`. Channels is WeChat's internal "Finder" plugin, so its screens should have class names containing `finder`. **Check this on the test phone** with the Accessibility inspector / `uiautomator dump`.
- WeChat scrambles its view IDs and may expose little text to accessibility. Plan for both:
  - **Text path:** read whatever text nodes are available (caption, author, product).
  - **Screenshot path:** `AccessibilityService.takeScreenshot()` (Android 11+, no extra permission prompt) → OCR, or send the image to **Qwen-VL** to read it.
- Mute with `AudioManager` the moment a new video is detected; unmute if allowed.

### 3.2 Classifier
**Tier A: rules** ([shared/rules/health_scam_rules.json](shared/rules/health_scam_rules.json)). Weighted signal groups:

| Signal group | Example phrases | Weight |
|---|---|---|
| Fear | 猝死, 器官衰竭, 血管堵, 就晚了, 千万别 | 0.30 |
| Miracle cure | 根治, 祖传, 秘方, 不用吃药, 停药也能 | 0.30 |
| Sales hook | 小黄车, 点击下方, 限时, 仅剩, 下单 | 0.30 |
| Fake authority | 老中医, 退休专家, 医院不会告诉你 | 0.15 |
| Targets the elderly | #中老年, #养生, 很多老人不知道 | 0.10 |
| Product card on screen | — | 0.15 |
| Combo: scare/cure **and** selling | — | +0.20 |

Score ≥ 0.65 → intercept · ≥ 0.25 → tip · otherwise allow. Verified medical accounts are always allowed.

**Tier B: Qwen** (via Alibaba Cloud Model Studio / 百炼, OpenAI-compatible API). Called only for tips and for intercepts with no curated explanation. Returns `{label, confidence, claim, truth, action, reason}` in plain Chinese.
- **Why Qwen:** best-in-class Chinese, available in mainland China, cheap, and you already know it.
- `qwen-plus` for text (default), `qwen-flash`/`qwen-turbo` if speed matters more, **`qwen-vl-plus`** for reading screenshots in Phase 3. Model names change, so check the 百炼 console.
- Later option: a small Qwen model running on the phone itself, so nothing leaves the device at all.

### 3.3 The explanation screen
4 beats, each read aloud slowly:
1. **等一下**: "我们先看看这个说法对不对"
2. **视频里说**: the claim, in big text
3. **其实**: the truth in one or two sentences + source
4. **怎么办**: what to do instead, then three big buttons: **换一个视频** / **🎣 看看钓鱼** / **继续看原视频**

Explanations come from 8 curated cards ([shared/debunks/debunks.json](shared/debunks/debunks.json)), from Qwen (labelled as AI-written), or from a generic "scare then sell" card. **Have a doctor or pharmacist review the curated cards before real use.**

### 3.4 Hobbies
- Hobby videos are never interrupted.
- After an explanation, one button invites him to his favourite hobby (opens a Channels search for 钓鱼 / 种菜 / 象棋 in the real app).
- The family can pre-follow a few good fishing / gardening / chess creators *with him*.
- No auto-likes or auto-follows in his name.

### 3.5 Purchase pause
On a flagged video's product page: "保健食品不是药物，不能代替药物治疗疾病。买之前可以再想想。" Both 再想想 and 立即购买 work. No alerts to anyone.

## 4. Data model

```text
VideoObservation { author{name, verified}, caption, hashtags[], onScreenText, product? }
Decision         { level: intercept | tip | allow, label, score, matched[], tier: rules | qwen, card? }
DebunkCard       { id, icon, match[], myth, truth, action, source, (en versions) }
Hobby            { id, label, icon, invite, phrases[] }
```
No event log and no history. Nothing is stored about what he watched.

## 5. Project structure

```text
video interception/
├── APP_OUTLINE.md
├── shared/                 # reused by the prototype and the Android app
│   ├── classifier.js       # rule engine (to be ported to Kotlin)
│   ├── rules/              # health_scam_rules.json, hobby_rules.json
│   ├── debunks/            # debunks.json
│   └── samples/            # feed.json: 25 labelled mock videos
├── prototype-web/          # Phase 1 ✓ (npm start)
└── android/                # Phase 2 (next)
```

## 6. Roadmap

| Phase | Goal | Deliverable | Needs |
|---|---|---|---|
| **1. Web prototype** ✓ | Prove the idea, demo-able | Simulated Channels feed, rules + Qwen, spoken explanations, feed chart | Done |
| **0. Research** (alongside) | Learn the real enemy | ~100 screenshots of real scare videos from Channels; top myths; hobby creators | Grandpa's OK |
| **2. Android MVP** ✓ | Real interception in WeChat Channels | AccessibilityService observer, mute, overlay, buttons (v0) | Android Studio, test phone |
| **3. Full coverage** (v1.0 built) | Harder cases | Screenshot + on-phone OCR ✓, Qwen text tier on the phone ✓, purchase pause ✓, hobby search ✓; Qwen-VL later | 百炼 API key |
| **4. Trial with Grandpa** | Real-world evidence | Install with his agreement, gather feedback, iterate | Family |

### Phase 2 prep for tonight
1. Install **Android Studio** (it bundles Java and the Android SDK).
2. On the test phone: Settings → About phone → tap *Build number* 7 times → enable **USB debugging**.
3. Install WeChat on it and open Channels once.
4. Note the phone's brand and Android version (Android 11+ is needed for `takeScreenshot`).

### How to measure success (without monitoring him)
- **Offline accuracy:** run the classifier on the labelled sample set and real screenshots: % of scam videos caught, % of real advice wrongly flagged.
- **On-phone counter he can see himself:** "Willow explained 12 videos this week". Stored only on his phone, never sent anywhere.
- **Ask him:** Does he feel calmer? Does he talk about fishing more than organ failure at dinner?

## 7. Risks & mitigations

| Risk | Mitigation |
|---|---|
| WeChat hides text from accessibility | Screenshot + OCR / Qwen-VL path |
| WeChat updates break detection | Selectors in config files; screenshot path as fallback |
| Phone kills the background service (Xiaomi/Huawei/OPPO battery savers) | Setup checklist: battery whitelist, auto-start, lock in recents |
| Blocking real medical advice | Verified-doctor allow-list; intercept only with selling/scare signals; Qwen second opinion; he can always keep watching |
| He feels watched or patronised | Tell him openly; no history; friendly tone; keep-watching button |
| API key on the phone could leak | Small relay server holds the key, or an on-device model later |
| Debunk content is wrong | Curated cards reviewed by a doctor; AI-written ones clearly labelled |
