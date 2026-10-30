package app.photowall

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView

/** An ImageView that, when [live] is set, draws its bitmap through a gently moving mesh ("живое фото"). */
class LiveImageView(context: Context) : ImageView(context) {
    var live: LiveRender? = null
    var gain: Double = 1.0

    private val meshPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onDraw(canvas: Canvas) {
        val l = live
        val d = drawable
        if (l == null || d !is BitmapDrawable || d.bitmap == null) {
            super.onDraw(canvas)
            return
        }
        l.update(SystemClock.uptimeMillis() / 1000.0, gain, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        canvas.save()
        canvas.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        canvas.concat(imageMatrix)
        canvas.drawBitmapMesh(d.bitmap, l.cols, l.rows, l.verts, 0, null, 0, meshPaint)
        canvas.restore()
        postInvalidateOnAnimation()
    }
}
