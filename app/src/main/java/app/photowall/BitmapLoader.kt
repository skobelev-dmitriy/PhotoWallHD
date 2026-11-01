package app.photowall

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.max

object BitmapLoader {
    /** Textures bigger than this fail to upload on many TV GPUs. */
    private const val MAX_SIDE = 4096

    /**
     * Decodes [file] no larger than needed to cover a [targetW] x [targetH] screen
     * (so 4K photos stay sharp on a 4K projector, but a 50 MP original is still
     * downsampled), honouring EXIF rotation.
     */
    fun decode(file: File, targetW: Int, targetH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.path, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val orientation = exifOrientation(file)
        val swapped = orientation in 5..8
        val dispW = if (swapped) bounds.outHeight else bounds.outWidth
        val dispH = if (swapped) bounds.outWidth else bounds.outHeight

        var sample = 1
        while (dispW / (sample * 2) >= targetW && dispH / (sample * 2) >= targetH) sample *= 2

        var bmp: Bitmap? = null
        var attempts = 0
        while (bmp == null && attempts < 3) {
            attempts++
            try {
                val o = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                bmp = BitmapFactory.decodeFile(file.path, o)
                if (bmp == null) return null
            } catch (e: OutOfMemoryError) {
                sample *= 2
            }
        }
        val decoded = bmp ?: return null

        val matrix = matrixFor(orientation)
        val longest = max(decoded.width, decoded.height)
        val scale = if (longest > MAX_SIDE) MAX_SIDE.toFloat() / longest else 1f
        if (matrix == null && scale >= 1f) return decoded

        return try {
            val m = matrix ?: Matrix()
            if (scale < 1f) m.postScale(scale, scale)
            val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
            if (out !== decoded) decoded.recycle()
            out
        } catch (e: OutOfMemoryError) {
            decoded
        }
    }

    /** Width and height as the photo is displayed (EXIF rotation applied), without decoding the pixels. */
    fun displaySize(file: File): IntArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeFile(file.path, bounds)
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return if (exifOrientation(file) in 5..8) intArrayOf(bounds.outHeight, bounds.outWidth) else intArrayOf(bounds.outWidth, bounds.outHeight)
    }

    fun thumbnail(file: File, maxSide: Int): Bitmap? {
        val bmp = decode(file, maxSide, maxSide) ?: return null
        val longest = max(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val s = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun exifOrientation(file: File): Int = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun matrixFor(orientation: Int): Matrix? {
        val m = Matrix()
        when (orientation) {
            2 -> m.setScale(-1f, 1f)
            3 -> m.setRotate(180f)
            4 -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.setRotate(90f)
            7 -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            8 -> m.setRotate(-90f)
            else -> return null
        }
        return m
    }
}
