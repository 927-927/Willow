package com.willow

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One line of text that OCR found in a screenshot, in screenshot pixels. */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    val height get() = bottom - top
}

/** What one screenshot of WeChat shows. */
sealed interface Screen {
    /** A Channels video, with its text sorted into fields. */
    data class Video(val obs: Observation) : Screen
    /** Anything else: the comments sheet, a profile, a menu, a loading screen. */
    data class Other(val why: String) : Screen
}

/**
 * Sorts the OCR lines of a Channels screenshot into an [Observation].
 *
 * WeChat hides its screen from accessibility services (android/probe/FINDINGS.md), so Willow reads
 * the screenshot instead. Layout of WeChat 8.0.69, top to bottom:
 *   video picture (big burned-in words, subtitles, danmaku)
 *   合集 / 作者提示 / AI labels, link or product card     ← left-aligned pills
 *   caption + #hashtags (2 lines), swapped for 热评 after a few seconds
 *   bottom bar: avatar · author · +关注 · 👍 6742 · ↗ 3.5万 · ♡ 1.0万 · 💬 662
 * The bottom bar's row of counts is the anchor: no counts row, no video. All distances are measured
 * in units of the counts' text height, so the parser works on any screen size.
 *
 * Plain Kotlin (no Android classes), so it is unit-tested on the PC: FeedParserTest.
 */
object FeedParser {
    private val COUNT = Regex("""^[\d.,]+\s*[万千wWkK]?\+?$""")
    private val COUNT_IN_ROW = Regex("""\d[\d.,]*\s*[万千wWkK]?\+?""")
    /** The reply count under a 热评 ("8条回复"): part of the comment. */
    private val REPLIES = Regex("""\d+\s*条回复""")
    /** WeChat's labels, also when OCR misreads a character (作者堤示, 含A生成内容) or reads the 合集 icon as a letter (D合集). */
    private val LABEL_FUZZY = Regex("""^作者.?示|A[Il1]?生成|生成内容|^.{0,2}合集[:：,，]""")
    /** The play time with the 🎧 and 弹 buttons beside it, read as one line ("11:37 6) 弹", "T1:34C弹"). */
    private val PLAYER_BUTTONS = Regex("""^.{0,12}\d.{0,6}弹$""")
    private val TIME = Regex("""^\d{1,2}[:：]\d{2}(\s*/\s*\d{1,2}[:：]\d{2})?$""")
    private val EPISODE = Regex("""^第\s*\d+\s*集$""")
    private val PRICE = Regex("""[¥￥]\s*\d""")
    private val FOLLOW = Regex("""\s*[+＋十]?\s*(关注|已关注|回关)$""")
    /** Promoted cards show these words in the bottom bar instead of numbers. */
    private val BAR_WORDS = setOf("喜欢", "推荐", "分享", "转发", "收藏", "评论", "赞", "点赞")
    /** WeChat's own buttons and tabs: never part of what the video says. */
    private val UI_WORDS = setOf(
        "关注", "已关注", "朋友", "推荐", "同城", "热门", "直播", "热评", "作者有回复", "弹", "弹幕",
        "展开", "收起", "全文", "发弹幕", "说点什么", "说点什么...", "写评论", "搜索", "返回",
    )
    /** A link card with one of these buttons is a promotion (location cards have no button). */
    private val BUTTONS = setOf(
        "领取", "去领取", "免费领", "免费领取", "购买", "去购买", "立即购买", "抢购", "立即抢购", "下单", "去下单",
        "咨询", "立即咨询", "了解更多", "去看看", "预约", "立即预约", "报名", "进店", "加微信",
    )
    private val LABEL_START = listOf("合集", "作者提示", "可能含有AI", "可能含AI", "含AI生成", "内容由AI")

    fun parse(lines: List<OcrLine>, w: Int, h: Int): Screen {
        val all = lines.map { it.copy(text = it.text.trim()) }.flatMap { splitCounts(it) }.filter { meaningful(it.text) }

        // 1. The counts row at the bottom: at least two counts on one row, spread across the screen.
        val counts = all.filter { it.cy > h * 0.80 && (COUNT.matches(it.text) || it.text in BAR_WORDS) }
        val row = counts.map { c -> counts.filter { abs(it.cy - c.cy) < max(c.height, h / 100) } }
            .filter { r -> r.size >= 2 && r.maxOf { it.right } - r.minOf { it.left } > w * 0.3 }
            .maxByOrNull { it.size }
            ?: return Screen.Other("no counts row")
        val unit = row.map { it.height }.sorted()[row.size / 2].coerceAtLeast(h / 200)
        val rowY = row.map { it.cy }.average().toInt()
        val barTop = rowY - 3 * unit
        val firstAction = row.minOf { it.left }

        // The numbers identify the video: they stay put while it plays and differ between videos.
        val countText = row.filter { COUNT.matches(it.text) }.sortedBy { it.left }.map { it.text }

        // 2. Author: the name left of the follow button and the counts.
        val bar = all.filter { it.cy in barTop..(rowY + 2 * unit) && it !in row }
        val author = bar.filter { it.right <= firstAction + unit && it.left < w * 0.6 }
            .sortedBy { it.left }
            .map { it.text.replace(FOLLOW, "").removePrefix("@").trim() }
            .firstOrNull { it.isNotEmpty() && it !in UI_WORDS && !COUNT.matches(it) } ?: ""

        // 3. Above the bar: labels, a link/product card, the caption (or 热评), burned-in subtitles.
        val above = all.filter { it.bottom <= barTop + unit / 2 && !isNoise(it.text) }.sortedBy { it.cy }
        // The 热评 tag may be read on its own ("热评") or glued to the comment ("热评 三伏天…").
        val commentRows = all.filter { it.text.startsWith("热评") && it.bottom <= barTop + unit / 2 }
        fun isComment(l: OcrLine) =
            REPLIES.containsMatchIn(l.text) || commentRows.any { abs(it.cy - l.cy) < max(it.height, l.height) * 0.6 }
        fun isLabel(l: OcrLine) = LABEL_START.any { l.text.startsWith(it) } || LABEL_FUZZY.containsMatchIn(l.text)

        val used = HashSet<OcrLine>()
        above.filter { isComment(it) }.forEach { used += it } // other viewers' words, not the video's

        val labels = above.filter { it !in used && isLabel(it) }
        used += labels
        // A label's leftovers on the same row (e.g. 第1集 read as its own line).
        val labelText = labels.map { l ->
            (listOf(l) + above.filter { it !in used && abs(it.cy - l.cy) < l.height / 2 }.also { used += it })
                .sortedBy { it.left }.joinToString(" ") { it.text }
        }

        // Promotion: a button word (or a short price tag), plus the card text on its left.
        val promoAnchor = above.firstOrNull {
            it !in used && (it.text in BUTTONS || (it.text.length <= 12 && PRICE.containsMatchIn(it.text)))
        }
        val promo = promoAnchor?.let { b ->
            val reach = max(b.height, unit) * 1.2
            val card = above.filter { it !in used && it.cy.toDouble() in (b.top - reach)..(b.bottom + reach) && it.left <= b.left }
            used += card
            ((card - b).sortedBy { it.cy } + b).joinToString(" ") { it.text }
        }

        // Caption: left-aligned, normal-sized lines stacked right above the bar.
        val captionLines = ArrayList<OcrLine>()
        var floor = barTop + unit / 2
        for (l in above.reversed()) {
            if (l in used) { if (isComment(l)) continue else break }
            val fits = l.left < w * 0.12 && l.height <= unit * 2.2 && floor - l.bottom <= max(l.height, unit) * 3
            if (!fits) break
            captionLines += l
            floor = l.top
        }
        used += captionLines
        val caption = captionLines.reversed().joinToString("") { it.text }

        // 4. Everything else in the picture: burned-in titles and subtitles (and some danmaku).
        val onScreen = all.filter { it !in used && it !in row && it !in bar && it.bottom <= barTop + unit / 2 }
            .filter { !isNoise(it.text) && !(it.cy < h * 0.15 && it.text.length <= 4) } // top tabs, icons
            .sortedBy { it.cy }
            .map { it.text }
            .distinct()

        return Screen.Video(Observation(author, caption, labelText, promo, onScreen = onScreen, counts = countText))
    }

    /**
     * OCR often reads neighbouring counts as one line ("1.7万 1.8万 9773", WeChat 8.0.79): split it
     * into one line per count, sharing out the box by character position.
     */
    private fun splitCounts(l: OcrLine): List<OcrLine> {
        val parts = COUNT_IN_ROW.findAll(l.text).toList()
        if (parts.size < 2 || l.text.replace(COUNT_IN_ROW, "").isNotBlank()) return listOf(l)
        val perChar = (l.right - l.left).toDouble() / l.text.length
        return parts.map { m ->
            l.copy(text = m.value.trim(), left = l.left + (m.range.first * perChar).toInt(),
                right = l.left + ((m.range.last + 1) * perChar).toInt())
        }
    }

    /** Words the OCR found that say nothing: counts, clock times, tab names, single stray glyphs. */
    private fun isNoise(t: String) =
        t in UI_WORDS || COUNT.matches(t) || TIME.matches(t) || EPISODE.matches(t) || (t.length <= 12 && PLAYER_BUTTONS.matches(t))

    private fun meaningful(t: String) = t.count { it.isLetterOrDigit() } >= 2 || COUNT.matches(t) || t in BAR_WORDS

    // ---- Fuzzy text comparison: OCR may read the same words slightly differently each time ----

    /** 0..1: longest common subsequence over the longer length. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val x = a.take(160)
        val y = b.take(160)
        var prev = IntArray(y.length + 1)
        var cur = IntArray(y.length + 1)
        for (i in 1..x.length) {
            for (j in 1..y.length) {
                cur[j] = if (x[i - 1] == y[j - 1]) prev[j - 1] + 1 else max(prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.length].toDouble() / max(x.length, y.length)
    }

    /** True when [a] and [b] are probably the same text read twice (one may be cut short). */
    fun alike(a: String, b: String, threshold: Double = 0.6): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        val shorter = min(a.length, b.length)
        val s = similarity(a, b)
        // A caption cut off with "…" in one frame still matches its longer self.
        return s >= threshold || (shorter >= 6 && s * max(a.length, b.length) / shorter >= 0.85)
    }
}
