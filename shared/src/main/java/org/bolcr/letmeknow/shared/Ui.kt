package org.bolcr.letmeknow.shared

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** The screens of both apps are built in code (no layouts), with these few pieces. */
object Ui {
    fun dp(c: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    fun text(c: Context, s: CharSequence, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(c).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun button(c: Context, s: CharSequence, sp: Float, fill: Int, ink: Int, onClick: () -> Unit): Button =
        Button(c).apply {
            text = s
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(ink)
            background = rounded(fill, dp(c, 14).toFloat())
            val pad = dp(c, 16)
            setPadding(pad, pad / 2, pad, pad / 2)
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    fun column(c: Context): LinearLayout = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }

    fun row(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun LinearLayout.add(v: View, w: Int = LinearLayout.LayoutParams.MATCH_PARENT,
                         h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
                         top: Int = 0, end: Int = 0): View {
        addView(v, LinearLayout.LayoutParams(w, h, weight).apply {
            topMargin = top
            marginEnd = end
        })
        return v
    }

    /** A drawable as a square bitmap, to send an app's icon to the radio. */
    fun bitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null && d.bitmap.width == size && d.bitmap.height == size) return d.bitmap
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return b
    }
}
