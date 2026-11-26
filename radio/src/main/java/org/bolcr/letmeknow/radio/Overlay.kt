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
        // A reading that ends lets the next thing in line sound.
        speaker.onIdle = { next() }
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
        ready?.second?.let { runCatching { it.release() } }
        ready = null
        speaker.shutdown()
    }

    private fun allowed() = AndroidSettings.canDrawOverlays(context)

    private companion object {
        /** How long a bubble shows its name before folding into its ball. */
        const val FOLD_MS = 5_000L
    }

    private fun changed(arrived: Notice?) {
        if (arrived?.audio != null) preload(arrived)
        if (arrived != null && !Settings.muted(arrived)) {
            if (Settings.sound(context)) speaker.chime()
            arrive(arrived)
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
        // The one that just arrived comes in whole, with its name, and folds in 5 seconds.
        if (arrived != null) unfolded[arrived.key] = unfolded[arrived.key] ?: Runnable {}
        notices.take(4).forEach { n ->
            val b = bubble(n)
            if (n.key == arrived?.key) foldLater(n.key, b)
            root.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(84)).apply { if (!first) topMargin = look.px(22) })
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
            // Room for the bubbles' shadow, which is big on purpose.
            val p = look.px(38)
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
            // A deep, dark shadow drawn by hand (Android's own is barely visible on the radio): the bubble floats
            // apart from everything behind it. It spills over the bubbles' padding, which doesn't clip it.
            background = FloatShadow(look.round(look.glass, 42, stroke = true),
                android.graphics.Color.argb(if (look.dark) 245 else 170, 0, 0, 0), look.px(22).toFloat(), look.px(12).toFloat())
            setPadding(look.px(10), 0, look.px(12), 0)
            contentDescription = "${n.title}, ${n.app}"
            // The numbers and marks on the photo's corners stick out of it; they must not be cut.
            clipChildren = false
            clipToPadding = false
        }
        row.addView(avatar(n, 64), LinearLayout.LayoutParams(look.px(64), look.px(64)))
        val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text(n.title.ifBlank { n.app }, 24f, look.ink, bold = true).apply {
            maxWidth = look.px(230)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        words.addView(text("${appLine(n)} · ${ago(n.time)}", 16f, look.soft))
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
        // The small number on the ball's photo, for when the bubble is folded.
        val mini = text(Inbox.count(n.key).toString(), 15f, Look.ON_ORANGE, bold = true).apply {
            gravity = Gravity.CENTER
            background = look.round(Look.ORANGE, 15)
            minWidth = look.px(30)
            includeFontPadding = false
            setPadding(look.px(6), 0, look.px(6), 0)
            alpha = 0f
            scaleX = 0.4f; scaleY = 0.4f
        }
        (row.getChildAt(0) as FrameLayout).addView(mini, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(30),
            (if (n.work) Gravity.START else Gravity.END) or Gravity.TOP).apply {
            if (n.work) marginStart = -look.px(6) else marginEnd = -look.px(6)
            topMargin = -look.px(6)
        })
        if (unfolded[n.key] == null) fold(row, animated = false)
        drag(row, onThrow = { Inbox.remove(n.key) }, onHold = { openMute(n) }) {
            // Folded: the first touch shows the name again; the next one opens the message.
            if (unfolded[n.key] == null) { unfold(n.key, row); foldLater(n.key, row) } else openPanel(n.key)
        }
        return row
    }

    /** Conversations showing their name now (just arrived, or touched once); the others are just the ball. */
    private val unfolded = HashMap<String, Runnable>()

    /** In 5 seconds, if nothing else happened, [row] folds into its ball. */
    private fun foldLater(key: String, row: View) {
        unfolded[key]?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (unfolded.remove(key) != null && row.isAttachedToWindow) fold(row as ViewGroup, animated = true)
        }
        unfolded[key] = r
        main.postDelayed(r, FOLD_MS)
    }

    /**
     * Folds the bubble into a ball: the name fades and narrows away, the big number too, and a small number appears
     * on the photo. [unfold] does the reverse.
     */
    private fun fold(row: ViewGroup, animated: Boolean) = shape(row, folded = true, animated = animated)

    private fun unfold(key: String, row: View) {
        unfolded[key] = unfolded[key] ?: Runnable {}
        shape(row as ViewGroup, folded = false, animated = true)
    }

    private fun shape(row: ViewGroup, folded: Boolean, animated: Boolean) {
        val words = row.getChildAt(1)
        val badge = row.getChildAt(2)
        val mini = (row.getChildAt(0) as ViewGroup).let { it.getChildAt(it.childCount - 1) }
        fun natural(v: View): Int {
            v.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            return v.measuredWidth
        }
        val wordsW = natural(words)
        val badgeW = natural(badge)
        val gap = look.px(14)
        val endPad = look.px(12)
        val ballPad = look.px(10)
        fun at(k: Float) {
            // k = 1 open, 0 folded.
            (words.layoutParams as LinearLayout.LayoutParams).apply {
                width = (wordsW * k).toInt()
                marginStart = (gap * k).toInt(); marginEnd = (gap * k).toInt()
            }
            badge.layoutParams.width = (badgeW * k).toInt()
            words.alpha = (k * 1.6f - 0.6f).coerceIn(0f, 1f)
            badge.alpha = k
            mini.alpha = 1 - k
            mini.scaleX = 0.4f + 0.6f * (1 - k); mini.scaleY = mini.scaleX
            row.setPadding(ballPad, 0, (ballPad + (endPad - ballPad) * k).toInt(), 0)
            row.requestLayout()
        }
        val from = if (folded) 1f else 0f
        val to = 1f - from
        if (!animated) { at(to); return }
        ValueAnimator.ofFloat(from, to).apply {
            duration = 420
            interpolator = if (folded) DecelerateInterpolator(1.6f) else OvershootInterpolator(1.1f)
            addUpdateListener { at((it.animatedValue as Float).coerceIn(0f, 1.05f)) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // Back to its own size when open, so a longer name later still fits.
                    if (!folded) { words.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT; badge.layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT; row.requestLayout() }
                }
            })
            start()
        }
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
     * Touching opens. Dragging up or down, or holding a moment and then dragging anywhere, moves all the bubbles (also
     * to the other side of the screen), so they never cover what is behind. Holding still fills an orange ring around the photo and opens [onHold] by itself. Pulling
     * a conversation sideways lifts it into its own little window that follows the finger anywhere; past half its
     * width a red ✕ shows, and only lifting the finger with the ✕ showing removes it ([onThrow]). Coming back cancels.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun drag(v: View, onThrow: (() -> Unit)? = null, onHold: (() -> Unit)? = null, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var downX = 0f
        var startY = 0
        var startX = 0
        // 0 nothing yet, 1 moving the bubbles, 2 throwing this one, 4 the hold already opened its window.
        var gesture = 0
        val ring = if (onHold != null) HoldRing(look.px(10) + look.px(32), look.px(42).toFloat(), look.px(5).toFloat()).also { v.foreground = it } else null
        var filling: ValueAnimator? = null
        var ghost: Ghost? = null
        fun stopFilling() {
            filling?.cancel()
            filling = null
            ring?.progress = 0f
        }
        v.setOnTouchListener { view, e ->
            val params = bubblesParams ?: return@setOnTouchListener false
            val fromEnd = params.gravity and Gravity.END == Gravity.END
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startY = params.y; startX = params.x; gesture = 0
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                    if (ring != null) {
                        filling = ValueAnimator.ofFloat(0f, 1f).apply {
                            startDelay = 150
                            duration = 750
                            addUpdateListener { ring.progress = it.animatedValue as Float }
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                private var cancelled = false
                                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                                override fun onAnimationEnd(a: android.animation.Animator) {
                                    if (cancelled || gesture != 0) return
                                    gesture = 4
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                                    ring.progress = 0f
                                    onHold?.invoke()
                                }
                            })
                            start()
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (gesture == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                        // Held a moment first (the ring had started): move the bubbles anywhere. A quick sideways
                        // pull without holding: throw this one. Up or down: move.
                        val held = (ring?.progress ?: 0f) > 0f
                        stopFilling()
                        view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        gesture = if (!held && onThrow != null && abs(dx) > abs(dy) * 1.3f) 2 else 1
                        if (held) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        if (gesture == 2) ghost = Ghost(view)
                    }
                    when (gesture) {
                        1 -> {
                            val width = context.resources.displayMetrics.widthPixels
                            params.y = (startY + dy).toInt().coerceAtLeast(0)
                            // All the way across the screen; on letting go they stick to the nearer side.
                            val room = (width - (bubbles?.width ?: 0)).coerceAtLeast(0)
                            params.x = (startX + if (fromEnd) -dx else dx).toInt().coerceIn(0, room)
                            bubbles?.let { runCatching { wm.updateViewLayout(it, params) } }
                        }
                        2 -> ghost?.follow(dx, dy)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopFilling()
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    when (gesture) {
                        1 -> settle(params, e.rawX)
                        2 -> {
                            val g = ghost
                            ghost = null
                            if (g != null && g.past && e.actionMasked == MotionEvent.ACTION_UP) g.away { onThrow?.invoke() }
                            else g?.back()
                        }
                        0 -> if (e.actionMasked == MotionEvent.ACTION_UP) onTap()
                    }
                }
            }
            true
        }
    }

    /**
     * A conversation being thrown: its picture in its own small window, so it can go anywhere on the screen without
     * being cut by the bubbles' window. The real bubble waits, hidden, in its place.
     */
    private inner class Ghost(private val real: View) {
        private val home = IntArray(2).also { real.getLocationOnScreen(it) }
        private val box = FrameLayout(context)
        private val cross: View
        private val params = WindowManager.LayoutParams(
            real.width, real.height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = home[0]
            y = home[1]
        }
        /** Past half its width: lifting the finger now removes it. */
        var past = false
            private set

        init {
            val picture = android.graphics.Bitmap.createBitmap(real.width.coerceAtLeast(1), real.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
            real.draw(Canvas(picture))
            box.addView(ImageView(context).apply { setImageBitmap(picture) }, FrameLayout.LayoutParams(real.width, real.height))
            val size = look.px(64)
            cross = icon(R.drawable.ic_close, android.graphics.Color.WHITE, 30, null).apply {
                background = look.oval(Look.RED)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(look.px(17), look.px(17), look.px(17), look.px(17))
                alpha = 0f
                scaleX = 0.6f; scaleY = 0.6f
            }
            box.addView(cross, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
            runCatching { wm.addView(box, params) }
            real.visibility = View.INVISIBLE
        }

        fun follow(dx: Float, dy: Float) {
            params.x = (home[0] + dx).toInt()
            params.y = (home[1] + dy * 0.4f).toInt()
            runCatching { wm.updateViewLayout(box, params) }
            box.rotation = dx / real.width * 8f
            val now = abs(dx) > real.width * 0.5f
            if (now != past) {
                past = now
                if (now) real.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                cross.animate().alpha(if (now) 1f else 0f).scaleX(if (now) 1f else 0.6f).scaleY(if (now) 1f else 0.6f).setDuration(140).start()
            }
            box.alpha = if (now) 0.75f else 1f
        }

        /** Off the screen the way it was going, then gone. */
        fun away(then: () -> Unit) {
            val dir = if (params.x >= home[0]) 1 else -1
            val from = params.x
            val to = from + dir * (context.resources.displayMetrics.widthPixels / 2 + real.width)
            ValueAnimator.ofInt(from, to).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { params.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(box, params) } }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        runCatching { wm.removeView(box) }
                        then()
                    }
                })
                start()
            }
            box.animate().alpha(0f).setDuration(220).start()
        }

        /** Changed its mind: back to its place. */
        fun back() {
            val fx = params.x
            val fy = params.y
            cross.animate().alpha(0f).setDuration(100).start()
            box.animate().rotation(0f).alpha(1f).setDuration(260).start()
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 260
                interpolator = OvershootInterpolator(1.4f)
                addUpdateListener {
                    val k = it.animatedValue as Float
                    params.x = (home[0] + (fx - home[0]) * k).toInt()
                    params.y = (home[1] + (fy - home[1]) * k).toInt()
                    runCatching { wm.updateViewLayout(box, params) }
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) {
                        real.visibility = View.VISIBLE
                        runCatching { wm.removeView(box) }
                    }
                })
                start()
            }
        }
    }

    /** After moving: the bubbles keep their place, on the side of the screen where they were left. */
    private fun settle(params: WindowManager.LayoutParams, rawX: Float) {
        val width = context.resources.displayMetrics.widthPixels
        val left = rawX < width / 2
        val root = bubbles ?: return
        if (left != Settings.bubblesLeft(context)) {
            // The same spot on screen, measured from the other edge.
            params.x = (width - params.x - root.width).coerceIn(0, (width - root.width).coerceAtLeast(0))
            Settings.setBubblesLeft(context, left)
            params.gravity = Gravity.TOP or if (left) Gravity.START else Gravity.END
            runCatching { wm.updateViewLayout(root, params) }
            // After the touch ends: taking away the bubble that is being touched, inside its own touch, crashed the app.
            main.post { refreshBubbles(null) }
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
        // The radio's back button closes the window.
        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                    if (event.action == android.view.KeyEvent.ACTION_UP && panel === this) {
                        if (big != null) closeBig() else closePanel()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isFocusableInTouchMode = true
            setBackgroundColor(look.scrim)
            setOnClickListener { closePanel() }
            alpha = 0f
            animate().alpha(1f).setDuration(250).start()
        }
        val c = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = look.round(look.panel, 36, stroke = true)
            elevation = look.px(24).toFloat()
            setPadding(look.px(34), look.px(30), look.px(34), look.px(30))
            isClickable = true
        }
        // As big as the screen lets it (a small margin, below the radio's top bar), so nothing is ever cut.
        val onLeft = !Settings.bubblesLeft(context)
        val bar = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it != 0) context.resources.getDimensionPixelSize(it) else 0 }
        val m = look.px(18)
        root.addView(c, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(m, bar + m, m, m)
        })
        // Focusable, unlike the bubbles, so it hears the back button.
        wm.addView(root, overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        })
        root.requestFocus()
        listenHome()
        panel = root
        card = c
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

    /** The radio's home (and recent apps) button: no app hears it, but Android tells everyone it closes its menus. */
    private var home: android.content.BroadcastReceiver? = null

    private fun listenHome() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                val why = i.getStringExtra("reason")
                if (why == "homekey" || why == "recentapps") main.post { closePanel() }
            }
        }
        @Suppress("DEPRECATION")
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(r, filter)
            home = r
        } catch (_: Exception) {
        }
    }

    fun openPanel(key: String) {
        if (!allowed()) return
        createPanel()
        showRead(key, fade = false)
    }

    fun closePanel(animated: Boolean = true) {
        val root = panel ?: return
        main.post { releaseLater() }
        home?.let { runCatching { context.unregisterReceiver(it) } }
        home = null
        stopAudio()
        big = null
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

    /** The way each kind of window sits in the card: the message uses all of it, the others are centred. */
    private fun fill(c: LinearLayout, whole: Boolean) {
        c.removeAllViews()
        if (whole) {
            c.setPadding(0, 0, 0, 0)
            c.gravity = Gravity.TOP
        } else {
            c.setPadding(look.px(34), look.px(26), look.px(34), look.px(26))
            c.gravity = Gravity.CENTER_VERTICAL
        }
    }

    /**
     * The message: on the left, the driver's side, a column with the big buttons (they grow or shrink to the
     * radio's height); on top the name with A−, A+, silence, the others, Listo and close; the words get the rest.
     */
    private fun showRead(key: String, fade: Boolean) {
        val c = card ?: return
        val n = Inbox.get(key) ?: return closePanel()
        mode = Mode.READ
        openKey = key
        dictation?.stop()
        stopAudio()
        // It arrived while another one was open: now that it is in front, it is read (or its audio plays).
        if (later.remove(key)) {
            if (n.audio != null) startRow = key
            else main.postDelayed({ if (openKey == key && !busy()) speaker.read(n) else queue.addFirst(Turn(key, whole = true)) }, 500)
        }
        fill(c, whole = true)
        val whole = row(Gravity.TOP)
        c.addView(whole, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The column of buttons.
        val dm = context.resources.displayMetrics
        val label = look.px(28)
        val room = (c.height.takeIf { it > 0 } ?: (dm.heightPixels - look.px(120))) - look.px(36)
        val count = if (n.canReply) 4 else 2
        val size = ((room - count * label) / count - look.px(14)).coerceIn(look.px(64), look.px(112))
        val rail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(look.px(16), look.px(18), look.px(16), look.px(18))
            background = look.round(look.chip, 36)
        }
        fun place(v: View, i: Int) {
            rail.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 0, 1f))
            v.alpha = 0f
            v.translationX = -look.px(24).toFloat()
            v.animate().alpha(1f).translationX(0f).setStartDelay(i * 60L).setDuration(300).start()
        }
        val listen = action(R.drawable.ic_speaker, "Escuchar", Look.ORANGE, Look.ON_ORANGE, size = size) {
            if (speaker.speaking) { speaker.stop(); next() } else speaker.read(n)
        }
        place(listen, 0)
        speaker.onSpeaking = { on -> beat(listen.getChildAt(0), on) }
        if (n.canReply) {
            place(action(R.drawable.ic_mic, "Responder", Look.BLUE, Look.ON_BLUE, size = size) { showReply(key) }, 1)
            place(action(R.drawable.ic_quick, "Rápida", look.chip, look.ink, accent = Look.ORANGE, size = size) { showQuick(key) }, 2)
        }
        place(action(R.drawable.ic_phone, "Teléfono", look.chip, look.ink, size = size) {
            send(Protocol.message(Protocol.OPEN) { put("key", key) })
            if (n.voice) CarAudio.open(context)
            done(key, closeAfter = true)
        }, 3)
        whole.addView(rail, LinearLayout.LayoutParams(size + look.px(40), ViewGroup.LayoutParams.MATCH_PARENT))

        // The name on top, the words below.
        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(look.px(28), look.px(20), look.px(24), look.px(20))
        }
        whole.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        right.addView(header(n, full = true))
        right.addView(View(context).apply { setBackgroundColor(look.edge) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(1).coerceAtLeast(1)).apply { topMargin = look.px(16); bottomMargin = look.px(14) })

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val lines = n.lines
        val scale = Settings.textScale(context)
        if (n.audio != null) {
            body.addView(audioRow(n))
        } else if (n.voice) {
            body.addView(voiceRow(n))
        } else {
            lines.dropLast(1).takeLast(2).forEach { l ->
                body.addView(text(said(n, l.sender, l.text), 23f * scale, look.faint), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = look.px(8) })
            }
            val last = lines.lastOrNull()
            val words = if (last != null) said(n, last.sender, last.text) else n.text
            // A long message shrinks a little by itself, never below what can be read from the seat.
            val fit = when {
                words.length > 400 -> 0.72f
                words.length > 220 -> 0.85f
                else -> 1f
            }
            val shown = text(words, (42f * scale * fit).coerceAtLeast(30f), look.ink, bold = true).apply {
                setLineSpacing(0f, 1.12f)
            }
            val media = n.media
            if (media != null) {
                // A picture or a video: its thumbnail beside the words; touching it shows it big.
                val side = minOf(look.px(300), (room * 0.48f).toInt())
                val withPicture = row(Gravity.TOP)
                withPicture.addView(thumb(n, media, side), LinearLayout.LayoutParams(side, side))
                withPicture.addView(shown, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(20) })
                body.addView(withPicture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(4) })
            } else {
                body.addView(shown, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(4) })
            }
        }
        // What doesn't fit scrolls with the finger; a small arrow that bobs says there is more.
        val scroll = ScrollView(context).apply { addView(body); isVerticalScrollBarEnabled = false }
        val more = icon(R.drawable.ic_next, Look.ORANGE, 36, "Hay más texto").apply {
            rotation = 90f
            background = look.oval(look.panel)
            visibility = View.GONE
        }
        val textBox = FrameLayout(context).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(more, FrameLayout.LayoutParams(look.px(48), look.px(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
        right.addView(textBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Several conversations: a low bar at the bottom, and a sideways swipe on the words does the same.
        val all = Inbox.all
        val at = all.indexOfFirst { it.key == n.key }
        if (all.size > 1) {
            fun go(by: Int) {
                val next = all[(at + by + all.size) % all.size].key
                textBox.animate().translationX(-by * look.px(80).toFloat()).alpha(0f).setDuration(140)
                    .withEndAction { showRead(next, fade = true) }.start()
            }
            val bar = row(Gravity.CENTER_VERTICAL)
            fun flat(res: Int, description: String, by: Int) = FrameLayout(context).apply {
                background = look.round(look.chip, 26, stroke = true)
                contentDescription = description
                setOnClickListener { go(by) }
                addView(icon(res, look.ink, 30, null).apply { minimumWidth = 0; minimumHeight = 0 },
                    FrameLayout.LayoutParams(look.px(30), look.px(30), Gravity.CENTER))
            }
            bar.addView(flat(R.drawable.ic_prev, "Anterior", -1), LinearLayout.LayoutParams(look.px(170), look.px(52)))
            val dots = row(Gravity.CENTER)
            all.take(8).forEachIndexed { i, _ ->
                val d = View(context).apply { background = look.round(if (i == at) Look.ORANGE else look.off, 5) }
                dots.addView(d, LinearLayout.LayoutParams(look.px(if (i == at) 26 else 10), look.px(10)).apply { marginStart = look.px(4); marginEnd = look.px(4) })
            }
            bar.addView(dots, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(flat(R.drawable.ic_next, "Siguiente", 1), LinearLayout.LayoutParams(look.px(170), look.px(52)))
            right.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(12) })

            val far = look.px(110)
            var sx = 0f
            var sy = 0f
            scroll.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        if (abs(dx) > abs(e.rawY - sy) * 1.5f) textBox.translationX = dx * 0.5f
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dx = e.rawX - sx
                        val swiped = e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > far && abs(dx) > abs(e.rawY - sy) * 1.5f
                        // To the left shows the next one, to the right the one before, like turning pages.
                        if (swiped) go(if (dx < 0) 1 else -1) else textBox.animate().translationX(0f).setDuration(150).start()
                    }
                }
                false
            }
        }
        scroll.post {
            if (!scroll.canScrollVertically(1)) return@post
            more.visibility = View.VISIBLE
            ObjectAnimator.ofFloat(more, View.TRANSLATION_Y, 0f, look.px(8).toFloat(), 0f).apply {
                duration = 1000; repeatCount = ValueAnimator.INFINITE; start()
            }
            scroll.setOnScrollChangeListener { v, _, _, _, _ ->
                val end = !v.canScrollVertically(1)
                more.animate().alpha(if (end) 0f else 1f).setDuration(150).start()
            }
        }
        body.alpha = 0f
        body.translationY = look.px(16).toFloat()
        body.animate().alpha(1f).translationY(0f).setStartDelay(if (fade) 0 else 300).setDuration(380).start()
    }

    private fun appLine(n: Notice) = if (n.work) "${n.app} · Trabajo" else n.app

    /** "Ana: ..." in a group; just the text in a chat with one person. */
    private fun said(n: Notice, sender: String, text: String): String =
        if (sender.isNotBlank() && sender != n.title) "$sender: $text" else text

    /**
     * Who, and the small buttons. [full] (the message window) adds A− and A+, silencing this conversation and the
     * green Listo, all in the same line.
     */
    private fun header(n: Notice, full: Boolean = false): View {
        val h = row(Gravity.CENTER_VERTICAL)
        h.addView(avatar(n, 68), LinearLayout.LayoutParams(look.px(68), look.px(68)))
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        names.addView(text(n.title.ifBlank { n.app }, 30f, look.ink, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false })
        names.addView(text("${appLine(n)} · ${ago(n.time)}", 17f, look.soft).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(4) })
        h.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16); marginEnd = look.px(10) })
        fun gap() = h.addView(View(context).apply { setBackgroundColor(look.edge) },
            LinearLayout.LayoutParams(look.px(1).coerceAtLeast(1), look.px(44)).apply { marginStart = look.px(10); marginEnd = look.px(10) })
        fun smallAt(v: View, first: Boolean = false) = h.addView(v, LinearLayout.LayoutParams(look.px(64), look.px(64)).apply { if (!first) marginStart = look.px(10) })
        if (full) {
            smallAt(smallText("A−", "Letra más pequeña") { Settings.setTextScale(context, Settings.textScale(context) - 0.1f); showRead(n.key, fade = true) }, first = true)
            smallAt(smallText("A+", "Letra más grande") { Settings.setTextScale(context, Settings.textScale(context) + 0.1f); showRead(n.key, fade = true) })
            smallAt(small(R.drawable.ic_bell_off, "Silenciar esta conversación", tint = Look.PURPLE) { openMute(n) })
        }
        val all = Inbox.all
        val at = all.indexOfFirst { it.key == n.key }
        if (all.size > 1 && !full) {
            smallAt(small(R.drawable.ic_prev, "Anterior") { showRead(all[(at - 1 + all.size) % all.size].key, fade = true) })
            val dots = row(Gravity.CENTER).apply { setPadding(look.px(6), 0, look.px(6), 0) }
            all.take(6).forEachIndexed { i, _ ->
                val d = View(context).apply { background = look.round(if (i == at) Look.ORANGE else look.off, 5) }
                dots.addView(d, LinearLayout.LayoutParams(look.px(if (i == at) 24 else 9), look.px(9)).apply { marginStart = look.px(3); marginEnd = look.px(3) })
            }
            h.addView(dots, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(6) })
            smallAt(small(R.drawable.ic_next, "Siguiente") { showRead(all[(at + 1) % all.size].key, fade = true) })
        }
        if (full) {
            gap()
            val ok = row(Gravity.CENTER).apply {
                background = look.round(Look.GREEN, 32)
                setPadding(look.px(16), 0, look.px(22), 0)
                contentDescription = "Listo"
                addView(icon(R.drawable.ic_check, Look.ON_GREEN, 30, null), LinearLayout.LayoutParams(look.px(30), look.px(30)))
                addView(text("Listo", 20f, Look.ON_GREEN, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(8) })
                setOnClickListener { done(n.key, closeAfter = false) }
            }
            h.addView(ok, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(64)))
        }
        smallAt(small(R.drawable.ic_close, "Cerrar") { closePanel() })
        return h
    }

    /** The picture shown big over the whole window, if it is. */
    private var big: View? = null

    /** The thumbnail: the picture cut square, a play sign and the length on a video, and the corner arrows. */
    private fun thumb(n: Notice, media: android.graphics.Bitmap, side: Int): View {
        val f = FrameLayout(context).apply {
            background = look.round(look.chip, 22)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            contentDescription = if (n.video) "Ver el video en grande" else "Ver la foto en grande"
            setOnClickListener { showBig(n, media) }
        }
        f.addView(ImageView(context).apply { setImageBitmap(media); scaleType = ImageView.ScaleType.CENTER_CROP },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val shade = android.graphics.Color.argb(140, 0, 0, 0)
        if (n.video) {
            val play = (side * 0.27f).toInt()
            f.addView(icon(R.drawable.ic_play, android.graphics.Color.WHITE, 34, null).apply {
                background = look.oval(shade)
                val p = (play * 0.28f).toInt()
                setPadding(p, p, p, p)
            }, FrameLayout.LayoutParams(play, play, Gravity.CENTER))
            if (n.length.isNotEmpty()) {
                f.addView(text(n.length, 16f, android.graphics.Color.WHITE, bold = true).apply {
                    background = look.round(shade, 10)
                    setPadding(look.px(10), look.px(4), look.px(10), look.px(4))
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM).apply {
                    marginEnd = look.px(12); bottomMargin = look.px(12)
                })
            }
        }
        f.addView(icon(R.drawable.ic_expand, android.graphics.Color.WHITE, 22, null).apply {
            background = look.oval(shade)
            setPadding(look.px(9), look.px(9), look.px(9), look.px(9))
        }, FrameLayout.LayoutParams(look.px(40), look.px(40), Gravity.START or Gravity.TOP).apply {
            marginStart = look.px(12); topMargin = look.px(12)
        })
        f.scaleX = 0.4f; f.scaleY = 0.4f; f.alpha = 0f
        f.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(OvershootInterpolator(1.2f)).start()
        return f
    }

    /** The picture as big as the window lets; touching anywhere (or the radio's back button) goes back. */
    private fun showBig(n: Notice, media: android.graphics.Bitmap) {
        val root = panel as? FrameLayout ?: return
        closeBig()
        speaker.stop()
        val cover = FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.argb(210, 0, 0, 0))
            isClickable = true
            setOnClickListener { closeBig() }
            alpha = 0f
            animate().alpha(1f).setDuration(200).start()
        }
        val dm = context.resources.displayMetrics
        val room = minOf(dm.widthPixels, dm.heightPixels) - look.px(80)
        val k = minOf(room.toFloat() / media.width, room.toFloat() / media.height, 3f)
        val pic = ImageView(context).apply {
            setImageBitmap(media)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = look.round(android.graphics.Color.BLACK, 26)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            scaleX = 0.4f; scaleY = 0.4f
            animate().scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
        cover.addView(pic, FrameLayout.LayoutParams((media.width * k).toInt(), (media.height * k).toInt(), Gravity.CENTER))
        if (n.video) {
            // The radio only has the first frame: the video itself plays on the phone.
            cover.addView(row(Gravity.CENTER).apply {
                background = look.round(Look.ORANGE, 36)
                setPadding(look.px(24), 0, look.px(26), 0)
                contentDescription = "Ver el video en el teléfono"
                addView(icon(R.drawable.ic_play_phone, Look.ON_ORANGE, 32, null), LinearLayout.LayoutParams(look.px(32), look.px(32)))
                addView(text("Teléfono", 20f, Look.ON_ORANGE, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(10) })
                setOnClickListener {
                    send(Protocol.message(Protocol.OPEN) { put("key", n.key) })
                    CarAudio.open(context)
                    done(n.key, closeAfter = true)
                }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, look.px(72), Gravity.START or Gravity.BOTTOM).apply {
                marginStart = look.px(30); bottomMargin = look.px(30)
            })
        }
        cover.addView(icon(R.drawable.ic_close, android.graphics.Color.WHITE, 28, "Volver").apply {
            background = look.oval(android.graphics.Color.argb(40, 255, 255, 255))
            setPadding(look.px(18), look.px(18), look.px(18), look.px(18))
            setOnClickListener { closeBig() }
        }, FrameLayout.LayoutParams(look.px(64), look.px(64), Gravity.END or Gravity.TOP).apply {
            marginEnd = look.px(30); topMargin = look.px(30)
        })
        root.addView(cover, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        big = cover
    }

    private fun closeBig() {
        val v = big ?: return
        big = null
        v.animate().alpha(0f).setDuration(180).withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
    }

    private var player: android.media.MediaPlayer? = null
    private var progress: ValueAnimator? = null

    /** The audio of the last message that brought one, already loaded so it starts at the touch. */
    private var ready: Pair<String, android.media.MediaPlayer>? = null

    // ---------------------------------------------------------------- what sounds, one after the other

    /** One thing to say or play: the whole message ([whole], its audio if it brought one) or just who sent it. */
    private class Turn(val key: String, val whole: Boolean)

    /** What waits to sound, in order; nothing starts while something else sounds. */
    private val queue = ArrayDeque<Turn>()
    /** Messages to read whole once the window closes (they arrived while it was open), or when opened on them. */
    private val later = LinkedHashSet<String>()
    /** The audio that should start by itself as soon as it finishes loading. */
    private var waitingAudio: String? = null

    /**
     * A new message: with "leer solo" it is read (or its audio played); with the window open on another one it is
     * only announced and read when the window closes; with "anunciar" just who sent it.
     */
    private fun arrive(n: Notice) {
        val whole = Settings.autoRead(context)
        when {
            whole && panel != null && openKey != n.key -> { later.add(n.key); queue.add(Turn(n.key, whole = false)) }
            whole -> queue.add(Turn(n.key, whole = true))
            Settings.announce(context) -> queue.add(Turn(n.key, whole = false))
            else -> return
        }
        // After the arrival sound.
        main.postDelayed({ next() }, 700)
    }

    private fun busy(): Boolean =
        speaker.speaking || waitingAudio != null ||
            runCatching { player?.isPlaying == true || ready?.second?.isPlaying == true }.getOrDefault(false)

    /** Starts the next thing in line, if nothing is sounding. */
    private fun next() {
        if (busy()) return
        while (true) {
            val t = queue.removeFirstOrNull() ?: return
            val n = Inbox.get(t.key) ?: continue
            if (t.whole && panel != null && openKey != t.key) { later.add(t.key); continue }
            if (!t.whole) { speaker.announce(n, if (Settings.announceCount(context)) Inbox.count(n.key) else 0); return }
            if (n.audio != null) playAlone(n) else speaker.read(n)
            return
        }
    }

    /** Its audio, with nothing open: from the loaded player, or as soon as it loads. */
    private fun playAlone(n: Notice) {
        val r = ready
        if (r == null || r.first != audioId(n)) { waitingAudio = audioId(n); preload(n); return }
        speaker.stop()
        r.second.setOnCompletionListener { it.seekTo(0); main.post { next() } }
        runCatching { r.second.start() }.onFailure { main.post { next() } }
    }

    /** The window closed: what waited is read now, in the order it came. */
    private fun releaseLater() {
        later.forEach { queue.add(Turn(it, whole = true)) }
        later.clear()
        main.postDelayed({ next() }, 400)
    }

    /** Which audio: the conversation and the audio's size (a new voice message in the same chat is another one). */
    private fun audioId(n: Notice) = n.key + "#" + (n.audio?.size ?: 0)

    /**
     * Loads [n]'s audio in the background as soon as it arrives; with [play], it starts by itself once loaded (after
     * the arrival sound), through the car's speakers.
     */
    private val loading = HashSet<String>()

    private fun preload(n: Notice) {
        val id = audioId(n)
        if (n.audio == null || ready?.first == id || !loading.add(id)) return
        Thread {
            val mp = prepareAudio(n) ?: run {
                // The radio can't play it: the words are read instead.
                main.post { loading.remove(id); if (waitingAudio == id) { waitingAudio = null; speaker.read(n) } }
                return@Thread
            }
            main.post {
                loading.remove(id)
                ready?.second?.let { old ->
                    // One that is still playing by itself finishes first; it is let go when it ends.
                    if (runCatching { old.isPlaying }.getOrDefault(false)) old.setOnCompletionListener { it.release() }
                    else runCatching { old.release() }
                }
                ready = audioId(n) to mp
                if (waitingAudio == audioId(n)) { waitingAudio = null; playAlone(n) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun takeReady(n: Notice): android.media.MediaPlayer? {
        val (key, mp) = ready ?: return null
        if (key != audioId(n)) return null
        ready = null
        return mp
    }

    /** A player for the audio, from a file it can read; null if the radio can't play that kind of audio. */
    private fun prepareAudio(n: Notice): android.media.MediaPlayer? {
        val bytes = n.audio ?: return null
        val ext = n.audioType.substringAfter('/').substringBefore(';').ifBlank { "audio" }
        val file = java.io.File(context.cacheDir, "audio-${n.key.hashCode().toUInt()}.$ext")
        val mp = android.media.MediaPlayer()
        return try {
            file.writeBytes(bytes)
            mp.setAudioAttributes(android.media.AudioAttributes.Builder()
                // The same way as the reading voice, which the radio lets through at once.
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(file.path)
            mp.prepare()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    private var playerKey: String? = null
    /** The message whose audio row starts playing as soon as it is shown. */
    private var startRow: String? = null

    /** Stops the audio; the player stays loaded for that message, so opening it again starts at once too. */
    private fun stopAudio() {
        progress?.cancel()
        progress = null
        val mp = player ?: return
        player = null
        val key = playerKey
        try {
            if (mp.isPlaying) mp.pause()
            mp.seekTo(0)
            mp.setOnCompletionListener(null)
            if (key != null && Inbox.get(key.substringBeforeLast('#')) != null) {
                ready?.second?.let { runCatching { it.release() } }
                ready = key to mp
                return
            }
        } catch (_: Exception) {
        }
        runCatching { mp.release() }
    }

    /**
     * Audio that came inside the notification: it plays here, through the car's speakers. The button plays and
     * pauses; the bars move and the line fills while it plays, and the time says how much is left.
     */
    private fun audioRow(n: Notice): View {
        val r = row(Gravity.CENTER_VERTICAL).apply {
            background = look.round(android.graphics.Color.argb(26, 56, 198, 244), 28)
            setPadding(look.px(26), look.px(24), look.px(26), look.px(24))
        }
        val button = icon(R.drawable.ic_play, Look.ON_BLUE, 40, "Oír el audio").apply {
            background = look.oval(Look.BLUE)
            setPadding(look.px(28), look.px(28), look.px(28), look.px(28))
        }
        r.addView(button, LinearLayout.LayoutParams(look.px(96), look.px(96)))
        val mid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val wave = Wave(context, Look.BLUE)
        wave.alpha = 0.45f
        mid.addView(wave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(54)))
        val track = FrameLayout(context).apply { background = look.round(look.off, 4) }
        val line = View(context).apply { background = look.round(Look.BLUE, 4); pivotX = 0f; scaleX = 0f }
        track.addView(line, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mid.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(8)).apply { topMargin = look.px(12) })
        r.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(22); marginEnd = look.px(22) })
        val time = text("", 26f, look.ink, bold = true)
        r.addView(time)

        fun clock(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }
        // Usually ready already (prepared when the message arrived); if not, prepared now.
        val mp = takeReady(n) ?: prepareAudio(n) ?: return voiceRow(n)
        playerKey = audioId(n)
        player = mp
        val total = mp.duration.toLong().coerceAtLeast(1)
        time.text = clock(total)
        val fill = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val k = it.animatedValue as Float
                line.scaleX = k
                time.text = clock((total * (1 - k)).toLong() + 999)
            }
        }
        progress = fill
        fun playing(on: Boolean) {
            button.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
            button.contentDescription = if (on) "Pausa" else "Oír el audio"
            wave.animate().alpha(if (on) 1f else 0.45f).setDuration(200).start()
        }
        mp.setOnCompletionListener {
            playing(false)
            fill.cancel()
            line.scaleX = 0f
            time.text = clock(total)
            it.seekTo(0)
            next()
        }
        if (startRow == n.key) { startRow = null; button.post { if (!mp.isPlaying) button.performClick() } }
        // Already playing by itself ("leer solo"): the line and the button pick it up where it is.
        if (mp.isPlaying) {
            fill.start()
            fill.currentPlayTime = mp.currentPosition.toLong()
            playing(true)
        }
        button.setOnClickListener {
            if (player !== mp) return@setOnClickListener
            if (mp.isPlaying) {
                mp.pause(); fill.pause(); playing(false)
            } else {
                speaker.stop()
                mp.start()
                if (fill.isPaused) fill.resume() else fill.start()
                playing(true)
            }
        }
        return r
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
                CarAudio.open(context)
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
        stopAudio()
        fill(c, whole = false)
        val h = row(Gravity.CENTER_VERTICAL)
        h.addView(avatar(n, 56), LinearLayout.LayoutParams(look.px(56), look.px(56)))
        h.addView(text("→ ${n.title.ifBlank { n.app }}", 28f, look.ink, bold = true).apply { isSingleLine = true; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = look.px(16) })
        c.addView(h)

        val ripples = Ripples(context, Look.BLUE)
        val micSize = look.px(140)
        val mic = icon(R.drawable.ic_mic, Look.ON_BLUE, 72, "Escuchando").apply {
            background = look.oval(Look.BLUE)
            setPadding(look.px(38), look.px(38), look.px(38), look.px(38))
            elevation = look.px(12).toFloat()
        }
        val micBox = FrameLayout(context).apply {
            addView(ripples, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(mic, FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER))
        }
        // The microphone takes what is left, so the buttons below always fit on the radio's screen.
        c.addView(micBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val words = text("", 36f, look.ink, bold = true).apply { gravity = Gravity.CENTER; minLines = 2; maxLines = 3; ellipsize = TextUtils.TruncateAt.START }
        c.addView(words, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = look.px(12) })

        var said = ""
        val d = Dictation(context,
            onWords = { w, final -> said = w; words.text = if (final) w else "$w…" },
            onLevel = { level -> mic.scaleX = 1f + level * 0.12f; mic.scaleY = mic.scaleX },
            onFail = { if (said.isBlank()) words.text = "No le oí. Toque «Otra vez»."; ripples.stop() })
        dictation = d

        val actions = row(Gravity.CENTER)
        actions.addView(action(R.drawable.ic_close, "Cancelar", look.chip, Look.RED, size = look.px(104)) { showRead(key, fade = true) }, spaced())
        actions.addView(action(R.drawable.ic_redo, "Otra vez", look.chip, look.ink, size = look.px(104)) {
            said = ""; words.text = ""; ripples.start(); d.start()
        }, spaced())
        actions.addView(action(R.drawable.ic_send, "Enviar", Look.GREEN, Look.ON_GREEN, size = look.px(104)) {
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
        fill(c, whole = false)
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
        back.addView(action(R.drawable.ic_prev, "Volver", look.chip, look.ink, size = look.px(96)) { showRead(key, fade = true) })
        c.addView(back)
    }

    /** Holding a bubble: silence that conversation for a while; below, the sound, reading aloud and the letters. */
    private fun openMute(n: Notice) {
        if (!allowed()) return
        val c = createPanel()
        mode = Mode.MUTE
        openKey = null
        fill(c, whole = false)
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
                addView(text(label, 34f, look.ink, bold = true).apply { gravity = Gravity.CENTER; includeFontPadding = false },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(text("min", 16f, look.soft, bold = true).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                setOnClickListener { mute(System.currentTimeMillis() + m * 60_000L) }
            }
            grid.addView(b, LinearLayout.LayoutParams(0, look.px(92), 1f).apply { marginEnd = look.px(14) })
        }
        val untilOff = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = look.round(look.strong, 24)
            contentDescription = "Hasta apagar el carro"
            addView(icon(R.drawable.ic_power, look.strongInk, 40, null), LinearLayout.LayoutParams(look.px(40), look.px(40)))
            setOnClickListener { mute(Settings.UNTIL_OFF) }
        }
        grid.addView(untilOff, LinearLayout.LayoutParams(0, look.px(92), 1f).apply { marginEnd = look.px(14) })
        // For good: also after turning the car off, until "Volver a oír" on the radio's LetMeKnow screen.
        val always = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = look.round(Look.WORK, 24)
            contentDescription = "Silenciar para siempre"
            addView(icon(R.drawable.ic_infinity, android.graphics.Color.WHITE, 44, null), LinearLayout.LayoutParams(look.px(44), look.px(36)))
            addView(text("Siempre", 17f, android.graphics.Color.WHITE, bold = true).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setOnClickListener { Settings.muteForever(context, n); closePanel() }
        }
        grid.addView(always, LinearLayout.LayoutParams(0, look.px(92), 1.25f))
        ObjectAnimator.ofPropertyValuesHolder(always,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f, 1f)).apply {
            duration = 1600; repeatCount = ValueAnimator.INFINITE; start()
        }
        c.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(22) })

        c.addView(View(context).apply { setBackgroundColor(look.edge) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, look.px(1).coerceAtLeast(1)).apply { topMargin = look.px(22); bottomMargin = look.px(12) })
        // The voice that reads this conversation; touching one says a sample with it.
        val voiceLine = row(Gravity.CENTER_VERTICAL)
        voiceLine.addView(icon(R.drawable.ic_speaker, Look.BLUE, 32, null), LinearLayout.LayoutParams(look.px(32), look.px(32)))
        voiceLine.addView(text("Voz", 24f, look.ink, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(16); marginEnd = look.px(18) })
        val chips = mutableListOf<Pair<Boolean, LinearLayout>>()
        fun paintChips() {
            val female = Settings.female(context, n)
            chips.forEach { (isFemale, chip) ->
                val on = isFemale == female
                chip.background = if (on) look.round(Look.BLUE, 24) else look.round(look.chip, 24, stroke = true)
                val ink = if (on) Look.ON_BLUE else look.ink
                (chip.getChildAt(0) as ImageView).setColorFilter(ink, PorterDuff.Mode.SRC_IN)
                (chip.getChildAt(1) as TextView).setTextColor(ink)
            }
        }
        listOf(false to "Hombre", true to "Mujer").forEachIndexed { i, (isFemale, label) ->
            val chip = row(Gravity.CENTER).apply {
                contentDescription = "Voz de $label"
                addView(icon(if (isFemale) R.drawable.ic_female else R.drawable.ic_male, look.ink, 30, null), LinearLayout.LayoutParams(look.px(30), look.px(30)))
                addView(text(label, 22f, look.ink, bold = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = look.px(10) })
                setOnClickListener {
                    Settings.setFemale(context, n, isFemale)
                    paintChips()
                    animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
                    speaker.say("Así le leo los mensajes de ${n.title.ifBlank { n.app }}.", if (isFemale) CloudVoice.FEMALE else CloudVoice.MALE)
                }
            }
            chips += isFemale to chip
            voiceLine.addView(chip, LinearLayout.LayoutParams(0, look.px(72), 1f).apply { if (i == 0) marginEnd = look.px(12) })
        }
        paintChips()
        c.addView(voiceLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = look.px(6) })
        c.addView(toggle(R.drawable.ic_bell, "Sonido al llegar", Settings.sound(context)) { Settings.setSound(context, it) })
        c.addView(toggle(R.drawable.ic_speaker, "Leer en voz alta solo", Settings.autoRead(context)) { Settings.setAutoRead(context, it) })
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
        text = Emoji.of(s)
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
    private fun action(res: Int, label: String, fill: Int, ink: Int, accent: Int? = null, size: Int = look.px(124), onClick: () -> Unit): LinearLayout {
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
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
            val inner = (size * 0.45f).toInt()
            addView(icon(res, accent ?: ink, 56, null).apply { minimumWidth = 0; minimumHeight = 0 }, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
        }
        col.addView(btn, LinearLayout.LayoutParams(size, size))
        col.addView(text(label, 16f, look.soft, bold = true).apply { includeFontPadding = false }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = look.px(8) })
        return col
    }

    private fun smallText(words: String, description: String, onClick: () -> Unit) = FrameLayout(context).apply {
        background = look.round(look.chip, 32, stroke = true)
        contentDescription = description
        setOnClickListener { onClick() }
        addView(text(words, 22f, look.ink, bold = true).apply { gravity = Gravity.CENTER; includeFontPadding = false },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        layoutParams = LinearLayout.LayoutParams(look.px(64), look.px(64))
    }

    private fun small(res: Int, description: String, tint: Int? = null, onClick: () -> Unit) = FrameLayout(context).apply {
        background = if (tint != null) look.round(android.graphics.Color.argb(40, android.graphics.Color.red(tint), android.graphics.Color.green(tint), android.graphics.Color.blue(tint)), 32)
            else look.round(look.chip, 32, stroke = true)
        contentDescription = description
        setOnClickListener { onClick() }
        addView(icon(res, tint ?: look.ink, 28, null), FrameLayout.LayoutParams(look.px(28), look.px(28), Gravity.CENTER))
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
        if (n.work) {
            // From the phone's work profile: a purple briefcase on top.
            val b = look.px(sizeDp * 0.4f)
            val work = ImageView(context).apply {
                setImageResource(R.drawable.ic_work)
                setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                background = look.oval(Look.WORK)
                val p = (b * 0.2f).toInt()
                setPadding(p, p, p, p)
                contentDescription = "Trabajo"
            }
            frame.addView(work, FrameLayout.LayoutParams(b, b, Gravity.END or Gravity.TOP).apply {
                marginEnd = -look.px(4); topMargin = -look.px(4)
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

/** The orange ring that fills around a bubble's photo while it is held; full, the silence window opens. */
class HoldRing(private val centerX: Int, private val radius: Float, stroke: Float) : android.graphics.drawable.Drawable() {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = android.graphics.Color.argb(60, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Look.ORANGE; strokeCap = Paint.Cap.ROUND }
    var progress = 0f
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        if (progress <= 0f) return
        val cy = bounds.exactCenterY()
        val r = android.graphics.RectF(centerX - radius, cy - radius, centerX + radius, cy + radius)
        canvas.drawOval(r, track)
        canvas.drawArc(r, -90f, 360f * progress, false, fill)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** [glass] with a soft shadow around and below it, outside the bubble's own edges, never behind the glass itself. */
class FloatShadow(private val glass: android.graphics.drawable.Drawable, color: Int, private val blur: Float, private val drop: Float) :
    android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }
    private val shape = android.graphics.Path()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        glass.bounds = bounds
        shape.reset()
        val r = bounds.height() / 2f
        shape.addRoundRect(android.graphics.RectF(bounds), r, r, android.graphics.Path.Direction.CW)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val r = b.height() / 2f
        // No clipping around the glass (a clipped edge has no smoothing and looked jagged on the radio): the shadow
        // goes under the glass, which is nearly solid anyway.
        canvas.drawRoundRect(b.left - blur * 0.15f, b.top + drop * 0.6f, b.right + blur * 0.15f, b.bottom + drop, r, r, paint)
        glass.draw(canvas)
    }

    override fun getOutline(outline: Outline) = glass.getOutline(outline)
    override fun setAlpha(alpha: Int) { glass.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { glass.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
