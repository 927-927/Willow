package com.willow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo

/**
 * Things Willow does in WeChat, only ever because he tapped a Willow button or swiped:
 * change the video, tell WeChat "不感兴趣", search for his hobby.
 *
 * WeChat usually hides its screen from accessibility services, so each action first tries WeChat's
 * view tree (when it happens to be readable) and otherwise finds its target in a screenshot (OCR)
 * and taps it like a finger would.
 */
class WeChatActions(
    private val svc: AccessibilityService,
    private val reader: ScreenReader,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val screen get() = svc.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
    /** Where the Channels search button was the last time WeChat's tree was readable. */
    private var searchButton: Rect? = null

    /** A normal finger swipe: up = next video, down = previous video. */
    fun swipe(up: Boolean) {
        val (w, h) = screen
        val x = w / 2f
        val (from, to) = if (up) h * 0.70f to h * 0.25f else h * 0.30f to h * 0.75f
        gesture(Path().apply { moveTo(x, from); lineTo(x, to) }, 220)
    }

    /**
     * 换一个视频: long-press the video, tap 不感兴趣 if WeChat offers it, then move on.
     * If the menu isn't there (WeChat changed it), it closes whatever opened and just swipes.
     * [done] runs once the feed has moved (or Willow gave up).
     */
    fun notInterestedThenNext(video: Observation?, done: () -> Unit) {
        val (w, h) = screen
        gesture(Path().apply { moveTo(w / 2f, h * 0.45f) }, 900)
        main.postDelayed({
            read(whole = true) { shot ->
                val item = shot?.lines?.firstOrNull { "不感兴趣" in it.text }
                if (shot == null || item == null) {
                    log("不感兴趣 not found after long-press; swiping")
                    // Something covers the video (an unknown menu): close it. Back on the bare feed
                    // would leave Channels, so only when the screenshot isn't the feed.
                    val covered = shot != null && FeedParser.parse(shot.lines, shot.width, shot.height) !is Screen.Video
                    if (covered) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    main.postDelayed({ swipe(up = true); done() }, 400)
                    return@read
                }
                tap(shot.screenX(item), shot.screenY(item))
                log("不感兴趣 tapped")
                // WeChat may move on by itself; if the same video is still there, swipe.
                main.postDelayed({
                    read(whole = false) { after ->
                        val now = after?.let { FeedParser.parse(it.lines, it.width, it.height) as? Screen.Video }?.obs
                        if (now == null || now.sameVideoAs(video)) swipe(up = true)
                        done()
                    }
                }, 1200)
            }
        }, 1300)
    }

    /** Remembers where the search button is while WeChat's tree is readable (cheap: one id lookup). */
    fun rememberSearchButton() {
        val node = wechatRoots().firstNotNullOfOrNull { r ->
            r.findAccessibilityNodeInfosByViewId(SEARCH_ICON).firstOrNull { it.isVisibleToUser }
        } ?: return
        searchButton = Rect().also { node.getBoundsInScreen(it) }.takeIf { !it.isEmpty }
    }

    /**
     * Hobby invite: open Channels search and look for 钓鱼 / 种菜 / 下象棋.
     * [cantDoIt] runs when Willow can't, so the caller can tell him how to do it himself.
     */
    fun searchFor(query: String, cantDoIt: (String) -> Unit, done: () -> Unit) {
        // 1. WeChat's tree, if readable right now.
        val node = wechatRoots().firstNotNullOfOrNull { r ->
            r.findAccessibilityNodeInfosByViewId(SEARCH_ICON).firstOrNull { it.isVisibleToUser }
        }?.let { clickableSelfOrParent(it) }
        if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return typeQuery(query, 8, cantDoIt, done)

        // 2. Where the button was last seen.
        searchButton?.let {
            tap(it.exactCenterX(), it.exactCenterY())
            return typeQuery(query, 8, cantDoIt, done)
        }

        // 3. The screenshot: the button sits at the right end of the tab row (关注 · 朋友 · 推荐).
        read(whole = false) { shot ->
            val tab = shot?.lines?.firstOrNull { it.text in TABS && it.cy < shot.height * 0.15 }
            if (shot == null || tab == null) {
                log("search button not found")
                cantDoIt("tab row not found")
                done()
                return@read
            }
            tap(shot.screenX(SEARCH_X), shot.screenY(tab))
            typeQuery(query, 8, cantDoIt, done)
        }
    }

    private fun typeQuery(query: String, attempts: Int, cantDoIt: (String) -> Unit, done: () -> Unit) {
        main.postDelayed({
            // a) WeChat's tree, if readable.
            val box = svc.rootInActiveWindow?.let { findEditText(it) }
            if (box != null) {
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
                }
                box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                main.postDelayed({
                    val entered = box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    log("search typed (tree), enter=$entered")
                    done()
                }, 400)
                return@postDelayed
            }
            // b) Android 13+: Willow types into the box that has the cursor, like a keyboard would.
            //    Used only here, right after he tapped the hobby button. It never reads what is typed.
            if (Build.VERSION.SDK_INT >= 33) {
                val ime = svc.inputMethod
                val ic = ime?.currentInputConnection
                if (ic != null) {
                    ic.commitText(query, 1, null)
                    val action = (ime.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                    ic.performEditorAction(if (action > EditorInfo.IME_ACTION_NONE) action else EditorInfo.IME_ACTION_SEARCH)
                    log("search typed (input method), action=$action")
                    done()
                    return@postDelayed
                }
            }
            if (attempts > 0) typeQuery(query, attempts - 1, cantDoIt, done)
            else { log("search box not found"); cantDoIt("no search box"); done() }
        }, 400)
    }

    /** Reads the screen when the reader is free (it may be busy with a video check). */
    private fun read(whole: Boolean, attempts: Int = 8, done: (ScreenReader.Shot?) -> Unit) {
        if (reader.read(whole, done)) return
        if (attempts > 0) main.postDelayed({ read(whole, attempts - 1, done) }, 250) else done(null)
    }

    private fun tap(x: Float, y: Float) = gesture(Path().apply { moveTo(x, y) }, 60)

    private fun gesture(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun wechatRoots(): List<AccessibilityNodeInfo> =
        svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }.filter { it.packageName == WillowService.WECHAT }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(4) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    private fun findEditText(n: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (n.isEditable || n.className?.toString()?.endsWith("EditText") == true) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findEditText(c, depth + 1)?.let { return it }
        }
        return null
    }

    private companion object {
        /** WeChat 8.0.69's search button (obfuscated id, android/probe/FINDINGS.md). */
        const val SEARCH_ICON = "com.tencent.mm:id/r0l"
        val TABS = setOf("推荐", "关注", "朋友", "同城")
        /** The search button's place across the top bar, as a fraction of the width. */
        const val SEARCH_X = 0.92f
    }
}
