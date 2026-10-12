package com.willow.probe

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** One screen: is the probe switched on, and a button to the Accessibility settings. */
class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        status = TextView(this).apply { textSize = 24f; gravity = Gravity.CENTER }
        val help = TextView(this).apply {
            textSize = 18f
            text = "这是开发测试工具，只装在测试手机上。\n\n" +
                "1. 点上面的按钮，在「无障碍」里打开 Willow Probe。\n" +
                "2. 打开微信 → 发现 → 视频号，正常滑动几个视频。\n" +
                "3. 电脑上看日志：adb logcat -s WillowProbe"
        }
        val open = Button(this).apply {
            text = "打开无障碍设置"
            textSize = 20f
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true  // Android 15+ draws apps edge-to-edge; keep clear of the status bar
            setPadding(pad, pad * 2, pad, pad)
            addView(status)
            addView(open)
            addView(help)
        })
    }

    override fun onResume() {
        super.onResume()
        val on = ProbeService.running
        status.text = if (on) "✅ Probe 已开启" else "⚪ Probe 未开启"
        status.setTextColor(if (on) Color.rgb(46, 125, 50) else Color.GRAY)
    }
}
