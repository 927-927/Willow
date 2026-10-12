# Willow Probe (test phone only)

A throwaway developer tool. It answers three questions about WeChat Channels (视频号) before the real Willow app is built:

| Question | How the probe checks it |
|---|---|
| What text can we read (author, caption, #hashtags, product card)? | Dumps WeChat's accessibility tree to logcat after each swipe |
| Does `takeScreenshot()` give a real picture of the video? | `probe.sh shot` saves a PNG and logs its brightness (≈0 means a black frame) |
| Can we mute the video instantly? | `probe.sh mute` / `unmute` and log the result |

On-screen text is logged only on Channels screens (activity names containing `finder`). Other WeChat screens, such as chats, log class names only. **Never install this on Grandpa's phone.**

## Build and install (no Gradle needed)
```bash
android/probe/probe.sh install
```
This uses Android Studio's bundled JDK and Kotlin compiler plus the SDK build tools (see `android/build-offline.sh`). Then, on the phone: Settings → Accessibility → **Willow Probe (测试用)** → on.

## Test session
```bash
android/probe/probe.sh log
```
In a second terminal, while 视频号 is open on the phone:
```bash
android/probe/probe.sh dump
```

## What to record afterwards
- The Channels activity class name(s), e.g. `com.tencent.mm.plugin.finder.ui.…`
- Which node holds the author / caption / hashtags / product card, and whether its `id=` is stable
- Which event signals "a new video swiped in" (scroll event `from/to` indexes, a screen change, …) and how long after the swipe it arrives
- Whether the screenshot is black, and whether mute works while the video plays
