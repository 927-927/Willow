package com.willow

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The family's setup screen: is Willow on, open the Accessibility settings, see a sample. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var demo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(24)

        val title = label("🌳 柳荫 Willow", 32f, Palette.green, bold = true)
        status = label("", 22f, Palette.ink, bold = true)
        details = label("", 17f, Palette.muted)
        val open = bigButton("打开无障碍设置", ButtonStyle.PRIMARY) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        demo = bigButton("看看效果", ButtonStyle.SECONDARY) { WillowService.instance?.demo() }
        val about = label(
            "在微信视频号里，遇到“先吓人、再卖药”的视频时：\n" +
                "· 先把声音关掉，再用大字和慢慢的声音一起看看这个说法对不对\n" +
                "· 每次都可以选「继续看原视频」\n" +
                "· 在手机上截图识字，截图用完就丢，不保存、不上传\n" +
                (if (BuildConfig.AI_KEY.isNotEmpty()) "· 拿不准的视频，只把屏幕上的文字发给 AI（${Ai.NAME_ZH}）帮忙判断\n" else "") +
                "· 不记录看过什么，不告诉任何人\n\n" +
                "设置方法：点「打开无障碍设置」→ 已下载的应用 → 柳荫 Willow → 打开。",
            19f, Palette.navInk,
        )

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
            addView(title)
            addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            addView(open, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(demo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.paper)
            fitsSystemWindows = true
            addView(column)
        })
    }

    override fun onResume() {
        super.onResume()
        val on = WillowService.instance != null
        status.text = if (on) "✅ 保护中" else "⚪ 还没打开"
        status.setTextColor(if (on) Palette.green else Palette.muted)
        status.gravity = Gravity.START
        details.text = "读屏：截图 + 手机上识字\n" +
            (if (BuildConfig.AI_KEY.isNotEmpty()) "AI 帮忙判断：已开启（${Ai.NAME_ZH} ${BuildConfig.AI_MODEL}）"
            else "AI 帮忙判断：没有设置，只用规则") +
            "\n版本 ${BuildConfig.VERSION_NAME}"
        demo.setUsable(on)
    }
}
