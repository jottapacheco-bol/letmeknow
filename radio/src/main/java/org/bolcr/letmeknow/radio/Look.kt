package org.bolcr.letmeknow.radio

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import kotlin.math.min

/**
 * The radio's look (the drawings the pastor saw, 26 Nov 2026): frosted panels over his apps, orange for the main
 * thing, light blue for everything about the voice, green to send or finish. Dark at night, light by day.
 */
class Look(context: Context) {
    val dark = Settings.dark(context)
    private val density = context.resources.displayMetrics.density
    /** The drawings are for a 1280 wide screen; a smaller one shrinks everything alike. */
    private val scale = min(1f, context.resources.displayMetrics.widthPixels / density / 1280f).coerceAtLeast(0.6f)

    fun px(dp: Number): Int = (dp.toFloat() * scale * density).toInt()
    fun sp(v: Float): Float = v * scale

    val panel = if (dark) Color.argb(235, 24, 28, 35) else Color.argb(240, 255, 255, 255)
    val glass = if (dark) Color.argb(220, 28, 32, 40) else Color.argb(230, 255, 255, 255)
    val chip = if (dark) Color.argb(20, 255, 255, 255) else Color.argb(13, 16, 24, 40)
    val edge = if (dark) Color.argb(31, 255, 255, 255) else Color.argb(26, 16, 24, 40)
    val scrim = if (dark) Color.argb(115, 0, 0, 0) else Color.argb(46, 20, 28, 40)
    val ink = if (dark) Color.rgb(242, 244, 247) else Color.rgb(18, 21, 26)
    val soft = if (dark) Color.rgb(169, 177, 189) else Color.rgb(77, 85, 98)
    val faint = if (dark) Color.rgb(138, 147, 161) else Color.rgb(95, 103, 116)
    val off = if (dark) Color.argb(56, 255, 255, 255) else Color.argb(51, 16, 24, 40)
    val strong = if (dark) Color.rgb(242, 244, 247) else Color.rgb(18, 21, 26)
    val strongInk = if (dark) Color.rgb(14, 16, 20) else Color.WHITE
    val solidBack = if (dark) Color.rgb(26, 30, 37) else Color.WHITE

    fun round(color: Int, radiusDp: Number, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = px(radiusDp).toFloat()
        if (stroke) setStroke(px(1).coerceAtLeast(1), edge)
    }

    fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    companion object {
        val ORANGE = Color.rgb(255, 138, 76)
        val ON_ORANGE = Color.rgb(26, 10, 2)
        val BLUE = Color.rgb(56, 198, 244)
        val ON_BLUE = Color.rgb(4, 32, 43)
        val GREEN = Color.rgb(43, 191, 106)
        val ON_GREEN = Color.rgb(4, 33, 15)
        val RED = Color.rgb(240, 89, 74)
        val PURPLE = Color.rgb(180, 140, 255)

        /** A steady color for a contact without photo, from its name. */
        fun avatarColor(name: String): Int {
            val colors = intArrayOf(0xFFC2185B.toInt(), 0xFF5E6AD2.toInt(), 0xFF3F7D8C.toInt(), 0xFF8E5CC4.toInt(),
                0xFFD0682E.toInt(), 0xFF2E7D5B.toInt(), 0xFF3D6FD8.toInt())
            return colors[Math.floorMod(name.hashCode(), colors.size)]
        }
    }
}
