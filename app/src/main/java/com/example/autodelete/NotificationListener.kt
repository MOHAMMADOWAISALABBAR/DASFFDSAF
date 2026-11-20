package com.example.autodelete

import android.app.KeyguardManager
import android.app.Notification
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap

class NotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "WATapService"
        private const val DEDUP_WINDOW_MS = 4_000L
        private val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val MEDIA_HINTS = listOf(
            "📷", "🎥", "🎤", "🎵", "📄", "💟", "🎞",
            "صورة", "فيديو", "رسالة صوتية", "ملصق", "مستند", "مقطع صوتي",
            "photo", "video", "voice message", "sticker", "document", "audio", "gif"
        )

        @Volatile
        private var instance: NotificationListener? = null
        private val recentFingerprints = ConcurrentHashMap<String, Long>()

        fun cancelNotificationByKey(key: String?) {
            if (key.isNullOrBlank()) return
            val svc = instance ?: return
            try {
                svc.cancelNotification(key)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel notification: ${e.message}")
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in SUPPORTED_PACKAGES) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val cleanTitle = ContactMatcher.cleanText(rawTitle)

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val latestMsg = style?.messages?.lastOrNull()

        val personName = latestMsg?.person?.name?.toString()?.let { ContactMatcher.cleanText(it) }
        val personUris = extractPersonUris(notification, style)

        val match = ContactMatcher.matchNotification(
            ctx = this,
            cleanTitle = cleanTitle,
            personName = personName,
            personUris = personUris
        ) ?: return

        val messageText = ContactMatcher.cleanText(
            latestMsg?.text?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: ""
        )
        if (messageText.isEmpty()) return

        val senderName = personName?.takeIf { it.isNotBlank() } ?: match.displayLabel

        val now = System.currentTimeMillis()
        val fingerprint = "$pkg|${match.matchedRule}|$messageText"
        val prevTime = recentFingerprints[fingerprint]
        if (prevTime != null && (now - prevTime) < DEDUP_WINDOW_MS) {
            return
        }
        recentFingerprints[fingerprint] = now
        pruneOldFingerprints(now)

        val isMediaOrTruncated = detectMediaOrTruncated(messageText)
        Log.d(TAG, "Matched [$pkg] ${match.displayLabel} (rule=${match.matchedRule}): ${messageText.take(40)}")

        // 1. الرفع الدائم إلى الشبكة المحلية (يعمل حتى أثناء قفل الشاشة)
        if (AppPrefs.isLanUploadEnabled(this)) {
            LanUploader.enqueueMessage(
                this,
                PendingLanMessage(
                    contact = match.displayLabel,
                    phone = match.normalizedPhone ?: "",
                    sender = senderName,
                    message = messageText,
                    packageName = pkg,
                    isMedia = isMediaOrTruncated,
                    timestampMs = sbn.postTime.takeIf { it > 0 } ?: now
                )
            )
        }

        // 2. الحذف التلقائي أو التأجيل حتى فتح القفل
        if (!AppPrefs.isAutoDeleteEnabled(this)) {
            return
        }

        if (!AutoDeleteService.isRunning()) {
            AppPrefs.appendLog(this, "تخطي حذف رسالة من ${match.displayLabel} (خدمة الوصول غير مفعّلة)")
            return
        }

        val task = DeleteTask(
            packageName = pkg,
            targetDisplayLabel = match.displayLabel,
            targetHeaderAliases = match.possibleHeaderAliases,
            targetPhoneDigits = match.normalizedPhone,
            messageText = messageText,
            isMediaOrTruncated = isMediaOrTruncated,
            notificationKey = sbn.key,
            contentIntent = notification.contentIntent,
            isManualTest = false
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isLockedSecurely = km.isKeyguardLocked && km.isKeyguardSecure
        val isScreenOffWithLock = km.isKeyguardLocked

        if (isLockedSecurely) {
            // لا يمكن لأي تطبيق في أندرويد تجاوز PIN/البصمة، لكن يمكن إخفاء الإشعار وتأجيل الحذف فور فتح القفل
            if (AppPrefs.isHideNotifWhenLockedEnabled(this)) {
                cancelNotificationByKey(sbn.key)
            }
            if (AppPrefs.isDeferWhenLockedEnabled(this)) {
                AutoDeleteService.enqueueDeferredUntilUnlock(task)
                AppPrefs.appendLog(
                    this,
                    "الشاشة مقفلة بقفل آمن: تم حفظ رسالة ${match.displayLabel} للحذف التلقائي فور فتح القفل"
                )
            } else {
                AppPrefs.appendLog(this, "تخطي حذف رسالة من ${match.displayLabel} (الشاشة مقفلة)")
            }
            return
        }

        if (!pm.isInteractive && !isScreenOffWithLock) {
            // الشاشة مطفأة لكن بدون قفل آمن: يمكن إيقاظ الشاشة والحذف مباشرة
            AutoDeleteService.wakeScreenBriefly(this)
        }

        AutoDeleteService.enqueueDelete(task)
    }

    @Suppress("DEPRECATION")
    private fun extractPersonUris(
        notification: Notification,
        style: NotificationCompat.MessagingStyle?
    ): List<String> {
        val uris = LinkedHashSet<String>()
        style?.messages?.forEach { m ->
            m.person?.uri?.takeIf { it.isNotBlank() }?.let { uris.add(it) }
            m.person?.key?.takeIf { it.isNotBlank() }?.let { uris.add(it) }
        }
        val extras = notification.extras
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extras != null) {
            try {
                val people = extras.getParcelableArrayList<Person>(Notification.EXTRA_PEOPLE_LIST)
                people?.forEach { p ->
                    p.uri?.takeIf { it.isNotBlank() }?.let { uris.add(it) }
                    p.key?.takeIf { it.isNotBlank() }?.let { uris.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        return uris.toList()
    }

    private fun detectMediaOrTruncated(text: String): Boolean {
        if (text.endsWith("…") || text.endsWith("...")) return true
        val lower = text.lowercase()
        return MEDIA_HINTS.any { lower.contains(it.lowercase()) }
    }

    private fun pruneOldFingerprints(now: Long) {
        if (recentFingerprints.size > 50) {
            val it = recentFingerprints.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value > DEDUP_WINDOW_MS * 3) {
                    it.remove()
                }
            }
        }
    }
}
