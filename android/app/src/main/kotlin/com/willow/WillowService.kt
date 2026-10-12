package com.willow

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Willow's observer, sitting on top of WeChat Channels (behaviour spec in CLAUDE.md):
 *  1. Mute first, decide second: the moment a swipe starts (or Channels opens), mute the media stream.
 *  2. When the feed settles, read the on-screen card and classify it with the shared rules.
 *  3. Intercept → explanation card (stays muted). Tip → yellow banner + sound. Allow → sound back.
 *
 * Nothing is stored and nothing leaves the phone. Willow never hides a video: the feed only
 * changes when he swipes or taps a Willow button himself.
 */
class WillowService : AccessibilityService(), ExplainOverlay.Listener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var rules: Rules
    private lateinit var voice: Voice
    private lateinit var overlay: ExplainOverlay
    private lateinit var tip: TipBanner
    private lateinit var actions: WeChatActions
    private lateinit var audio: AudioManager

    private var inChannels = false
    private var current: Observation? = null
    private var decision: Decision? = null
    private var keptWatching: Observation? = null // he chose 继续看原视频 for this video
    private var demoShowing = false // the sample card from the app's 看看效果 button
    private var mutedByWillow = false
    private var settling = false
    private var swipeStartedAt = 0L
    private var captionRetries = 0
    private val activityCache = HashMap<String, Boolean>()

    private val settle = Runnable { settling = false; check() }
    private val recheck = Runnable { check() }

    // ---- Lifecycle ----

    override fun onServiceConnected() {
        instance = this
        rules = Rules(this)
        voice = Voice(this)
        overlay = ExplainOverlay(this, voice, this)
        tip = TipBanner(this)
        actions = WeChatActions(this, ::log)
        audio = getSystemService(AudioManager::class.java)
        if (DEV) {
            val filter = IntentFilter(ACTION_TEST).apply { addAction(ACTION_SELFTEST) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(devCommands, filter, Context.RECEIVER_EXPORTED)
            else registerReceiver(devCommands, filter)
        }
        log("connected · rules ok · ${rules.hobbies.size} hobbies")
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) {
            overlay.hide()
            tip.hide()
            unmute()
            voice.shutdown()
        }
        if (DEV) runCatching { unregisterReceiver(devCommands) }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---- Events ----

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = e.className?.toString() ?: return
                if (!isActivity(cls)) return // popups and menus don't change the screen
                log("screen ${cls.substringAfterLast('.')}")
                if (cls.contains("finder", ignoreCase = true)) {
                    // Entered Channels, or moved to another Channels screen: a new first video.
                    inChannels = true
                    current = null
                    startCheck(ENTER_SETTLE_MS)
                } else if (inChannels) {
                    leaveChannels()
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (inChannels && !overlay.showing && isFeedScroll(e)) startCheck(SETTLE_MS)
            }
        }
    }

    /** Mute first, decide second. */
    private fun startCheck(delay: Long) {
        if (!settling) {
            settling = true
            swipeStartedAt = SystemClock.uptimeMillis()
            mute()
        }
        main.removeCallbacks(settle)
        main.removeCallbacks(recheck)
        main.postDelayed(settle, delay)
    }

    private fun check() {
        val obs = readCard()
        if (obs == null) { // no video on screen (profile page, menu…)
            if (DEV) log("no video card · " + wechatRoots().joinToString { CardReader.describe(it, screenH()) }.ifEmpty { "no WeChat window" })
            if (!overlay.showing) unmute()
            return
        }
        val cur = current
        if (obs.sameVideoAs(cur)) {
            if (cur != null && cur.caption.isEmpty() && obs.caption.isNotEmpty()) {
                current = obs // the caption arrived late: decide again with more text
                decideAndAct(obs, late = true)
            } else if (!overlay.showing) {
                unmute() // a bounce, not a new video
            }
            return
        }
        current = obs
        captionRetries = 0
        keptWatching = null
        overlay.hide()
        tip.hide()
        decideAndAct(obs, late = false)
    }

    private fun decideAndAct(obs: Observation, late: Boolean) {
        val before = decision?.level
        val d = rules.decide(obs)
        decision = d
        log("${d.level} ${"%.2f".format(d.score)} ${d.label} card=${d.card?.id ?: "-"} " +
            d.matched.joinToString(" ") { m -> m.labelZh + (if (m.phrases.isEmpty()) "" else ":" + m.phrases.take(3).joinToString("、")) } +
            " · ${SystemClock.uptimeMillis() - swipeStartedAt}ms" + if (late) " (late caption)" else "")

        if (!(late && d.level == before)) {
            when (d.level) {
                Level.INTERCEPT ->
                    if (obs.sameVideoAs(keptWatching)) unmute()
                    else { mute(); demoShowing = false; overlay.show(d, hobbyInvite()) }
                Level.TIP -> { unmute(); tip.show(d.card?.truth ?: rules.tipText) }
                Level.ALLOW -> unmute()
            }
        }
        // WeChat sometimes shows the top comment instead of the caption; look again shortly.
        if (obs.caption.isEmpty() && captionRetries++ < 3) main.postDelayed(recheck, 1500)
    }

    private fun leaveChannels() {
        inChannels = false
        current = null
        decision = null
        keptWatching = null
        settling = false
        main.removeCallbacks(settle)
        main.removeCallbacks(recheck)
        overlay.hide()
        tip.hide()
        unmute()
    }

    // ---- Explanation card buttons ----

    override fun onKeepWatching() {
        if (closeDemo()) return
        keptWatching = current
        overlay.hide()
        unmute()
    }

    override fun onNextVideo() {
        if (closeDemo()) return
        overlay.hide()
        val video = current
        actions.notInterestedThenNext(isSameVideo = { readCard()?.sameVideoAs(video) == true })
    }

    override fun onHobby(hobby: Hobby) {
        if (closeDemo()) return
        overlay.hide()
        actions.searchFor(hobby.labelZh, feedRoot(), screenH())
    }

    override fun onSwipeVideo(up: Boolean) {
        if (closeDemo()) return
        overlay.hide()
        actions.swipe(up)
    }

    /** The sample card sits over Willow's own screen, so its buttons only close it. */
    private fun closeDemo(): Boolean {
        if (!demoShowing) return false
        demoShowing = false
        overlay.hide()
        return true
    }

    // ---- Helpers ----

    /** His favourite hobby first (hobby_rules.json order). */
    private fun hobbyInvite() = rules.hobbies.first()

    private fun readCard(): Observation? {
        val h = screenH()
        for (root in wechatRoots()) CardReader.read(root, h)?.let { return it }
        return null
    }

    private fun feedRoot(): AccessibilityNodeInfo? {
        val h = screenH()
        return wechatRoots().firstOrNull { CardReader.read(it, h) != null }
    }

    private fun wechatRoots(): List<AccessibilityNodeInfo> =
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }.filter { it.packageName == WECHAT }

    /** The vertical video feed fills the screen; comment lists and tab bars don't. */
    private fun isFeedScroll(e: AccessibilityEvent): Boolean {
        val src = e.source ?: return true
        val b = Rect().also { src.getBoundsInScreen(it) }
        return b.height() >= screenH() * 0.7
    }

    private fun isActivity(cls: String): Boolean = activityCache.getOrPut(cls) {
        runCatching { packageManager.getActivityInfo(ComponentName(WECHAT, cls), 0) }.isSuccess
    }

    private fun screenH(): Int =
        if (Build.VERSION.SDK_INT >= 30) getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.height()
        else resources.displayMetrics.heightPixels

    private fun mute() {
        if (mutedByWillow || audio.isStreamMute(AudioManager.STREAM_MUSIC)) return // never undo his own mute
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mutedByWillow = true
    }

    private fun unmute() {
        if (!mutedByWillow) return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        mutedByWillow = false
    }

    private fun log(msg: String) {
        if (DEV) Log.i(TAG, msg)
    }

    // ---- Demo and test hooks ----

    /** "看看效果" in the app: shows a sample explanation card without needing a scam video. */
    fun demo() {
        val card = rules.cardById("bp_stop") ?: rules.genericCard
        demoShowing = true
        overlay.show(Decision(Level.INTERCEPT, "predatory_health", 1.0, emptyList(), card, "library", null), hobbyInvite())
    }

    /**
     * Test phone only: force a decision on the current video.
     * adb shell am broadcast -p com.willow -a com.willow.TEST --es level intercept --es card bp_stop
     */
    private val devCommands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SELFTEST) return selfTest()
            val level = when (intent.getStringExtra("level")) {
                "tip" -> Level.TIP
                "allow" -> Level.ALLOW
                else -> Level.INTERCEPT
            }
            val card = rules.cardById(intent.getStringExtra("card")) ?: rules.genericCard
            current = readCard() ?: current
            log("TEST force $level card=${card.id}")
            keptWatching = null
            overlay.hide()
            tip.hide()
            when (level) {
                Level.INTERCEPT -> { mute(); demoShowing = false; overlay.show(Decision(level, "test", 1.0, emptyList(), card, "library", null), hobbyInvite()) }
                Level.TIP -> { unmute(); tip.show(card.truth) }
                Level.ALLOW -> unmute()
            }
        }
    }

    /**
     * Runs shared/samples/feed.json through the Kotlin rules and logs one line per video, in the
     * same format as the Node check, so the port can be diffed against shared/classifier.js.
     */
    private fun selfTest() {
        val feed = org.json.JSONObject(assets.open("feed.json").bufferedReader().use { it.readText() })
        val videos = feed.getJSONArray("videos")
        val extra = org.json.JSONArray(SELFTEST_EXTRA)
        for (list in listOf(videos, extra)) for (i in 0 until list.length()) {
            val v = list.getJSONObject(i)
            val author = v.optJSONObject("author")
            val tags = v.optJSONArray("hashtags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val obs = Observation(
                author = author?.optString("name") ?: "",
                caption = v.optString("caption"),
                labels = listOf(tags.joinToString(" "), v.optString("onScreenText")).filter { it.isNotEmpty() },
                promo = v.optJSONObject("product")?.optString("title"),
                verified = author?.optString("verified")?.takeIf { it.isNotEmpty() && it != "null" },
            )
            val d = rules.decide(obs)
            Log.i(TAG, "SELFTEST ${v.getString("id").padEnd(4)} ${v.getString("category").padEnd(11)} " +
                "${d.level.name.lowercase().padEnd(9)} ${"%.2f".format(d.score)} ${d.label}")
        }
    }

    companion object {
        const val TAG = "Willow"
        const val WECHAT = "com.tencent.mm"
        const val ACTION_TEST = "com.willow.TEST"
        const val ACTION_SELFTEST = "com.willow.SELFTEST"
        /** The off-topic probes from the Node check (scratch levels.mjs), kept identical. */
        private const val SELFTEST_EXTRA = """[
            {"id":"x1","category":"hobby","author":{"name":"钓友老王"},"caption":"钓鱼千万别用这种饵，限时特价鱼竿点下方链接","hashtags":["#钓鱼"]},
            {"id":"x2","category":"other","author":{"name":"数码"},"caption":"限时特价！手机壳包邮，最后一天","hashtags":[]},
            {"id":"x3","category":"scam","author":{"name":"养生"},"caption":"降压药千万别再吃了，越吃越伤肾！点击下方小黄车","hashtags":["#中老年"]}
        ]"""
        /** Test-phone logging and the adb test hook. Set to false before installing for Grandpa: no logs. */
        const val DEV = true
        const val SETTLE_MS = 350L
        const val ENTER_SETTLE_MS = 700L

        @Volatile var instance: WillowService? = null
    }
}
