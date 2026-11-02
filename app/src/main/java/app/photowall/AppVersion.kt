package app.photowall

import android.content.Context
import android.os.Build

/** The installed build: versionName ("1.4.0-abc1234": release + git commit) and versionCode (100 + number of commits). */
object AppVersion {
    class Info(val name: String, val code: Long) {
        val label: String get() = "v$name · ${tr("сборка", "build")} $code"
    }

    fun get(ctx: Context): Info = try {
        val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()
        Info(p.versionName ?: "?", code)
    } catch (e: Exception) {
        Info("?", 0)
    }
}
