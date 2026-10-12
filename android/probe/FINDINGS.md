# Probe findings: WeChat Channels on a real phone

**Test phone:** Honor Magic5 Pro (PGT-AN10), MagicOS 10, Android 16 (API 36), 1312×2848 @ 560 dpi
**WeChat:** 8.0.69 (versionCode 3040)
**Date:** 2026-10-12

## ⚠️ Update, same day 12:04: WeChat stopped exposing its screen
For the first ~6 minutes after login everything below was readable. From 12:04 onward (same WeChat process,
no restart) **every WeChat screen returns an empty tree** to both the probe and Willow: the window root has
0 children, and nodes found by id report `isVisibleToUser=false`. This matches reports that WeChat 8.0.52+
deliberately hides or scrambles its content from third-party accessibility services. Workarounds that
impersonate a system service are **not** an option for Willow (deceptive, fragile, fights WeChat's anti-bot
measure). Still working at 12:25: window/scroll **events** (we know when he swipes), **`takeScreenshot()`**
(full picture, including the caption, author, 合集 and AI label, *and* the big words burned into the video)
and **mute**. So the reading path moves to screenshot + on-device OCR.

## Answers to the three questions (first 6 minutes)

| Question | Answer |
|---|---|
| Can an accessibility service read the video's text? | **Yes, a lot.** Author, caption + #hashtags, top comment (热评), danmaku, WeChat's AI labels, 作者提示, 合集, promotion cards with their button. **No:** words burned into the video picture (needs OCR). |
| Does `takeScreenshot()` work on Channels? | **Yes.** Real picture, not black (brightness 64/255). 933 ms for a full-resolution PNG. |
| Can we mute instantly? | **Yes.** `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_MUTE)` while the video plays: muted, then volume restored on unmute. 70–271 ms. |

## Screens (activity class names)
| Activity | What it is |
|---|---|
| `com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI` | Channels home feed (tabs 关注 / 同城 / 朋友 / 推荐) |
| `com.tencent.mm.plugin.finder.ui.FinderShareFeedRelUI` | Feed opened from a shared video / related feed. Same card layout |
| `com.tencent.mm.plugin.finder.ui.FinderSelfUI` | Channels "我" page (the user's own profile; **Willow must not read this**) |
| `com.tencent.mm.ui.LauncherUI` | WeChat main tabs (not Channels) |

## One video card (view ids are obfuscated, valid for WeChat 8.0.69 only)
| id | Content | Example |
|---|---|---|
| `fs3` | Video player container, `desc="暂停"`, holds a `TextureView`. **Starts each card** | |
| `a8p` | Author name | 本珍养生记 |
| `o45` | Caption + hashtags, **cut off with "…" after ~2 lines** | 世上最好的养生不是名贵补品…#中老年养生 #健康养生 #不… |
| `ehb` (+ `ehh`="热评") | Top comment. WeChat **alternates** it with the caption in the same place | 三伏天养生：初伏7月15日… |
| `lob` | AI label, either WeChat's own or the author's | 可能含有AI生成内容 / 作者提示: 含AI生成内容 |
| `ghj` | Author note | 作者提示: 个人观点，仅供参考 |
| `egg` / `egf` | Collection (合集) and episode | 合集: 黄帝内经讲养生 / 第1集 |
| `edj` / `edl` / `eew` | Link card: title / subtitle / button. Used for **location** *and* **promotions** | 舒缓肩颈，强身健体 / 每天20分钟，腰椎活络舒畅 / 领取 |
| `b25` | Danmaku (floating comments) | 感恩分享，谢谢 |
| `ggt` | Social proof | 6个朋友关注 |
| `ng3` `msm` `i1g` `c6s` | Like / share / favourite / comment counts. Promoted cards show words (喜欢 / 推荐 / 评论) instead of numbers | 8.3万 |
| `iqp` | Playback time on longer videos | 01:24 |

## Behaviour that matters for Willow
1. **Neighbouring videos are preloaded** with their text: the next card sits just below the screen (`top ≥ 2848`), the previous one just above (`bottom ≤ 0`). Willow can **classify the next video before it appears**, so the decision is ready the moment it scrolls in.
2. **But offscreen cards can be stale.** A preloaded card still showed the previous video's 热评 (RecyclerView reuses views). Re-read the card once it is on screen before acting.
3. **Scroll events carry no position info** (`from=-1 to=-1 items=-1 dy=0`). Detect "new video" by checking whether the on-screen card's author + caption changed after the swipe settles.
4. The on-screen card is the `fs3` whose bounds start at `top = 0`.
5. A full tree walk takes 40–850 ms (66–255 nodes on Channels). Willow should look up the known ids directly (`findAccessibilityNodeInfosByViewId("com.tencent.mm:id/a8p")`), with a fallback when a WeChat update renames them.
6. **IDs will change with WeChat updates.** Keep them in a versioned table, plus a structural fallback (e.g. "the text node at the bottom-left above the author row").
7. Big text burned into the picture (三伏天怎么吃养生 / 多吃鸭肉) is invisible to accessibility, so it needs screenshot → OCR / Qwen-VL.

## Signals worth adding to the rules
- `lob` AI label (含AI生成内容): strong hint for the "AI voice + scare" pattern. Not proof on its own.
- Promotion card button (`eew`: 领取 / 购买 / 咨询…) = sales hook, even when the caption is innocent.
- 合集 names like 黄帝内经讲养生, and author names ending in 养生记 / 养身课.
- Hashtags seen: #养生 #食养 #中老年养生 #健康养生 #健康科普 #饮食知识 #涨知识.

## Setup lessons (Windows + Honor)
- Honor exposes the ADB interface through Windows' generic WinUSB driver, which adb's default backend ignores. Run adb with `ADB_LIBUSB=1` (done in `probe.sh`). libusb mode doesn't notice newly plugged devices on Windows: restart the adb server after replugging.
- 「允许通过HDB连接设备」 is Honor's own phone-assistant protocol and isn't needed.
- Git Bash rewrites `/sdcard/...` paths, so set `MSYS_NO_PATHCONV=1` (done in `probe.sh`).
- MagicOS asks for confirmation on every `adb install`; an unanswered prompt fails with `INSTALL_FAILED_ABORTED`.
