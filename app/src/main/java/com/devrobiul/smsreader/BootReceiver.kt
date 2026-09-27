package com.devrobiul.smsreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * BootReceiver — Auto-starts the foreground service after:
 *   - Phone restart (BOOT_COMPLETED)
 *   - App update (MY_PACKAGE_REPLACED)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    if (Prefs.isMonitoringEnabled(context)) {
                        PaymentMonitorService.start(context)
                    }
                }
            }
        } catch (_: Exception) { }
    }
}
