package app.photowall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

/** Full-screen filmstrip player. Remote: ← → frames, OK next / pause, Menu settings, Back exit. */
class FilmstripActivity : Activity() {
    private lateinit var view: FilmstripView
    private lateinit var film: Filmstrip
    private val positions get() = getSharedPreferences("film_positions", Context.MODE_PRIVATE)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val store = FilmstripStore.get(this)
        val f = store.get(intent.getStringExtra(EXTRA_ID) ?: "")
        if (f == null || f.frames.isEmpty()) {
            Toast.makeText(this, tr("Диафильм не найден", "Filmstrip not found"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        film = f
        view = FilmstripView(this, film, store, Prefs(this))
        view.onClose = { finish() }
        view.onFrame = { positions.edit().putInt(film.id, it).apply() }
        setContentView(view)
        ServerService.start(this, boot = false)
    }

    override fun onResume() {
        super.onResume()
        if (!::view.isInitialized) return
        hideSystemUi()
        val at = if (intent.hasExtra(EXTRA_FRAME)) intent.getIntExtra(EXTRA_FRAME, 0) else positions.getInt(film.id, 0)
        view.start(if (at >= film.frames.size - 1 && !intent.hasExtra(EXTRA_FRAME)) 0 else at)
        Remote.film = view
    }

    override fun onPause() {
        if (::view.isInitialized) {
            Remote.film = null
            view.stop()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::view.isInitialized) view.release()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!::view.isInitialized) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> view.next()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> view.prev()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> view.action()
            KeyEvent.KEYCODE_MENU -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    /** Touch: the right two thirds of the screen go forward, the left third back. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (::view.isInitialized && e.action == MotionEvent.ACTION_UP) {
            if (e.x < view.width / 3f) view.prev() else view.action()
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_FRAME = "frame"
    }
}
