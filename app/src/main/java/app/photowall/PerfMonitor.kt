package app.photowall

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.view.Choreographer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Small pure helpers (no Android calls), so they can be tested on a plain JVM. */
object PerfMath {
    class ThreadStat(val name: String, val ticks: Long)

    /** One line of /proc/<pid>/task/<tid>/stat: thread name and CPU time (utime + stime) in clock ticks. */
    fun parseStat(line: String): ThreadStat? {
        val open = line.indexOf('(')
        val close = line.lastIndexOf(')')
        if (open < 0 || close < open) return null
        val rest = line.substring(close + 1).trim().split(' ')   // starts at field 3 (state); utime is field 14, stime field 15
        val utime = rest.getOrNull(11)?.toLongOrNull() ?: return null
        val stime = rest.getOrNull(12)?.toLongOrNull() ?: return null
        return ThreadStat(line.substring(open + 1, close), utime + stime)
    }

    /** Counts events in the last [spanMs] milliseconds. */
    class Window(private val spanMs: Long = 60_000) {
        private val times = ArrayDeque<Long>()

        @Synchronized
        fun add(now: Long) {
            times.addLast(now)
            trim(now)
        }

        @Synchronized
        fun count(now: Long): Int {
            trim(now)
            return times.size
        }

        private fun trim(now: Long) {
            while (times.isNotEmpty() && now - times.first() > spanMs) times.removeFirst()
        }
    }

    /** Frames per second, share of slow frames and the worst gap, from frame timestamps (ms) within the last [windowMs]. */
    class FrameStats(val fps: Double, val jankPercent: Double, val worstMs: Long)

    fun frameStats(stamps: List<Long>, now: Long, windowMs: Long = 2000, jankMs: Long = 34): FrameStats {
        val recent = stamps.filter { now - it <= windowMs }
        if (recent.size < 2) return FrameStats(0.0, 0.0, 0)
        var slow = 0
        var worst = 0L
        for (i in 1 until recent.size) {
            val gap = recent[i] - recent[i - 1]
            if (gap > jankMs) slow++
            if (gap > worst) worst = gap
        }
        val span = (recent.last() - recent.first()).coerceAtLeast(1)
        return FrameStats((recent.size - 1) * 1000.0 / span, slow * 100.0 / (recent.size - 1), worst)
    }
}

/**
 * Cheap runtime diagnostics for the web page ("why is it slow?"): process CPU and the busiest threads, memory, garbage collection,
 * frames per second, time spent drawing, background work (thumbnails, image sizes) and busiest web requests.
 * It only samples while the page asks for it, so it costs nothing the rest of the time.
 */
object PerfMonitor {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val startedAt = System.currentTimeMillis()

    // frames (only while someone is looking)
    private val frameLock = Any()
    private val frames = ArrayDeque<Long>()
    @Volatile private var lastTouch = 0L
    @Volatile private var frameLoopOn = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis()
            synchronized(frameLock) {
                frames.addLast(now)
                while (frames.size > 300) frames.removeFirst()
            }
            if (now - lastTouch < 15_000) Choreographer.getInstance().postFrameCallback(this) else frameLoopOn = false
        }
    }

    // draw time per kind of view (exponential moving average, ms), background work, requests
    private val drawMs = ConcurrentHashMap<String, Double>()
    private val events = ConcurrentHashMap<String, PerfMath.Window>()
    private val requests = ConcurrentHashMap<String, PerfMath.Window>()
    val activeConnections = AtomicInteger(0)
    private val requestsTotal = AtomicLong(0)

    /** Approximate bitmap memory held by the slideshow (current, outgoing and prefetched photos). */
    @Volatile var slideshowBitmapBytes = 0L

    // CPU sampling state
    private var prevWall = 0L
    private var prevCpuMs = 0L
    private var prevThreads = HashMap<String, Long>()
    private var prevGcCount = 0L
    private var prevGcMs = 0L
    private var cached: JSONObject? = null
    private val clockTick: Long = try { Os.sysconf(OsConstants._SC_CLK_TCK) } catch (e: Throwable) { 100L }

    /** Call whenever a client asks for stats: keeps the frame counter running for the next 15 seconds. */
    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
        if (!frameLoopOn) {
            frameLoopOn = true
            main.post { Choreographer.getInstance().postFrameCallback(frameCallback) }
        }
    }

    fun draw(kind: String, nanos: Long) {
        val ms = nanos / 1_000_000.0
        drawMs.merge(kind, ms) { old, new -> old * 0.9 + new * 0.1 }
    }

    fun event(name: String) {
        events.getOrPut(name) { PerfMath.Window() }.add(System.currentTimeMillis())
    }

    fun request(path: String) {
        requestsTotal.incrementAndGet()
        requests.getOrPut(path) { PerfMath.Window() }.add(System.currentTimeMillis())
    }

    @Synchronized
    fun snapshot(ctx: Context): JSONObject {
        touch()
        val now = SystemClock.elapsedRealtime()
        val cores = Runtime.getRuntime().availableProcessors()
        val o = JSONObject().put("uptimeSec", (System.currentTimeMillis() - startedAt) / 1000).put("cores", cores).put("mode", Remote.mode)

        // --- CPU of this app, and which threads use it ---
        val cpuMs = Process.getElapsedCpuTime()
        val threads = readThreads()
        val dt = now - prevWall
        val cpu = JSONObject()
        if (prevWall != 0L && dt >= 500) {
            val used = (cpuMs - prevCpuMs).toDouble() / dt          // cores busy on average
            cpu.put("coresBusy", round(used)).put("percentOfAll", round(used / cores * 100))
            val top = JSONArray()
            threads.entries.map { (name, ticks) -> name to (ticks - (prevThreads[name] ?: ticks)) * 1000.0 / clockTick / dt * 100 }
                .filter { it.second >= 0.5 }.sortedByDescending { it.second }.take(6)
                .forEach { top.put(JSONObject().put("name", it.first).put("percent", round(it.second))) }
            cpu.put("threads", top)
            prevWall = now; prevCpuMs = cpuMs; prevThreads = HashMap(threads)
        } else if (prevWall == 0L) {
            prevWall = now; prevCpuMs = cpuMs; prevThreads = HashMap(threads)
            cached?.optJSONObject("cpu")?.let { return cached!! }
        } else {
            cached?.optJSONObject("cpu")?.let { cpu.put("coresBusy", it.optDouble("coresBusy")).put("percentOfAll", it.optDouble("percentOfAll")).put("threads", it.optJSONArray("threads")) }
        }
        readCpuFreq()?.let { (cur, max) -> cpu.put("freqMHz", cur).put("maxFreqMHz", max) }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                cpu.put("thermal", (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus)  // 0 none … 3 severe … 6 shutdown
            } catch (e: Exception) {
            }
        }
        o.put("cpu", cpu)

        // --- memory ---
        val rt = Runtime.getRuntime()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val dbg = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        o.put(
            "memory",
            JSONObject().put("javaUsedMB", mb(rt.totalMemory() - rt.freeMemory())).put("javaMaxMB", mb(rt.maxMemory()))
                .put("nativeMB", mb(Debug.getNativeHeapAllocatedSize())).put("pssMB", dbg.totalPss / 1024)
                .put("systemAvailMB", mb(mi.availMem)).put("systemTotalMB", mb(mi.totalMem)).put("lowMemory", mi.lowMemory)
                .put("bitmapsMB", mb(slideshowBitmapBytes)),
        )

        // --- garbage collection since the last sample ---
        val gcCount = stat("art.gc.gc-count")
        val gcMs = stat("art.gc.gc-time")
        val gc = JSONObject()
        if (dt in 500..60_000 && prevGcCount != 0L) {
            gc.put("perSec", round((gcCount - prevGcCount) * 1000.0 / dt)).put("msPerSec", round((gcMs - prevGcMs) * 1000.0 / dt))
        }
        prevGcCount = gcCount; prevGcMs = gcMs
        o.put("gc", gc)

        // --- frames and drawing ---
        val stamps = synchronized(frameLock) { frames.toList() }
        val fs = PerfMath.frameStats(stamps, SystemClock.uptimeMillis())
        o.put("frames", JSONObject().put("fps", round(fs.fps)).put("jankPercent", round(fs.jankPercent)).put("worstMs", fs.worstMs).put("animating", fs.fps > 0.5))
        val draw = JSONObject()
        drawMs.forEach { (k, v) -> draw.put(k, round(v)) }
        o.put("drawMs", draw)

        // --- background work and the web server ---
        val t = System.currentTimeMillis()
        val ev = JSONObject()
        events.forEach { (k, w) -> ev.put(k, w.count(t)) }
        val hot = JSONObject()
        requests.entries.map { it.key to it.value.count(t) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(6).forEach { hot.put(it.first, it.second) }
        o.put("work", ev)
        o.put("server", JSONObject().put("activeConnections", activeConnections.get()).put("requestsTotal", requestsTotal.get()).put("last60s", hot))
        cached = o
        return o
    }

    private fun readThreads(): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            File("/proc/self/task").listFiles()?.forEach { dir ->
                try {
                    PerfMath.parseStat(File(dir, "stat").readText())?.let { s -> out["${s.name}#${dir.name}"] = s.ticks }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
        // merge threads that share a name (e.g. many "photowall-http") into one line
        val merged = HashMap<String, Long>()
        for ((k, v) in out) merged.merge(k.substringBefore('#'), v) { a, b -> a + b }
        return merged
    }

    private fun readCpuFreq(): Pair<Int, Int>? = try {
        val cur = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim().toInt() / 1000
        val max = File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000
        cur to max
    } catch (e: Exception) {
        null // not readable on many devices
    }

    private fun stat(name: String): Long = try { Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L } catch (e: Exception) { 0L }
    private fun mb(bytes: Long) = bytes / (1024 * 1024)
    private fun round(v: Double) = Math.round(v * 10) / 10.0
}
