package com.willow

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Reads the Channels video card that is on screen right now.
 *
 * WeChat's view ids are obfuscated. These match WeChat 8.0.69 (android/probe/FINDINGS.md);
 * after a WeChat update, re-run the probe. If the ids stop matching, Willow falls back to all
 * visible text in the lower part of the screen, so it degrades instead of going blind.
 */
object CardReader {
    private const val ID = "com.tencent.mm:id/"
    private const val PLAYER = "fs3"
    private const val AUTHOR = "a8p"
    private const val CAPTION = "o45"
    private val LABELS = listOf("lob", "ghj", "egg") // AI label, 作者提示, 合集
    private const val LINK_TITLE = "edj"
    private const val LINK_SUBTITLE = "edl"
    private const val LINK_BUTTON = "eew"
    private const val SEARCH_ICON = "r0l"

    /** Null when no video is on screen (Channels profile page, a menu, …). */
    fun read(root: AccessibilityNodeInfo, screenH: Int): Observation? {
        if (visible(root, PLAYER, screenH) == null && !hasVideoSurface(root, screenH)) return null
        val author = text(root, AUTHOR, screenH)
        val caption = text(root, CAPTION, screenH)
        if (author.isEmpty() && caption.isEmpty()) return fallback(root, screenH)

        val labels = LABELS.map { text(root, it, screenH) }.filter { it.isNotEmpty() }
        // The link card shows either a location or a promotion; only a button (领取 / 购买…) makes it a promotion.
        val button = text(root, LINK_BUTTON, screenH)
        val promo = if (button.isEmpty()) null else
            listOf(text(root, LINK_TITLE, screenH), text(root, LINK_SUBTITLE, screenH), button)
                .filter { it.isNotEmpty() }.joinToString(" ")
        return Observation(author, caption, labels, promo)
    }

    /** Test-phone diagnostics: what the reader can see in one window. */
    fun describe(root: AccessibilityNodeInfo, screenH: Int): String {
        val players = root.findAccessibilityNodeInfosByViewId(ID + PLAYER)
        val authors = root.findAccessibilityNodeInfosByViewId(ID + AUTHOR)
        val vis = players.count { n -> n.isVisibleToUser }
        return "window(children=${root.childCount} players=${players.size}/visible=$vis authors=${authors.size} " +
            "surface=${hasVideoSurface(root, screenH)} h=$screenH)"
    }

    /** The clickable search button in the Channels top bar. */
    fun searchButton(root: AccessibilityNodeInfo, screenH: Int): AccessibilityNodeInfo? =
        visible(root, SEARCH_ICON, screenH)?.let { clickableSelfOrParent(it) }

    fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        repeat(4) {
            if (n?.isClickable == true) return n
            n = n?.parent
        }
        return null
    }

    private fun visible(root: AccessibilityNodeInfo, id: String, screenH: Int): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByViewId(ID + id).firstOrNull { n ->
            val b = Rect().also { n.getBoundsInScreen(it) }
            n.isVisibleToUser && b.height() > 0 && b.top < screenH && b.bottom > 0
        }

    private fun text(root: AccessibilityNodeInfo, id: String, screenH: Int): String =
        visible(root, id, screenH)?.text?.toString()?.trim() ?: ""

    // ---- Fallback when WeChat renamed its ids ----

    private val COUNT = Regex("""^[\d.,]+[万千kKwW]?\+?$""")
    private val UI_WORDS = setOf("关注", "推荐", "朋友", "同城", "热评", "作者有回复", "喜欢", "评论", "分享", "收藏")

    private fun fallback(root: AccessibilityNodeInfo, screenH: Int): Observation {
        val texts = mutableListOf<String>()
        collect(root, screenH, texts, 0)
        return Observation("", texts.joinToString(" "), emptyList(), null)
    }

    private fun collect(n: AccessibilityNodeInfo, screenH: Int, out: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 30) return
        val t = n.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && n.isVisibleToUser && t !in UI_WORDS && !COUNT.matches(t)) {
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.top > screenH * 0.55) out += t // the caption area; skips the top bar and danmaku
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, screenH, out, depth + 1) }
    }

    private fun hasVideoSurface(n: AccessibilityNodeInfo, screenH: Int, depth: Int = 0): Boolean {
        if (depth > 40) return false
        val cls = n.className?.toString() ?: ""
        if ((cls.endsWith("TextureView") || cls.endsWith("SurfaceView")) && n.isVisibleToUser) {
            val b = Rect().also { n.getBoundsInScreen(it) }
            if (b.height() > screenH / 2) return true
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            if (hasVideoSurface(c, screenH, depth + 1)) return true
        }
        return false
    }
}
