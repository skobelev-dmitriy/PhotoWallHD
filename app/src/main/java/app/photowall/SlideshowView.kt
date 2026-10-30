package app.photowall

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.TimeInterpolator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.random.Random

/**
 * The picture wall itself: cross-fades full-screen photos, optional slow zoom,
 * clock overlay, night schedule. Used by both the activity and the screensaver.
 */
class SlideshowView(context: Context) : FrameLayout(context) {
    /** Short messages such as "Paused" for the host to display. */
    var onStatus: ((String) -> Unit)? = null

    /** Asks the host to close the show (used by the remote "stop" command). */
    var onClose: (() -> Unit)? = null

    private val prefs = Prefs(context)
    private val store = PhotoStore.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val dm = context.resources.displayMetrics

    private val stage = FrameLayout(context)
    private var front = newImage()
    private var back = newImage()
    private val emptyView = TextView(context)
    private val clockBox = LinearLayout(context)
    private val timeView = TextView(context)
    private val dateView = TextView(context)
    private val dimCover = View(context)
    private val nightCover = View(context)

    private var running = false
    private var paused = false
    private var night = false
    private var firstImage = true

    private var playlist: List<String> = emptyList()
    private var index = -1
    private var seenVersion = -1

    private var cacheName: String? = null
    private var cacheBmp: Bitmap? = null
    private var loadingName: String? = null
    private var wantedName: String? = null
    private var wantedDir = 1

    private val animators = ArrayList<Animator>()
    private val fmt24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val fmt12 = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val fmtDate = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private var clockKey = ""

    private val advance = Runnable { go(1) }
    private val finishTask = Runnable {
        back.setImageDrawable(null)
        back.alpha = 1f
        back.translationX = 0f
        prefetch()
    }
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }
    private val nightTick = object : Runnable {
        override fun run() {
            checkNight()
            handler.postDelayed(this, 15_000)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        stage.setBackgroundColor(Color.BLACK)
        stage.addView(back)
        stage.addView(front)
        addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))

        emptyView.setTextColor(Color.WHITE)
        emptyView.gravity = Gravity.CENTER
        emptyView.visibility = GONE
        addView(emptyView, FrameLayout.LayoutParams(MATCH, MATCH))

        timeView.setTextColor(Color.argb(235, 255, 255, 255))
        timeView.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        timeView.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        dateView.setTextColor(Color.argb(220, 255, 255, 255))
        dateView.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        dateView.setShadowLayer(10f, 0f, 2f, Color.BLACK)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.addView(timeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clockBox.addView(dateView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        clockBox.visibility = GONE
        addView(clockBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        dimCover.setBackgroundColor(Color.TRANSPARENT)
        addView(dimCover, FrameLayout.LayoutParams(MATCH, MATCH))
        nightCover.setBackgroundColor(Color.BLACK)
        nightCover.visibility = GONE
        addView(nightCover, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---- public controls (main thread) ----

    fun start() {
        if (running) return
        running = true
        paused = false
        firstImage = true
        checkNight()
        handler.postDelayed(nightTick, 15_000)
        handler.post(clockTick)
        if (!night) go(1)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advance)
        handler.removeCallbacks(finishTask)
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(nightTick)
        settle()
        front.setImageDrawable(null)
        back.setImageDrawable(null)
        cacheBmp = null
        cacheName = null
        wantedName = null
        night = false
        nightCover.visibility = GONE
    }

    fun release() {
        stop()
        loader.shutdownNow()
    }

    fun next() {
        if (running) go(1)
    }

    fun prev() {
        if (running) go(-1)
    }

    fun setPaused(p: Boolean) {
        if (p == paused) return
        paused = p
        handler.removeCallbacks(advance)
        if (!p && running && !night) handler.postDelayed(advance, 800)
        onStatus?.invoke(if (p) tr("Пауза", "Paused") else tr("Продолжаем", "Resumed"))
    }

    fun togglePause() = setPaused(!paused)

    // ---- playlist ----

    private fun syncPlaylist(reshuffle: Boolean) {
        val names = store.list().map { it.name }
        val current = playlist.getOrNull(index)
        playlist = if (prefs.shuffle) {
            if (reshuffle || playlist.isEmpty()) names.shuffled()
            else playlist.filter { it in names } + names.filter { it !in playlist }.shuffled()
        } else {
            names
        }
        index = if (current != null) {
            val i = playlist.indexOf(current)
            if (i >= 0) i else min(index, playlist.size - 1)
        } else {
            -1
        }
        seenVersion = store.version
    }

    private fun go(delta: Int) {
        if (!running || night) return
        handler.removeCallbacks(advance)

        if (playlist.isEmpty() || store.version != seenVersion) syncPlaylist(false)
        var next = index + delta
        if (next >= playlist.size) {
            syncPlaylist(true)
            next = 0
        } else if (next < 0) {
            next = playlist.size - 1
        }
        if (playlist.isEmpty() || next !in playlist.indices) {
            showEmpty()
            handler.postDelayed(advance, 4000)
            return
        }
        emptyView.visibility = GONE
        index = next
        request(playlist[next], if (delta == 0) 1 else delta)
    }

    // ---- loading ----

    private fun request(name: String, dir: Int) {
        wantedName = name
        wantedDir = dir
        val cached = cacheBmp
        if (cacheName == name && cached != null) {
            cacheBmp = null
            cacheName = null
            present(cached, dir)
            return
        }
        if (loadingName == name) return
        startLoad(name)
    }

    private fun prefetch() {
        if (playlist.size < 2) return
        val n = playlist.getOrNull(index + 1) ?: return
        if (n == cacheName || n == loadingName) return
        startLoad(n)
    }

    private fun startLoad(name: String) {
        loadingName = name
        val tw = dm.widthPixels
        val th = dm.heightPixels
        val file = File(store.dir, name)
        loader.execute {
            val bmp = BitmapLoader.decode(file, tw, th)
            handler.post {
                if (loadingName == name) loadingName = null
                onLoaded(name, bmp)
            }
        }
    }

    private fun onLoaded(name: String, bmp: Bitmap?) {
        if (!running) return
        if (bmp == null) {
            if (wantedName == name) {
                // unreadable file: drop it from this round and move on
                val i = playlist.indexOf(name)
                playlist = playlist.filter { it != name }
                index = if (wantedDir >= 0) i - 1 else i
                go(wantedDir)
            }
            return
        }
        if (wantedName == name) {
            present(bmp, wantedDir)
        } else {
            cacheName = name
            cacheBmp = bmp
        }
    }

    // ---- showing ----

    private fun present(bmp: Bitmap, dir: Int) {
        wantedName = null
        settle()
        val w = if (width > 0) width else dm.widthPixels
        val incoming = back
        val outgoing = front

        incoming.setImageBitmap(bmp)
        incoming.scaleType = scaleFor(bmp)
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.pivotX = w * (0.3f + 0.4f * Random.nextFloat())
        incoming.pivotY = (if (height > 0) height else dm.heightPixels) * (0.3f + 0.4f * Random.nextFloat())
        stage.bringChildToFront(incoming)

        var transitionMs = 0L
        when (if (firstImage) "none" else prefs.transition) {
            "slide" -> {
                transitionMs = 900
                incoming.translationX = (if (dir < 0) -w else w).toFloat()
                animate(incoming, TRANSLATION_X, 0f, transitionMs, DecelerateInterpolator())
                animate(outgoing, TRANSLATION_X, (if (dir < 0) w else -w).toFloat(), transitionMs, DecelerateInterpolator())
            }
            "fade" -> {
                transitionMs = 1500
                incoming.alpha = 0f
                animate(incoming, ALPHA, 1f, transitionMs, LinearInterpolator())
            }
            else -> outgoing.setImageDrawable(null)
        }
        firstImage = false

        if (prefs.kenBurns) {
            val ms = prefs.intervalSec * 1000L + 3000
            animate(incoming, SCALE_X, 1.1f, ms, LinearInterpolator())
            animate(incoming, SCALE_Y, 1.1f, ms, LinearInterpolator())
        }

        front = incoming
        back = outgoing

        dimCover.setBackgroundColor(Color.argb(prefs.dim * 255 / 100, 0, 0, 0))
        handler.removeCallbacks(finishTask)
        handler.postDelayed(finishTask, transitionMs + 100)

        handler.removeCallbacks(advance)
        if (!paused) handler.postDelayed(advance, prefs.intervalSec * 1000L)
    }

    private fun animate(v: View, prop: android.util.Property<View, Float>, to: Float, ms: Long, interp: TimeInterpolator) {
        val a = ObjectAnimator.ofFloat(v, prop, to)
        a.duration = ms
        a.interpolator = interp
        animators.add(a)
        a.start()
    }

    /** Ends any transition in progress so the next one starts from a clean state. */
    private fun settle() {
        handler.removeCallbacks(finishTask)
        for (a in animators) a.cancel()
        animators.clear()
        front.alpha = 1f
        front.translationX = 0f
        back.setImageDrawable(null)
        back.alpha = 1f
        back.translationX = 0f
    }

    private fun scaleFor(bmp: Bitmap): ImageView.ScaleType = when (prefs.fit) {
        "fill" -> ImageView.ScaleType.CENTER_CROP
        "auto" -> {
            val vw = if (width > 0) width else dm.widthPixels
            val vh = if (height > 0) height else dm.heightPixels
            val ratio = (bmp.width.toFloat() / bmp.height) / (vw.toFloat() / vh)
            if (ratio in 0.78f..1.28f) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        else -> ImageView.ScaleType.FIT_CENTER
    }

    private fun showEmpty() {
        settle()
        front.setImageDrawable(null)
        firstImage = true
        val base = min(width, height).takeIf { it > 0 } ?: min(dm.widthPixels, dm.heightPixels)
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * 0.045f)
        emptyView.text = tr("Пока нет ни одной фотографии", "No photos yet") + "\n\n" +
            tr("Откройте на телефоне или компьютере\n(в той же сети Wi-Fi):", "Open on your phone or computer\n(same Wi-Fi network):") +
            "\n" + Net.url(ServerService.port)
        emptyView.visibility = VISIBLE
    }

    // ---- clock & night schedule ----

    private fun updateClock() {
        if (night || !prefs.clock || width == 0) {
            clockBox.visibility = GONE
            return
        }
        val key = "${prefs.clockPos}|${prefs.clockSize}|$width|$height"
        if (key != clockKey) {
            clockKey = key
            layoutClock()
        }
        clockBox.visibility = VISIBLE
        val now = Date()
        timeView.text = (if (prefs.clock24) fmt24 else fmt12).format(now)
        dateView.visibility = if (prefs.clockDate) VISIBLE else GONE
        if (prefs.clockDate) dateView.text = fmtDate.format(now).replaceFirstChar { it.uppercase() }
    }

    private fun layoutClock() {
        val base = min(width, height).toFloat()
        val factor = when (prefs.clockSize) {
            "s" -> 0.07f
            "l" -> 0.16f
            else -> 0.11f
        }
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * factor)
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * factor * 0.33f)
        val (gravity, inner) = when (prefs.clockPos) {
            "tl" -> (Gravity.TOP or Gravity.START) to Gravity.START
            "tr" -> (Gravity.TOP or Gravity.END) to Gravity.END
            "bl" -> (Gravity.BOTTOM or Gravity.START) to Gravity.START
            "bc" -> (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) to Gravity.CENTER_HORIZONTAL
            else -> (Gravity.BOTTOM or Gravity.END) to Gravity.END
        }
        val m = (base * 0.04f).toInt()
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity)
        lp.setMargins(m, m, m, m)
        clockBox.layoutParams = lp
        clockBox.gravity = inner
    }

    private fun checkNight() {
        val sleep = !prefs.inActiveWindow()
        if (sleep == night) return
        night = sleep
        if (night) {
            handler.removeCallbacks(advance)
            nightCover.visibility = VISIBLE
            clockBox.visibility = GONE
        } else {
            nightCover.visibility = GONE
            if (running) go(1)
        }
    }

    private fun newImage() = ImageView(context).apply {
        layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val ALPHA: android.util.Property<View, Float> = View.ALPHA
        val TRANSLATION_X: android.util.Property<View, Float> = View.TRANSLATION_X
        val SCALE_X: android.util.Property<View, Float> = View.SCALE_X
        val SCALE_Y: android.util.Property<View, Float> = View.SCALE_Y
    }
}
