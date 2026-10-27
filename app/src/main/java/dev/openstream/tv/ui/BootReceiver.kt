package dev.openstream.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.openstream.tv.data.Preferences

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Preferences(context).launchOnBoot) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_STARTED_FROM_BOOT, true),
            )
        }
    }
}
