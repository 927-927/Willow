package com.willow

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Things Willow does in WeChat, only ever because he tapped a Willow button or swiped:
 * change the video, tell WeChat "不感兴趣", search for his hobby.
 */
class WeChatActions(private val svc: AccessibilityService, private val log: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    private val screen get() = svc.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** A normal finger swipe: up = next video, down = previous video. */
    fun swipe(up: Boolean) {
        val (w, h) = screen
        val x = w / 2f
        val (from, to) = if (up) h * 0.70f to h * 0.25f else h * 0.30f to h * 0.75f
        val path = Path().apply { moveTo(x, from); lineTo(x, to) }
        gesture(path, 220)
    }

    /**
     * 换一个视频: long-press the video, tap 不感兴趣 if WeChat offers it, then move on.
     * If the menu isn't there (WeChat changed it), it just swipes to the next video.
     */
    fun notInterestedThenNext(isSameVideo: () -> Boolean) {
        val (w, h) = screen
        gesture(Path().apply { moveTo(w / 2f, h * 0.45f) }, 900)
        main.postDelayed({
            val item = findText("不感兴趣")
            if (item == null) {
                log("不感兴趣 not found after long-press; swiping")
                if (topWindowIsMenu()) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                main.postDelayed({ swipe(up = true) }, 300)
                return@postDelayed
            }
            val clicked = CardReader.clickableSelfOrParent(item)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            log("不感兴趣 clicked=$clicked")
            // WeChat may move on by itself; if the same video is still there, swipe.
            main.postDelayed({ if (isSameVideo()) swipe(up = true) }, 900)
        }, 1500)
    }

    /** Hobby invite: open Channels search and look for 钓鱼 / 种菜 / 下象棋. */
    fun searchFor(query: String, root: AccessibilityNodeInfo?, screenH: Int) {
        val button = root?.let { CardReader.searchButton(it, screenH) }
        if (button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) != true) {
            log("search button not found")
            return
        }
        main.postDelayed({ typeQuery(query, attempts = 6) }, 700)
    }

    private fun typeQuery(query: String, attempts: Int) {
        val box = svc.rootInActiveWindow?.let { findEditText(it) }
        if (box == null) {
            if (attempts > 0) main.postDelayed({ typeQuery(query, attempts - 1) }, 400) else log("search box not found")
            return
        }
        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        main.postDelayed({
            val entered = Build.VERSION.SDK_INT >= 30 &&
                box.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            log("search typed, enter=$entered")
        }, 400)
    }

    private fun gesture(path: Path, durationMs: Long) {
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun wechatRoots(): List<AccessibilityNodeInfo> =
        svc.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }.filter { it.packageName == WillowService.WECHAT }

    private fun findText(text: String): AccessibilityNodeInfo? =
        wechatRoots().firstNotNullOfOrNull { r ->
            r.findAccessibilityNodeInfosByText(text).firstOrNull { it.text?.toString()?.trim() == text && it.isVisibleToUser }
        }

    /** True when a WeChat popup (not the video feed) is on top. */
    private fun topWindowIsMenu(): Boolean {
        val top = svc.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        val root = top.root ?: return false
        return root.packageName == WillowService.WECHAT && CardReader.read(root, screen.second) == null
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
}
