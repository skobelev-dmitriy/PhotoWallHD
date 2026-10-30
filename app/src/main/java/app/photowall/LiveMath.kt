package app.photowall

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One animated region: an effect with its parameters and bounding box. The formulas mirror vision/app/effects.py
 * (see docs/EFFECT-SPEC.md); a test compares them with vision/tests/reference.json.
 * Displacements are in fractions of the image width.
 */
class LayerMath(
    val effect: String,
    private val amplitude: Double,
    private val speed: Double,
    private val wavelength: Double,
    private val perspective: Double,
    private val spatial: Double,
    private val u0: Double,
    private val v0: Double,
    private val u1: Double,
    private val v1: Double,
) {
    /** Writes the displacement into out[0] = dx, out[1] = dy (before mask and strength). */
    fun displace(u: Double, v: Double, t: Double, aspect: Double, out: DoubleArray) {
        val a = amplitude
        val f = speed
        val span = max(v1 - v0, 1e-6)
        when (effect) {
            "ripple" -> {
                val lam = max(wavelength, 1e-4)
                val g = ((v - v0) / span).coerceIn(0.0, 1.0)
                val w = (1.0 - perspective) + perspective * g
                val s = v / lam + 0.37 * u * aspect / lam
                out[0] = a * w * (sin(TAU * (s - f * t)) + 0.5 * sin(TAU * (1.6 * s + 0.31 * u * aspect / lam + 2.0 * f * t))) / 1.5
                out[1] = 0.35 * a * w * cos(TAU * (1.3 * s - f * t + 0.25))
            }
            "sway" -> {
                val h = ((v1 - v) / span).coerceIn(0.0, 1.0).pow(1.5)
                val ph = TAU * (f * t + spatial * u)
                out[0] = a * h * (0.7 * sin(ph) + 0.3 * sin(2.0 * ph + 1.1))
                out[1] = 0.15 * a * h * sin(ph + 1.7)
            }
            "clouds" -> {
                out[0] = a * (sin(TAU * (0.7 * u + 0.3 * v - f * t)) + 0.5 * sin(TAU * (1.9 * u - 1.1 * v - 2.0 * f * t) + 1.0)) / 1.5
                out[1] = 0.4 * a * sin(TAU * (0.5 * u + 0.9 * v - f * t) + 2.0)
            }
            "breathe" -> {
                val s = a * sin(TAU * f * t)
                val k = s / (1.0 + s)
                out[0] = (u - (u0 + u1) / 2.0) * k * 0.5
                out[1] = (v - v1) * k / aspect
            }
            "flow" -> {
                val lam = max(wavelength, 1e-4)
                out[0] = a * sin(TAU * (v / lam - f * t) + TAU * 0.3 * u * aspect / lam)
                out[1] = 0.0
            }
            else -> {
                out[0] = 0.0
                out[1] = 0.0
            }
        }
    }

    companion object {
        private const val TAU = 2.0 * Math.PI

        /** Parameter defaults, as in vision/app/effects.py DEFAULT_PARAMS. */
        fun fromJson(effect: String, params: JSONObject?, bbox: JSONArray?): LayerMath {
            fun p(key: String, def: Double) = params?.optDouble(key, def) ?: def
            val (da, df, dl) = when (effect) {
                "ripple" -> Triple(0.0016, 0.35, 0.12)
                "sway" -> Triple(0.004, 0.25, 0.12)
                "clouds" -> Triple(0.002, 0.05, 0.12)
                "breathe" -> Triple(0.002, 0.2, 0.12)
                else -> Triple(0.006, 0.5, 0.15) // flow
            }
            return LayerMath(
                effect, p("amplitude", da), p("speed", df), p("wavelength", dl), p("perspective", 0.7), p("spatial", 3.0),
                bbox?.optDouble(0, 0.0) ?: 0.0, bbox?.optDouble(1, 0.0) ?: 0.0, bbox?.optDouble(2, 1.0) ?: 1.0, bbox?.optDouble(3, 1.0) ?: 1.0,
            )
        }
    }
}
