package app.photowall

import android.content.Context
import org.json.JSONObject
import java.util.Calendar
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** True if [now] (minutes from midnight) is inside [on]..[off]; windows may cross midnight, equal ends mean always. */
fun inWindow(now: Int, on: Int, off: Int): Boolean = when {
    on == off -> true
    on < off -> now in on until off
    else -> now >= on || now < off
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("photowall", Context.MODE_PRIVATE)

    var intervalSec by intPref("intervalSec", 30, 3, 86400)
    var transition by strPref("transition", "fade", setOf("fade", "slide", "none"))
    var fit by strPref("fit", "fit", setOf("fit", "fill", "auto"))
    var kenBurns by boolPref("kenBurns", true)
    var shuffle by boolPref("shuffle", false)

    var clock by boolPref("clock", true)
    var clockPos by strPref("clockPos", "br", setOf("tl", "tr", "bl", "br", "bc"))
    var clockSize by strPref("clockSize", "m", setOf("s", "m", "l"))
    var clock24 by boolPref("clock24", true)
    var clockDate by boolPref("clockDate", true)

    /** Percent of black laid over the picture, handy in a dark room. */
    var dim by intPref("dim", 0, 0, 80)

    var scheduleEnabled by boolPref("scheduleEnabled", false)
    var onMinutes by intPref("onMinutes", 18 * 60, 0, 1439)
    var offMinutes by intPref("offMinutes", 23 * 60, 0, 1439)

    /** Start the show by itself after boot / when the app is opened / when the schedule window begins. */
    var autostart by boolPref("autostart", true)

    var pin by strPref("pin", "", null)

    /** Id of the playlist to play ("" = all photos) and how it is chosen: "manual" or by each playlist's time window. */
    var activePlaylist by strPref("activePlaylist", "", null)
    var playlistMode by strPref("playlistMode", "manual", setOf("manual", "schedule"))

    /** "Living photos": address of the vision service, on/off, and a global multiplier for the motion (percent). */
    var visionUrl by strPref("visionUrl", "", null)
    var live by boolPref("live", true)
    var liveStrength by intPref("liveStrength", 100, 0, 400)

    // disco mode
    var discoSource by strPref("discoSource", "remote", setOf("remote", "mic", "output", "demo"))
    var discoVisual by strPref("discoVisual", "auto", setOf("auto", "bars", "circle", "rings", "wave", "lamps", "fireworks", "warp", "hills"))
    var discoAutoSec by intPref("discoAutoSec", 20, 5, 600)
    var discoSens by intPref("discoSens", 100, 40, 400)
    var discoFlash by boolPref("discoFlash", true)

    /** False while the schedule says "night": the screen stays black. */
    fun inActiveWindow(): Boolean {
        if (!scheduleEnabled) return true
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return inWindow(now, onMinutes, offMinutes)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("intervalSec", intervalSec)
        put("transition", transition)
        put("fit", fit)
        put("kenBurns", kenBurns)
        put("shuffle", shuffle)
        put("clock", clock)
        put("clockPos", clockPos)
        put("clockSize", clockSize)
        put("clock24", clock24)
        put("clockDate", clockDate)
        put("dim", dim)
        put("scheduleEnabled", scheduleEnabled)
        put("onMinutes", onMinutes)
        put("offMinutes", offMinutes)
        put("autostart", autostart)
        put("hasPin", pin.isNotEmpty())
        put("activePlaylist", activePlaylist)
        put("visionUrl", visionUrl)
        put("live", live)
        put("liveStrength", liveStrength)
        put("playlistMode", playlistMode)
        put("discoSource", discoSource)
        put("discoVisual", discoVisual)
        put("discoAutoSec", discoAutoSec)
        put("discoSens", discoSens)
        put("discoFlash", discoFlash)
    }

    fun update(j: JSONObject) {
        if (j.has("intervalSec")) intervalSec = j.optInt("intervalSec", intervalSec)
        if (j.has("transition")) transition = j.optString("transition", transition)
        if (j.has("fit")) fit = j.optString("fit", fit)
        if (j.has("kenBurns")) kenBurns = j.optBoolean("kenBurns", kenBurns)
        if (j.has("shuffle")) shuffle = j.optBoolean("shuffle", shuffle)
        if (j.has("clock")) clock = j.optBoolean("clock", clock)
        if (j.has("clockPos")) clockPos = j.optString("clockPos", clockPos)
        if (j.has("clockSize")) clockSize = j.optString("clockSize", clockSize)
        if (j.has("clock24")) clock24 = j.optBoolean("clock24", clock24)
        if (j.has("clockDate")) clockDate = j.optBoolean("clockDate", clockDate)
        if (j.has("dim")) dim = j.optInt("dim", dim)
        if (j.has("scheduleEnabled")) scheduleEnabled = j.optBoolean("scheduleEnabled", scheduleEnabled)
        if (j.has("onMinutes")) onMinutes = j.optInt("onMinutes", onMinutes)
        if (j.has("offMinutes")) offMinutes = j.optInt("offMinutes", offMinutes)
        if (j.has("autostart")) autostart = j.optBoolean("autostart", autostart)
        if (j.has("discoSource")) discoSource = j.optString("discoSource", discoSource)
        if (j.has("discoVisual")) discoVisual = j.optString("discoVisual", discoVisual)
        if (j.has("discoAutoSec")) discoAutoSec = j.optInt("discoAutoSec", discoAutoSec)
        if (j.has("discoSens")) discoSens = j.optInt("discoSens", discoSens)
        if (j.has("discoFlash")) discoFlash = j.optBoolean("discoFlash", discoFlash)
        if (j.has("visionUrl")) visionUrl = j.optString("visionUrl", "").trim().trimEnd('/').take(200)
        if (j.has("live")) live = j.optBoolean("live", live)
        if (j.has("liveStrength")) liveStrength = j.optInt("liveStrength", liveStrength)
        if (j.has("activePlaylist")) activePlaylist = j.optString("activePlaylist", activePlaylist)
        if (j.has("playlistMode")) playlistMode = j.optString("playlistMode", playlistMode)
        if (j.has("pin")) pin = j.optString("pin", "").trim().take(32)
    }

    private fun intPref(key: String, def: Int, min: Int, max: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value.coerceIn(min, max)).apply()
        }
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply()
        }
    }

    private fun strPref(key: String, def: String, allowed: Set<String>?) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            if (allowed == null || value in allowed) sp.edit().putString(key, value).apply()
        }
    }
}
