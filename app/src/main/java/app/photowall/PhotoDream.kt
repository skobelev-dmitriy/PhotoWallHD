package app.photowall

import android.service.dreams.DreamService

/** Lets the slideshow be chosen as the system screensaver (Settings > Screen saver). */
class PhotoDream : DreamService() {
    private var show: SlideshowView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val v = SlideshowView(this)
        show = v
        setContentView(v)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        show?.start()
    }

    override fun onDreamingStopped() {
        show?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        show?.release()
        show = null
        super.onDetachedFromWindow()
    }
}
