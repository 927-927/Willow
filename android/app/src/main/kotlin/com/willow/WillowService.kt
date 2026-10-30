package com.willow

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Willow's observer, sitting on top of WeChat Channels (behaviour spec in CLAUDE.md):
 *  1. Mute first, decide second: the moment a swipe starts (or Channels opens), mute the media stream.
 *  2. When the feed settles, take a screenshot, read it with on-phone OCR, sort the words into
 *     author / caption / labels / burned-in text ([FeedParser]) and classify them with the shared rules.
 *     Unsure → ask the AI (Qwen) with that text only. A few more screenshots follow in the first seconds,
 *     because the scariest words often come later as subtitles.
 *  3. Intercept → explanation card (stays muted). Tip → yellow banner + sound. Allow → sound back.
 *
 * Nothing is stored: screenshots are dropped right after OCR and Willow only remembers the one video
 * on screen. Willow never hides a video: the feed changes only when he swipes or taps a Willow button.
 */
class WillowService : AccessibilityService(), ExplainOverlay.Listener {

    /** The video on screen and what Willow decided about it. Memory only, replaced by the next video. */
    private class Video(var obs: Observation) {
        var decision: Decision? = null
        var frames = 1             // screenshots read of it so far
        var keptWatching = false   // he chose 继续看原视频
        var tipShown = false
        var askingAi = false
        var aiAsks = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val scanToken = Any()
    private lateinit var rules: Rules
    private lateinit var ai: Ai
    private lateinit var reader: ScreenReader
    private lateinit var voice: Voice
    private lateinit var overlay: ExplainOverlay
    private lateinit var tip: Banner
    private lateinit var note: Banner // purchase pause and hints
    private lateinit var actions: WeChatActions
    private lateinit var audio: AudioManager

    private var feed: String? = null        // the Channels feed screen he is on, or null
    private var suspendedFeed: String? = null // WeChat went to the background while on this feed
    private var video: Video? = null
    private var gen = 0                     // bumps on every swipe and screen change; older reads are dropped
    private var settling = false
    private var swipeStartedAt = 0L
    private var lastWasOther = false        // the last screenshot wasn't the feed (comments open…)
    private var acting = false              // Willow is doing 换一个 / hobby search for him
    private var saleUntil = 0L              // he just left a flagged video that sells something
    private var mutedByWillow = false
    private var demoShowing = false
    private val activityCache = HashMap<String, Boolean>()

    // ---- Lifecycle ----

    override fun onServiceConnected() {
        instance = this
        rules = Rules(this)
        ai = Ai(this)
        reader = ScreenReader(this, ::log)
        voice = Voice(this)
        overlay = ExplainOverlay(this, voice, this)
        tip = Banner(this)
        note = Banner(this)
        actions = WeChatActions(this, reader, ::log)
        audio = getSystemService(AudioManager::class.java)
        if (DEV) {
            val filter = IntentFilter(ACTION_TEST).apply {
                addAction(ACTION_SELFTEST); addAction(ACTION_OCR); addAction(ACTION_AI)
            }
            registerReceiver(devCommands, filter, Context.RECEIVER_EXPORTED)
        }
        log("connected · v${BuildConfig.VERSION_NAME} · rules ok · ${rules.hobbies.size} hobbies · " +
            if (ai.available) "AI ${ai.model}" else "AI off (no key)")
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacksAndMessages(null)
        if (::overlay.isInitialized) {
            overlay.hide()
            tip.hide()
            note.hide()
            unmute()
            voice.shutdown()
            reader.close()
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
                suspendedFeed = null
                if (isFeed(cls)) enterFeed(cls) else onOtherScreen(cls)
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                if (feed != null && !overlay.showing && !acting && isFeedScroll(e)) {
                    if (DEV) log("scroll ${e.className?.toString()?.substringAfterLast('.')} ${e.source?.let { s -> Rect().also { s.getBoundsInScreen(it) }.toShortString() }}")
                    startCheck(SETTLE_MS)
                }
            else -> // WeChat is drawing again after the screen was off or another app was on top
                suspendedFeed?.let { if (wechatOnTop()) enterFeed(it) }
        }
    }

    /** Entered Channels, came back to it, or moved to another Channels feed. */
    private fun enterFeed(cls: String) {
        feed = cls
        saleUntil = 0
        lastWasOther = false
        note.hide()
        actions.rememberSearchButton()
        startCheck(ENTER_SETTLE_MS)
        watch()
    }

    private fun onOtherScreen(cls: String) {
        gen++
        if (feed != null) {
            feed = null
            settling = false
            main.removeCallbacksAndMessages(scanToken)
            overlay.hide()
            tip.hide()
            unmute()
            // Willow keeps [video], so coming back to the same video doesn't start over.
            val d = video?.decision
            if (d != null && d.level != Level.ALLOW && d.selling) saleUntil = SystemClock.uptimeMillis() + SALE_WINDOW_MS
        }
        if (SystemClock.uptimeMillis() < saleUntil && SHOP_SCREEN.containsMatchIn(cls)) checkPurchasePage(gen, attempt = 0)
        if (!SHOP_SCREEN.containsMatchIn(cls)) note.hide()
    }

    /** Mute first, decide second. */
    private fun startCheck(delay: Long) {
        if (!settling) {
            settling = true
            swipeStartedAt = SystemClock.uptimeMillis()
            // After a non-feed screenshot (comments open) scrolling is probably the comment list: don't mute.
            if (!lastWasOther) mute()
        }
        gen++
        val myGen = gen
        main.removeCallbacksAndMessages(scanToken)
        later(delay) { settling = false; scan(myGen, FIRST_READ_RETRIES) }
    }

    /** [retries]: the first read after a swipe may catch a transition or a loading screen; look again. */
    private fun scan(myGen: Int, retries: Int = 0) {
        if (myGen != gen || feed == null || overlay.showing || acting) return
        val started = reader.read(whole = false) { shot -> onShot(myGen, shot, retries) }
        if (!started) later(250) { scan(myGen, retries) } // the previous read is still running
    }

    private fun onShot(myGen: Int, shot: ScreenReader.Shot?, retries: Int) {
        if (myGen != gen || feed == null || overlay.showing || acting) return
        val screen = shot?.let { FeedParser.parse(it.lines, it.width, it.height) }
        if (DEV && shot != null && screen != null) log(describe(shot, screen))
        if (screen !is Screen.Video) { // comments, a menu, a profile, a failed read: nothing to judge
            if (retries > 0 && !lastWasOther) later(RETRY_MS) { scan(myGen, retries - 1) }
            else { lastWasOther = screen != null; restoreSound() }
            return
        }
        lastWasOther = false

        val v = video
        if (v != null && screen.obs.sameVideoAs(v.obs)) {
            val before = v.obs.text
            v.obs = v.obs.merge(screen.obs)
            v.frames++
            if (v.obs.text != before && !v.keptWatching) decide(v, update = true) else resume(v)
        } else {
            val nv = Video(screen.obs)
            video = nv
            overlay.hide()
            tip.hide()
            decide(nv, update = false)
        }
        // Look again a few times: subtitles and big words change while the video plays.
        val cur = video ?: return
        if (cur.frames <= FRAME_GAPS_MS.size && !cur.keptWatching) later(FRAME_GAPS_MS[cur.frames - 1]) { scan(myGen) }
    }

    private fun decide(v: Video, update: Boolean) {
        if (v.askingAi) return // the AI's answer is on its way; it decides
        val d = rules.decide(v.obs)
        log("${if (update) "update" else "new"} ${d.level} ${"%.2f".format(d.score)} ${d.label} card=${d.card?.id ?: "-"} " +
            d.matched.joinToString(" ") { m -> m.labelZh + (if (m.phrases.isEmpty()) "" else ":" + m.phrases.take(3).joinToString("、")) } +
            " · ${SystemClock.uptimeMillis() - swipeStartedAt}ms")
        val cur = v.decision
        // Later screenshots only make things stricter (Level: INTERCEPT < TIP < ALLOW).
        if (update && cur != null && d.level.ordinal >= cur.level.ordinal) return resume(v)
        if (rules.unsure(d) && ai.available && v.aiAsks < MAX_AI_ASKS) askAi(v, d) else apply(v, d)
    }

    /** Spec #3: the AI only when the rules are unsure. The sound stays off while it thinks. */
    private fun askAi(v: Video, d: Decision) {
        v.aiAsks++
        v.askingAi = true
        mute()
        ai.ask(v.obs, d.matched.map { it.labelZh }) { answer, err ->
            v.askingAi = false
            if (video !== v || feed == null) return@ask // he moved on
            if (answer == null) log("AI failed ($err) → rules")
            else log("AI ${answer.label} ${"%.2f".format(answer.confidence)} ${answer.ms}ms · ${answer.reason}")
            apply(v, if (answer != null) rules.withAi(d, answer) else d)
        }
    }

    private fun apply(v: Video, d: Decision) {
        v.decision = d
        when (d.level) {
            Level.INTERCEPT ->
                if (v.keptWatching) unmute()
                else if (!overlay.showing) { mute(); tip.hide(); demoShowing = false; overlay.show(d, hobbyInvite()) }
            Level.TIP -> {
                unmute()
                if (!v.tipShown) { v.tipShown = true; tip.note("💡 Willow 小提示", d.card?.truth ?: rules.tipText) }
            }
            Level.ALLOW -> unmute()
        }
    }

    /** The same video again (a bounce, or back from another screen): restore what was decided. */
    private fun resume(v: Video) {
        if (v.askingAi) return mute()
        val d = v.decision ?: return
        if (d.level == Level.INTERCEPT && !v.keptWatching) apply(v, d) else unmute()
    }

    private fun restoreSound() {
        if (!overlay.showing && video?.askingAi != true) unmute()
    }

    // ---- Purchase pause (spec #6) ----

    /** On a shop page right after a flagged selling video: a gentle note if there is a buy button. */
    private fun checkPurchasePage(myGen: Int, attempt: Int) {
        later(if (attempt == 0) 1200 else 2500) {
            if (myGen != gen || feed != null) return@later
            val started = reader.read(whole = true) { shot ->
                if (myGen != gen || feed != null || shot == null) return@read
                if (shot.lines.any { l -> BUY_WORDS.any { it in l.text } }) showPurchaseNote()
                else if (attempt == 0) checkPurchasePage(myGen, 1) // the page may still be loading
            }
            if (!started) checkPurchasePage(myGen, attempt)
        }
    }

    /** Both buttons work: 再想想 leaves the shop page, 立即购买 just closes the note. No alerts to anyone. */
    private fun showPurchaseNote() {
        saleUntil = 0
        note.show("💡 买之前想一想", "保健食品不是药物，不能代替药物治疗疾病。买之前可以再想想，或者问问医生。", listOf(
            Banner.Button("再想想", ButtonStyle.PRIMARY) { performGlobalAction(GLOBAL_ACTION_BACK) },
            Banner.Button("立即购买", ButtonStyle.OUTLINE) {},
        ))
        watch()
    }

    // ---- Explanation card buttons ----

    override fun onKeepWatching() {
        if (closeDemo()) return
        video?.keptWatching = true
        overlay.hide()
        unmute()
    }

    override fun onNextVideo() {
        if (closeDemo()) return
        overlay.hide()
        acting = true
        actions.notInterestedThenNext(video?.obs) { acting = false; startCheck(SETTLE_MS) }
    }

    override fun onHobby(hobby: Hobby) {
        if (closeDemo()) return
        overlay.hide()
        acting = true
        actions.searchFor(hobby.labelZh, cantDoIt = {
            note.note("${hobby.icon} 想${hobby.invite}？", "点视频号右上角的 🔍，搜「${hobby.labelZh}」就能看到。")
        }) { acting = false }
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

    // ---- Safety net: never leave the phone muted, or a card on screen, outside WeChat ----

    private val watchdog = object : Runnable {
        override fun run() {
            val busy = feed != null || mutedByWillow || overlay.showing || tip.showing || note.showing
            if (!busy || demoShowing) return
            if (!wechatOnTop()) {
                log("WeChat left the screen (top app: ${windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName})")
                suspendedFeed = feed
                onOtherScreen("")
                overlay.hide()
                tip.hide()
                note.hide()
                unmute()
                return
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun watch() {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** WeChat is the app on top (the notification shade or Willow's own overlays don't count as leaving). */
    private fun wechatOnTop(): Boolean =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root?.packageName == WECHAT

    // ---- Helpers ----

    /** His favourite hobby first (hobby_rules.json order). */
    private fun hobbyInvite() = rules.hobbies.first()

    private fun later(ms: Long, block: () -> Unit) {
        main.postAtTime(block, scanToken, SystemClock.uptimeMillis() + ms)
    }

    /** Only the feeds are ever photographed: never his own page, messages or settings. */
    private fun isFeed(cls: String) = FEED_SCREEN.containsMatchIn(cls) && !PRIVATE_SCREEN.containsMatchIn(cls)

    /** The vertical video feed fills the screen; comment lists and tab bars don't. */
    private fun isFeedScroll(e: AccessibilityEvent): Boolean {
        val src = e.source ?: return true
        val b = Rect().also { src.getBoundsInScreen(it) }
        if (b.isEmpty) return true // WeChat hides its views: assume the feed, the screenshot will tell
        return b.height() >= screenH() * 0.7
    }

    private fun isActivity(cls: String): Boolean = activityCache.getOrPut(cls) {
        runCatching { packageManager.getActivityInfo(ComponentName(WECHAT, cls), 0) }.isSuccess
    }

    private fun screenH(): Int = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.height()

    private fun mute() {
        if (mutedByWillow || audio.isStreamMute(AudioManager.STREAM_MUSIC)) return // never undo his own mute
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mutedByWillow = true
        watch()
    }

    private fun unmute() {
        if (!mutedByWillow) return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        mutedByWillow = false
    }

    private fun log(msg: String) {
        if (DEV) Log.i(TAG, msg)
    }

    /** Test phone only: what one screenshot was read as. */
    private fun describe(shot: ScreenReader.Shot, screen: Screen): String = when (screen) {
        is Screen.Other -> "read ${shot.ms}ms ${shot.lines.size} lines → not a video (${screen.why})"
        is Screen.Video -> with(screen.obs) {
            "read ${shot.ms}ms ${shot.lines.size} lines → author=$author | caption=$caption | labels=$labels | " +
                "promo=$promo | screen=$onScreen | counts=$counts"
        }
    }

    // ---- Demo and test hooks ----

    /** "看看效果" in the app: shows a sample explanation card without needing a scam video. */
    fun demo() {
        val card = rules.cardById("bp_stop") ?: rules.genericCard
        demoShowing = true
        overlay.show(Decision(Level.INTERCEPT, "predatory_health", 1.0, emptyList(), card, "library", null), hobbyInvite())
    }

    /**
     * Test phone only (debug build). From the PC:
     *   adb shell am broadcast -p com.willow -a com.willow.TEST --es level intercept --es card bp_stop
     *   adb shell am broadcast -p com.willow -a com.willow.OCR        (log what the screen reads as)
     *   adb shell am broadcast -p com.willow -a com.willow.AI --es text "降压药千万别吃了"
     *   adb shell am broadcast -p com.willow -a com.willow.SELFTEST   (Kotlin rules vs shared/classifier.js)
     */
    private val devCommands = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_SELFTEST -> return selfTest()
                ACTION_OCR -> return ocrDump()
                ACTION_AI -> return aiTest(intent.getStringExtra("text"))
            }
            val level = when (intent.getStringExtra("level")) {
                "tip" -> Level.TIP
                "allow" -> Level.ALLOW
                else -> Level.INTERCEPT
            }
            val card = rules.cardById(intent.getStringExtra("card")) ?: rules.genericCard
            log("TEST force $level card=${card.id}")
            val v = video ?: Video(Observation("", "", emptyList(), null)).also { video = it }
            v.keptWatching = false
            v.tipShown = false
            overlay.hide()
            tip.hide()
            apply(v, Decision(level, "test", 1.0, emptyList(), card, "library", null))
        }
    }

    private fun ocrDump() {
        if (!reader.read(whole = false) { shot ->
                if (shot == null) return@read log("OCR: no screenshot")
                Log.i(TAG, "OCR ${shot.width}x${shot.height} ${shot.ms}ms")
                shot.lines.sortedBy { it.top }.forEach { l ->
                    Log.i(TAG, "  [${l.left},${l.top} ${l.right - l.left}x${l.height}] ${l.text}")
                }
                Log.i(TAG, describe(shot, FeedParser.parse(shot.lines, shot.width, shot.height)))
            }) log("OCR: reader busy, try again")
    }

    private fun aiTest(text: String?) {
        val obs = video?.obs?.takeIf { text == null } ?: Observation("测试", text ?: "降压药千万别再吃了，这个茶喝三个月血压就正常", emptyList(), null)
        val d = rules.decide(obs)
        log("AI test ${ai.model} (rules: ${d.level} ${d.label}) asking…")
        ai.ask(obs, d.matched.map { it.labelZh }) { answer, err ->
            if (answer == null) log("AI test failed: $err")
            else log("AI test ${answer.label} ${answer.confidence} ${answer.ms}ms · claim=${answer.claim} · truth=${answer.truth} · action=${answer.action} · ${answer.reason}")
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
                labels = listOf(tags.joinToString(" ")).filter { it.isNotEmpty() },
                promo = v.optJSONObject("product")?.optString("title"),
                verified = author?.optString("verified")?.takeIf { it.isNotEmpty() && it != "null" },
                onScreen = listOf(v.optString("onScreenText")).filter { it.isNotEmpty() },
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
        const val ACTION_OCR = "com.willow.OCR"
        const val ACTION_AI = "com.willow.AI"
        /** The off-topic probes from the Node check (scratch levels.mjs), kept identical. */
        private const val SELFTEST_EXTRA = """[
            {"id":"x1","category":"hobby","author":{"name":"钓友老王"},"caption":"钓鱼千万别用这种饵，限时特价鱼竿点下方链接","hashtags":["#钓鱼"]},
            {"id":"x2","category":"other","author":{"name":"数码"},"caption":"限时特价！手机壳包邮，最后一天","hashtags":[]},
            {"id":"x3","category":"scam","author":{"name":"养生"},"caption":"降压药千万别再吃了，越吃越伤肾！点击下方小黄车","hashtags":["#中老年"]}
        ]"""
        /** Logs and adb test hooks: on in the debug build (test phone), off in the release build (Grandpa). */
        val DEV = BuildConfig.DEBUG
        const val SETTLE_MS = 350L
        const val ENTER_SETTLE_MS = 700L
        const val FIRST_READ_RETRIES = 2
        const val RETRY_MS = 600L
        /** Gaps between the follow-up screenshots of one video: read at about 0, 2, 5 and 9 s. */
        val FRAME_GAPS_MS = longArrayOf(2000, 3000, 4000)
        const val MAX_AI_ASKS = 2
        const val WATCHDOG_MS = 1500L
        const val SALE_WINDOW_MS = 60_000L

        /** Channels feed screens (WeChat 8.0.69: FinderHomeAffinityUI, FinderShareFeedRelUI). */
        private val FEED_SCREEN = Regex("(?i)finder.*(home|feed|timeline)")
        /** Never photographed, even if the name looks like a feed: his own page, messages, settings. */
        private val PRIVATE_SCREEN = Regex("(?i)(self|msg|message|conversation|chat|setting|mention|notify|fans|wallet|account|contact)")
        /** Shop pages, where the purchase pause may appear: mini-program shops, Channels shops, web shops. */
        private val SHOP_SCREEN = Regex("(?i)(appbrand|shop|store|ecs|product|goods|commodity|mall|webview)")
        private val BUY_WORDS = listOf("立即购买", "立即抢购", "马上抢", "去购买", "加入购物车", "提交订单", "确认下单", "立即下单", "领券购买")

        @Volatile var instance: WillowService? = null
    }
}
