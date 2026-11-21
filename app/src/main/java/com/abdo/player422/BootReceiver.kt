package com.abdo.player422

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens the player and resumes the last channel when the receiver boots (if enabled). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_AUTOSTART, false)) return
        val launch = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(launch) } catch (_: Exception) { }
    }
}
