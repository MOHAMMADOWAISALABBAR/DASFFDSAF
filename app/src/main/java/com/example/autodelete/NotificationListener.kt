package com.example.autodelete

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WATapService"
        private val BIDI_MARKS = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.whatsapp") return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val prefs = getSharedPreferences("autodelete_prefs", MODE_PRIVATE)
        val targetContactName = cleanText(prefs.getString("target_contact", "") ?: "")
        if (targetContactName.isEmpty()) return

        val rawTitle = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val cleanTitle = cleanText(rawTitle)

        if (!isMatchingContactTitle(cleanTitle, targetContactName)) return

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(sbn.notification)
        val messageText = cleanText(
            style?.messages?.lastOrNull()?.text?.toString()
                ?: sbn.notification.extras
                    .getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: ""
        )
        if (messageText.isEmpty()) return

        // عدم إلغاء الإشعار أو بدء الحذف إذا كانت الشاشة مغلقة/مقفلة أو خدمة الحذف غير متصلة
        if (!isDeviceUnlockedAndInteractive() || !AutoDeleteService.isRunning()) {
            Log.w(TAG, "Skipped auto-delete: device locked or AccessibilityService not running")
            return
        }

        Log.d(TAG, "Notification matched for $cleanTitle: ${messageText.take(40)}")

        try {
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cancel notification: ${e.message}")
        }

        AutoDeleteService.triggerDelete(targetContactName, messageText)
    }

    private fun isMatchingContactTitle(title: String, target: String): Boolean {
        if (title.equals(target, ignoreCase = true)) return true
        // صيغة واتساب عند تعدد الرسائل: "أحمد (رسالتان)" أو "أحمد: ..."
        if (title.startsWith("$target (", ignoreCase = true)) return true
        if (title.startsWith("$target:", ignoreCase = true)) return true
        return false
    }

    private fun isDeviceUnlockedAndInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun cleanText(input: String): String {
        return input.replace(BIDI_MARKS, "").trim()
    }
}
