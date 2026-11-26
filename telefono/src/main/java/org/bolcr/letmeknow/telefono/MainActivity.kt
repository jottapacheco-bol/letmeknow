package org.bolcr.letmeknow.telefono

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import org.bolcr.letmeknow.shared.Notice
import org.bolcr.letmeknow.shared.Protocol
import org.bolcr.letmeknow.shared.Ui
import org.bolcr.letmeknow.shared.Ui.add

/** The three permissions, whether the radio is connected, a test, and which apps go to the radio. */
class MainActivity : Activity() {
    // Light or dark, as the phone is set (the drawings' colors: orange for the main buttons).
    private val dark by lazy {
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    private val page by lazy { if (dark) 0xFF0E1014.toInt() else Color.WHITE }
    private val ink by lazy { if (dark) 0xFFF2F4F7.toInt() else Color.rgb(28, 28, 30) }
    private val soft by lazy { if (dark) 0xFFA9B1BD.toInt() else Color.rgb(90, 98, 112) }
    private val accent by lazy { if (dark) 0xFFFF8A4C.toInt() else 0xFFE0703A.toInt() }
    private val onAccent by lazy { if (dark) 0xFF1A0A02.toInt() else Color.WHITE }
    private val ok by lazy { if (dark) 0xFF2BBF6A.toInt() else Color.rgb(30, 140, 70) }
    private val bad by lazy { if (dark) 0xFFFF6B5E.toInt() else Color.rgb(200, 60, 40) }

    private val tick = Handler(Looper.getMainLooper())
    private lateinit var listenerState: TextView
    private lateinit var overlayState: TextView
    private lateinit var batteryState: TextView
    private var networkState: TextView? = null
    private lateinit var radioState: TextView
    private lateinit var adapter: Apps

    private companion object {
        /** The work profile's colour (the same purple as on the radio). */
        const val WORK = 0xFF8E6BE8.toInt()
        const val LOCAL_API = 37
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    /** [pkg] is the app's id in [Choices]: with "@work" for the work profile's copy. */
    private data class App(val pkg: String, val label: String, val icon: Drawable, val work: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        @Suppress("DEPRECATION")
        if (!dark) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val c = this
        val pad = Ui.dp(c, 20)

        val head = Ui.column(c).apply { setPadding(pad, pad, pad, Ui.dp(c, 8)) }
        head.add(Ui.text(c, "LetMeKnow", 30f, ink, bold = true))
        head.add(Ui.text(c, "Manda las notificaciones al radio del carro, en letra grande.", 16f, soft), top = Ui.dp(c, 4))

        listenerState = step(head, "1. Leer las notificaciones", "Dar permiso") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        overlayState = step(head, "2. Abrir apps desde el radio", "Dar permiso") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        batteryState = step(head, "3. Seguir funcionando con la pantalla apagada", "Permitir") { askBattery() }
        if (Build.VERSION.SDK_INT >= LOCAL_API) {
            networkState = step(head, "4. Ver el radio en el Wi-Fi", "Permitir") { requestPermissions(arrayOf(LOCAL_NETWORK), 7) }
            if (!localNetworkOn()) requestPermissions(arrayOf(LOCAL_NETWORK), 7)
        }

        radioState = Ui.text(c, "", 17f, ink)
        head.add(radioState, top = Ui.dp(c, 22))
        head.add(Ui.button(c, "Mandar una prueba al radio", 17f, accent, onAccent) { test() }, top = Ui.dp(c, 12))

        val pause = Switch(c).apply {
            text = "Pausar todo (no mandar nada al radio)"
            textSize = 16f
            setTextColor(ink)
            isChecked = Choices.paused(c)
            setOnCheckedChangeListener { _, on -> Choices.setPaused(c, on) }
        }
        head.add(pause, top = Ui.dp(c, 18))

        head.add(Ui.text(c, "Apps que llegan al radio", 20f, ink, bold = true), top = Ui.dp(c, 26))
        val search = EditText(c).apply {
            hint = "Buscar una app"
            textSize = 17f
            isSingleLine = true
            setTextColor(ink)
            setHintTextColor(soft)
        }
        head.add(search, top = Ui.dp(c, 6))

        adapter = Apps()
        val list = ListView(c).apply {
            addHeaderView(head, null, false)
            this.adapter = this@MainActivity.adapter
            divider = null
            setBackgroundColor(page)
            // Newer Android draws the app under the status bar: keep the words clear of it.
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(list)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = adapter.filter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
        })
        loadApps()
        // The radio may connect even before the notification permission is given (for the test).
        Server.start(this)
    }

    private fun step(parent: LinearLayout, title: String, action: String, onClick: () -> Unit): TextView {
        val c = this
        val row = Ui.row(c)
        val col = Ui.column(c)
        col.add(Ui.text(c, title, 17f, ink, bold = true))
        val state = Ui.text(c, "", 15f, soft)
        col.add(state, top = Ui.dp(c, 2))
        row.add(col, w = 0, weight = 1f, end = Ui.dp(c, 12))
        val b = Ui.button(c, action, 15f, accent, onAccent, onClick)
        row.add(b, w = LinearLayout.LayoutParams.WRAP_CONTENT)
        state.tag = b
        parent.add(row, top = Ui.dp(c, 18))
        return state
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (localNetworkOn()) Server.announceAgain(this)
    }

    /** Android 17 blocks the radio on the Wi-Fi until "local network" is allowed. */
    private fun localNetworkOn() =
        Build.VERSION.SDK_INT < LOCAL_API || checkSelfPermission(LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        tick.removeCallbacksAndMessages(null)
    }

    private fun refresh() {
        show(listenerState, listenerOn(), "Listo", "Falta: busque LetMeKnow y enciéndalo")
        show(overlayState, Settings.canDrawOverlays(this), "Listo", "Falta: para «Ver en el teléfono»")
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        show(batteryState, pm.isIgnoringBatteryOptimizations(packageName), "Listo", "Recomendado")
        networkState?.let { show(it, localNetworkOn(), "Listo", "Falta: sin esto no encuentra el radio") }
        val radio = Server.radio
        radioState.text = if (radio != null) "✅ Radio conectado ($radio)"
        else "⏳ Esperando al radio… (este teléfono: " + Server.addresses().joinToString(", ").ifEmpty { "sin Wi-Fi" } + ")"
        tick.postDelayed({ refresh() }, 1000)
    }

    private fun show(state: TextView, on: Boolean, yes: String, no: String) {
        state.text = if (on) "✅ $yes" else "⚠️ $no"
        state.setTextColor(if (on) ok else bad)
        (state.tag as? View)?.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun listenerOn(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val me = ComponentName(this, Listener::class.java)
        return flat.split(":").any { ComponentName.unflattenFromString(it) == me }
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun test() {
        val icon = Ui.bitmap(applicationInfo.loadIcon(packageManager), 144)
        val n = Notice(
            key = "letmeknow:prueba",
            pkg = packageName,
            app = "LetMeKnow",
            title = "Prueba",
            text = "Si puede leer esto en el radio, todo funciona. Toque «Listo» para quitarla.",
            lines = emptyList(),
            time = System.currentTimeMillis(),
            voice = false,
            canReply = true,
            icon = icon,
            photo = null,
            media = sample(),
        )
        Server.send(Protocol.message(Protocol.POST) { put("n", n.toJson()) })
        if (Server.radio == null) android.widget.Toast.makeText(this, "El radio todavía no está conectado", android.widget.Toast.LENGTH_LONG).show()
    }

    /** A sunset drawn here, so the test also shows how a picture looks on the radio. */
    private fun sample(): android.graphics.Bitmap {
        val b = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 600f, 600f,
            intArrayOf(0xFFF2A65A.toInt(), 0xFFC2185B.toInt(), 0xFF5E6AD2.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 600f, 600f, p)
        p.shader = null
        p.color = 0xD9FFF0C8.toInt()
        c.drawCircle(440f, 160f, 68f, p)
        p.color = 0x8C140A1E.toInt()
        c.drawPath(android.graphics.Path().apply {
            moveTo(0f, 480f); lineTo(180f, 300f); lineTo(320f, 420f); lineTo(440f, 320f); lineTo(600f, 460f)
            lineTo(600f, 600f); lineTo(0f, 600f); close()
        }, p)
        return b
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = getSystemService(android.content.pm.LauncherApps::class.java)
            val me = android.os.Process.myUserHandle()
            // The personal profile and, if the phone has one, the work profile: the same app can be chosen in each.
            val apps = launcher.profiles.flatMap { user ->
                val work = user != me
                launcher.getActivityList(null, user)
                    .map { it.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != packageName }
                    .map {
                        val icon = it.loadIcon(pm).let { d -> if (work) pm.getUserBadgedIcon(d, user) else d }
                        App(Choices.id(it.packageName, work), pm.getApplicationLabel(it).toString(), icon, work)
                    }
            }
            val on = Choices.apps(this)
            // The chosen ones first, then by name.
            val sorted = apps.sortedWith(compareBy<App>({ it.pkg !in on }, { it.work }, { it.label.lowercase() }))
            runOnUiThread { adapter.set(sorted) }
        }.start()
    }

    private inner class Apps : BaseAdapter() {
        private var all = emptyList<App>()
        private var shown = emptyList<App>()
        private var query = ""

        fun set(list: List<App>) {
            all = list
            filter(query)
        }

        fun filter(q: String) {
            query = q
            shown = if (q.isBlank()) all else all.filter { it.label.contains(q.trim(), ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val c = this@MainActivity
            val row = (convertView as? LinearLayout) ?: Ui.row(c).apply {
                val p = Ui.dp(c, 20)
                setPadding(p, Ui.dp(c, 8), p, Ui.dp(c, 8))
                val size = Ui.dp(c, 40)
                add(ImageView(c), w = size, h = size, end = Ui.dp(c, 14))
                add(Ui.text(c, "", 17f, ink), w = 0, weight = 1f)
                // The work profile's apps say so, in purple, like their bubbles on the radio.
                add(Ui.text(c, "Trabajo", 13f, 0xFFFFFFFF.toInt(), bold = true).apply {
                    background = Ui.rounded(WORK, Ui.dp(c, 10).toFloat())
                    setPadding(Ui.dp(c, 10), Ui.dp(c, 3), Ui.dp(c, 10), Ui.dp(c, 3))
                }, w = LinearLayout.LayoutParams.WRAP_CONTENT, end = Ui.dp(c, 10))
                add(CheckBox(c).apply { isClickable = false }, w = LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val app = shown[position]
            val box = row.getChildAt(3) as CheckBox
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            row.getChildAt(2).visibility = if (app.work) View.VISIBLE else View.GONE
            box.isChecked = app.pkg in Choices.apps(c)
            row.setOnClickListener {
                val now = !box.isChecked
                Choices.set(c, app.pkg, now)
                box.isChecked = now
            }
            return row
        }
    }
}
