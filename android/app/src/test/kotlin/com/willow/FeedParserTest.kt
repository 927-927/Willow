package com.willow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FeedParser against the layout of a real Channels screenshot (android/probe/build/shots/files/shot-1.png,
 * Honor Magic5 Pro, WeChat 8.0.69, 1312×2848). Positions were measured on the picture shown at
 * 921×2000 and scaled back up, so the boxes look like what ML Kit returns.
 * Run on the PC: android/gradlew -p android :app:testDebugUnitTest
 */
class FeedParserTest {

    private val w = 1312
    private val h = 2848
    private val k = 2848.0 / 2000

    /** A line measured on the 921×2000 preview. */
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) =
        OcrLine(text, (l * k).toInt(), (t * k).toInt(), (r * k).toInt(), (b * k).toInt())

    private val bottomBar = listOf(
        line("阅书明礼知今", 158, 1865, 318, 1897),
        line("+关注", 348, 1863, 420, 1897),
        line("6742", 462, 1905, 525, 1932),
        line("3.5万", 578, 1905, 643, 1932),
        line("1.0万", 697, 1905, 762, 1932),
        line("662", 825, 1905, 870, 1932),
    )

    private val caption = listOf(
        line("世上最好的养生不是名贵补品，而是这9件不花钱的小事", 38, 1697, 884, 1733),
        line("——您做到了几件？#中老年养生 #健康养生 #不…", 38, 1747, 780, 1783),
    )

    private val probeShot = listOf(
        line("12:25", 305, 45, 385, 80),
        line("尤其年纪大了", 105, 770, 810, 890),
        line("以后", 345, 930, 580, 1040),
        line("10:07", 730, 1375, 810, 1410),
        line("弹", 838, 1372, 875, 1412),
        line("合集: 黄帝内经讲养生", 120, 1503, 425, 1538),
        line("第1集", 446, 1505, 515, 1537),
        line("作者提示: 含AI生成内容", 55, 1605, 387, 1640),
    ) + caption + bottomBar

    private fun video(lines: List<OcrLine>): Observation {
        val s = FeedParser.parse(lines.shuffled(java.util.Random(7)), w, h)
        assertTrue("expected a video, got $s", s is Screen.Video)
        return (s as Screen.Video).obs
    }

    @Test fun readsTheProbeScreenshot() {
        val o = video(probeShot)
        assertEquals("阅书明礼知今", o.author)
        assertEquals("世上最好的养生不是名贵补品，而是这9件不花钱的小事——您做到了几件？#中老年养生 #健康养生 #不…", o.caption)
        assertEquals(listOf("合集: 黄帝内经讲养生", "作者提示: 含AI生成内容"), o.labels)
        assertNull(o.promo)
        assertEquals(listOf("尤其年纪大了", "以后"), o.onScreen)
        assertEquals(listOf("6742", "3.5万", "1.0万", "662"), o.counts)
        assertEquals(listOf("#中老年养生", "#健康养生", "#不…"), o.hashtags)
    }

    @Test fun topCommentIsNotTheCaption() {
        val swapped = probeShot - caption.toSet() + listOf(
            line("热评", 38, 1712, 98, 1745),
            line("三伏天养生：初伏7月15日，多喝温水少吃冰", 110, 1712, 860, 1745),
        )
        val o = video(swapped)
        assertEquals("", o.caption)
        assertFalse(o.text.contains("三伏天"))
        // Same video: the numbers match, and the missing caption doesn't count against it.
        assertTrue(o.sameVideoAs(video(probeShot)))
    }

    @Test fun homeFeedWithPromotionCard() {
        val lines = listOf(
            line("关注", 300, 130, 360, 165),
            line("朋友", 420, 130, 480, 165),
            line("推荐", 540, 130, 600, 165),
            line("血管堵了千万别不当回事", 90, 700, 830, 800),
            line("老中医祖传秘方三天通血管", 60, 1520, 520, 1552),
            line("¥99 限时特价", 60, 1560, 300, 1590),
            line("购买", 760, 1532, 840, 1572),
            line("降压药别乱吃，看完你就懂了 #中老年 #养生", 38, 1747, 820, 1783),
            line("本珍养生记", 158, 1865, 300, 1897),
            line("喜欢", 462, 1905, 525, 1932),
            line("推荐", 578, 1905, 643, 1932),
            line("12", 705, 1905, 740, 1932),
            line("评论", 825, 1905, 870, 1932),
        )
        val o = video(lines)
        assertEquals("本珍养生记", o.author)
        assertEquals("降压药别乱吃，看完你就懂了 #中老年 #养生", o.caption)
        assertEquals("老中医祖传秘方三天通血管 ¥99 限时特价 购买", o.promo)
        assertEquals(listOf("血管堵了千万别不当回事"), o.onScreen) // the tabs are not the video's words
        assertEquals(listOf("12"), o.counts)
    }

    @Test fun commentsSheetIsNotAVideo() {
        val lines = listOf(
            line("尤其年纪大了", 105, 300, 810, 400),
            line("662条评论", 360, 760, 560, 800),
            line("说得好，谢谢分享", 140, 900, 600, 935),
            line("23", 850, 905, 880, 930),
            line("我爸也这样", 140, 1050, 400, 1085),
            line("5", 860, 1055, 875, 1080),
            line("说点什么...", 60, 1900, 400, 1940),
        )
        assertTrue(FeedParser.parse(lines, w, h) is Screen.Other)
    }

    @Test fun nextVideoFromTheSameAuthorIsANewVideo() {
        val first = video(probeShot)
        val next = video(probeShot - caption.toSet() - bottomBar.drop(2).toSet() + listOf(
            line("养生先养心，这三件事每天做一做", 38, 1747, 820, 1783),
            line("1203", 462, 1905, 525, 1932),
            line("2.1万", 578, 1905, 643, 1932),
            line("8866", 697, 1905, 762, 1932),
            line("98", 825, 1905, 870, 1932),
        ))
        assertEquals(first.author, next.author)
        assertFalse(next.sameVideoAs(first))
    }

    @Test fun likingTheVideoKeepsItTheSameVideo() {
        val first = video(probeShot)
        val liked = video(probeShot - bottomBar[2] + line("6743", 462, 1905, 525, 1932))
        assertTrue(liked.sameVideoAs(first))
    }

    @Test fun mergeAddsLaterSubtitlesOnce() {
        val a = video(probeShot)
        val b = video(probeShot - probeShot[1] - probeShot[2] + line("血管堵了就晚了", 105, 770, 810, 890))
        val m = a.merge(b).merge(b)
        assertEquals(listOf("尤其年纪大了", "以后", "血管堵了就晚了"), m.onScreen)
        assertTrue(m.text.contains("血管堵了就晚了"))
    }

    /** WeChat 8.0.79 on the test phone (2026-10-24): the exact ML Kit output, [left,top width×height] at 1080×2344. */
    @Test fun countsReadAsOneLine() {
        fun ml(text: String, l: Int, t: Int, w: Int, h: Int) = OcrLine(text, l, t, l + w, t + h)
        val lines = listOf(
            ml("內客仅供参考,如有不适请线下就医", 397, 95, 608, 40),
            ml("AlGC动画科普,无不良引导,请注意辨別", 343, 156, 709, 40),
            ml("胀", 819, 481, 185, 184),
            ml("-我是", 461, 1573, 149, 86),
            ml("我害怕白萝H的顺啊", 235, 1657, 615, 94),
            ml("弹", 969, 1721, 53, 49),
            ml("作者堤示:含A生成内容", 65, 1868, 388, 37),
            ml("热评薏米湿,白萝ト.顺,山楂...消,黄芪..补,姜,.", 61, 1975, 955, 42),
            ml("暖8条回复", 46, 2051, 171, 37),
            ml("凸", 526, 2143, 111, 70),
            ml("老佗养生记+关注", 184, 2186, 289, 36),
            ml("1.7万 1.8万 9773", 543, 2236, 346, 30),
            ml("140", 970, 2237, 49, 25),
        )
        val s = FeedParser.parse(lines, 1080, 2344)
        assertTrue("expected a video, got $s", s is Screen.Video)
        val o = (s as Screen.Video).obs
        assertEquals("老佗养生记", o.author)
        assertEquals(listOf("1.7万", "1.8万", "9773", "140"), o.counts)
        assertEquals(listOf("作者堤示:含A生成内容"), o.labels)
        assertEquals("", o.caption) // only the 热评 and its reply count are showing
        assertFalse(o.text.contains("薏米") || o.text.contains("条回复"))
        assertTrue(o.onScreen.contains("我害怕白萝H的顺啊"))
    }

    /** WeChat 8.0.79: the 合集 icon read as a letter, the play time glued to the 🎧 and 弹 buttons. */
    @Test fun collectionIconAndPlayerButtons() {
        val lines = probeShot - probeShot[3] - probeShot[4] - probeShot[5] - probeShot[6] + listOf(
            line("11:37 6) 弹", 640, 1375, 875, 1412),
            line("D合集,黄帝内经讲养生第1集", 60, 1503, 515, 1538),
        )
        val o = video(lines)
        assertEquals(listOf("D合集,黄帝内经讲养生第1集", "作者提示: 含AI生成内容"), o.labels)
        assertEquals(listOf("尤其年纪大了", "以后"), o.onScreen)
    }

    @Test fun ocrNoiseStillMatches() {
        assertTrue(FeedParser.alike("阅书明礼知今", "阅书明礼知"))
        assertTrue(FeedParser.alike("世上最好的养生不是名贵补品", "世上最好的养生不是名贵补晶"))
        assertFalse(FeedParser.alike("本珍养生记", "钓友老王"))
    }
}
