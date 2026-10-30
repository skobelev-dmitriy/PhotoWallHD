package app.photowall

import android.app.Activity
import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** Remote-friendly settings: every row is a button that cycles through its values. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private val rows = ArrayList<Pair<Button, () -> String>>()
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val pad = Ui.dp(this, 32)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = tr("Настройки", "Settings")
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        val intervals = listOf(5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600)
        cycle(tr("Смена фото", "Change photo every"), intervals, { prefs.intervalSec }, { prefs.intervalSec = it }) { sec ->
            when {
                sec < 60 -> "$sec ${tr("с", "s")}"
                sec < 3600 -> "${sec / 60} ${tr("мин", "min")}"
                else -> "${sec / 3600} ${tr("ч", "h")}"
            }
        }
        cycle(tr("Переход", "Transition"), listOf("fade", "slide", "none"), { prefs.transition }, { prefs.transition = it }) {
            when (it) {
                "fade" -> tr("плавное растворение", "crossfade")
                "slide" -> tr("сдвиг", "slide")
                else -> tr("без эффекта", "none")
            }
        }
        cycle(tr("Масштаб", "Scaling"), listOf("fit", "fill", "auto"), { prefs.fit }, { prefs.fit = it }) {
            when (it) {
                "fit" -> tr("целиком (поля по краям)", "fit whole picture")
                "fill" -> tr("на весь экран (с обрезкой)", "fill screen (crop)")
                else -> tr("авто", "auto")
            }
        }
        toggle(tr("Медленное приближение", "Slow zoom (Ken Burns)"), { prefs.kenBurns }, { prefs.kenBurns = it })
        toggle(tr("Случайный порядок", "Shuffle"), { prefs.shuffle }, { prefs.shuffle = it })
        cycle(tr("Затемнение", "Dimming"), listOf(0, 10, 20, 30, 40, 50, 60), { prefs.dim }, { prefs.dim = it }) { "$it%" }

        toggle(tr("Часы", "Clock"), { prefs.clock }, { prefs.clock = it })
        cycle(tr("Положение часов", "Clock position"), listOf("tl", "tr", "bl", "bc", "br"), { prefs.clockPos }, { prefs.clockPos = it }) {
            when (it) {
                "tl" -> tr("слева сверху", "top left")
                "tr" -> tr("справа сверху", "top right")
                "bl" -> tr("слева снизу", "bottom left")
                "bc" -> tr("снизу по центру", "bottom center")
                else -> tr("справа снизу", "bottom right")
            }
        }
        cycle(tr("Размер часов", "Clock size"), listOf("s", "m", "l"), { prefs.clockSize }, { prefs.clockSize = it }) {
            when (it) {
                "s" -> tr("маленькие", "small")
                "m" -> tr("средние", "medium")
                else -> tr("большие", "large")
            }
        }
        toggle(tr("24-часовой формат", "24-hour format"), { prefs.clock24 }, { prefs.clock24 = it })
        toggle(tr("Дата под часами", "Show date"), { prefs.clockDate }, { prefs.clockDate = it })

        toggle(tr("Расписание (вне его — чёрный экран)", "Schedule (black screen outside it)"), { prefs.scheduleEnabled }, { prefs.scheduleEnabled = it })
        timeRow(tr("Включать в", "Turn on at"), { prefs.onMinutes }, { prefs.onMinutes = it })
        timeRow(tr("Выключать в", "Turn off at"), { prefs.offMinutes }, { prefs.offMinutes = it })
        toggle(tr("Автозапуск показа", "Auto-start slideshow"), { prefs.autostart }, { prefs.autostart = it })

        column.addView(Ui.button(this, tr("Назад", "Back")) { finish() })

        val maxW = min(resources.displayMetrics.widthPixels, Ui.dp(this, 620))
        val holder = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        holder.addView(column, LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF12151D.toInt())
            addView(holder)
        })
        rows.firstOrNull()?.first?.requestFocus()
    }

    private fun addRow(text: () -> String, onClick: () -> Unit) {
        lateinit var b: Button
        b = Ui.button(this, text()) {
            onClick()
            b.text = text()
        }
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        rows.add(b to text)
        column.addView(b)
    }

    private fun <T> cycle(label: String, options: List<T>, get: () -> T, set: (T) -> Unit, show: (T) -> String) {
        addRow({ "$label: ${show(get())}" }) {
            val i = options.indexOf(get())
            set(options[(i + 1) % options.size])
        }
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        addRow({ "$label: ${if (get()) tr("вкл", "on") else tr("выкл", "off")}" }) { set(!get()) }
    }

    private fun timeRow(label: String, get: () -> Int, set: (Int) -> Unit) {
        fun fmt() = "%02d:%02d".format(get() / 60, get() % 60)
        lateinit var b: Button
        b = Ui.button(this, "$label: ${fmt()}") {
            TimePickerDialog(this, { _, h, m ->
                set(h * 60 + m)
                b.text = "$label: ${fmt()}"
            }, get() / 60, get() % 60, true).show()
        }
        b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        rows.add(b to { "$label: ${fmt()}" })
        column.addView(b)
    }
}
