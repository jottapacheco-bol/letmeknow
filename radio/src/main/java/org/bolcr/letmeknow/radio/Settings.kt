package org.bolcr.letmeknow.radio

import android.content.Context
import android.content.res.Configuration

/** The radio's choices, kept on the radio. */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)

    /** An address typed by hand, when the phone is not found by itself. Empty: find it. */
    fun phoneAddress(c: Context): String = prefs(c).getString("phone", "").orEmpty()
    fun setPhoneAddress(c: Context, v: String) = prefs(c).edit().putString("phone", v.trim()).apply()

    fun lastPhone(c: Context): String? = prefs(c).getString("last", null)
    fun setLastPhone(c: Context, v: String) = prefs(c).edit().putString("last", v).apply()

    /** A sound when a notification arrives. On by default. */
    fun sound(c: Context): Boolean = prefs(c).getBoolean("sound", true)
    fun setSound(c: Context, v: Boolean) = prefs(c).edit().putBoolean("sound", v).apply()

    /** Read every new message aloud by itself. Off by default. */
    fun autoRead(c: Context): Boolean = prefs(c).getBoolean("autoRead", false)
    fun setAutoRead(c: Context, v: Boolean) = prefs(c).edit().putBoolean("autoRead", v).apply()

    /** The message's letters: 0.8 to 1.6 times the drawn size (A− and A+). */
    fun textScale(c: Context): Float = prefs(c).getFloat("textScale", 1f)
    fun setTextScale(c: Context, v: Float) =
        prefs(c).edit().putFloat("textScale", (Math.round(v * 10) / 10f).coerceIn(0.8f, 1.6f)).apply()

    /** 0 as the radio (its night mode follows the car's lights), 1 light, 2 dark. */
    fun theme(c: Context): Int = prefs(c).getInt("theme", 0)
    fun setTheme(c: Context, v: Int) = prefs(c).edit().putInt("theme", v).apply()

    fun dark(c: Context): Boolean = when (theme(c)) {
        1 -> false
        2 -> true
        else -> c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /** Where the bubbles were left (dragged), from the top of the screen. */
    fun bubblesY(c: Context): Int = prefs(c).getInt("bubblesY", -1)
    fun setBubblesY(c: Context, v: Int) = prefs(c).edit().putInt("bubblesY", v).apply()
    fun bubblesX(c: Context): Int = prefs(c).getInt("bubblesX", -1)
    fun setBubblesX(c: Context, v: Int) = prefs(c).edit().putInt("bubblesX", v).apply()
    fun bubblesLeft(c: Context): Boolean = prefs(c).getBoolean("bubblesLeft", false)
    fun setBubblesLeft(c: Context, v: Boolean) = prefs(c).edit().putBoolean("bubblesLeft", v).apply()

    /** Where the message window was left (moved with the finger), its top-left corner. -1: as drawn. */
    fun panelX(c: Context): Int = prefs(c).getInt("panelX", -1)
    fun panelY(c: Context): Int = prefs(c).getInt("panelY", -1)
    fun setPanelPlace(c: Context, x: Int, y: Int) = prefs(c).edit().putInt("panelX", x).putInt("panelY", y).apply()

    /** Silenced until this time; [UNTIL_OFF] = until the car is turned off (the radio's app starts again). */
    @Volatile var mutedUntil: Long = 0L
    const val UNTIL_OFF = Long.MAX_VALUE

    fun muted(): Boolean = System.currentTimeMillis() < mutedUntil
}
