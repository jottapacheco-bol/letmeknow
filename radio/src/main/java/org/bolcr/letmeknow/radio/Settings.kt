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

    /** Say "Notificación nueva de …" when one arrives and is not read whole. Off by default. */
    fun announce(c: Context): Boolean = prefs(c).getBoolean("announce", false)
    fun setAnnounce(c: Context, v: Boolean) = prefs(c).edit().putBoolean("announce", v).apply()

    /** The announcement also says how many unread messages that conversation has. Off by default. */
    fun announceCount(c: Context): Boolean = prefs(c).getBoolean("announceCount", false)
    fun setAnnounceCount(c: Context, v: Boolean) = prefs(c).edit().putBoolean("announceCount", v).apply()

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

    /** Everything silenced until this time (from the radio's screen); [UNTIL_OFF] = until the car is turned off. */
    @Volatile var mutedUntil: Long = 0L
        set(v) { field = v; changed() }
    const val UNTIL_OFF = Long.MAX_VALUE

    fun muted(): Boolean = System.currentTimeMillis() < mutedUntil

    /** One conversation silenced (holding its bubble), until a time. Kept until the car is turned off. */
    private val quiet = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun conversation(n: org.bolcr.letmeknow.shared.Notice) = n.pkg + (if (n.work) "@work" else "") + "|" + n.title.ifBlank { n.key }

    fun muted(n: org.bolcr.letmeknow.shared.Notice): Boolean =
        muted() || conversation(n) in foreverIds || (quiet[conversation(n)] ?: 0L) > System.currentTimeMillis()

    /** A conversation silenced for good ("Siempre"), kept on the radio until "Volver a oír". */
    data class Forever(val id: String, val name: String, val app: String)

    @Volatile private var forever: List<Forever> = emptyList()
    @Volatile private var foreverIds: Set<String> = emptySet()

    fun forever(): List<Forever> = forever

    /** Reads the ones silenced for good; once, when the radio's service starts. */
    fun load(c: Context) {
        val arr = try { org.json.JSONArray(prefs(c).getString("forever", "[]")) } catch (e: Exception) { org.json.JSONArray() }
        val list = (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { Forever(it.optString("id"), it.optString("name"), it.optString("app")) }
        }.filter { it.id.isNotEmpty() }
        forever = list
        foreverIds = list.map { it.id }.toSet()
        voices = flags(c, "voices")
        reads = flags(c, "reads")
    }

    private fun flags(c: Context, name: String): Map<String, Boolean> = try {
        val o = org.json.JSONObject(prefs(c).getString(name, "{}"))
        o.keys().asSequence().associateWith { o.optBoolean(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun saveFlags(c: Context, name: String, map: Map<String, Boolean>) {
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        prefs(c).edit().putString(name, o.toString()).apply()
    }

    private fun saveForever(c: Context, list: List<Forever>) {
        forever = list
        foreverIds = list.map { it.id }.toSet()
        val arr = org.json.JSONArray()
        list.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("app", it.app)) }
        prefs(c).edit().putString("forever", arr.toString()).apply()
        changed()
    }

    fun muteForever(c: Context, n: org.bolcr.letmeknow.shared.Notice) {
        val id = conversation(n)
        val app = if (n.work) "${n.app} · Trabajo" else n.app
        saveForever(c, forever.filter { it.id != id } + Forever(id, n.title.ifBlank { n.app }, app))
    }

    fun hearAgain(c: Context, id: String) = saveForever(c, forever.filter { it.id != id })

    /** The voice that reads each conversation: true a woman's, false a man's; the others take [femaleVoice]. */
    @Volatile private var voices: Map<String, Boolean> = emptyMap()

    fun female(c: Context, n: org.bolcr.letmeknow.shared.Notice): Boolean = voices[conversation(n)] ?: femaleVoice(c)

    fun setFemale(c: Context, n: org.bolcr.letmeknow.shared.Notice, female: Boolean) {
        voices = voices + (conversation(n) to female)
        saveFlags(c, "voices", voices)
    }

    /** Whether each conversation is read aloud by itself when it arrives; the others follow [autoRead]. */
    @Volatile private var reads: Map<String, Boolean> = emptyMap()

    fun autoRead(c: Context, n: org.bolcr.letmeknow.shared.Notice): Boolean = reads[conversation(n)] ?: autoRead(c)

    fun setAutoRead(c: Context, n: org.bolcr.letmeknow.shared.Notice, on: Boolean) {
        reads = reads + (conversation(n) to on)
        saveFlags(c, "reads", reads)
    }

    /** The voice for conversations without their own: a man's (Charon, as Levite) unless chosen otherwise. */
    fun femaleVoice(c: Context): Boolean = prefs(c).getBoolean("femaleVoice", false)
    fun setFemaleVoice(c: Context, v: Boolean) = prefs(c).edit().putBoolean("femaleVoice", v).apply()

    fun mute(n: org.bolcr.letmeknow.shared.Notice, until: Long) {
        if (until == 0L) quiet.remove(conversation(n)) else quiet[conversation(n)] = until
        changed()
    }

    /** Forgets silences that ended; true if one did (the bubbles come back). */
    fun tidy(): Boolean {
        val now = System.currentTimeMillis()
        var ended = quiet.entries.removeIf { it.value <= now }
        if (mutedUntil != 0L && mutedUntil <= now) { mutedUntil = 0L; ended = true }
        return ended
    }

    /** Told when a silence starts or ends (the bubbles, the radio's screen). */
    val watchers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    private fun changed() = watchers.forEach { it() }
}
