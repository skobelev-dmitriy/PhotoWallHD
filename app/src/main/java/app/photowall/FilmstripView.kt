package app.photowall

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/**
 * Plays a filmstrip: one frame at a time, centred, advanced by the remote (or by a timer). In the "projector" theme the
 * picture gets film grain, a vignette, a faint flicker, a tiny jitter and a warm tone, like a filmstrip on a wall.
 */
class FilmstripView(context: Context, private val film: Filmstrip, private val store: FilmstripStore, private val prefs: Prefs) : View(context) {
    /** Asks the host to close the player (used by the remote "stop" command). */
    var onClose: (() -> Unit)? = null

    /** Called whenever the frame changes (to save the position). */
    var onFrame: ((Int) -> Unit)? = null

    var index = 0
        private set
    val total get() = film.frames.size
    val filmId get() = film.id
    var isPaused = false
        private set
    var ended = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val dm = context.resources.displayMetrics
    private val cache = HashMap<Int, Bitmap>()
    private val loading = HashSet<Int>()
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var changedAt = 0L
    private var running = false

    private val projector = prefs.filmTheme == "projector"
    private val autoSec = prefs.filmAutoSec

    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val overlay = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }
    private val grain: BitmapShader?
    private val grainPaint = Paint()
    private val grainMatrix = Matrix()
    private var vignette: Paint? = null
    private var flickerAlpha = 0
    private var flickerAt = 0L
    private var jx = 0f
    private var jy = 0f
    private var jitterAt = 0L
    private var jitterUntil = 0L
    private val dst = RectF()

    private val advance = Runnable { if (!isPaused) next(auto = true) }

    init {
        setBackgroundColor(if (projector) Color.rgb(16, 14, 12) else Color.BLACK)
        if (projector) {
            // a warm tone: a bit more red, a bit less blue
            framePaint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(1.04f, 1.0f, 0.92f, 1f) })
            val noise = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
            val px = IntArray(160 * 160) { val v = Random.nextInt(256); Color.argb(255, v, v, v) }
            noise.setPixels(px, 0, 160, 0, 0, 160, 160)
            grain = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grainPaint.shader = grain
            grainPaint.alpha = 22
        } else {
            grain = null
        }
    }

    // ---- controls (main thread) ----

    fun start(at: Int) {
        running = true
        ended = false
        index = at.coerceIn(0, total - 1)
        current = null
        previous = null
        show(index, animate = false)
        invalidate()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advance)
        cache.clear()
        loading.clear()
        current = null
        previous = null
    }

    fun release() {
        stop()
        loader.shutdownNow()
    }

    fun next(auto: Boolean = false) {
        if (!running) return
        if (index >= total - 1) {
            ended = true
            handler.removeCallbacks(advance)
            invalidate()
            return
        }
        ended = false
        show(index + 1, animate = true)
    }

    fun prev() {
        if (!running) return
        ended = false
        if (index > 0) show(index - 1, animate = true)
    }

    fun restart() {
        ended = false
        show(0, animate = true)
    }

    fun setPaused(p: Boolean) {
        isPaused = p
        handler.removeCallbacks(advance)
        if (!p && autoSec > 0 && !ended) handler.postDelayed(advance, autoSec * 1000L)
        invalidate()
    }

    /** The "OK" button: restart at the end, pause/resume when a timer runs, otherwise next frame. */
    fun action() {
        when {
            ended -> restart()
            autoSec > 0 -> setPaused(!isPaused)
            else -> next()
        }
    }

    // ---- frames ----

    private fun show(i: Int, animate: Boolean) {
        index = i
        onFrame?.invoke(i)
        val bmp = cache[i]
        if (bmp != null) {
            swapTo(bmp, animate)
        } else {
            load(i) { b -> if (index == i && b != null) swapTo(b, animate) }
        }
        // keep the neighbours ready so the next click is instant
        for (n in listOf(i + 1, i - 1, i + 2)) if (n in 0 until total && cache[n] == null) load(n) {}
        cache.keys.filter { it < i - 2 || it > i + 3 }.forEach { cache.remove(it) }
    }

    private fun swapTo(b: Bitmap, animate: Boolean) {
        previous = if (animate) current else null
        current = b
        changedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(advance)
        if (autoSec > 0 && !isPaused && index < total - 1) handler.postDelayed(advance, autoSec * 1000L)
        else if (autoSec > 0 && !isPaused && index >= total - 1) handler.postDelayed({ ended = true; invalidate() }, autoSec * 1000L)
        invalidate()
    }

    private fun load(i: Int, done: (Bitmap?) -> Unit) {
        if (!loading.add(i)) return
        val file: File = store.frameFile(film.id, i) ?: run { loading.remove(i); done(null); return }
        val tw = dm.widthPixels
        val th = dm.heightPixels
        loader.execute {
            val b = BitmapLoader.decode(file, tw, th)
            handler.post {
                loading.remove(i)
                if (b != null && running) cache[i] = b
                done(b)
            }
        }
    }

    // ---- drawing ----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (projector) {
            vignette = Paint().apply {
                shader = RadialGradient(
                    w / 2f, h / 2f, hypot(w.toFloat(), h.toFloat()) / 2f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb(150, 0, 0, 0)), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP,
                )
            }
        }
    }

    private fun fit(b: Bitmap) {
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w / b.width, h / b.height)
        val dw = b.width * s
        val dh = b.height * s
        dst.set((w - dw) / 2f + jx, (h - dh) / 2f + jy, (w + dw) / 2f + jx, (h + dh) / 2f + jy)
    }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val fade = ((now - changedAt) / 220f).coerceIn(0f, 1f)
        previous?.let { if (fade < 1f) { fit(it); framePaint.alpha = 255; c.drawBitmap(it, null, dst, framePaint) } }
        current?.let { fit(it); framePaint.alpha = (255 * fade).toInt(); c.drawBitmap(it, null, dst, framePaint) }
        framePaint.alpha = 255

        if (projector) {
            if (now - jitterAt > 2000 + (now % 2000)) { // a tiny shake every 2-4 s
                jitterAt = now; jitterUntil = now + 90
                jx = (Random.nextFloat() - 0.5f) * 2f * dm.density; jy = (Random.nextFloat() - 0.5f) * 2f * dm.density
            } else if (now > jitterUntil) { jx = 0f; jy = 0f }
            if (now - flickerAt > 90) { flickerAt = now; flickerAlpha = Random.nextInt(0, 16) }
            grain?.let {
                grainMatrix.setTranslate(Random.nextFloat() * 160f, Random.nextFloat() * 160f)
                it.setLocalMatrix(grainMatrix)
                c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), grainPaint)
            }
            vignette?.let { c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
            overlay.color = Color.argb(flickerAlpha, 0, 0, 0)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlay)
        }

        val m = min(width, height).toFloat()
        // frame counter for two seconds after a change; paused / end hints
        val sinceChange = now - changedAt
        if (sinceChange < 2000 || isPaused || ended) {
            text.textSize = m * 0.032f
            text.alpha = if (isPaused || ended) 230 else (255 * (1f - sinceChange / 2000f)).toInt().coerceIn(0, 255)
            val label = when {
                ended -> tr("Конец · OK — сначала · Назад — выход", "The end · OK — again · Back — exit")
                isPaused -> "⏸  ${index + 1} / $total"
                else -> "${index + 1} / $total"
            }
            c.drawText(label, m * 0.04f, height - m * 0.04f, text)
        }
        // thin progress line along the very top
        overlay.color = Color.argb(160, 255, 255, 255)
        c.drawRect(0f, 0f, width * (index + 1f) / total, dm.density * 2f, overlay)

        if (running && (projector || fade < 1f || isPaused || ended || sinceChange < 2000)) postInvalidateOnAnimation()
    }
}
