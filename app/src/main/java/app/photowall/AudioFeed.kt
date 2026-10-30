package app.photowall

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.SystemClock
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Turns sound into numbers the visualizations can draw: [BANDS] spectrum bands, bass / mid / treble,
 * a beat pulse and a short waveform. The sound comes from the microphone, from the device's own audio
 * output (Visualizer API), or from a built-in generator so the mode always has something to show.
 */
class AudioFeed(private val prefs: Prefs) {
    companion object {
        const val BANDS = 48
        const val WAVE = 256
        private const val FFT_N = 2048
        private const val RATE = 44100
        private const val SILENCE = 0.0015f
    }

    /** Smoothed band levels 0..1, low frequencies first. */
    val bands = FloatArray(BANDS)
    val wave = FloatArray(WAVE)

    @Volatile var bass = 0f
    @Volatile var mid = 0f
    @Volatile var treble = 0f

    /** 1.0 on the frame a beat is detected, then decays. */
    @Volatile var beat = 0f

    /** The source actually in use: "mic", "output" or "demo". */
    @Volatile var activeSource = "demo"

    /** Human-readable reason when we had to fall back to something else. */
    @Volatile var note: String? = null

    /** How long the input has been silent, in seconds. */
    @Volatile var silentSec = 0f

    @Volatile private var remoteLastMs = 0L
    @Volatile private var running = false
    private var thread: Thread? = null
    private var visualizer: Visualizer? = null
    private var beatPending = false

    private var agc = 0.01f
    private var wavePeak = 0.1f
    private var bassAvg = 0f
    private var lastBeatMs = 0L
    private val raw = FloatArray(BANDS)
    private val remoteRaw = FloatArray(BANDS)
    private val fft = Fft(FFT_N)

    fun start(requested: String, micAllowed: Boolean) {
        stop()
        running = true
        note = null
        silentSec = 0f
        when {
            requested == "demo" -> startDemo()
            requested == "remote" -> startDemo("remote")
            !micAllowed -> {
                note = tr("Нет разрешения на запись звука: показываю демо", "No audio permission: showing the demo")
                startDemo()
            }
            requested == "output" -> if (!startOutput()) startMic()
            else -> startMic()
        }
    }

    fun stop() {
        running = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (e: Exception) {
        }
        visualizer = null
        thread?.interrupt()
        thread = null
    }

    /** Call once per frame from the UI thread. */
    fun tick(dt: Float) {
        if (activeSource == "remote") {
            val fresh = SystemClock.uptimeMillis() - remoteLastMs < 2500
            note = if (fresh) null else tr(
                "Показываю демо. Чтобы играла ваша музыка, откройте на компьютере ${Net.url(ServerService.port)}/sender",
                "Showing the demo. To use your music, open ${Net.url(ServerService.port)}/sender on a computer"
            )
        }
        if (beatPending) {
            beatPending = false
            beat = 1f
        } else {
            beat = max(0f, beat - dt * 2.5f)
        }
    }

    // ---- microphone ----

    private fun startMic() {
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, FFT_N * 4))
        } catch (e: Exception) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            note = tr("Микрофон недоступен: показываю демо", "Microphone unavailable: showing the demo")
            startDemo()
            return
        }
        activeSource = "mic"
        thread = Thread({
            try {
                rec.startRecording()
                val hop = ShortArray(1024)
                val samples = FloatArray(FFT_N)
                val re = FloatArray(FFT_N)
                val im = FloatArray(FFT_N)
                val mag = FloatArray(FFT_N / 2)
                val binHz = RATE.toFloat() / FFT_N
                while (running) {
                    val n = rec.read(hop, 0, hop.size)
                    if (n <= 0) continue
                    System.arraycopy(samples, n, samples, 0, FFT_N - n)
                    var sum = 0f
                    var peak = 0f
                    for (i in 0 until n) {
                        val s = hop[i] / 32768f
                        samples[FFT_N - n + i] = s
                        sum += s * s
                        peak = max(peak, abs(s))
                    }
                    for (i in 0 until FFT_N) {
                        re[i] = samples[i] * fft.window[i]
                        im[i] = 0f
                    }
                    fft.transform(re, im)
                    for (k in 1 until FFT_N / 2) mag[k] = hypot(re[k], im[k]) / (FFT_N / 4f)
                    mapBands(mag, FFT_N / 2, binHz, raw)

                    wavePeak = max(wavePeak * 0.995f, max(peak, 0.02f))
                    val gain = prefs.discoSens / 100f / wavePeak
                    for (i in 0 until WAVE) wave[i] = (samples[FFT_N - 2 * WAVE + 2 * i] * gain).coerceIn(-1f, 1f)

                    process(raw, kotlin.math.sqrt(sum / n), n / RATE.toFloat())
                }
            } catch (e: Exception) {
                // stopped or the device revoked the microphone
            } finally {
                try {
                    rec.stop()
                } catch (e: Exception) {
                }
                rec.release()
            }
        }, "photowall-mic").also { it.isDaemon = true; it.start() }
    }

    // ---- device audio output ----

    private fun startOutput(): Boolean {
        return try {
            val v = Visualizer(0)
            val size = Visualizer.getCaptureSizeRange()[1]
            v.captureSize = size
            val mag = FloatArray(size / 2)
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vis: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    val w = waveform ?: return
                    var peak = 0f
                    for (i in 0 until WAVE) {
                        val s = ((w[(i * w.size / WAVE).coerceAtMost(w.size - 1)].toInt() and 0xFF) - 128) / 128f
                        wave[i] = (s * prefs.discoSens / 100f).coerceIn(-1f, 1f)
                        peak = max(peak, abs(s))
                    }
                    outputPeak = peak
                }

                override fun onFftDataCapture(vis: Visualizer?, data: ByteArray?, samplingRate: Int) {
                    val d = data ?: return
                    val n = d.size
                    for (k in 1 until n / 2) {
                        val re = d[2 * k].toFloat()
                        val im = d[2 * k + 1].toFloat()
                        mag[k] = hypot(re, im) / 128f
                    }
                    val binHz = (samplingRate / 1000f) / n
                    mapBands(mag, n / 2, binHz, raw)
                    process(raw, outputPeak * 0.3f, 0.05f)
                }
            }, Visualizer.getMaxCaptureRate(), true, true)
            v.enabled = true
            visualizer = v
            activeSource = "output"
            true
        } catch (e: Exception) {
            note = tr("Системный звук недоступен: слушаю микрофон", "Output capture unavailable: using the microphone")
            false
        }
    }

    @Volatile private var outputPeak = 0f

    // ---- built-in generator ----

    /** Built-in beat. With source "remote" it only plays while no browser is sending sound. */
    private fun startDemo(source: String = "demo") {
        activeSource = source
        thread = Thread({
            var t = 0f
            val r = java.util.Random()
            try {
                while (running) {
                    if (activeSource == "remote" && SystemClock.uptimeMillis() - remoteLastMs < 1500) {
                        Thread.sleep(100)
                        continue
                    }
                    t += 0.016f
                    val phase = (t * 124f / 60f) % 1f
                    val kick = exp(-phase * 6f)
                    val hat = exp(-((t * 124f / 30f) % 1f) * 9f)
                    val sweep = 24f + 18f * sin(t * 0.7f)
                    for (i in 0 until BANDS) {
                        var v = 0.04f * r.nextFloat()
                        if (i < 8) v += kick * (0.7f + 0.3f * sin(t * 3f + i))
                        if (i >= 30) v += hat * 0.5f * r.nextFloat()
                        val d = (i - sweep) / 4f
                        v += 0.45f * exp(-d * d)
                        raw[i] = v * 0.3f
                    }
                    for (i in 0 until WAVE) {
                        wave[i] = (0.6f * sin(i * 0.15f + t * 9f) * (0.4f + kick) + 0.2f * sin(i * 0.7f + t * 30f)).coerceIn(-1f, 1f)
                    }
                    process(raw, 1f, 0.016f)
                    Thread.sleep(16)
                }
            } catch (e: InterruptedException) {
            }
        }, "photowall-demo").also { it.isDaemon = true; it.start() }
    }

    // ---- sound sent from a browser (see assets/web/sender.html) ----

    /**
     * One frame from the browser: [bands] are linear amplitudes x10000 for [BANDS] bands, [w] a waveform in -100..100,
     * [rms] the level x10000.
     */
    fun pushRemote(bands: JSONArray?, w: JSONArray?, rms: Double) {
        if (activeSource != "remote" || bands == null) return
        val now = SystemClock.uptimeMillis()
        val dt = ((now - remoteLastMs) / 1000f).coerceIn(0.01f, 0.2f)
        remoteLastMs = now
        for (i in 0 until BANDS) remoteRaw[i] = bands.optDouble(i, 0.0).toFloat() / 10000f
        if (w != null) {
            val sens = prefs.discoSens / 100f
            for (i in 0 until min(w.length(), WAVE)) wave[i] = (w.optDouble(i, 0.0).toFloat() / 100f * sens).coerceIn(-1f, 1f)
        }
        process(remoteRaw, (rms / 10000.0).toFloat(), dt)
    }

    // ---- analysis ----

    /** Groups FFT bins into [BANDS] log-spaced bands between 50 Hz and 14 kHz. */
    private fun mapBands(mag: FloatArray, count: Int, binHz: Float, out: FloatArray) {
        val fMin = 50f
        val fMax = min(14000f, binHz * count * 0.98f)
        val ratio = fMax / fMin
        for (i in 0 until BANDS) {
            val f0 = fMin * ratio.pow(i.toFloat() / BANDS)
            val f1 = fMin * ratio.pow((i + 1f) / BANDS)
            val b0 = max(1, (f0 / binHz).toInt())
            val b1 = min(count, (f1 / binHz).toInt())
            var m = 0f
            if (b1 - b0 <= 1) {
                val p = (f0 + f1) * 0.5f / binHz
                val k = p.toInt().coerceIn(1, count - 2)
                val fr = p - k
                m = mag[k] * (1f - fr) + mag[k + 1] * fr
            } else {
                for (b in b0 until b1) if (mag[b] > m) m = mag[b]
            }
            out[i] = m
        }
    }

    /** Auto gain, smoothing, bass/mid/treble and beat detection for one frame of band energies. */
    @Synchronized
    private fun process(r: FloatArray, signal: Float, seconds: Float) {
        var mx = 0f
        for (i in 0 until BANDS) {
            r[i] *= 1f + i * 0.08f // music is bass-heavy; tilt so highs are visible too
            if (r[i] > mx) mx = r[i]
        }
        agc = max(agc * 0.9985f, max(mx, 0.004f))
        val sens = prefs.discoSens / 100f

        var nb = 0f
        for (i in 0 until BANDS) {
            val n = ((r[i] / agc).pow(0.7f) * sens).coerceIn(0f, 1f)
            if (i < 8) nb += n / 8f
            val p = bands[i]
            bands[i] = if (n > p) p + (n - p) * 0.7f else p * 0.88f + n * 0.12f
        }
        bass = avg(0, 8)
        mid = avg(8, 28)
        treble = avg(28, BANDS)

        bassAvg = bassAvg * 0.95f + nb * 0.05f
        val now = SystemClock.uptimeMillis()
        if (nb > bassAvg * 1.35f && nb > 0.35f && now - lastBeatMs > 260) {
            lastBeatMs = now
            beatPending = true
        }

        silentSec = if (signal < SILENCE) silentSec + seconds else 0f
    }

    private fun avg(from: Int, to: Int): Float {
        var s = 0f
        for (i in from until to) s += bands[i]
        return s / (to - from)
    }
}

/** In-place radix-2 FFT with a Hann window. */
class Fft(private val n: Int) {
    val window = FloatArray(n) { (0.5 - 0.5 * cos(2.0 * PI * it / (n - 1))).toFloat() }
    private val cosT = FloatArray(n / 2) { cos(-2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(-2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n)

    init {
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val tr = re[j + half] * wr - im[j + half] * wi
                    val ti = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - tr
                    im[j + half] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
