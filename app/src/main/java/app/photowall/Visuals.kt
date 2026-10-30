package app.photowall

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

object Pal {
    private val hsv = FloatArray(3)

    /** Colour from hue in degrees; used from the UI thread only. */
    fun hue(h: Float, s: Float = 1f, v: Float = 1f, a: Int = 255): Int {
        hsv[0] = ((h % 360f) + 360f) % 360f
        hsv[1] = s
        hsv[2] = v
        return Color.HSVToColor(a, hsv)
    }
}

/** One algorithmic visualization. Draw everything from [AudioFeed] numbers and time; keep allocations out of [draw]. */
interface Visual {
    val id: String
    val title: String
    fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float)
}

fun allVisuals(): List<Visual> = listOf(
    BarsVisual(), CircleVisual(), RingsVisual(), WaveVisual(),
    LampsVisual(), FireworksVisual(), WarpVisual(), HillsVisual(),
)

class BarsVisual : Visual {
    override val id = "bars"
    override val title = tr("Спектр", "Spectrum")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peaks = FloatArray(AudioFeed.BANDS)

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 8f, 0.8f, 0.10f + 0.14f * a.bass))
        val n = AudioFeed.BANDS
        val gap = w * 0.004f
        val bw = (w - gap * (n + 1)) / n
        val maxH = h * 0.7f
        val base = h * 0.82f
        for (i in 0 until n) {
            val v = a.bands[i]
            peaks[i] = max(v, peaks[i] - dt * 0.45f)
            val x = gap + i * (bw + gap)
            val hue = i * 300f / n + t * 30f
            p.color = Pal.hue(hue, 0.85f)
            c.drawRoundRect(x, base - v * maxH, x + bw, base, bw * 0.3f, bw * 0.3f, p)
            p.color = Pal.hue(hue, 0.85f, 1f, 70)
            c.drawRect(x, base + gap, x + bw, base + gap + v * maxH * 0.3f, p)
            p.color = Color.WHITE
            val py = base - peaks[i] * maxH
            c.drawRect(x, py - bw * 0.3f, x + bw, py, p)
        }
    }
}

class CircleVisual : Visual {
    override val id = "circle"
    override val title = tr("Круг", "Radial")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 6f + 200f, 0.7f, 0.06f + 0.1f * a.bass))
        val cx = w / 2
        val cy = h / 2
        val m = min(w, h)
        val r0 = m * (0.17f + 0.05f * a.bass + 0.04f * a.beat)
        val spokes = 96
        p.style = Paint.Style.STROKE
        p.strokeWidth = m * 0.0075f
        for (k in 0 until spokes) {
            val half = spokes / 2
            val idx = if (k < half) k else spokes - 1 - k
            val band = (idx * AudioFeed.BANDS / half).coerceAtMost(AudioFeed.BANDS - 1)
            val ang = t * 0.3f + k * 2f * PI.toFloat() / spokes
            val len = a.bands[band] * m * 0.3f + m * 0.006f
            val ca = cos(ang)
            val sa = sin(ang)
            p.color = Pal.hue(k * 360f / spokes + t * 40f)
            c.drawLine(cx + ca * r0, cy + sa * r0, cx + ca * (r0 + len), cy + sa * (r0 + len), p)
        }
        p.style = Paint.Style.FILL
        p.color = Pal.hue(t * 40f + 180f, 0.8f, 1f, 60 + (a.beat * 120).toInt())
        c.drawCircle(cx, cy, r0 * 0.9f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = m * 0.01f
        p.color = Color.WHITE
        c.drawCircle(cx, cy, r0 * 0.9f, p)
    }
}

class RingsVisual : Visual {
    override val id = "rings"
    override val title = tr("Кольца", "Rings")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val radius = FloatArray(24)
    private val alpha = FloatArray(24)
    private val hue = FloatArray(24)
    private var next = 0

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 10f + 260f, 0.8f, 0.05f + 0.08f * a.bass))
        val cx = w / 2
        val cy = h / 2
        val m = min(w, h)
        if (a.beat >= 0.999f) {
            radius[next] = m * 0.06f
            alpha[next] = 1f
            hue[next] = t * 50f
            next = (next + 1) % radius.size
        }
        p.style = Paint.Style.STROKE
        for (i in radius.indices) {
            if (alpha[i] <= 0f) continue
            radius[i] += dt * m * (0.45f + 0.5f * a.mid)
            alpha[i] -= dt * 0.55f
            if (alpha[i] <= 0f) continue
            p.strokeWidth = m * 0.03f * alpha[i] + 2f
            p.color = Pal.hue(hue[i] + radius[i] * 0.1f, 0.9f, 1f, (alpha[i] * 255).toInt())
            c.drawCircle(cx, cy, radius[i], p)
        }
        p.style = Paint.Style.FILL
        p.color = Pal.hue(t * 50f + 90f, 0.7f, 1f, 220)
        c.drawCircle(cx, cy, m * (0.05f + 0.09f * a.bass + 0.04f * a.beat), p)
        p.color = Color.WHITE
        c.drawCircle(cx, cy, m * (0.02f + 0.03f * a.treble), p)
    }
}

class WaveVisual : Visual {
    override val id = "wave"
    override val title = tr("Осциллограф", "Waveform")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 7f + 120f, 0.8f, 0.05f + 0.1f * a.bass))
        val m = min(w, h)
        for (line in 0..1) {
            val sign = if (line == 0) 1f else -1f
            path.reset()
            for (i in 0 until AudioFeed.WAVE) {
                val x = i * w / (AudioFeed.WAVE - 1)
                val y = h / 2 + sign * a.wave[i] * h * 0.33f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val hue = t * 40f + line * 150f
            for (pass in 0..2) {
                p.strokeWidth = m * (0.03f - pass * 0.011f)
                p.color = Pal.hue(hue, 0.9f - pass * 0.3f, 1f, intArrayOf(45, 110, 255)[pass])
                c.drawPath(path, p)
            }
        }
    }
}

/** Classic three-lamp "colour music": bass, mid and treble each drive their own coloured light. */
class LampsVisual : Visual {
    override val id = "lamps"
    override val title = tr("Цветомузыка", "Colour music")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val colors = intArrayOf(Color.rgb(255, 40, 30), Color.rgb(60, 255, 80), Color.rgb(50, 110, 255))
    private val shaders = colors.map {
        RadialGradient(0f, 0f, 1f, intArrayOf(it, Color.argb(120, Color.red(it), Color.green(it), Color.blue(it)), Color.TRANSPARENT), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Color.rgb(6, 4, 10))
        val m = min(w, h)
        val levels = floatArrayOf(a.bass, a.mid, a.treble)
        val xs = floatArrayOf(w * 0.22f, w * 0.5f, w * 0.78f)
        val ys = floatArrayOf(h * 0.62f, h * 0.42f, h * 0.62f)
        for (i in 0..2) {
            val lv = levels[i]
            val r = m * (0.3f + 0.75f * lv)
            p.shader = shaders[i]
            p.alpha = (70 + 185 * lv).toInt().coerceAtMost(255)
            c.save()
            c.translate(xs[i] + sin(t * (0.6f + i * 0.2f)) * w * 0.03f, ys[i])
            c.scale(r, r)
            c.drawCircle(0f, 0f, 1f, p)
            c.restore()
        }
        p.shader = null
    }
}

class FireworksVisual : Visual {
    override val id = "fireworks"
    override val title = tr("Фейерверк", "Fireworks")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val n = 420
    private val x = FloatArray(n)
    private val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val life = FloatArray(n)
    private val hue = FloatArray(n)
    private var next = 0

    private fun spawn(px: Float, py: Float, speed: Float, hu: Float) {
        val ang = Random.nextFloat() * 2f * PI.toFloat()
        val s = speed * (0.3f + Random.nextFloat() * 0.7f)
        x[next] = px; y[next] = py
        vx[next] = cos(ang) * s; vy[next] = sin(ang) * s
        life[next] = 0.6f + Random.nextFloat() * 0.4f
        hue[next] = hu + Random.nextFloat() * 40f
        next = (next + 1) % n
    }

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 5f + 240f, 0.8f, 0.04f + 0.06f * a.bass))
        val m = min(w, h)
        if (a.beat >= 0.999f) {
            val px = w * (0.15f + Random.nextFloat() * 0.7f)
            val py = h * (0.15f + Random.nextFloat() * 0.45f)
            val hu = Random.nextFloat() * 360f
            repeat(45 + (a.bass * 60).toInt()) { spawn(px, py, m * 0.7f, hu) }
        }
        if (a.treble > 0.45f && Random.nextFloat() < 0.5f) {
            spawn(Random.nextFloat() * w, h, m * 0.25f, t * 60f)
            vy[(next + n - 1) % n] = -m * (0.4f + a.treble * 0.4f)
        }
        for (i in 0 until n) {
            if (life[i] <= 0f) continue
            vy[i] += m * 0.35f * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
            life[i] -= dt * 0.7f
            if (life[i] <= 0f) continue
            p.color = Pal.hue(hue[i], 0.85f, 1f, (life[i] * 255).toInt().coerceIn(0, 255))
            c.drawCircle(x[i], y[i], m * 0.006f + m * 0.012f * life[i], p)
        }
    }
}

class WarpVisual : Visual {
    override val id = "warp"
    override val title = tr("Полёт сквозь звёзды", "Starfield")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val n = 260
    private val sx = FloatArray(n) { Random.nextFloat() * 2f - 1f }
    private val sy = FloatArray(n) { Random.nextFloat() * 2f - 1f }
    private val sz = FloatArray(n) { Random.nextFloat() }

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 4f + 220f, 0.8f, 0.03f + 0.07f * a.bass))
        val cx = w / 2
        val cy = h / 2
        val m = min(w, h)
        val speed = 0.12f + 0.9f * a.bass + 0.7f * a.beat
        for (i in 0 until n) {
            val z0 = sz[i]
            val z1 = z0 - speed * dt
            if (z1 <= 0.03f) {
                sx[i] = Random.nextFloat() * 2f - 1f
                sy[i] = Random.nextFloat() * 2f - 1f
                sz[i] = 1f
                continue
            }
            sz[i] = z1
            val k = w * 0.5f
            val x0 = cx + sx[i] / z0 * k * 0.5f
            val y0 = cy + sy[i] / z0 * k * 0.5f
            val x1 = cx + sx[i] / z1 * k * 0.5f
            val y1 = cy + sy[i] / z1 * k * 0.5f
            p.strokeWidth = m * 0.0025f + (1f - z1) * m * 0.007f
            p.color = Pal.hue(t * 20f + z1 * 120f, 0.45f, 1f, (80 + (1f - z1) * 175).toInt())
            c.drawLine(x0, y0, x1, y1, p)
        }
    }
}

class HillsVisual : Visual {
    override val id = "hills"
    override val title = tr("Холмы", "Hills")
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    override fun draw(c: Canvas, w: Float, h: Float, a: AudioFeed, t: Float, dt: Float) {
        c.drawColor(Pal.hue(t * 6f + 270f, 0.7f, 0.12f + 0.12f * a.bass))
        val n = AudioFeed.BANDS
        for (layer in 0..3) {
            val off = layer * 0.07f
            path.reset()
            path.moveTo(0f, h)
            for (i in 0..n) {
                val bi = ((i + layer * 5) % n)
                val v = a.bands[bi] * (1f - layer * 0.18f) + 0.04f * sin(t * 1.3f + i * 0.5f + layer)
                val x = i * w / n
                val y = h * (0.88f - off - v * 0.5f)
                if (i == 0) path.lineTo(x, y) else path.lineTo(x, y)
            }
            path.lineTo(w, h)
            path.close()
            p.color = Pal.hue(t * 12f + layer * 60f, 0.75f, 0.95f - layer * 0.12f, 215)
            c.drawPath(path, p)
        }
    }
}
