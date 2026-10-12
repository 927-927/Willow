package com.willow

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.TextView

/** Colours from prototype-web/public/styles.css (.debunk, .big, .tip). */
object Palette {
    val paper = Color.parseColor("#FBF6EC")
    val ink = Color.parseColor("#2B2416")
    val muted = Color.parseColor("#8A7A5C")
    val green = Color.parseColor("#2F7D4F")
    val greenSoft = Color.parseColor("#E3EFE6")
    val greenInk = Color.parseColor("#1F5A37")
    val line = Color.parseColor("#CDBF9F")
    val dotOff = Color.parseColor("#E2D8C3")
    val navBg = Color.parseColor("#FFFDF8")
    val navInk = Color.parseColor("#4A3F2C")
    val tipBg = Color.parseColor("#FFF8E1")
    val tipInk = Color.parseColor("#3B2F12")
    val tipLine = Color.parseColor("#F0D58A")
}

fun Context.dp(v: Number): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun rounded(fill: Int, radius: Int, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) =
    GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun Context.label(text: String, sp: Float, color: Int = Palette.ink, bold: Boolean = false) = TextView(this).apply {
    this.text = text
    textSize = sp
    setTextColor(color)
    if (bold) typeface = Typeface.DEFAULT_BOLD
    setLineSpacing(0f, 1.3f)
}

enum class ButtonStyle { PRIMARY, SECONDARY, OUTLINE, NAV }

/** A big, plain button (.big / .nav-btn in the prototype). */
fun Context.bigButton(text: String, style: ButtonStyle, onClick: () -> Unit) = TextView(this).apply {
    this.text = text
    gravity = Gravity.CENTER
    typeface = Typeface.DEFAULT_BOLD
    textSize = if (style == ButtonStyle.NAV) 20f else 23f
    minHeight = dp(if (style == ButtonStyle.NAV) 56 else 64)
    setPadding(dp(8), dp(6), dp(8), dp(6))
    val r = dp(14)
    when (style) {
        ButtonStyle.PRIMARY -> { background = rounded(Palette.green, r); setTextColor(Color.WHITE) }
        ButtonStyle.SECONDARY -> { background = rounded(Palette.greenSoft, r); setTextColor(Palette.greenInk) }
        ButtonStyle.OUTLINE -> { background = rounded(Color.TRANSPARENT, r, dp(2), Palette.line); setTextColor(Palette.navInk) }
        ButtonStyle.NAV -> { background = rounded(Palette.navBg, r, dp(2), Palette.line); setTextColor(Palette.navInk) }
    }
    isClickable = true
    setOnClickListener { onClick() }
}

fun View.setUsable(on: Boolean) {
    isEnabled = on
    alpha = if (on) 1f else 0.35f
}

/** Keeps [content] clear of the status and navigation bars on an edge-to-edge overlay. */
fun View.padForSystemBars(content: View, extra: Int) {
    setOnApplyWindowInsetsListener { _, insets ->
        val top: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            top = bars.top; bottom = bars.bottom
        } else {
            @Suppress("DEPRECATION")
            top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            bottom = insets.systemWindowInsetBottom
        }
        content.setPadding(extra, maxOf(top, context.dp(28)) + extra / 2, extra, bottom + extra / 2)
        insets
    }
}
