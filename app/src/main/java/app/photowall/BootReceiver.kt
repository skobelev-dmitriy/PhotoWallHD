package app.photowall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ServerService.start(context, boot = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
