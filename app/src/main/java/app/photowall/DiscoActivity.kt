package app.photowall

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast

class DiscoActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var feed: AudioFeed
    private lateinit var view: DiscoView
    private var asked = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        prefs = Prefs(this)
        feed = AudioFeed(prefs)
        view = DiscoView(this, feed, prefs)
        view.onClose = { finish() }
        view.setOnClickListener { view.step(1) }
        setContentView(view)
        ServerService.start(this, boot = false)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        startFeed()
        view.resume()
        Remote.disco = view
    }

    override fun onPause() {
        Remote.disco = null
        view.pause()
        feed.stop()
        super.onPause()
    }

    private fun hasMic() = Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startFeed() {
        val src = prefs.discoSource
        if (src != "demo" && !hasMic() && !asked) {
            asked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        feed.start(src, hasMic())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startFeed()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> view.step(1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> view.step(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val on = view.toggleAuto()
                toast(if (on) tr("Автосмена эффектов", "Auto-switching on") else tr("Эффект зафиксирован", "Effect locked"))
            }
            KeyEvent.KEYCODE_DPAD_UP -> sensitivity(+20)
            KeyEvent.KEYCODE_DPAD_DOWN -> sensitivity(-20)
            KeyEvent.KEYCODE_MENU -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun sensitivity(delta: Int) {
        prefs.discoSens += delta
        toast(tr("Чувствительность: ${prefs.discoSens}%", "Sensitivity: ${prefs.discoSens}%"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
}
