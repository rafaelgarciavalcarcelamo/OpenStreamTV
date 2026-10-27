package dev.openstream.tv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.openstream.tv.R

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

@Suppress("DEPRECATION")
fun Activity.useTvFullscreen() {
    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
}

fun Context.label(text: String, sizeSp: Float = 16f, secondary: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        gravity = Gravity.CENTER_VERTICAL
    }

fun Context.action(text: String): Button = Button(this).apply {
    this.text = text
    isAllCaps = false
    isFocusable = true
    background = getDrawable(R.drawable.button_focus)
    setTextColor(context.getColorStateList(R.color.button_text))
    setPadding(dp(16), 0, dp(16), 0)
    typeface = Typeface.DEFAULT_BOLD
    tvFocusMotion()
}

fun View.tvFocusMotion() {
    setOnFocusChangeListener { view, focused ->
        view.animate().cancel()
        view.animate()
            .scaleX(if (focused) 1.025f else 1f)
            .scaleY(if (focused) 1.025f else 1f)
            .setDuration(120L)
            .start()
        view.elevation = if (focused) context.dp(8).toFloat() else 0f
    }
}

fun View.margin(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) {
    val params = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    params.setMargins(context.dp(left), context.dp(top), context.dp(right), context.dp(bottom))
    layoutParams = params
}

fun Context.sectionTitle(text: String) = label(text.uppercase(), 12f, secondary = true).apply {
    typeface = Typeface.DEFAULT_BOLD
    letterSpacing = 0.12f
    setPadding(dp(12), dp(12), dp(12), dp(8))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}
