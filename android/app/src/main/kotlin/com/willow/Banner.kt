package com.willow

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout

/**
 * A gentle note under the Channels top bar. The video (or page) underneath keeps working: touches
 * outside the note go straight through to WeChat. Used for:
 *  - the tip (spec #5): yellow, the video keeps playing with sound, closed with 知道了
 *  - the purchase pause (spec #6): 再想想 and 立即购买 both work
 *  - short hints when Willow can't do something itself (no silent no-ops)
 */
class Banner(private val ctx: Context) {

    class Button(val text: String, val style: ButtonStyle, val onClick: () -> Unit)

    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var view: View? = null

    val showing get() = view != null

    /** Every button closes the note, then does its own thing. */
    fun show(title: String, text: String, buttons: List<Button>) {
        hide()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            buttons.forEachIndexed { i, b ->
                val btn = ctx.bigButton(b.text, b.style) { hide(); b.onClick() }.apply {
                    textSize = 19f
                    minHeight = ctx.dp(48)
                    setPadding(ctx.dp(22), 0, ctx.dp(22), 0)
                }
                addView(btn, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = ctx.dp(10) })
            }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.tipBg, ctx.dp(16), ctx.dp(2), Palette.tipLine)
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            addView(ctx.label(title, 17f, Palette.tipInk, bold = true))
            addView(ctx.label(text, 21f, Palette.tipInk), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(4) })
            addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(8) })
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

    /** One 知道了 button. */
    fun note(title: String, text: String) = show(title, text, listOf(Button("知道了", ButtonStyle.PRIMARY) {}))

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
