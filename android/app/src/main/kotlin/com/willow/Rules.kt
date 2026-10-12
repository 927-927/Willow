package com.willow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Willow can read from one Channels video card: VideoObservation in shared/classifier.js.
 * [labels] holds WeChat's on-screen labels (AI label, 作者提示, 合集); [promo] is a promotion card
 * with a button (领取 / 购买…), which counts as a product card.
 */
data class Observation(
    val author: String,
    val caption: String,
    val labels: List<String>,
    val promo: String?,
    /** Platform verification type, e.g. "medical". WeChat's badge isn't readable yet, so null on the phone. */
    val verified: String? = null,
) {
    /** observationText() in classifier.js. */
    val text: String
        get() = listOf(author, caption, labels.joinToString(" "), promo ?: "")
            .filter { it.isNotBlank() }.joinToString(" ")

    /** WeChat swaps the caption for the top comment after a while, so a missing caption still matches. */
    fun sameVideoAs(other: Observation?): Boolean =
        other != null && author == other.author &&
            (caption.isEmpty() || other.caption.isEmpty() || caption == other.caption)
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
    /** "library" (curated), "generic" (scare-then-sell) or null. "ai" comes with Qwen. */
    val cardSource: String?,
    val hobby: Hobby?,
)

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

    /** classifyWithRules() + pickDebunk() + finishDecision() (rules tier only; Qwen comes later). */
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
        fun json(ctx: Context, name: String) = JSONObject(ctx.assets.open(name).bufferedReader().use { it.readText() })
        fun JSONArray.strings() = List(length()) { getString(it) }
        fun JSONArray.objects() = List(length()) { getJSONObject(it) }
        fun card(o: JSONObject) = Card(
            o.getString("id"), o.optString("icon", "🧾"), o.getString("myth_zh"),
            o.getString("truth_zh"), o.getString("action_zh"), o.optString("source_zh", ""),
        )
    }
}
