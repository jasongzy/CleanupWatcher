package com.jasongzy.cleanupwatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        )
            return
        if (!context.getSharedPreferences("settings", 0).getBoolean("boot", false)) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Backend(context.applicationContext).bootStart()
            } catch (e: Exception) {
                android.util.Log.w("CleanupWatcher", "Boot startup failed", e)
            } finally {
                result.finish()
            }
        }
    }
}
