package org.bolcr.letmeknow.radio

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The radio's own screen, used rarely: the two permissions, whether the phone is connected, a test bubble, the look
 * and the sound. Everything else happens in the bubbles.
 */
class MainActivity : Activity() {
    private lateinit var look: Look
    private lateinit var state: TextView
    private val refresh: () -> Unit = { runOnUiThread { paintState() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        Settings.load(this)
        RadioService.start(this)
        RadioService.watchers.add(refresh)
    }

    override fun onDestroy() {
        RadioService.watchers.remove(refresh)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        look = Look(this)
        val bg = if (look.dark) 0xFF0E1014.toInt() else 0xFFEEF0F3.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.px(48), look.px(36), look.px(48), look.px(36))
        }
        val head = row()
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_bell)
            setColorFilter(Look.ON_ORANGE, PorterDuff.Mode.SRC_IN)
            background = look.round(Look.ORANGE, 16)
            setPadding(look.px(12), look.px(12), look.px(12), look.px(12))
        }, LinearLayout.LayoutParams(look.px(56), look.px(56)))
        head.addView(text("LetMeKnow", 34f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16) })
        head.addView(pill("Probar", Look.ORANGE, Look.ON_ORANGE) { RadioService.start(this, RadioService.ACTION_TEST) })
        col.addView(head)

        state = text("", 22f, look.ink, true)
        col.addView(card(state), spaced())
        paintState()

        val overlayOk = AndroidSettings.canDrawOverlays(this)
        col.addView(card(permission("Mostrar burbujas encima de las apps", overlayOk) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }), spaced())
        val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        col.addView(card(permission("Micrófono para responder dictando", micOk) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }), spaced())

        // Everything silenced for a while (one conversation is silenced holding its bubble).
        val quiet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val muted = Settings.muted()
        val quietHead = row()
        quietHead.addView(ImageView(this).apply {
            setImageResource(if (muted) R.drawable.ic_bell_off else R.drawable.ic_bell)
            setColorFilter(if (muted) Look.PURPLE else look.ink, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(look.px(34), look.px(34)))
        val left = Settings.mutedUntil - System.currentTimeMillis()
        quietHead.addView(text(when {
            !muted -> "Silenciar todo"
            Settings.mutedUntil == Settings.UNTIL_OFF -> "En silencio hasta apagar el carro"
            else -> "En silencio, ${left / 60_000 + 1} min más"
        }, 22f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(14) })
        if (muted) quietHead.addView(pill("Quitar silencio", Look.ORANGE, Look.ON_ORANGE) { Settings.mutedUntil = 0L; build() })
        quiet.addView(quietHead)
        val times = row()
        listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h", -1 to "Hasta apagar").forEachIndexed { i, (m, label) ->
            times.addView(pill(label, look.chip, look.ink) {
                Settings.mutedUntil = if (m < 0) Settings.UNTIL_OFF else System.currentTimeMillis() + m * 60_000L
                build()
            }.apply {
                if (m < 0) {
                    // The radio's font has no power glyph: draw the icon instead.
                    val power = getDrawable(R.drawable.ic_power)!!.mutate()
                    power.setTint(look.ink)
                    power.setBounds(0, 0, look.px(30), look.px(30))
                    text = android.text.SpannableString("  $label").apply {
                        setSpan(android.text.style.ImageSpan(power, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, 0)
                    }
                }
            }, LinearLayout.LayoutParams(0, look.px(72), if (m < 0) 1.8f else 1f).apply { if (i < 4) marginEnd = look.px(12) })
        }
        quiet.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(14) })
        col.addView(card(quiet), spaced())

        // Conversations silenced for good, each with "Volver a oír". Only when there is one.
        val forever = Settings.forever()
        if (forever.isNotEmpty()) {
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            list.addView(row().apply {
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_infinity)
                    setColorFilter(Look.WORK, PorterDuff.Mode.SRC_IN)
                }, LinearLayout.LayoutParams(look.px(40), look.px(34)))
                addView(text("Silenciados para siempre", 22f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(14) })
            })
            forever.forEach { f ->
                val r = row().apply {
                    background = look.round(look.chip, 20)
                    setPadding(look.px(14), look.px(12), look.px(14), look.px(12))
                }
                r.addView(text(f.name.take(1).uppercase(), 24f, android.graphics.Color.WHITE, true).apply {
                    gravity = Gravity.CENTER
                    background = look.oval(Look.avatarColor(f.name))
                }, LinearLayout.LayoutParams(look.px(56), look.px(56)))
                r.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(f.name, 22f, look.ink, true).apply { isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(text(f.app, 16f, look.soft, false))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16); marginEnd = look.px(12) })
                val again = pill("Volver a oír", Look.GREEN, Look.ON_GREEN) {}
                again.setOnClickListener {
                    // A green check, then it leaves the list; from now on it shows and sounds again.
                    again.text = "✓"
                    r.animate().translationX(look.px(120).toFloat()).alpha(0f).setStartDelay(350).setDuration(300)
                        .withEndAction { Settings.hearAgain(this, f.id); build() }.start()
                }
                r.addView(again)
                list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(12) })
            }
            col.addView(card(list), spaced())
        }

        // The last time the app fell: what went wrong, for a photo.
        Crash.last(this)?.let { what ->
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(row().apply {
                val at = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(Crash.at(this@MainActivity)))
                addView(text("La app se cerró ($at)", 22f, Look.RED, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(pill("Borrar", look.chip, look.ink) { Crash.clear(this@MainActivity); build() })
            })
            box.addView(text(what, 14f, look.soft, false).apply { typeface = android.graphics.Typeface.MONOSPACE },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(10) })
            col.addView(card(box), spaced())
        }

        val looks = row()
        listOf("Como el radio", "Claro", "Oscuro").forEachIndexed { i, name ->
            val on = Settings.theme(this) == i
            looks.addView(pill(name, if (on) look.strong else look.chip, if (on) look.strongInk else look.ink) {
                Settings.setTheme(this, i)
                build()
            }, LinearLayout.LayoutParams(0, look.px(72), 1f).apply { if (i < 2) marginEnd = look.px(12) })
        }
        col.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Apariencia", 22f, look.ink, true))
            addView(looks, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(14) })
        }), spaced())

        val size = row()
        val sample = text("Aa", 30f * Settings.textScale(this), look.ink, true)
        size.addView(text("Tamaño del texto", 22f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        size.addView(sample, LinearLayout.LayoutParams(look.px(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        listOf("A−" to -0.1f, "A+" to 0.1f).forEach { (label, by) ->
            size.addView(pill(label, look.chip, look.ink) {
                Settings.setTextScale(this, Settings.textScale(this) + by)
                sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sp(30f * Settings.textScale(this)))
            }, LinearLayout.LayoutParams(look.px(96), look.px(72)).apply { marginStart = look.px(12) })
        }
        col.addView(card(size), spaced())

        // Google's voice: works, or why not (so the pastor can tell what to fix).
        val voiceState = text("", 20f, look.soft, false)
        val voiceRow = row()
        voiceRow.addView(text("Voz de Google", 22f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        voiceRow.addView(pill("Probar la voz", Look.BLUE, Look.ON_BLUE) {
            voiceState.text = "Probando…"
            voiceState.setTextColor(look.soft)
            Thread {
                val voice = if (Settings.femaleVoice(this)) CloudVoice.FEMALE else CloudVoice.MALE
                val ok = CloudVoice(this).say("Hola, soy la voz de LetMeKnow. " + System.currentTimeMillis() % 1000, voice) != null
                runOnUiThread {
                    voiceState.text = if (ok) "✓ Funciona" else "✗ " + (CloudVoice.problem ?: "No respondió")
                    voiceState.setTextColor(if (ok) Look.GREEN else Look.RED)
                }
            }.start()
        })
        // What happens when a message arrives, for all of them.
        col.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Al llegar un mensaje", 22f, look.ink, true))
            addView(switchRow(R.drawable.ic_bell, "Sonido al llegar", Settings.sound(this@MainActivity)) { Settings.setSound(this@MainActivity, it) })
            addView(switchRow(R.drawable.ic_speaker, "Leer en voz alta solo", Settings.autoRead(this@MainActivity)) { Settings.setAutoRead(this@MainActivity, it) })
            addView(switchRow(R.drawable.ic_chat, "Anunciar: «Notificación nueva de…»", Settings.announce(this@MainActivity)) { Settings.setAnnounce(this@MainActivity, it) })
            addView(switchRow(R.drawable.ic_quick, "Decir cuántos mensajes sin leer", Settings.announceCount(this@MainActivity)) { Settings.setAnnounceCount(this@MainActivity, it) })
            addView(text("«Leer en voz alta solo» es para las conversaciones que no tienen uno propio: cada una se cambia al sostener su burbuja. Si se lee solo, no hace falta el anuncio: ya dice de quién es. Con una ventana abierta solo se anuncia, y se lee al cerrarla.", 16f, look.soft, false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(8) })
        }), spaced())

        // The voice for conversations without their own (each one is chosen holding its bubble).
        val voices = row()
        listOf(false to "Hombre", true to "Mujer").forEachIndexed { i, (female, label) ->
            val on = Settings.femaleVoice(this) == female
            voices.addView(pill(label, if (on) Look.BLUE else look.chip, if (on) Look.ON_BLUE else look.ink) {
                Settings.setFemaleVoice(this, female)
                build()
            }.apply {
                val d = getDrawable(if (female) R.drawable.ic_female else R.drawable.ic_male)!!.mutate()
                d.setTint(if (on) Look.ON_BLUE else look.ink)
                d.setBounds(0, 0, look.px(28), look.px(28))
                text = android.text.SpannableString("  $label").apply {
                    setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, 0)
                }
            }, LinearLayout.LayoutParams(0, look.px(72), 1f).apply { if (i == 0) marginEnd = look.px(12) })
        }
        col.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(voiceRow)
            addView(text("Para las conversaciones que no tienen una escogida:", 18f, look.soft, false),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(12) })
            addView(voices, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(10) })
            addView(voiceState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(10) })
        }), spaced())
        CloudVoice.problem?.let { voiceState.text = "✗ $it"; voiceState.setTextColor(Look.RED) }

        val address = EditText(this).apply {
            setText(Settings.phoneAddress(this@MainActivity))
            hint = "Se busca solo"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sp(22f))
            setTextColor(look.ink)
            setHintTextColor(look.soft)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setOnFocusChangeListener { _, has -> if (!has) Settings.setPhoneAddress(this@MainActivity, text.toString()) }
        }
        col.addView(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Dirección del teléfono (solo si no lo encuentra)", 20f, look.soft, false))
            addView(address)
        }), spaced())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(col)
        })
    }

    override fun onPause() {
        super.onPause()
        currentFocus?.clearFocus()
    }

    private fun paintState() {
        if (!::state.isInitialized) return
        val phone = RadioService.phone
        state.text = if (phone != null) "●  Teléfono conectado ($phone)" else "○  Buscando el teléfono en el Wi-Fi…"
        state.setTextColor(if (phone != null) Look.GREEN else look.soft)
    }

    private fun permission(label: String, ok: Boolean, ask: () -> Unit): View {
        val r = row()
        r.addView(text(label, 22f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (ok) r.addView(text("✓ Listo", 22f, Look.GREEN, true))
        else r.addView(pill("Dar permiso", Look.BLUE, Look.ON_BLUE, ask))
        return r
    }

    private fun card(v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = look.round(if (look.dark) 0xFF181C22.toInt() else 0xFFFFFFFF.toInt(), 24, stroke = true)
        setPadding(look.px(26), look.px(22), look.px(26), look.px(22))
        addView(v)
    }

    private fun spaced() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(18) }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sp(sp))
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun pill(s: String, fill: Int, ink: Int, onClick: () -> Unit) = text(s, 22f, ink, true).apply {
        gravity = Gravity.CENTER
        background = look.round(fill, 20, stroke = fill == look.chip)
        setPadding(look.px(26), look.px(16), look.px(26), look.px(16))
        setOnClickListener { onClick() }
    }

    /** A line with an icon, words and the green switch, like the ones in the bubbles' window. */
    private fun switchRow(res: Int, label: String, on: Boolean, change: (Boolean) -> Unit): View {
        val r = row().apply { setPadding(0, look.px(10), 0, look.px(10)) }
        r.addView(ImageView(this).apply {
            setImageResource(res)
            setColorFilter(look.ink, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(look.px(32), look.px(32)))
        r.addView(text(label, 20f, look.ink, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16) })
        val track = android.widget.FrameLayout(this)
        val knob = View(this).apply { background = look.oval(android.graphics.Color.WHITE) }
        track.addView(knob, android.widget.FrameLayout.LayoutParams(look.px(36), look.px(36), Gravity.CENTER_VERTICAL))
        var state = on
        fun paint(animate: Boolean) {
            track.background = look.round(if (state) Look.GREEN else look.off, 22)
            val x = if (state) look.px(36).toFloat() else look.px(4).toFloat()
            if (animate) knob.animate().translationX(x).setDuration(160).start() else knob.translationX = x
        }
        paint(false)
        r.addView(track, LinearLayout.LayoutParams(look.px(76), look.px(44)))
        r.setOnClickListener { state = !state; change(state); paint(true) }
        return r
    }
}
