package cn.yuntong.companion.platform

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.yuntong.companion.YuntongApplication

class SmsReceiptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = (context.applicationContext as YuntongApplication).controller
        val eventId = intent.getStringExtra("eventId") ?: return
        val stage = intent.getStringExtra("stage") ?: return
        if (stage !in setOf("sent", "delivered") || !eventId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) return
        val total = intent.getIntExtra("partCount", 1)
        val part = intent.getIntExtra("part", 0)
        if (total !in 1..10 || part !in 0 until total) return
        val source = intent.getStringExtra("source") ?: "fall"
        val contactName = intent.getStringExtra("contactName").orEmpty()
        val prefs = context.getSharedPreferences("sms_receipts", Context.MODE_PRIVATE)
        val key = "$eventId:$stage"
        if (prefs.getBoolean("$key:done", false) || prefs.getBoolean("$key:part:$part", false)) return
        if (resultCode != Activity.RESULT_OK) {
            prefs.edit().putBoolean("$key:done", true).commit()
            controller.onSmsReceipt(eventId, stage, false, source, contactName)
            return
        }
        val count = prefs.getInt("$key:count", 0) + 1
        prefs.edit().putBoolean("$key:part:$part", true).putInt("$key:count", count)
            .putBoolean("$key:done", count == total).commit()
        if (count == total) controller.onSmsReceipt(eventId, stage, true, source, contactName)
    }
}
