package dev.openstream.tv.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.openstream.tv.R

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun qrBitmap(content: String, size: Int): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) 0xff05090d.toInt() else 0xffffffff.toInt()
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

fun View.applyTouchInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime(),
        )
        view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        insets
    }
}

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
    stateListAnimator = null
}

fun View.tvControlFocus(description: String) {
    isFocusable = true
    contentDescription = description
    foreground = context.getDrawable(R.drawable.tv_control_focus)
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
