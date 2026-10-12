package com.willow

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * The gentle tip (spec #5): a yellow banner under the Channels top bar while the video keeps
 * playing with sound. Touches outside the banner go straight through to WeChat.
 */
class TipBanner(private val ctx: Context) {

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(text: String) {
        hide()
        val close = ctx.bigButton("知道了", ButtonStyle.PRIMARY) { hide() }.apply {
            textSize = 19f
            minHeight = ctx.dp(48)
            setPadding(ctx.dp(22), 0, ctx.dp(22), 0)
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.tipBg, ctx.dp(16), ctx.dp(2), Palette.tipLine)
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            addView(ctx.label("💡 Willow 小提示", 17f, Palette.tipInk, bold = true))
            addView(ctx.label(text, 21f, Palette.tipInk), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(close, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = ctx.dp(8) })
        }
        val frame = LinearLayout(ctx).apply {
            setPadding(ctx.dp(12), 0, ctx.dp(12), 0)
            addView(card, LinearLayout.LayoutParams(-1, -2))
        }
        wm.addView(frame, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = ctx.dp(96) // below the Channels top bar (返回 · 关注 / 推荐 · 搜索)
        })
        view = frame
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
