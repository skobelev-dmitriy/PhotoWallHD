package app.photowall

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var store: PhotoStore
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var overlayButton: Button
    private var countdownLeft = 0
    private var counting = false

    private val refresh = object : Runnable {
        override fun run() {
            val n = store.list().size
            status.text = if (n == 0) tr("Фотографий пока нет", "No photos yet")
            else tr("Фотографий: $n", "Photos: $n")
            address.text = Net.url(ServerService.port)
            overlayButton.visibility = if (needsOverlayPermission()) android.view.View.VISIBLE else android.view.View.GONE
            handler.postDelayed(this, 2000)
        }
    }

    private val countdown = object : Runnable {
        override fun run() {
            if (countdownLeft <= 0) {
                counting = false
                hint.text = hintText
                startShow()
                return
            }
            hint.text = tr("Показ начнётся через $countdownLeft с. Нажмите любую кнопку, чтобы отменить.",
                "Slideshow starts in $countdownLeft s. Press any button to cancel.")
            countdownLeft--
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var hint: TextView
    private val hintText get() = tr(
        "Откройте этот адрес на телефоне или компьютере в той же сети Wi-Fi, чтобы загружать фото.",
        "Open this address on a phone or computer on the same Wi-Fi network to upload photos."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = PhotoStore.get(this)
        ServerService.start(this, boot = false)

        val pad = Ui.dp(this, 32)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        column.addView(label(34f, Color.WHITE, true).apply { text = "PhotoWall HD" })
        status = label(20f, 0xFFB8C0D4.toInt()).also { column.addView(it) }
        address = label(34f, 0xFF7FB6E8.toInt(), true).also {
            it.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 8))
            column.addView(it)
        }
        hint = label(16f, 0xFF8890A4.toInt()).also {
            it.text = hintText
            it.setPadding(0, 0, 0, Ui.dp(this, 20))
            column.addView(it)
        }

        val start = Ui.button(this, tr("▶  Запустить показ", "▶  Start slideshow")) { cancelCountdown(); startShow() }
        column.addView(start)
        column.addView(Ui.button(this, tr("⚙  Настройки", "⚙  Settings")) {
            cancelCountdown()
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        overlayButton = Ui.button(this, tr("Разрешить автозапуск поверх других окон", "Allow auto-start over other apps")) {
            cancelCountdown()
            openOverlaySettings()
        }
        column.addView(overlayButton)
        column.addView(Ui.button(this, tr("Выход (остановить загрузку по сети)", "Quit (stop the upload server)")) {
            stopService(Intent(this, ServerService::class.java))
            finishAffinity()
        })

        val maxW = min(resources.displayMetrics.widthPixels, Ui.dp(this, 620))
        val holder = LinearLayout(this).apply { gravity = Gravity.CENTER }
        holder.addView(column, LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF12151D.toInt())
            isFillViewport = true
            addView(holder, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        start.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        if (!autoStartHandled) {
            autoStartHandled = true
            if (prefs.autostart && prefs.inActiveWindow() && store.list().isNotEmpty()) {
                countdownLeft = 5
                counting = true
                handler.post(countdown)
            }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        cancelCountdown()
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        cancelCountdown()
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelCountdown() {
        if (counting) {
            counting = false
            handler.removeCallbacks(countdown)
            countdownLeft = 0
            hint.text = hintText
        }
    }

    private fun startShow() {
        startActivity(Intent(this, SlideshowActivity::class.java))
    }

    private fun needsOverlayPermission() = Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
                Toast.makeText(this, tr("Эта настройка недоступна на устройстве", "This setting is not available on the device"), Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        var autoStartHandled = false
    }
}
