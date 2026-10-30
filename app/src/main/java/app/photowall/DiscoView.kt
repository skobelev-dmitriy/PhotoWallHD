package app.photowall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import kotlin.math.min

/** Full-screen canvas that draws the current visualization and switches between them. */
class DiscoView(context: Context, private val feed: AudioFeed, private val prefs: Prefs) : View(context) {
    /** Asks the host to close the mode (used by the remote "stop" command). */
    var onClose: (() -> Unit)? = null

    private val visuals = allVisuals()
    private var index = 0
    private var auto = true
    private var autoSec = 20
    private var flash = true
    private var t = 0f
    private var switchT = -10f
    private var autoTimer = 0f
    private var prefTimer = 10f
    private var lastNs = 0L
    private var running = false

    private val hud = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        setShadowLayer(8f, 0f, 2f, Color.BLACK)
    }

    fun resume() {
        running = true
        lastNs = 0L
        invalidate()
    }

    fun pause() {
        running = false
    }

    /** Manual switch: remembers the choice, which also turns auto-rotation off. */
    fun step(delta: Int) {
        index = (index + delta + visuals.size) % visuals.size
        switchT = t
        prefs.discoVisual = visuals[index].id
        auto = false
    }

    fun toggleAuto(): Boolean {
        auto = !auto
        prefs.discoVisual = if (auto) "auto" else visuals[index].id
        return auto
    }

    private fun syncPrefs() {
        val v = prefs.discoVisual
        if (v == "auto") {
            auto = true
        } else {
            auto = false
            val i = visuals.indexOfFirst { it.id == v }
            if (i >= 0 && i != index) {
                index = i
                switchT = t
            }
        }
        autoSec = prefs.discoAutoSec
        flash = prefs.discoFlash
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now
        t += dt
        feed.tick(dt)

        prefTimer += dt
        if (prefTimer > 1f) {
            prefTimer = 0f
            syncPrefs()
        }
        if (auto) {
            autoTimer += dt
            if (autoTimer > autoSec) {
                autoTimer = 0f
                index = (index + 1) % visuals.size
                switchT = t
            }
        }

        val w = width.toFloat()
        val h = height.toFloat()
        visuals[index].draw(c, w, h, feed, t, dt)

        // gentle beat flash: capped at ~11% white so it never strobes hard
        if (flash) {
            val a = (feed.beat * 28f).toInt()
            if (a > 2) c.drawColor(Color.argb(a, 255, 255, 255))
        }
        // fade-in from black when the visualization changes
        val fade = 1f - (t - switchT) / 0.5f
        if (fade > 0f) c.drawColor(Color.argb((fade * 255).toInt(), 0, 0, 0))

        drawHud(c, w, h)
        if (running) postInvalidateOnAnimation()
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        val m = min(w, h)
        hud.textSize = m * 0.035f
        if (t - switchT < 3f) {
            hud.alpha = ((1f - (t - switchT) / 3f) * 255).toInt().coerceIn(0, 255)
            c.drawText(visuals[index].title + if (auto) "  ·  auto" else "", m * 0.04f, m * 0.07f, hud)
        }
        val note = feed.note
        val msg = when {
            note != null -> note
            feed.activeSource != "demo" && feed.silentSec > 8f -> tr(
                "Звука не слышно (источник: ${if (feed.activeSource == "mic") "микрофон" else "системный звук"}). Сделайте музыку громче или выберите «Демо» в настройках.",
                "No sound detected. Turn the music up or pick \"Demo\" in the settings."
            )
            else -> null
        }
        if (msg != null) {
            hud.alpha = 230
            hud.textSize = m * 0.03f
            c.drawText(msg, m * 0.04f, h - m * 0.05f, hud)
        }
    }
}
