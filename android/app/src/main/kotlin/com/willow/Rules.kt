package com.willow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Willow can read from one Channels video: VideoObservation in shared/classifier.js.
 * Filled from a screenshot by [FeedParser]. [labels] holds WeChat's labels (AI label, 作者提示, 合集);
 * [promo] is a promotion card with a button (领取 / 购买…) or a price, which counts as a product card;
 * [onScreen] is the text burned into the picture (big titles, subtitles of the voice).
 * [counts] (likes, shares, …) only tells videos apart; it is never classified or sent anywhere.
 */
data class Observation(
    val author: String,
    val caption: String,
    val labels: List<String>,
    val promo: String?,
    /** Platform verification type, e.g. "medical". WeChat's badge isn't readable yet, so null on the phone. */
    val verified: String? = null,
    val onScreen: List<String> = emptyList(),
    val counts: List<String> = emptyList(),
) {
    /** observationText() in classifier.js. */
    val text: String
        get() = listOf(author, caption, labels.joinToString(" "), onScreen.joinToString(" "), promo ?: "")
            .filter { it.isNotBlank() }.joinToString(" ")

    val hashtags: List<String> get() = HASHTAG.findAll(caption).map { it.value }.toList()

    /**
     * Is this still the same video? OCR reads the same words slightly differently each time, and WeChat
     * swaps the caption for the top comment after a few seconds, so this compares loosely:
     * the like/share/comment numbers first, then the author, then the caption.
     */
    fun sameVideoAs(other: Observation?): Boolean {
        if (other == null) return false
        val a = counts
        val b = other.counts
        if (a.size >= 2 && a.size == b.size) {
            val equal = a.indices.count { a[it] == b[it] }
            if (equal >= 2 && equal >= a.size - 1) return true // one number may tick up while he watches
            if (equal == 0) return false
        }
        val authors = author.isNotEmpty() && other.author.isNotEmpty()
        if (authors && !FeedParser.alike(author, other.author)) return false
        if (caption.isNotEmpty() && other.caption.isNotEmpty()) return FeedParser.alike(caption, other.caption, 0.5)
        return authors
    }

    /** Adds what a later screenshot of the same video shows (new subtitles, a caption that was hidden). */
    fun merge(later: Observation): Observation {
        val words = onScreen.toMutableList()
        for (t in later.onScreen) {
            if (words.sumOf { it.length } > MAX_SCREEN_CHARS) break
            if (words.none { FeedParser.alike(it, t, 0.8) }) words += t
        }
        return copy(
            author = author.ifEmpty { later.author },
            caption = caption.ifEmpty { later.caption },
            labels = (labels + later.labels.filter { l -> labels.none { FeedParser.alike(it, l, 0.8) } }),
            promo = promo ?: later.promo,
            onScreen = words,
            counts = later.counts.ifEmpty { counts },
        )
    }

    private companion object {
        val HASHTAG = Regex("""#[^#\s]+""")
        const val MAX_SCREEN_CHARS = 400
    }
}

enum class Level { INTERCEPT, TIP, ALLOW }

class Match(val id: String, val labelZh: String, val phrases: List<String>)

class Card(
    val id: String, val icon: String, val myth: String, val truth: String, val action: String, val source: String,
)

class Hobby(val id: String, val labelZh: String, val icon: String, val invite: String, val phrases: List<String>)

class Decision(
    val level: Level,
    val label: String,
    val score: Double,
    val matched: List<Match>,
    val card: Card?,
    /** "library" (curated), "ai" (written by the AI for this video), "generic" (scare-then-sell) or null. */
    val cardSource: String?,
    val hobby: Hobby?,
    /** "rules", or "ai" when the AI's label replaced the rules' level. */
    val tier: String = "rules",
    val ai: AiAnswer? = null,
) {
    /** Selling something: the purchase pause (spec #6) applies to its product page. */
    val selling: Boolean get() = matched.any { it.id == "sales" || it.id == "hasProduct" }
}

/**
 * Kotlin port of shared/classifier.js, reading the same JSON files (packed as assets by
 * android/build-offline.sh). Keep the two in step: the web prototype is the reference.
 */
class Rules(ctx: Context) {

    private class Group(val id: String, val labelZh: String, val weight: Double, val phrases: List<String>)
    private class Combo(val labelZh: String, val anyOf: List<String>, val andAnyOf: List<String>, val bonus: Double)

    private val scam = json(ctx, "health_scam_rules.json")
    private val debunks = json(ctx, "debunks.json")

    private val groups = scam.getJSONObject("groups").let { g ->
        g.keys().asSequence().map { id ->
            val o = g.getJSONObject(id)
            Group(id, o.getString("label_zh"), o.getDouble("weight"), o.getJSONArray("phrases").strings())
        }.toList()
    }
    private val product = scam.getJSONObject("features").getJSONObject("hasProduct")
    private val combos = scam.getJSONArray("combos").objects().map {
        Combo(it.getString("label_zh"), it.getJSONArray("any_of").strings(), it.getJSONArray("and_any_of").strings(), it.getDouble("bonus"))
    }
    private val topic: List<String>? = scam.optJSONObject("topic")?.getJSONArray("phrases")?.strings()
    private val verifiedTypes = scam.optJSONObject("allow")?.optJSONArray("verifiedTypes")?.strings() ?: emptyList()
    private val interceptAt = scam.getJSONObject("thresholds").getDouble("intercept")
    private val tipAt = scam.getJSONObject("thresholds").getDouble("tip")

    private val cards = debunks.getJSONArray("cards").objects().map { card(it) to it.getJSONArray("match").strings() }
    val genericCard = card(debunks.getJSONObject("generic"))
    val tipText: String = debunks.getJSONObject("tip_generic").getString("text_zh")

    val hobbies = json(ctx, "hobby_rules.json").getJSONArray("hobbies").objects().map {
        Hobby(it.getString("id"), it.getString("label_zh"), it.getString("icon"), it.getString("invite_zh"), it.getJSONArray("phrases").strings())
    }

    /** classifyWithRules() + pickDebunk() + finishDecision() (rules tier only; the AI comes later). */
    fun decide(obs: Observation): Decision {
        val text = obs.text
        if (obs.verified != null && obs.verified in verifiedTypes) {
            return Decision(Level.ALLOW, "verified_medical", 0.0, emptyList(), null, null, matchHobby(text))
        }

        val matched = mutableListOf<Match>()
        var score = 0.0
        for (g in groups) {
            val hits = g.phrases.filter { text.contains(it) }
            if (hits.isNotEmpty()) {
                matched += Match(g.id, g.labelZh, hits)
                score += g.weight
            }
        }
        if (obs.promo != null) {
            matched += Match("hasProduct", product.getString("label_zh"), listOf(obs.promo))
            score += product.getDouble("weight")
        }
        val hit = matched.map { it.id }.toSet()
        for (c in combos) {
            if (c.anyOf.any { it in hit } && c.andAnyOf.any { it in hit }) {
                matched += Match("combo", c.labelZh, emptyList())
                score += c.bonus
            }
        }
        score = minOf(1.0, Math.round(score * 100) / 100.0)

        // Health-topic gate: fear or sales words alone (a fishing-gear sale) never trigger anything.
        val onTopic = topic == null || topic.any { text.contains(it) }
        val level = when {
            !onTopic -> Level.ALLOW
            score >= interceptAt -> Level.INTERCEPT
            score >= tipAt -> Level.TIP
            else -> Level.ALLOW
        }
        val label = when {
            !onTopic -> "off_topic"
            level == Level.INTERCEPT -> "predatory_health"
            level == Level.TIP -> "dubious_health"
            else -> "other"
        }

        val curated = pickCard(text)
        val card = curated ?: if (level == Level.INTERCEPT) genericCard else null
        val source = when {
            curated != null -> "library"
            card != null -> "generic"
            else -> null
        }
        return Decision(level, label, score, matched, card, source, matchHobby(text))
    }

    /** Spec #3: ask the AI only when unsure: tip-level videos, or intercepts with no curated card. */
    fun unsure(d: Decision): Boolean =
        d.label != "verified_medical" && (d.level == Level.TIP || (d.level == Level.INTERCEPT && d.cardSource != "library"))

    /** decide() + finishDecision() in prototype-web/public/app.js: the AI's label overrides the rules. */
    fun withAi(d: Decision, ai: AiAnswer): Decision {
        val level = when (ai.label) {
            "predatory_health" -> Level.INTERCEPT
            "dubious_health" -> Level.TIP
            else -> Level.ALLOW
        }
        val curated = d.card.takeIf { d.cardSource == "library" }
        val (card, source) = when {
            curated != null -> curated to "library"
            level == Level.ALLOW -> null to null
            ai.claim.isNotBlank() && ai.truth.isNotBlank() ->
                Card("ai", "💬", ai.claim, ai.truth, ai.action.ifBlank { "身体的事，先问问医生" }, AI_SOURCE) to "ai"
            level == Level.INTERCEPT -> genericCard to "generic"
            else -> null to null
        }
        return Decision(level, ai.label, d.score, d.matched, card, source, d.hobby, "ai", ai)
    }

    fun cardById(id: String?): Card? = cards.firstOrNull { it.first.id == id }?.first

    /** pickDebunk(): needs ≥ 2 distinct keyword hits, so one shared word doesn't pull in an off-topic card. */
    private fun pickCard(text: String): Card? {
        var best: Card? = null
        var bestHits = 1
        for ((card, keys) in cards) {
            val hits = keys.count { text.contains(it) }
            if (hits > bestHits) { best = card; bestHits = hits }
        }
        return best
    }

    /** matchHobby(). */
    private fun matchHobby(text: String): Hobby? {
        var best: Hobby? = null
        var bestHits = 0
        for (h in hobbies) {
            val hits = h.phrases.count { text.contains(it) }
            if (hits > bestHits) { best = h; bestHits = hits }
        }
        return best
    }

    private companion object {
        val AI_SOURCE = "这段解释由 AI（${Ai.NAME_ZH}）生成，仅供参考。身体的事，请问医生。"
        fun json(ctx: Context, name: String) = JSONObject(ctx.assets.open(name).bufferedReader().use { it.readText() })
        fun JSONArray.strings() = List(length()) { getString(it) }
        fun JSONArray.objects() = List(length()) { getJSONObject(it) }
        fun card(o: JSONObject) = Card(
            o.getString("id"), o.optString("icon", "🧾"), o.getString("myth_zh"),
            o.getString("truth_zh"), o.getString("action_zh"), o.optString("source_zh", ""),
        )
    }
}
