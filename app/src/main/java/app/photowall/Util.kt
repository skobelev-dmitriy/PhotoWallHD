package app.photowall

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/** Picks the Russian or English string depending on the device language. */
fun tr(ru: String, en: String): String = if (Locale.getDefault().language == "ru") ru else en

object Net {
    fun localIp(): String? = try {
        val addrs = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress }
        (addrs.firstOrNull { it.isSiteLocalAddress } ?: addrs.firstOrNull())?.hostAddress
    } catch (e: Exception) {
        null
    }

    fun url(port: Int): String = "http://${localIp() ?: "?.?.?.?"}:$port"
}

/** Controls the slideshow that is currently on screen (if any) from the web UI. */
object Remote {
    @Volatile
    var view: SlideshowView? = null

    @Volatile
    var disco: DiscoView? = null

    /** The audio analyser of the disco screen, so the web API can push sound into it. */
    @Volatile
    var feed: AudioFeed? = null

    val active: Boolean get() = view != null || disco != null

    /** What is on screen right now: "slideshow", "disco" or "idle". */
    val mode: String get() = when {
        disco != null -> "disco"
        view != null -> "slideshow"
        else -> "idle"
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val snapLock = Any()
    private var snapJpeg: ByteArray? = null
    private var snapAt = 0L

    /** Low-frame-rate preview of the disco screen (null if disco is not on screen). Cached for 400 ms. */
    fun snapshot(): ByteArray? {
        val d = disco ?: return null
        synchronized(snapLock) {
            val cached = snapJpeg
            if (cached != null && android.os.SystemClock.uptimeMillis() - snapAt < 400) return cached
        }
        val result = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            try {
                result.set(d.snapshotJpeg(640))
            } catch (e: Exception) {
                // view is going away
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)) return null
        val jpeg = result.get() ?: return null
        synchronized(snapLock) {
            snapJpeg = jpeg
            snapAt = android.os.SystemClock.uptimeMillis()
        }
        return jpeg
    }

    fun send(action: String) {
        main.post {
            when (action) {
                "dnext" -> disco?.step(1)
                "dprev" -> disco?.step(-1)
                "stop" -> {
                    disco?.onClose?.invoke()
                    view?.onClose?.invoke()
                }
            }
            val v = view ?: return@post
            when (action) {
                "next" -> v.next()
                "prev" -> v.prev()
                "pause" -> v.setPaused(true)
                "resume" -> v.setPaused(false)
                "toggle" -> v.togglePause()
            }
        }
    }
}

object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(ctx: Context, color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, 10).toFloat()
    }

    /** A button with a clearly visible focus state, so it is usable from a TV remote. */
    fun button(ctx: Context, text: String, onClick: () -> Unit): Button = Button(ctx).apply {
        this.text = text
        isAllCaps = false
        textSize = 20f
        setTextColor(Color.WHITE)
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(ctx, 0xFF3D7BFF.toInt()))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(ctx, 0xFF3D7BFF.toInt()))
            addState(intArrayOf(), rounded(ctx, 0xFF2A2F3C.toInt()))
        }
        setPadding(dp(ctx, 24), dp(ctx, 14), dp(ctx, 24), dp(ctx, 14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(ctx, 10) }
        setOnClickListener { onClick() }
    }
}
