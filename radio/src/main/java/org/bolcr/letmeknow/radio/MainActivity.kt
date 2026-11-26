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
}
