package app.photowall

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.min

/** The saved filmstrips, as a list that works with a TV remote. */
class FilmstripsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = Ui.dp(this, 32)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "🎞  " + tr("Диафильмы", "Filmstrips")
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })

        val films = FilmstripStore.get(this).list()
        if (films.isEmpty()) {
            column.addView(TextView(this).apply {
                text = tr(
                    "Пока нет сохранённых диафильмов.\n\nИмпортируйте файл .cbz на странице управления:\n${Net.url(ServerService.port)}",
                    "No saved filmstrips yet.\n\nImport a .cbz file on the control page:\n${Net.url(ServerService.port)}",
                )
                textSize = 20f
                setTextColor(0xFFB8C0D4.toInt())
                gravity = Gravity.CENTER
                setPadding(0, Ui.dp(this@FilmstripsActivity, 24), 0, Ui.dp(this@FilmstripsActivity, 24))
            })
        }
        var first: android.view.View? = null
        for (f in films) {
            val b = Ui.button(this, "▶  ${f.title}   ·   ${f.frames.size} ${tr("кадров", "frames")}") {
                startActivity(Intent(this, FilmstripActivity::class.java).putExtra(FilmstripActivity.EXTRA_ID, f.id))
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            column.addView(b)
            if (first == null) first = b
        }
        column.addView(Ui.button(this, tr("Назад", "Back")) { finish() })

        val maxW = min(resources.displayMetrics.widthPixels, Ui.dp(this, 720))
        val holder = LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL }
        holder.addView(column, LinearLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF12151D.toInt())
            addView(holder)
        })
        first?.requestFocus()
    }
}
