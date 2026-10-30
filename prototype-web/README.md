# Willow — browser prototype

A phone-shaped simulation of WeChat Channels (视频号) with Willow running on top:

- **Mute first, decide second.** Each new video is muted while Willow reads what's on screen.
- **Intercept + explain.** Predatory health videos (fear claims + miracle cures + selling) are covered by a calm, spoken 4-step explanation: pause → what the video claims → the truth → what to do.
- **Gentle tips.** Questionable-but-not-selling videos keep playing with a small tip.
- **Grandpa stays in charge.** Every interception has "继续看原视频" (keep watching). Willow never hides videos and keeps no history.
- **Feed steering via his own taps.** Tapping 换一个 sends "not interested"; the hobby button (钓鱼 / 种菜 / 下棋) invites him to something he enjoys.
- **AI for unclear cases.** Rules decide instantly; only uncertain videos are sent to the AI (Qwen; text only).

## Run it

Needs Node.js 22.9+ (no `npm install` needed: zero dependencies).

```bash
cd prototype-web
npm start
```

Open http://localhost:5173 and tap **点一下开始**.

## Turn on the AI (optional)

1. Get an API key from Alibaba Cloud Model Studio (百炼): https://bailian.console.aliyun.com/
2. Copy `.env.example` to `.env` and set `DASHSCOPE_API_KEY=...`
3. Restart `npm start`. The panel shows "Connected · Qwen, model qwen-plus".

Videos the rules aren't sure about then go to the AI. Try `m2` (grapefruit + blood-pressure pills): the rules alone show a tip, but the AI recognises it as correct advice and lets it play. `s8` (bee-propolis immunity) has no curated card, so the AI writes the explanation.

## Demo script (for a recorded walkthrough)

1. Tap start. Swipe through a few videos. Hobby and real-doctor videos play normally.
2. On a scare video, point out the 🔇 "Willow 检查中…" pill, then the 4-step explanation. Show that he controls the pace: ‹ 上一步 / 下一步 ›, the dots, swiping sideways, ⏸ pause and 🔊 replay.
3. Tap **继续看原视频** once to show he's never blocked. Tap the 🛒 product: a gentle note, both buttons still work.
4. On the next scare video, tap **换一个视频** and watch the "How the feed changes" chart move.
5. Click **Simulate a week** with Willow on. Then **Reset feed**, switch Willow off, and simulate again. With Willow, scare videos fall from about 36% to about 12% of the feed; without it, they grow to about 75%.
6. Turn on **English subtitles** for viewers who don't read Chinese.

## What's real and what's simulated

| Part | In this prototype | In the Android app |
|---|---|---|
| Videos | CSS scenes + text-to-speech | Real WeChat Channels videos |
| What Willow reads | Author, caption, hashtags, product, on-screen text | Same, via AccessibilityService (+ screenshot OCR for burned-in text) |
| Rules, debunk cards, hobbies | `../shared/` | Same JSON files |
| AI tier | `server.mjs` → Qwen (DashScope) | Phone → Qwen (DashScope), `Ai.kt` |
| Recommender | Toy model in `recommender.js` | The platform's real algorithm; Willow only sends "not interested" when he taps 换一个 |

## Files

```
prototype-web/
  server.mjs           static server + /api/classify (Qwen)
  public/index.html    page layout
  public/app.js        feed flow, decisions, debunk screen, panel
  public/scenes.js     mock video rendering
  public/recommender.js toy platform recommender
  public/chart.js      feed-share chart
  public/voice.js      text-to-speech
../shared/
  classifier.js        rule engine (ported to Kotlin later)
  rules/               scam signals + hobbies
  debunks/             curated explanations (have a doctor review them)
  samples/feed.json    25 mock videos
```
