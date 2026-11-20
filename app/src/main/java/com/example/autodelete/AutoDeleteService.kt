package com.example.autodelete

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoDeleteService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var isProcessing = false
    private var pendingExpiresAt = 0L

    private val watchdogRunnable = Runnable {
        if (isProcessing || pendingMessageToDelete != null || manualDeleteRequested) {
            Log.w(TAG, "Watchdog timeout — resetting pending state")
            resetPending()
        }
    }

    companion object {
        private const val TAG = "WATapService"
        private const val OPERATION_TIMEOUT_MS = 12_000L
        private val BIDI_MARKS = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
        private val TIMESTAMP_PATTERN = Regex("^\\d{1,2}:\\d{2}(\\s*[APMapmصم]+)?$")

        @Volatile
        private var instance: AutoDeleteService? = null
        @Volatile
        private var pendingTargetContact: String? = null
        @Volatile
        private var pendingMessageToDelete: String? = null
        @Volatile
        private var manualDeleteRequested: Boolean = false

        fun isRunning(): Boolean = instance != null

        fun triggerDelete(targetContact: String, messageText: String) {
            val svc = instance ?: return
            pendingTargetContact = targetContact
            pendingMessageToDelete = messageText
            manualDeleteRequested = false
            svc.startOperationWindow()
            svc.openWhatsAppAndScheduleDelete()
        }

        fun requestManualTest(ctx: Context) {
            val svc = instance
            if (svc == null) {
                Toast.makeText(ctx, "فعّل خدمة الحذف (Accessibility) أولاً", Toast.LENGTH_SHORT).show()
                return
            }
            val savedTarget = ctx.getSharedPreferences("autodelete_prefs", Context.MODE_PRIVATE)
                .getString("target_contact", "")?.trim()
            pendingTargetContact = savedTarget?.takeIf { it.isNotEmpty() }
            pendingMessageToDelete = null
            manualDeleteRequested = true
            svc.startOperationWindow()
            svc.openWhatsAppAndScheduleDelete()
            Toast.makeText(ctx, "جارٍ فتح واتساب ومحاولة حذف آخر رسالة...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutoDeleteService connected")
    }

    private fun startOperationWindow() {
        pendingExpiresAt = System.currentTimeMillis() + OPERATION_TIMEOUT_MS
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, OPERATION_TIMEOUT_MS)
    }

    private fun hasActivePendingTask(): Boolean {
        if (pendingMessageToDelete == null && !manualDeleteRequested) return false
        if (System.currentTimeMillis() > pendingExpiresAt) {
            resetPending()
            return false
        }
        if (!isDeviceUnlockedAndInteractive()) {
            resetPending()
            return false
        }
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != "com.whatsapp") return
        if (!hasActivePendingTask()) return
        if (isProcessing) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            scheduleFindAttempt(700)
        }
    }

    private fun openWhatsAppAndScheduleDelete() {
        val intent = packageManager.getLaunchIntentForPackage("com.whatsapp")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch WhatsApp from background: ${e.message}")
            }
        } else {
            Log.e(TAG, "WhatsApp launch intent not found")
        }
        scheduleFindAttempt(1200)
    }

    private fun scheduleFindAttempt(delayMs: Long) {
        handler.removeCallbacks(findAttemptRunnable)
        handler.postDelayed(findAttemptRunnable, delayMs)
    }

    private val findAttemptRunnable = Runnable {
        findAndDeleteMessage()
    }

    private fun findAndDeleteMessage() {
        if (isProcessing) return
        if (!hasActivePendingTask()) return

        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow is null — waiting within timeout window")
            return
        }

        if (root.packageName?.toString() != "com.whatsapp") {
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels

        // التحقق من عنوان المحادثة لمنع الحذف من محادثة خاطئة في الوضع التلقائي
        val targetContact = pendingTargetContact
        if (!manualDeleteRequested && !targetContact.isNullOrBlank()) {
            if (!isTargetChatOpen(root, targetContact, screenHeight)) {
                Log.w(TAG, "Open WhatsApp screen does not match target contact: $targetContact")
                return
            }
        }

        val messageNode: AccessibilityNodeInfo? = if (manualDeleteRequested) {
            findLatestMessageBubble(root, screenHeight)
        } else {
            val targetText = pendingMessageToDelete ?: return
            findLatestMessageNodeByText(root, targetText, screenHeight)
        }

        if (messageNode == null) {
            if (manualDeleteRequested) {
                Toast.makeText(
                    this,
                    "لم يتم العثور على رسالة — تأكد أن المحادثة مفتوحة",
                    Toast.LENGTH_SHORT
                ).show()
                resetPending()
            } else {
                Log.w(TAG, "Exact target message not yet visible on screen")
            }
            return
        }

        val bounds = Rect()
        messageNode.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            resetPending()
            return
        }

        isProcessing = true
        Log.d(TAG, "Long pressing message at $bounds")

        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }

        val longPress = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 700))
            .build()

        val dispatched = dispatchGesture(longPress, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Long press OK — looking for delete button")
                handler.postDelayed({ clickDeleteButton() }, 500)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Long press CANCELLED — possibly Android 16 block")
                Toast.makeText(
                    this@AutoDeleteService,
                    "تم إلغاء الضغط المطول (قد يكون بسبب قيود النظام)",
                    Toast.LENGTH_LONG
                ).show()
                resetPending()
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "dispatchGesture (long press) returned false")
            resetPending()
        }
    }

    private fun clickDeleteButton() {
        val root = rootInActiveWindow
        if (root == null) {
            resetPending()
            return
        }

        val screenHeight = resources.displayMetrics.heightPixels
        val deleteKeywords = listOf("حذف", "Delete", "حذف لديّ", "حذف لدي", "Delete for me", "حذف لي")

        // أولوية للزر القابل للنقر في الشريط العلوي (ActionBar) لتجنب التقاط نص رسالة تحتوي كلمة "حذف"
        val deleteNode = findClickableActionNode(
            root = root,
            keywords = deleteKeywords,
            maxBottomY = (screenHeight * 0.25f).toInt()
        ) ?: findClickableActionNode(
            root = root,
            keywords = deleteKeywords,
            maxBottomY = screenHeight
        )

        if (deleteNode == null) {
            Log.w(TAG, "Delete action button not found")
            resetPending()
            return
        }

        val bounds = Rect()
        deleteNode.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            resetPending()
            return
        }

        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val tap = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()

        val dispatched = dispatchGesture(tap, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Delete icon/button tapped")
                handler.postDelayed({ confirmSecondDelete() }, 500)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Delete tap cancelled")
                resetPending()
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "dispatchGesture (delete button) returned false")
            resetPending()
        }
    }

    private fun confirmSecondDelete() {
        val root = rootInActiveWindow
        if (root == null) {
            resetPending()
            return
        }

        // نبحث فقط عن أزرار قابلة للنقر (وليس عنوان الحوار) لتأكيد الحذف
        val specificKeywords = listOf("حذف لديّ", "حذف لدي", "Delete for me", "حذف لي")
        val fallbackExactKeywords = listOf("حذف", "Delete")

        val confirmNode = findDialogButton(root, specificKeywords, exactOnly = false)
            ?: findDialogButton(root, fallbackExactKeywords, exactOnly = true)

        if (confirmNode == null) {
            Log.w(TAG, "Confirm delete button not found")
            resetPending()
            return
        }

        val bounds = Rect()
        confirmNode.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            resetPending()
            return
        }

        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val tap = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()

        val dispatched = dispatchGesture(tap, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Confirm delete tapped successfully")
                resetPending()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Confirm delete tap cancelled")
                resetPending()
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "dispatchGesture (confirm delete) returned false")
            resetPending()
        }
    }

    private fun resetPending() {
        handler.removeCallbacks(findAttemptRunnable)
        handler.removeCallbacks(watchdogRunnable)
        isProcessing = false
        pendingExpiresAt = 0L
        pendingTargetContact = null
        pendingMessageToDelete = null
        manualDeleteRequested = false
    }

    /** التحقق من أن عنوان المحادثة المفتوحة أعلى الشاشة يطابق الجهة المستهدفة */
    private fun isTargetChatOpen(
        root: AccessibilityNodeInfo,
        targetContact: String,
        screenHeight: Int
    ): Boolean {
        val headerLimitY = (screenHeight * 0.18f).toInt()
        val headerTexts = mutableListOf<String>()
        collectHeaderTexts(root, headerLimitY, headerTexts)
        val cleanTarget = cleanText(targetContact)
        return headerTexts.any { header ->
            header.equals(cleanTarget, ignoreCase = true) ||
                header.contains(cleanTarget, ignoreCase = true)
        }
    }

    private fun collectHeaderTexts(
        node: AccessibilityNodeInfo?,
        headerLimitY: Int,
        out: MutableList<String>
    ) {
        if (node == null) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val txt = cleanText(node.text?.toString() ?: "")
        if (txt.isNotEmpty() && !bounds.isEmpty && bounds.top >= 0 && bounds.bottom <= headerLimitY) {
            out.add(txt)
        }
        for (i in 0 until node.childCount) {
            collectHeaderTexts(node.getChild(i), headerLimitY, out)
        }
    }

    /** البحث عن أحدث فقاعة رسالة تطابق نص الرسالة (باختيار الأسفل في الشاشة) */
    private fun findLatestMessageNodeByText(
        root: AccessibilityNodeInfo,
        targetText: String,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val cleanTarget = cleanText(targetText)
        val minTopY = (screenHeight * 0.14f).toInt()
        val maxBottomY = (screenHeight * 0.88f).toInt()
        val matches = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()

        collectMatchingMessageNodes(root, cleanTarget, minTopY, maxBottomY, matches)
        return matches.maxByOrNull { it.first }?.second
    }

    private fun collectMatchingMessageNodes(
        node: AccessibilityNodeInfo?,
        cleanTarget: String,
        minTopY: Int,
        maxBottomY: Int,
        out: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val className = node.className?.toString() ?: ""
        val isInputField = className.contains("EditText", ignoreCase = true) || node.isEditable

        if (!isInputField) {
            val txt = cleanText(node.text?.toString() ?: "")
            if (txt.isNotEmpty() &&
                (txt.equals(cleanTarget, ignoreCase = true) || txt.contains(cleanTarget, ignoreCase = true))
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.top >= minTopY && bounds.bottom <= maxBottomY) {
                    out.add(bounds.bottom to node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectMatchingMessageNodes(node.getChild(i), cleanTarget, minTopY, maxBottomY, out)
        }
    }

    /** آخر فقاعة رسالة حقيقية في المحادثة (مع استبعاد التوقيت وحقل الكتابة والشريط العلوي) */
    private fun findLatestMessageBubble(
        root: AccessibilityNodeInfo,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val minTopY = (screenHeight * 0.14f).toInt()
        val maxBottomY = (screenHeight * 0.88f).toInt()
        val byResourceId = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val fallbackCandidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()

        traverseForBubbles(root, minTopY, maxBottomY, byResourceId, fallbackCandidates)

        return byResourceId.maxByOrNull { it.first }?.second
            ?: fallbackCandidates.maxByOrNull { it.first }?.second
    }

    private fun traverseForBubbles(
        node: AccessibilityNodeInfo?,
        minTopY: Int,
        maxBottomY: Int,
        byResourceId: MutableList<Pair<Int, AccessibilityNodeInfo>>,
        fallback: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val className = node.className?.toString() ?: ""
        val isInputField = className.contains("EditText", ignoreCase = true) || node.isEditable

        if (!isInputField && className.contains("TextView", ignoreCase = true)) {
            val txt = cleanText(node.text?.toString() ?: "")
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            val inChatRegion = !bounds.isEmpty && bounds.top >= minTopY && bounds.bottom <= maxBottomY
            val isTimestamp = TIMESTAMP_PATTERN.matches(txt)

            if (inChatRegion && txt.isNotEmpty() && !isTimestamp) {
                val viewId = node.viewIdResourceName ?: ""
                if (viewId.contains("message_text", ignoreCase = true)) {
                    byResourceId.add(bounds.bottom to node)
                } else if (!node.isClickable && txt.length > 1) {
                    fallback.add(bounds.bottom to node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            traverseForBubbles(node.getChild(i), minTopY, maxBottomY, byResourceId, fallback)
        }
    }

    /** البحث عن زر أو أيقونة قابلة للنقر في الشريط العلوي أو القائمة */
    private fun findClickableActionNode(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        maxBottomY: Int
    ): AccessibilityNodeInfo? {
        for (kw in keywords) {
            val found = searchClickableNode(root, kw, maxBottomY, exactOnly = false)
            if (found != null) return found
        }
        return null
    }

    /** البحث عن زر تأكيد داخل نافذة حوار (يتطلب isClickable == true لتجنب النقر على عنوان الحوار) */
    private fun findDialogButton(
        root: AccessibilityNodeInfo,
        keywords: List<String>,
        exactOnly: Boolean
    ): AccessibilityNodeInfo? {
        val screenHeight = resources.displayMetrics.heightPixels
        for (kw in keywords) {
            val found = searchClickableNode(root, kw, screenHeight, exactOnly = exactOnly)
            if (found != null) return found
        }
        return null
    }

    private fun searchClickableNode(
        node: AccessibilityNodeInfo?,
        kw: String,
        maxBottomY: Int,
        exactOnly: Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null

        val txt = cleanText(node.text?.toString() ?: "")
        val desc = cleanText(node.contentDescription?.toString() ?: "")

        val matched = if (exactOnly) {
            txt.equals(kw, ignoreCase = true) || desc.equals(kw, ignoreCase = true)
        } else {
            (txt.isNotEmpty() && txt.contains(kw, ignoreCase = true)) ||
                (desc.isNotEmpty() && desc.contains(kw, ignoreCase = true))
        }

        if (matched) {
            val clickableTarget = findNearestClickable(node)
            if (clickableTarget != null) {
                val bounds = Rect()
                clickableTarget.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.bottom <= maxBottomY) {
                    return clickableTarget
                }
            }
        }

        for (i in 0 until node.childCount) {
            val found = searchClickableNode(node.getChild(i), kw, maxBottomY, exactOnly)
            if (found != null) return found
        }
        return null
    }

    private fun findNearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 3) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    private fun isDeviceUnlockedAndInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun cleanText(input: String): String {
        return input.replace(BIDI_MARKS, "").trim()
    }

    override fun onInterrupt() {
        resetPending()
    }

    override fun onDestroy() {
        resetPending()
        instance = null
        super.onDestroy()
    }
}
