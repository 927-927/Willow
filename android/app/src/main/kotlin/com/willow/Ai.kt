package com.willow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** The AI's answer: label + a short explanation written for Grandpa (the JSON asked for in ai_system.txt). */
class AiAnswer(
    val label: String,
    val confidence: Double,
    val claim: String,
    val truth: String,
    val action: String,
    val reason: String,
    val ms: Long,
)

/**
 * The AI tier (spec #3), the phone's version of askAi() in prototype-web/server.mjs, with the same
 * prompt (shared/prompts/ai_system.txt). Called only for unclear videos, and only with the text
 * read off the video: author, caption, labels, burned-in words, product card. Never the screenshot,
 * never anything about Grandpa.
 *
 * DeepSeek (deepseek-flash) by default, or Qwen (百炼); both speak the OpenAI chat API. The provider and
 * key come from prototype-web/.env at build time (see app/build.gradle.kts). No key: rules only.
 */
class Ai(ctx: Context) {

    val available: Boolean get() = BuildConfig.AI_KEY.isNotEmpty()
    val model: String get() = BuildConfig.AI_MODEL

    private val prompt = ctx.assets.open("ai_system.txt").bufferedReader().use { it.readText() }.trim()
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    /** Same video, same words → same answer, without asking again. Memory only, small, cleared on leaving Channels. */
    private val cache = object : LinkedHashMap<String, AiAnswer>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiAnswer>) = size > 24
    }

    /** Calls [done] on the main thread with an answer, or null + the reason it failed. */
    fun ask(obs: Observation, ruleHints: List<String>, done: (AiAnswer?, String?) -> Unit) {
        if (!available) return done(null, "no key")
        val user = listOf(
            "作者：${obs.author}",
            "标题：${obs.caption}",
            "话题：${obs.hashtags.joinToString(" ")}",
            "画面大字：${obs.onScreen.joinToString(" / ")}",
            "平台标签：${obs.labels.joinToString(" / ").ifEmpty { "无" }}",
            "商品：${obs.promo ?: "无"}",
            "规则引擎命中的信号（仅供参考）：${ruleHints.joinToString("、").ifEmpty { "无" }}",
        ).joinToString("\n")
        cache[user]?.let { return done(it, null) }

        worker.execute {
            val result = runCatching { post(user) }
            main.post {
                result.onSuccess { cache[user] = it; done(it, null) }
                    .onFailure { done(null, it.message ?: it.javaClass.simpleName) }
            }
        }
    }

    fun clearCache() = cache.clear()

    private fun post(user: String): AiAnswer {
        val started = SystemClock.uptimeMillis()
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.1)
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", user)))
        // No "thinking" first: it would keep him waiting several seconds in front of a muted video.
        // Each provider has its own switch for it.
        if (BuildConfig.AI_PROVIDER == "qwen") body.put("enable_thinking", false)
        else body.put("thinking", JSONObject().put("type", "disabled"))

        val conn = URL("${BuildConfig.AI_BASE_URL}/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 8000 // he is waiting with the sound off; after this the rules decide
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.AI_KEY}")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) error("HTTP $code ${text.take(200)}")

            val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
                .trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            if (content.isEmpty()) error("empty answer") // DeepSeek's JSON mode can rarely return nothing
            val o = JSONObject(content)
            val label = o.optString("label")
            if (label !in LABELS) error("unexpected label: $label")
            return AiAnswer(
                label, o.optDouble("confidence", 0.0), o.optString("claim"), o.optString("truth"),
                o.optString("action"), o.optString("reason"), SystemClock.uptimeMillis() - started,
            )
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** For Grandpa's screens: the AI-written card says who wrote it. */
        val NAME_ZH = if (BuildConfig.AI_PROVIDER == "qwen") "通义千问" else "DeepSeek"
        private val LABELS = setOf("predatory_health", "dubious_health", "legit_health", "hobby", "other")
    }
}
