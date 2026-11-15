package com.simulasi.trading.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simulasi.trading.data.Prefs

/** Menyalakan lagi pemantauan latar belakang setelah HP restart, kalau statusnya masih ON. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.isEnabled(context)) {
            MonitorService.start(context)
        }
    }
}
