package org.bolcr.letmeknow.radio

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.HapticFeedbackConstants
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.bolcr.letmeknow.shared.Notice
import org.bolcr.letmeknow.shared.Protocol
import kotlin.math.abs

/**
 * What floats over the radio's apps (one or two on screen): the bubbles, one per conversation, and the panel that
 * grows out of a bubble when it is touched. Nothing here opens a screen of its own, so the apps behind stay as they
 * are.
 */
class Overlay(private val context: Context, private val send: (String) -> Unit) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val speaker = Speaker(context)
    private var look = Look(context)

    private var bubbles: LinearLayout? = null
    private var bubblesParams: WindowManager.LayoutParams? = null
    private var panel: FrameLayout? = null
    private var card: LinearLayout? = null
    /** The conversation open in the panel. */
    private var openKey: String? = null
    private var dictation: Dictation? = null
    private var pulse: ObjectAnimator? = null

    private val watcher: (Notice?) -> Unit = { arrived -> changed(arrived) }

    private val tick = object : Runnable {
        override fun run() {
            Settings.tidy()
            refreshBubbles(null)
            main.postDelayed(this, 30_000)
        }
    }

    private val silence: () -> Unit = { main.post { refreshBubbles(null) } }

    fun start() {
        Settings.watchers.add(silence)
        Inbox.watch(watcher)
        main.post(tick)
    }

    fun stop() {
        Settings.watchers.remove(silence)
        Inbox.unwatch(watcher)
        main.removeCallbacks(tick)
        closePanel(animated = false)
        bubbles?.let { runCatching { wm.removeView(it) } }
        bubbles = null
        speaker.shutdown()
    }

    private fun allowed() = AndroidSettings.canDrawOverlays(context)

    private fun changed(arrived: Notice?) {
        if (arrived != null && !Settings.muted(arrived)) {
            if (Settings.sound(context)) speaker.chime()
            if (Settings.autoRead(context)) main.postDelayed({ speaker.read(arrived) }, 600)
        }
        val open = openKey
        if (open != null) {
            when {
                Inbox.get(open) == null -> showNextOrClose()
                arrived?.key == open && mode == Mode.READ -> showRead(open, fade = false)
            }
        }
        refreshBubbles(arrived)
    }

    // ---------------------------------------------------------------- the bubbles

    private fun refreshBubbles(arrived: Notice?) {
        if (!allowed()) return
        look = Look(context)
        // Silenced conversations (or everything, from the radio's screen) are not shown until the silence ends.
        val notices = if (Settings.muted()) emptyList() else Inbox.all.filter { !Settings.muted(it) }
        if (notices.isEmpty() || panel != null) {
            bubbles?.visibility = View.GONE
            return
        }
        val root = bubbles ?: createBubbles()
        root.visibility = View.VISIBLE
        root.removeAllViews()
        root.gravity = if (Settings.bubblesLeft(context)) Gravity.START else Gravity.END
        var first = true
        notices.take(4).forEach { n ->
            val b = bubble(n)
            root.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(84)).apply { if (!first) topMargin = look.px(16) })
            first = false
            if (n.key == arrived?.key) enter(b)
        }
        if (notices.size > 4) {
            val more = text("+${notices.size - 4}", 20f, look.soft, bold = true).apply {
                background = look.round(look.glass, 24, stroke = true)
                setPadding(look.px(18), look.px(8), look.px(18), look.px(8))
            }
            root.addView(more, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(12) })
            drag(more) { openPanel(notices[4].key) }
        }
    }

    private fun createBubbles(): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val p = look.px(14)
            setPadding(p, p, p, p)
        }
        val params = overlayParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or if (Settings.bubblesLeft(context)) Gravity.START else Gravity.END
            x = Settings.bubblesX(context).takeIf { it >= 0 } ?: look.px(12)
            y = Settings.bubblesY(context).takeIf { it >= 0 } ?: look.px(30)
        }
        wm.addView(root, params)
        bubbles = root
        bubblesParams = params
        return root
    }

    /** One conversation: the photo with the app's icon, the name, the app and time, and how many messages. */
    private fun bubble(n: Notice): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = look.round(look.glass, 42, stroke = true)
            elevation = look.px(8).toFloat()
            setPadding(look.px(10), 0, look.px(12), 0)
            contentDescription = "${n.title}, ${n.app}"
        }
        row.addView(avatar(n, 64), LinearLayout.LayoutParams(look.px(64), look.px(64)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(n.title.ifBlank { n.app }, 24f, look.ink, bold = true).apply {
            maxWidth = look.px(230)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        words.addView(text("${n.app} · ${ago(n.time)}", 16f, look.soft))
        row.addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = look.px(14)
            marginEnd = look.px(14)
        })
        val badge = text(Inbox.count(n.key).toString(), 20f, Look.ON_ORANGE, bold = true).apply {
            gravity = Gravity.CENTER
            background = look.round(Look.ORANGE, 20)
            minWidth = look.px(40)
            setPadding(look.px(8), 0, look.px(8), 0)
        }
        row.addView(badge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(40)))
        drag(row, onThrow = { Inbox.remove(n.key) }, onHold = { openMute(n) }) { openPanel(n.key) }
        return row
    }

    /** The new one slides in from the edge, then its number beats a few times. */
    private fun enter(v: View) {
        val fromRight = !Settings.bubblesLeft(context)
        v.translationX = look.px(if (fromRight) 160 else -160).toFloat()
        v.scaleX = 0.6f
        v.scaleY = 0.6f
        v.alpha = 0f
        v.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(600).setInterpolator(OvershootInterpolator(1.4f)).start()
        val badge = (v as? ViewGroup)?.getChildAt(2) ?: return
        ObjectAnimator.ofPropertyValuesHolder(badge,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f, 1f)).apply {
            duration = 700
            startDelay = 600
            repeatCount = 3
            start()
        }
    }

    /**
     * Touching opens. Dragging (up, down, or holding a moment and then anywhere) moves all the bubbles, also to the
     * other side of the screen, so they never cover what is behind. A quick sideways throw of a conversation
     * ([onThrow]) sends it off the screen and removes it from the radio (it comes back if a new message arrives).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: View, onThrow: (() -> Unit)? = null, onHold: (() -> Unit)? = null, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val fling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 6
        var downY = 0f
        var downX = 0f
        var startY = 0
        var startX = 0
        // 0 nothing yet, 1 moving the bubbles, 2 throwing this one, 3 held still (lifting it opens [onHold]).
        var gesture = 0
        var tracker: VelocityTracker? = null
        val hold = Runnable {
            if (gesture == 0) {
                gesture = 3
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(120).start()
            }
        }
        v.setOnTouchListener { view, e ->
            val params = bubblesParams ?: return@setOnTouchListener false
            val fromEnd = params.gravity and Gravity.END == Gravity.END
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startY = params.y; startX = params.x; gesture = 0
                    tracker = VelocityTracker.obtain().also { it.addMovement(e) }
                    view.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start()
                    main.postDelayed(hold, 350)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (gesture == 3 && (abs(dx) > slop || abs(dy) > slop)) gesture = 1
                    if (gesture == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                        main.removeCallbacks(hold)
                        gesture = if (onThrow != null && abs(dx) > abs(dy) * 1.3f) 2 else 1
                        view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                    }
                    when (gesture) {
                        1 -> {
                            val width = context.resources.displayMetrics.widthPixels
                            params.y = (startY + dy).toInt().coerceAtLeast(0)
                            params.x = (startX + if (fromEnd) -dx else dx).toInt().coerceIn(0, width / 2)
                            bubbles?.let { runCatching { wm.updateViewLayout(it, params) } }
                        }
                        2 -> {
                            view.translationX = dx
                            view.alpha = (1f - abs(dx) / (view.width * 1.2f)).coerceIn(0.15f, 1f)
                            view.rotation = dx / view.width * 8f
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    when (gesture) {
                        1 -> {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            settle(params, e.rawX)
                        }
                        3 -> {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            if (e.actionMasked == MotionEvent.ACTION_UP) {
                                if (onHold != null) onHold() else settle(params, e.rawX)
                            }
                        }
                        2 -> {
                            val dx = e.rawX - downX
                            val thrown = abs(dx) > view.width * 0.4f || (abs(vx) > fling && vx * dx > 0)
                            if (thrown) {
                                val dir = if (dx > 0) 1f else -1f
                                view.animate().translationX(dir * view.width * 1.6f).alpha(0f).rotation(dir * 14f)
                                    .setDuration(220).setInterpolator(DecelerateInterpolator())
                                    .withEndAction { onThrow?.invoke() }.start()
                            } else {
                                view.animate().translationX(0f).alpha(1f).rotation(0f)
                                    .setDuration(260).setInterpolator(OvershootInterpolator(1.6f)).start()
                            }
                        }
                        else -> {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                        }
                    }
                }
            }
            true
        }
    }

    /** After moving: the bubbles keep their place, on the side of the screen where they were left. */
    private fun settle(params: WindowManager.LayoutParams, rawX: Float) {
        val width = context.resources.displayMetrics.widthPixels
        val left = rawX < width / 2
        val root = bubbles ?: return
        if (left != Settings.bubblesLeft(context)) {
            // The same spot on screen, measured from the other edge.
            params.x = (width - params.x - root.width).coerceIn(0, width / 2)
            Settings.setBubblesLeft(context, left)
            params.gravity = Gravity.TOP or if (left) Gravity.START else Gravity.END
            runCatching { wm.updateViewLayout(root, params) }
            refreshBubbles(null)
        }
        Settings.setBubblesY(context, params.y)
        Settings.setBubblesX(context, params.x)
    }

    // ---------------------------------------------------------------- the panel

    private enum class Mode { READ, REPLY, QUICK, MUTE }
    private var mode = Mode.READ

    private fun createPanel(): LinearLayout {
        closePanel(animated = false)
        look = Look(context)
        val root = FrameLayout(context).apply {
            setBackgroundColor(look.scrim)
            setOnClickListener { closePanel() }
            alpha = 0f
            animate().alpha(1f).setDuration(250).start()
        }
        val dm = context.resources.displayMetrics
        val w = minOf((dm.widthPixels * 0.8f).toInt(), look.px(980))
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = look.round(look.panel, 36, stroke = true)
            elevation = look.px(24).toFloat()
            setPadding(look.px(34), look.px(30), look.px(34), look.px(30))
            isClickable = true
        }
        val onLeft = !Settings.bubblesLeft(context)
        root.addView(c, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER_VERTICAL or if (onLeft) Gravity.START else Gravity.END).apply {
            marginStart = look.px(60)
            marginEnd = look.px(60)
        })
        wm.addView(root, overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        panel = root
        card = c
        movable(c, root)
        // It grows out of the bubbles' corner.
        c.post {
            c.pivotX = if (onLeft) c.width.toFloat() else 0f
            c.pivotY = 0f
            c.scaleX = 0.2f
            c.scaleY = 0.2f
            c.alpha = 0f
            c.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        bubbles?.visibility = View.GONE
        return c
    }

    /**
     * The window moves where the finger takes it (touching anywhere that is not a button) and stays there the next
     * time; it never leaves the screen, also when it grows (answering, quick replies).
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun movable(c: View, root: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        fun keepIn() {
            if (root.width == 0 || c.width == 0) return
            c.translationX = c.translationX.coerceIn(-c.left.toFloat(), (root.width - c.right).toFloat())
            c.translationY = c.translationY.coerceIn(-c.top.toFloat(), maxOf(0, root.height - c.bottom).toFloat())
        }
        var placed = false
        c.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!placed && c.width > 0) {
                placed = true
                val x = Settings.panelX(context)
                val y = Settings.panelY(context)
                if (x >= 0 && y >= 0) {
                    c.translationX = (x - c.left).toFloat()
                    c.translationY = (y - c.top).toFloat()
                }
            }
            keepIn()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var moving = false
        c.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = v.translationX; startY = v.translationY; moving = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moving && (abs(dx) > slop || abs(dy) > slop)) {
                        moving = true
                        v.animate().scaleX(1.02f).scaleY(1.02f).setDuration(120).start()
                    }
                    if (moving) {
                        v.translationX = startX + dx
                        v.translationY = startY + dy
                        keepIn()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (moving) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    Settings.setPanelPlace(context, (v.left + v.translationX).toInt(), (v.top + v.translationY).toInt())
                }
            }
            true
        }
    }

    fun openPanel(key: String) {
        if (!allowed()) return
        createPanel()
        showRead(key, fade = false)
    }

    fun closePanel(animated: Boolean = true) {
        val root = panel ?: return
        dictation?.stop()
        dictation = null
        pulse?.cancel()
        speaker.stop()
        openKey?.let { if (Inbox.get(it) != null) Inbox.seen(it) }
        openKey = null
        panel = null
        card = null
        if (animated) {
            root.animate().alpha(0f).setDuration(200).withEndAction { runCatching { wm.removeView(root) } }.start()
        } else {
            runCatching { wm.removeView(root) }
        }
        main.post { refreshBubbles(null) }
    }

    private fun showNextOrClose() {
        val next = Inbox.all.firstOrNull()
        if (next == null) closePanel() else showRead(next.key, fade = true)
    }

    /** The message in big letters and the five big buttons. */
    private fun showRead(key: String, fade: Boolean) {
        val c = card ?: return
        val n = Inbox.get(key) ?: return closePanel()
        mode = Mode.READ
        openKey = key
        dictation?.stop()
        c.removeAllViews()
        c.addView(header(n))

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lines = n.lines
        val scale = Settings.textScale(context)
        if (n.voice) {
            body.addView(voiceRow(n))
        } else {
            lines.dropLast(1).takeLast(2).forEach { l ->
                body.addView(text(said(n, l.sender, l.text), 24f * scale, look.faint), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = look.px(10) })
            }
            val last = lines.lastOrNull()
            val words = if (last != null) said(n, last.sender, last.text) else n.text
            // A long message shrinks a little by itself, never below what can be read from the seat.
            val fit = when {
                words.length > 400 -> 0.72f
                words.length > 220 -> 0.85f
                else -> 1f
            }
            body.addView(text(words, (44f * scale * fit).coerceAtLeast(32f), look.ink, bold = true).apply {
                setLineSpacing(0f, 1.15f)
            })
        }
        // What doesn't fit scrolls with the finger; a small arrow that bobs says there is more.
        val scroll = ScrollView(context).apply { addView(body); isVerticalScrollBarEnabled = false; isFillViewport = true }
        val more = icon(R.drawable.ic_next, Look.ORANGE, 36, "Hay más texto").apply {
            rotation = 90f
            visibility = View.GONE
        }
        val textBox = FrameLayout(context).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(more, FrameLayout.LayoutParams(look.px(44), look.px(44), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        val maxH = (context.resources.displayMetrics.heightPixels * 0.52f).toInt()
        c.addView(textBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = look.px(22); bottomMargin = look.px(26)
        })
        scroll.post {
            if (scroll.height <= maxH) return@post
            scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
            more.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(more, View.TRANSLATION_Y, 0f, look.px(8).toFloat(), 0f).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE; start()
            }
            scroll.setOnScrollChangeListener { v, _, y, _, _ ->
                val end = !v.canScrollVertically(1)
                more.animate().alpha(if (end) 0f else 1f).setDuration(150).start()
            }
        }
        body.alpha = 0f
        body.translationY = look.px(16).toFloat()
        body.animate().alpha(1f).translationY(0f).setStartDelay(if (fade) 0 else 300).setDuration(380).start()

        val actions = row(Gravity.CENTER).apply { weightSum = 5f }
        val listen = action(R.drawable.ic_speaker, "Escuchar", Look.ORANGE, Look.ON_ORANGE) {
            if (speaker.speaking) speaker.stop() else speaker.read(n)
        }
        actions.addView(listen, weighted())
        speaker.onSpeaking = { on -> beat(listen.getChildAt(0), on) }
        if (n.canReply) {
            actions.addView(action(R.drawable.ic_mic, "Responder", Look.BLUE, Look.ON_BLUE) { showReply(key) }, weighted())
            actions.addView(action(R.drawable.ic_quick, "Rápida", look.chip, look.ink, accent = Look.ORANGE) { showQuick(key) }, weighted())
        }
        actions.addView(action(R.drawable.ic_phone, "Teléfono", look.chip, look.ink) {
            send(Protocol.message(Protocol.OPEN) { put("key", key) })
            done(key, closeAfter = true)
        }, weighted())
        actions.addView(action(R.drawable.ic_check, "Listo", look.chip, Look.GREEN) { done(key, closeAfter = false) }, weighted())
        c.addView(actions)
    }

    /** "Ana: ..." in a group; just the text in a chat with one person. */
    private fun said(n: Notice, sender: String, text: String): String =
        if (sender.isNotBlank() && sender != n.title) "$sender: $text" else text

    private fun header(n: Notice): View {
        val h = row(Gravity.CENTER_VERTICAL)
        h.addView(avatar(n, 76), LinearLayout.LayoutParams(look.px(76), look.px(76)))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(n.title.ifBlank { n.app }, 32f, look.ink, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
        names.addView(text("${n.app} · ${ago(n.time)}", 18f, look.soft))
        h.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(18) })
        val all = Inbox.all
        val at = all.indexOfFirst { it.key == n.key }
        if (all.size > 1) {
            h.addView(small(R.drawable.ic_prev, "Anterior") { showRead(all[(at - 1 + all.size) % all.size].key, fade = true) })
            val dots = row(Gravity.CENTER).apply { setPadding(look.px(10), 0, look.px(10), 0) }
            all.take(6).forEachIndexed { i, _ ->
                val d = View(context).apply { background = look.round(if (i == at) Look.ORANGE else look.off, 5) }
                dots.addView(d, LinearLayout.LayoutParams(look.px(if (i == at) 26 else 10), look.px(10)).apply { marginStart = look.px(4); marginEnd = look.px(4) })
            }
            h.addView(dots)
            h.addView(small(R.drawable.ic_next, "Siguiente") { showRead(all[(at + 1) % all.size].key, fade = true) })
        }
        h.addView(small(R.drawable.ic_close, "Cerrar") { closePanel() }, LinearLayout.LayoutParams(look.px(64), look.px(64)).apply { marginStart = look.px(12) })
        return h
    }

    /** A voice message: the radio can't play it; "Oír" opens it on the phone, which plays through the car. */
    private fun voiceRow(n: Notice): View {
        val r = row(Gravity.CENTER_VERTICAL).apply {
            background = look.round(look.chip, 28, stroke = true)
            setPadding(look.px(26), look.px(22), look.px(26), look.px(22))
        }
        r.addView(icon(R.drawable.ic_mic, Look.BLUE, 42, null).apply {
            background = look.oval(android.graphics.Color.argb(40, 56, 198, 244))
            setPadding(look.px(20), look.px(20), look.px(20), look.px(20))
        }, LinearLayout.LayoutParams(look.px(84), look.px(84)))
        r.addView(Wave(context, if (look.dark) 0xFF7FD8F7.toInt() else 0xFF1689B3.toInt()),
            LinearLayout.LayoutParams(0, look.px(70), 1f).apply { marginStart = look.px(22); marginEnd = look.px(22) })
        val play = row(Gravity.CENTER).apply {
            background = look.round(Look.ORANGE, 42)
            setPadding(look.px(26), 0, look.px(26), 0)
            contentDescription = "Oír en el teléfono"
            addView(icon(R.drawable.ic_play_phone, Look.ON_ORANGE, 34, null), LinearLayout.LayoutParams(look.px(34), look.px(34)))
            addView(text("Oír", 22f, Look.ON_ORANGE, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(10) })
            setOnClickListener {
                send(Protocol.message(Protocol.OPEN) { put("key", n.key) })
                done(n.key, closeAfter = true)
            }
        }
        r.addView(play, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(84)))
        ObjectAnimator.ofFloat(play, View.TRANSLATION_X, 0f, look.px(6).toFloat(), 0f).apply {
            duration = 1400; repeatCount = ValueAnimator.INFINITE; start()
        }
        return r
    }

    /** Dictating the answer: a big microphone with rings, the words as they are said, and send. */
    private fun showReply(key: String) {
        val c = card ?: return
        val n = Inbox.get(key) ?: return
        mode = Mode.REPLY
        speaker.stop()
        c.removeAllViews()
        val h = row(Gravity.CENTER_VERTICAL)
        h.addView(avatar(n, 56), LinearLayout.LayoutParams(look.px(56), look.px(56)))
        h.addView(text("→ ${n.title.ifBlank { n.app }}", 28f, look.ink, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16) })
        c.addView(h)

        val ripples = Ripples(context, Look.BLUE)
        val micSize = look.px(160)
        val mic = icon(R.drawable.ic_mic, Look.ON_BLUE, 72, "Escuchando").apply {
            background = look.oval(Look.BLUE)
            setPadding(look.px(44), look.px(44), look.px(44), look.px(44))
            elevation = look.px(12).toFloat()
        }
        val micBox = FrameLayout(context).apply {
            addView(ripples, FrameLayout.LayoutParams(look.px(300), look.px(300), Gravity.CENTER))
            addView(mic, FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER))
        }
        c.addView(micBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(250)))
        val words = text("", 42f, look.ink, bold = true).apply { gravity = Gravity.CENTER; minLines = 2 }
        c.addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = look.px(20) })

        var said = ""
        val d = Dictation(context,
            onWords = { w, final -> said = w; words.text = if (final) w else "$w…" },
            onLevel = { level -> mic.scaleX = 1f + level * 0.12f; mic.scaleY = mic.scaleX },
            onFail = { if (said.isBlank()) words.text = "No le oí. Toque ↻ para intentar otra vez."; ripples.stop() })
        dictation = d

        val actions = row(Gravity.CENTER)
        actions.addView(action(R.drawable.ic_close, "Cancelar", look.chip, Look.RED) { showRead(key, fade = true) }, spaced())
        actions.addView(action(R.drawable.ic_redo, "Otra vez", look.chip, look.ink) {
            said = ""; words.text = ""; ripples.start(); d.start()
        }, spaced())
        actions.addView(action(R.drawable.ic_send, "Enviar", Look.GREEN, Look.ON_GREEN) {
            if (said.isNotBlank()) {
                send(Protocol.message(Protocol.REPLY) { put("key", key).put("text", said) })
                done(key, closeAfter = false)
            }
        }, spaced())
        c.addView(actions)

        if (!d.available) {
            words.text = "Este radio no tiene dictado por voz. Use una respuesta rápida."
            return
        }
        speaker.onSpeaking = {}
        ripples.start()
        d.start()
    }

    /** Four answers of one touch. */
    private fun showQuick(key: String) {
        val c = card ?: return
        val n = Inbox.get(key) ?: return
        mode = Mode.QUICK
        c.removeAllViews()
        c.addView(header(n))
        c.addView(text("RESPUESTAS RÁPIDAS", 18f, look.soft, bold = true).apply { letterSpacing = 0.08f },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(24); bottomMargin = look.px(14) })
        val quick = listOf(
            Triple(R.drawable.ic_car, "Voy manejando", Look.ORANGE),
            Triple(R.drawable.ic_thumb, "Ok, perfecto", Look.GREEN),
            Triple(R.drawable.ic_call, "Te llamo luego", Look.BLUE),
            Triple(R.drawable.ic_clock, "Llego en 10 min", Look.PURPLE),
        )
        quick.chunked(2).forEachIndexed { r, pair ->
            val line = row(Gravity.CENTER_VERTICAL)
            pair.forEachIndexed { i, (ic, words, tint) ->
                val chip = row(Gravity.CENTER_VERTICAL).apply {
                    background = look.round(look.chip, 28, stroke = true)
                    setPadding(look.px(22), 0, look.px(22), 0)
                    contentDescription = words
                    val box = icon(ic, tint, 32, null).apply {
                        background = look.round(android.graphics.Color.argb(40, android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint)), 18)
                        setPadding(look.px(12), look.px(12), look.px(12), look.px(12))
                    }
                    addView(box, LinearLayout.LayoutParams(look.px(56), look.px(56)))
                    addView(text(words, 28f, look.ink, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(18) })
                    setOnClickListener {
                        send(Protocol.message(Protocol.REPLY) { put("key", key).put("text", words) })
                        done(key, closeAfter = false)
                    }
                }
                line.addView(chip, LinearLayout.LayoutParams(0, look.px(96), 1f).apply { if (i == 0) marginEnd = look.px(18) })
                chip.alpha = 0f
                chip.translationY = look.px(24).toFloat()
                chip.animate().alpha(1f).translationY(0f).setStartDelay((r * 2 + i) * 70L).setDuration(350).start()
            }
            c.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = look.px(18) })
        }
        val back = row(Gravity.CENTER)
        back.addView(action(R.drawable.ic_prev, "Volver", look.chip, look.ink) { showRead(key, fade = true) })
        c.addView(back)
    }

    /** Silence for a while, the arrival sound and reading aloud by itself. */
    /** Holding a bubble: silence that conversation for a while; below, the sound, reading aloud and the letters. */
    private fun openMute(n: Notice) {
        if (!allowed()) return
        val c = createPanel()
        mode = Mode.MUTE
        openKey = null
        val h = row(Gravity.CENTER_VERTICAL)
        h.addView(avatar(n, 64), LinearLayout.LayoutParams(look.px(64), look.px(64)))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        names.addView(row(Gravity.CENTER_VERTICAL).apply {
            addView(icon(R.drawable.ic_bell_off, look.ink, 30, null), LinearLayout.LayoutParams(look.px(30), look.px(30)))
            addView(text("Silenciar", 28f, look.ink, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(10) })
        })
        names.addView(text(n.title.ifBlank { n.app }, 20f, look.soft).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
        h.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(18) })
        h.addView(small(R.drawable.ic_close, "Cerrar") { closePanel() })
        c.addView(h)

        fun mute(until: Long) {
            Settings.mute(n, until)
            closePanel()
        }
        val minutes = listOf(15 to "15", 30 to "30", 60 to "60")
        val grid = row(Gravity.CENTER_VERTICAL)
        minutes.forEach { (m, label) ->
            val b = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = look.round(look.chip, 24, stroke = true)
                contentDescription = "Silenciar $m minutos"
                addView(text(label, 32f, look.ink, bold = true))
                addView(text("min", 16f, look.soft, bold = true))
                setOnClickListener { mute(System.currentTimeMillis() + m * 60_000L) }
            }
            grid.addView(b, LinearLayout.LayoutParams(0, look.px(100), 1f).apply { marginEnd = look.px(14) })
        }
        val untilOff = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = look.round(look.strong, 24)
            contentDescription = "Hasta apagar el carro"
            addView(icon(R.drawable.ic_power, look.strongInk, 40, null), LinearLayout.LayoutParams(look.px(40), look.px(40)))
            setOnClickListener { mute(Settings.UNTIL_OFF) }
        }
        grid.addView(untilOff, LinearLayout.LayoutParams(0, look.px(100), 1f))
        c.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(22) })

        c.addView(View(context).apply { setBackgroundColor(look.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(1).coerceAtLeast(1)).apply { topMargin = look.px(22); bottomMargin = look.px(12) })
        c.addView(toggle(R.drawable.ic_bell, "Sonido al llegar", Settings.sound(context)) { Settings.setSound(context, it) })
        c.addView(toggle(R.drawable.ic_speaker, "Leer en voz alta solo", Settings.autoRead(context)) { Settings.setAutoRead(context, it) })
        c.addView(textSize())
    }

    /** A− and A+: the size of the message's letters, shown as it changes. */
    private fun textSize(): View {
        val r = row(Gravity.CENTER_VERTICAL).apply { setPadding(look.px(6), look.px(10), look.px(6), look.px(10)) }
        val sample = text("Aa", 30f * Settings.textScale(context), look.ink, bold = true)
        r.addView(sample, LinearLayout.LayoutParams(look.px(90), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(text("Tamaño del texto", 24f, look.ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(6) })
        fun step(by: Float) {
            Settings.setTextScale(context, Settings.textScale(context) + by)
            sample.setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sp(30f * Settings.textScale(context)))
            sample.animate().scaleX(1.15f).scaleY(1.15f).setDuration(90).withEndAction { sample.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }.start()
        }
        listOf("A−" to -0.1f, "A+" to 0.1f).forEachIndexed { i, (label, by) ->
            val b = text(label, 26f, look.ink, bold = true).apply {
                gravity = Gravity.CENTER
                background = look.round(look.chip, 20, stroke = true)
                contentDescription = if (by < 0) "Letra más pequeña" else "Letra más grande"
                setOnClickListener { step(by) }
            }
            r.addView(b, LinearLayout.LayoutParams(look.px(88), look.px(64)).apply { if (i == 0) marginEnd = look.px(12) })
        }
        return r
    }

    /** "Listo": a green check grows, then the next conversation (or the panel closes). */
    private fun done(key: String, closeAfter: Boolean) {
        val c = card ?: return
        dictation?.stop()
        c.removeAllViews()
        val check = icon(R.drawable.ic_check, Look.ON_GREEN, 80, "Listo").apply {
            background = look.oval(Look.GREEN)
            setPadding(look.px(40), look.px(40), look.px(40), look.px(40))
            scaleX = 0.3f; scaleY = 0.3f
        }
        val box = FrameLayout(context).apply { addView(check, FrameLayout.LayoutParams(look.px(160), look.px(160), Gravity.CENTER)) }
        c.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(300)))
        check.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(2f)).setDuration(380).start()
        main.postDelayed({
            if (card !== c) return@postDelayed
            openKey = null
            Inbox.remove(key)
            if (closeAfter || Inbox.all.isEmpty()) closePanel() else showNextOrClose()
        }, 800)
    }

    // ---------------------------------------------------------------- pieces

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, look.sp(sp))
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun icon(res: Int, color: Int, sizeDp: Int, description: String?) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(color, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description
        minimumWidth = look.px(sizeDp)
        minimumHeight = look.px(sizeDp)
    }


    private fun row(gravity: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        this.gravity = gravity
    }

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun spaced() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = look.px(34); marginEnd = look.px(34)
    }

    /** A big round button with one word under it. */
    private fun action(res: Int, label: String, fill: Int, ink: Int, accent: Int? = null, onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val size = look.px(124)
        val btn = FrameLayout(context).apply {
            background = if (fill == look.chip) look.round(fill, 62, stroke = true) else look.oval(fill)
            contentDescription = label
            isClickable = true
            setOnClickListener {
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(80).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }.start()
                onClick()
            }
            addView(icon(res, accent ?: ink, 56, null), FrameLayout.LayoutParams(look.px(56), look.px(56), Gravity.CENTER))
        }
        col.addView(btn, LinearLayout.LayoutParams(size, size))
        col.addView(text(label, 18f, look.soft, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(10) })
        return col
    }

    private fun small(res: Int, description: String, onClick: () -> Unit) = FrameLayout(context).apply {
        background = look.round(look.chip, 32, stroke = true)
        contentDescription = description
        setOnClickListener { onClick() }
        addView(icon(res, look.ink, 28, null), FrameLayout.LayoutParams(look.px(28), look.px(28), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(look.px(64), look.px(64))
    }

    private fun toggle(res: Int, label: String, on: Boolean, change: (Boolean) -> Unit): View {
        val r = row(Gravity.CENTER_VERTICAL).apply { setPadding(look.px(6), look.px(10), look.px(6), look.px(10)) }
        r.addView(icon(res, look.ink, 32, null), LinearLayout.LayoutParams(look.px(32), look.px(32)))
        r.addView(text(label, 24f, look.ink, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16) })
        val track = FrameLayout(context)
        val knob = View(context).apply { background = look.oval(android.graphics.Color.WHITE) }
        track.addView(knob, FrameLayout.LayoutParams(look.px(36), look.px(36), Gravity.CENTER_VERTICAL))
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

    /** The contact's photo (or initial) in a circle, with the app's own icon at its corner. */
    private fun avatar(n: Notice, sizeDp: Int): View {
        val size = look.px(sizeDp)
        val frame = FrameLayout(context).apply { clipChildren = false }
        val face: View = if (n.photo != null) {
            ImageView(context).apply {
                setImageBitmap(n.photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
                }
                clipToOutline = true
            }
        } else {
            text((n.title.ifBlank { n.app }).take(1).uppercase(), sizeDp * 0.42f, android.graphics.Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = look.oval(Look.avatarColor(n.title))
            }
        }
        frame.addView(face, FrameLayout.LayoutParams(size, size))
        n.icon?.let { bmp ->
            val b = look.px(sizeDp * 0.42f)
            val badge = ImageView(context).apply {
                setImageBitmap(bmp)
                background = look.oval(look.solidBack)
                val p = look.px(2)
                setPadding(p, p, p, p)
            }
            frame.addView(badge, FrameLayout.LayoutParams(b, b, Gravity.END or Gravity.BOTTOM).apply {
                marginEnd = -look.px(4); bottomMargin = -look.px(4)
            })
        }
        return frame
    }

    /** While it reads, the speaker button shows bars that move like a voice, inside the button (nothing grows). */
    private fun beat(v: View, on: Boolean) {
        pulse?.cancel()
        val btn = v as? FrameLayout ?: return
        val icon = btn.getChildAt(0)
        (btn.findViewWithTag<View>("bars"))?.let { btn.removeView(it) }
        icon.alpha = if (on) 0f else 1f
        if (!on) return
        val bars = SpeakBars(context, Look.ON_ORANGE).apply { tag = "bars" }
        btn.addView(bars, FrameLayout.LayoutParams(look.px(64), look.px(52), Gravity.CENTER))
    }

    private fun ago(time: Long): String {
        val min = (System.currentTimeMillis() - time) / 60_000
        return when {
            min < 1 -> "ahora"
            min < 60 -> "hace $min min"
            else -> "hace ${min / 60} h"
        }
    }
}

/** Three rings that grow out of the microphone while it listens. */
class Ripples(context: Context, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * context.resources.displayMetrics.density
    }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2100; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    fun start() { if (!anim.isRunning) anim.start() }
    fun stop() { anim.cancel(); t = 0f; invalidate() }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel() }

    override fun onDraw(canvas: Canvas) {
        if (!anim.isRunning) return
        val max = minOf(width, height) / 2f
        val base = max * 0.53f
        for (i in 0 until 3) {
            val p = (t + i / 3f) % 1f
            paint.color = color
            paint.alpha = ((1f - p) * 180).toInt()
            canvas.drawCircle(width / 2f, height / 2f, base + (max - base) * p, paint)
        }
    }
}

/** Five bars that rise and fall like someone talking, for the speaker button while it reads. */
class SpeakBars(context: Context, color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val speed = floatArrayOf(1.0f, 1.7f, 1.3f, 2.1f, 1.5f)
    private val phase = floatArrayOf(0f, 1.1f, 2.3f, 0.6f, 1.8f)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, (Math.PI * 20).toFloat()).apply {
        duration = 9000; repeatCount = ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel() }

    override fun onDraw(canvas: Canvas) {
        val n = speed.size
        val bar = width / (n * 1.8f)
        val gap = (width - bar * n) / (n - 1)
        for (i in 0 until n) {
            // Two waves together, so it never looks like a regular pulse.
            val k = 0.25f + 0.75f * ((kotlin.math.sin(t * speed[i] + phase[i]) * 0.6f + kotlin.math.sin(t * 2.7f + i) * 0.4f) * 0.5f + 0.5f)
            val h = height * k
            val x = i * (bar + gap)
            canvas.drawRoundRect(x, (height - h) / 2, x + bar, (height + h) / 2, bar / 2, bar / 2, paint)
        }
    }
}

/** The bars of a voice message, moving softly. */
class Wave(context: Context, private val color: Int) : View(context) {
    private val heights = floatArrayOf(.3f, .5f, .75f, .55f, .9f, .4f, .65f, 1f, .5f, .35f, .8f, .9f, .5f, .3f, .6f, .85f, .45f, .7f, .95f, .55f, .4f, .7f, .5f, .35f, .6f, .4f, .3f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
        duration = 1400; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); anim.cancel() }

    override fun onDraw(canvas: Canvas) {
        val n = heights.size
        val gap = width / (n * 1.8f)
        val bar = gap * 0.8f
        for (i in 0 until n) {
            val k = 0.7f + 0.3f * kotlin.math.sin(t + i * 0.6f)
            val h = height * heights[i] * k
            val x = i * (bar + gap)
            canvas.drawRoundRect(x, (height - h) / 2, x + bar, (height + h) / 2, bar / 2, bar / 2, paint)
        }
    }
}

