package com.willow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/**
 * The 4-step explanation card (等一下 → 视频里说 → 其实 → 怎么办) drawn over WeChat.
 * Behaviour spec #4 in CLAUDE.md; openDebunk()/showBeat() in prototype-web/public/app.js.
 *
 * - Autoplay moves on only after the voice finishes + 2.5 s; with no voice, max(5 s, 300 ms × chars).
 *   A sentence is never cut off.
 * - Any manual navigation (buttons, dots, swiping left/right) stops autoplay; ⏸/▶ toggles it.
 * - 继续看原视频 is on every step. Swiping up/down still changes the video.
 */
class ExplainOverlay(
    private val ctx: Context, // must be the AccessibilityService, for TYPE_ACCESSIBILITY_OVERLAY
    private val voice: Voice,
    private val listener: Listener,
) {
    interface Listener {
        fun onKeepWatching()
        fun onNextVideo()
        fun onHobby(hobby: Hobby)
        fun onSwipeVideo(up: Boolean)
    }

    private class Beat(val icon: String, val title: String, val body: String, val speech: String, val source: String = "")

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val timerToken = Any()

    private var root: View? = null
    private var beats = emptyList<Beat>()
    private var index = 0
    private var auto = true
    private var run = 0

    private lateinit var dots: List<View>
    private lateinit var iconView: TextView
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var sourceView: TextView
    private lateinit var prevBtn: TextView
    private lateinit var autoBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var actions: View

    val showing get() = root != null

    fun show(decision: Decision, hobby: Hobby) {
        hide()
        val card = decision.card ?: return
        beats = listOf(
            Beat("🍵", "等一下", "我们先看看这个说法对不对", "等一下，我们先看看这个说法对不对。"),
            Beat("❓", "视频里说", card.myth, "视频里说：${card.myth}。"),
            Beat("✅", "其实", card.truth, "其实，${card.truth}", card.source),
            Beat(card.icon, "怎么办", card.action, "所以，${card.action}"),
        )
        index = 0
        auto = true
        val view = build(decision, hobby)
        wm.addView(view, layoutParams())
        root = view
        showBeat(0)
    }

    fun hide() {
        run++
        main.removeCallbacksAndMessages(timerToken)
        voice.stop()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    // ---- Steps ----

    private fun showBeat(i: Int, manual: Boolean = false) {
        if (root == null) return
        val last = beats.size - 1
        index = i.coerceIn(0, last)
        if (manual) auto = false
        val b = beats[index]

        iconView.text = b.icon
        titleView.text = b.title
        bodyView.text = b.body
        sourceView.text = b.source
        sourceView.visibility = if (b.source.isEmpty()) View.GONE else View.VISIBLE
        dots.forEachIndexed { k, d -> d.background = rounded(if (k <= index) Palette.green else Palette.dotOff, ctx.dp(3)) }
        prevBtn.setUsable(index > 0)
        nextBtn.setUsable(index < last)
        actions.visibility = if (index == last) View.VISIBLE else View.GONE
        renderAuto()
        speak()
    }

    /** Speak the current step, then (if autoplay is on) schedule the next one. */
    private fun speak() {
        val myRun = ++run
        main.removeCallbacksAndMessages(timerToken)
        val b = beats[index]
        voice.say(b.speech) { spoke ->
            if (myRun != run) return@say // he navigated, paused, or left in the meantime
            schedule(if (spoke) PAUSE_AFTER_VOICE_MS else max(MIN_NO_VOICE_MS, b.body.length * MS_PER_CHAR_NO_VOICE))
        }
    }

    private fun schedule(delay: Long) {
        main.removeCallbacksAndMessages(timerToken)
        if (!auto || index >= beats.size - 1) return
        val myRun = run
        val tick = object : Runnable {
            override fun run() {
                if (myRun != run || !auto || root == null) return
                if (voice.speaking) { // never cut a sentence off
                    main.postAtTime(this, timerToken, SystemClock.uptimeMillis() + 500)
                    return
                }
                showBeat(index + 1)
            }
        }
        main.postAtTime(tick, timerToken, SystemClock.uptimeMillis() + delay)
    }

    private fun toggleAuto() {
        auto = !auto
        renderAuto()
        if (auto) {
            run++ // the pending speak() callback must not schedule a second advance
            schedule(RESUME_DELAY_MS)
        } else {
            main.removeCallbacksAndMessages(timerToken)
        }
    }

    private fun renderAuto() {
        val atEnd = index >= beats.size - 1
        autoBtn.text = if (auto && !atEnd) "⏸ 暂停" else "▶ 自动"
        autoBtn.setUsable(!atEnd)
    }

    // ---- Layout ----

    private fun build(decision: Decision, hobby: Hobby): View {
        val pad = ctx.dp(22)

        // Top: progress dots (tappable) and 继续看原视频 on every step.
        val dotRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        dots = List(beats.size) { k ->
            val bar = View(ctx)
            FrameLayout(ctx).apply {
                setPadding(ctx.dp(3), ctx.dp(16), ctx.dp(3), ctx.dp(16)) // tall hit target, thin bar
                addView(bar, FrameLayout.LayoutParams(ctx.dp(34), ctx.dp(6)))
                setOnClickListener { showBeat(k, manual = true) }
                dotRow.addView(this)
            }
            bar
        }
        val keepTop = ctx.label("继续看原视频 ›", 19f, Palette.muted).apply {
            setPadding(ctx.dp(12), ctx.dp(10), 0, ctx.dp(10))
            paint.isUnderlineText = true
            setOnClickListener { listener.onKeepWatching() }
        }
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dotRow, LinearLayout.LayoutParams(0, -2, 1f))
            addView(keepTop)
        }

        // Middle: the step itself, in big text.
        iconView = ctx.label("", 64f)
        titleView = ctx.label("", 22f, Palette.muted, bold = true)
        val replay = ctx.label("🔊", 22f).apply {
            gravity = Gravity.CENTER
            background = rounded(Palette.dotOff, ctx.dp(28))
            setOnClickListener { auto = false; renderAuto(); speak() }
        }
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(replay, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)).apply { leftMargin = ctx.dp(14) })
        }
        bodyView = ctx.label("", 30f, Palette.ink, bold = true)
        sourceView = ctx.label("", 15f, Palette.muted)
        val beat = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(iconView)
            addView(titleRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ctx.dp(6) })
            addView(bodyView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
            addView(sourceView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(16) })
        }
        val middle = ScrollView(ctx).apply {
            isFillViewport = true
            addView(FrameLayout(ctx).apply {
                addView(beat, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            })
        }

        // ‹ 上一步 · ⏸ 暂停 · 下一步 ›
        prevBtn = ctx.bigButton("‹ 上一步", ButtonStyle.NAV) { showBeat(index - 1, manual = true) }
        autoBtn = ctx.bigButton("⏸ 暂停", ButtonStyle.NAV) { toggleAuto() }
        nextBtn = ctx.bigButton("下一步 ›", ButtonStyle.NAV) { showBeat(index + 1, manual = true) }
        val nav = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(prevBtn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(autoBtn, LinearLayout.LayoutParams(ctx.dp(112), -2).apply { leftMargin = ctx.dp(8); rightMargin = ctx.dp(8) })
            addView(nextBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // Last step: 换一个视频 / hobby invite / 继续看原视频.
        actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val lp = { LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) } }
            addView(ctx.bigButton("换一个视频", ButtonStyle.PRIMARY) { listener.onNextVideo() }, lp())
            addView(ctx.bigButton("${hobby.icon} ${hobby.invite}", ButtonStyle.SECONDARY) { listener.onHobby(hobby) }, lp())
            addView(ctx.bigButton("继续看原视频 ▶", ButtonStyle.OUTLINE) { listener.onKeepWatching() }, lp())
        }

        val foot = ctx.label("🌳 Willow" + if (decision.cardSource == "ai") " · AI 解释" else "", 13f, Palette.muted).apply {
            gravity = Gravity.CENTER
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(top)
            addView(middle, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
            addView(actions, LinearLayout.LayoutParams(-1, -2))
            addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(10) })
        }

        return SwipeLayout(ctx).apply {
            setBackgroundColor(Palette.paper)
            addView(column, FrameLayout.LayoutParams(-1, -1))
            padForSystemBars(column, pad)
        }
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Not focusable: the Back key still goes to WeChat, so he can always leave as usual.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.OPAQUE,
    ).apply {
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /** Sees every touch first: left/right swipes change the step, up/down swipes change the video. */
    @SuppressLint("ViewConstructor")
    private inner class SwipeLayout(context: Context) : FrameLayout(context) {
        private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.rawX - e1.rawX
                val dy = e2.rawY - e1.rawY
                if (abs(dx) > abs(dy) && abs(dx) > ctx.dp(70)) {
                    showBeat(index + if (dx < 0) 1 else -1, manual = true)
                    return true
                }
                if (abs(dy) > abs(dx) && abs(dy) > ctx.dp(90)) {
                    listener.onSwipeVideo(up = dy < 0)
                    return true
                }
                return false
            }
        })

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            detector.onTouchEvent(ev)
            super.dispatchTouchEvent(ev)
            return true // keep receiving the whole gesture even when it starts on empty space
        }
    }

    private companion object {
        const val PAUSE_AFTER_VOICE_MS = 2500L     // reading time after the voice finishes
        const val MS_PER_CHAR_NO_VOICE = 300L      // when there is no voice: ~3 characters/second
        const val MIN_NO_VOICE_MS = 5000L
        const val RESUME_DELAY_MS = 1500L
    }
}
