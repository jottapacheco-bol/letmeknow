package org.bolcr.letmeknow.telefono

import android.content.Context

/** Which apps send their notifications to the radio. WhatsApp is on from the start. */
object Choices {
    private const val FILE = "choices"
    private const val APPS = "apps"
    private const val PAUSED = "paused"
    val FIRST = setOf("com.whatsapp", "com.whatsapp.w4b")

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun apps(c: Context): Set<String> = prefs(c).getStringSet(APPS, null)?.toSet() ?: FIRST

    fun isOn(c: Context, pkg: String): Boolean = !paused(c) && pkg in apps(c)

    fun set(c: Context, pkg: String, on: Boolean) {
        val now = apps(c).toMutableSet()
        if (on) now.add(pkg) else now.remove(pkg)
        prefs(c).edit().putStringSet(APPS, now).apply()
    }

    /** Everything stops going to the radio for a while, without losing the list. */
    fun paused(c: Context): Boolean = prefs(c).getBoolean(PAUSED, false)

    fun setPaused(c: Context, p: Boolean) = prefs(c).edit().putBoolean(PAUSED, p).apply()
}
