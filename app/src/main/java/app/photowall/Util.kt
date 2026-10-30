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

    val active: Boolean get() = view != null || disco != null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun send(action: String) {
        main.post {
            when (action) {
                "dnext" -> disco?.step(1)
                "dprev" -> disco?.step(-1)
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
