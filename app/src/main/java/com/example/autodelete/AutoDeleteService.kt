package com.example.autodelete

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat

data class DeleteTask(
    val packageName: String,
    val targetDisplayLabel: String?,
    val targetHeaderAliases: List<String>,
    val targetPhoneDigits: String?,
    val messageText: String?,
    val isMediaOrTruncated: Boolean,
    val notificationKey: String?,
    val contentIntent: PendingIntent?,
    val isManualTest: Boolean
)

class AutoDeleteService : AccessibilityService() {

    private enum class State {
        IDLE,
        OPENING_CHAT,
        WAITING_DELETE_ACTION,
        WAITING_CONFIRM_DIALOG
    }

    private val handler = Handler(Looper.getMainLooper())
    private val taskQueue = ArrayDeque<DeleteTask>()
    private val deferredQueue = ArrayDeque<DeleteTask>()

    private var currentTask: DeleteTask? = null
    private var state: State = State.IDLE
    private var stateEnteredAt: Long = 0L
    private var stepActionDispatched: Boolean = false
    private var lastLongPressBounds: Rect? = null
    private var usedGestureFallbackForLongPress: Boolean = false
    private var triedOverflowMenu: Boolean = false
    private var receiverRegistered: Boolean = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) {
                handler.postDelayed({ flushDeferredIfUnlocked() }, 900L)
            }
        }
    }

    private val stepRunnable = Runnable {
        processCurrentState()
    }

    private val watchdogRunnable = Runnable {
        val task = currentTask ?: return@Runnable
        val label = task.targetDisplayLabel ?: "اختبار يدوي"
        Log.w(TAG, "Watchdog timeout in state $state for $label")
        AppPrefs.appendLog(this, "انتهت المهلة في مرحلة $state ($label)")
        finishCurrentTaskAndProceed(succeeded = false)
    }

    companion object {
        private const val TAG = "WATapService"
        private const val OPERATION_TIMEOUT_MS = 15_000L
        private val SUPPORTED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val TIMESTAMP_PATTERN = Regex("^\\d{1,2}:\\d{2}(\\s*[APMapmصم]+)?$")
        private val EXCLUDED_VIEW_ID_HINTS = listOf(
            "wallpaper", "background", "avatar", "profile", "contact_photo",
            "emoji", "camera", "input", "entry", "send", "voice_note_btn",
            "conversation_contact", "toolbar", "header", "date_wrapper", "info"
        )
        private val BUBBLE_ID_HINTS = listOf(
            "message_text", "caption", "sticker_image", "audio_player",
            "video_thumbnail", "document_", "media_container", "image_thumb"
        )
        private val DELETE_RESOURCE_IDS = listOf(
            "menuitem_delete", "action_delete", "delete_button", "btn_delete"
        )

        @Volatile
        private var instance: AutoDeleteService? = null

        fun isRunning(): Boolean = instance != null

        fun getDeferredCount(): Int = instance?.deferredQueue?.size ?: 0

        @Suppress("DEPRECATION")
        fun wakeScreenBriefly(ctx: Context) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive) {
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "AutoDeleteWA:WakeLock"
                    )
                    wl.acquire(10_000L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock failed: ${e.message}")
            }
        }

        fun enqueueDelete(task: DeleteTask) {
            val svc = instance ?: return
            svc.handler.post {
                svc.taskQueue.addLast(task)
                if (svc.state == State.IDLE) {
                    svc.startNextTask()
                }
            }
        }

        fun enqueueDeferredUntilUnlock(task: DeleteTask) {
            val svc = instance ?: return
            svc.handler.post {
                if (svc.deferredQueue.size >= 30) {
                    svc.deferredQueue.removeFirstOrNull()
                }
                svc.deferredQueue.addLast(task)
            }
        }

        fun requestManualTest(ctx: Context) {
            val svc = instance
            if (svc == null) {
                Toast.makeText(ctx, "فعّل خدمة الحذف (Accessibility) أولاً", Toast.LENGTH_SHORT).show()
                return
            }
            val pm = ctx.packageManager
            val pkg = when {
                pm.getLaunchIntentForPackage("com.whatsapp") != null -> "com.whatsapp"
                pm.getLaunchIntentForPackage("com.whatsapp.w4b") != null -> "com.whatsapp.w4b"
                else -> "com.whatsapp"
            }
            val firstTarget = ContactMatcher.resolveAllTargets(ctx).firstOrNull()
            enqueueDelete(
                DeleteTask(
                    packageName = pkg,
                    targetDisplayLabel = firstTarget?.displayNames?.firstOrNull() ?: firstTarget?.rawRule,
                    targetHeaderAliases = firstTarget?.displayNames ?: emptyList(),
                    targetPhoneDigits = firstTarget?.normalizedDigits,
                    messageText = null,
                    isMediaOrTruncated = true,
                    notificationKey = null,
                    contentIntent = null,
                    isManualTest = true
                )
            )
            Toast.makeText(ctx, "جارٍ فتح واتساب ومحاولة حذف آخر رسالة...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerUnlockReceiver()
        Log.d(TAG, "AutoDeleteService connected")
    }

    private fun registerUnlockReceiver() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            ContextCompat.registerReceiver(
                this,
                unlockReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register unlock receiver: ${e.message}")
        }
    }

    private fun flushDeferredIfUnlocked() {
        if (deferredQueue.isEmpty()) return
        if (!isDeviceUnlockedAndInteractive()) return
        val count = deferredQueue.size
        AppPrefs.appendLog(this, "تم فتح القفل: بدء حذف $count رسالة مؤجّلة تلقائياً")
        while (deferredQueue.isNotEmpty()) {
            taskQueue.addLast(deferredQueue.removeFirst())
        }
        if (state == State.IDLE) {
            startNextTask()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in SUPPORTED_PACKAGES) return
        if (state == State.IDLE || currentTask == null) return
        if (currentTask?.packageName != pkg) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            scheduleStep(250L)
        }
    }

    private fun startNextTask() {
        if (state != State.IDLE) return
        val next = taskQueue.removeFirstOrNull() ?: return

        if (!isDeviceUnlockedAndInteractive()) {
            if (!next.isManualTest && AppPrefs.isDeferWhenLockedEnabled(this)) {
                deferredQueue.addFirst(next)
                AppPrefs.appendLog(this, "تأجيل المهمة لحين فتح قفل الشاشة")
            } else {
                AppPrefs.appendLog(this, "توقف الحذف لأن الشاشة مقفلة")
            }
            return
        }

        currentTask = next
        transitionTo(State.OPENING_CHAT)
        lastLongPressBounds = null
        usedGestureFallbackForLongPress = false
        triedOverflowMenu = false

        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, OPERATION_TIMEOUT_MS)

        openTargetChat(next)
        scheduleStep(800L)
    }

    private fun openTargetChat(task: DeleteTask) {
        // 1. الأولوية لفتح المحادثة مباشرة عبر contentIntent القادم من الإشعار
        val pi = task.contentIntent
        if (pi != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        )
                    pi.send(this, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                Log.d(TAG, "Opened target chat directly via contentIntent")
                return
            } catch (e: Exception) {
                Log.w(TAG, "contentIntent.send() failed: ${e.message}")
            }
        }

        // 2. بديل مباشر عبر رابط الرقم إذا كان لدينا رقم دولي (مفيد للمهام المؤجلة بعد فتح القفل)
        val phone = task.targetPhoneDigits
        if (!task.isManualTest && !phone.isNullOrBlank() && phone.length >= 10 && !phone.startsWith("0")) {
            try {
                val waIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://api.whatsapp.com/send?phone=$phone")
                ).apply {
                    setPackage(task.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(waIntent)
                Log.d(TAG, "Opened target chat via phone deep link")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Phone deep link failed: ${e.message}")
            }
        }

        // 3. فتح واتساب العادي كخيار أخير
        val intent = packageManager.getLaunchIntentForPackage(task.packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch ${task.packageName}: ${e.message}")
                AppPrefs.appendLog(this, "تعذّر فتح واتساب: ${e.message}")
            }
        } else {
            AppPrefs.appendLog(this, "لم يتم العثور على تطبيق ${task.packageName}")
        }
    }

    private fun transitionTo(newState: State) {
        state = newState
        stateEnteredAt = System.currentTimeMillis()
        stepActionDispatched = false
    }

    private fun scheduleStep(delayMs: Long) {
        handler.removeCallbacks(stepRunnable)
        handler.postDelayed(stepRunnable, delayMs)
    }

    /** جمع كل النوافذ النشطة لواتساب (بما فيها نافذة المحادثة ونافذة حوار تأكيد الحذف) */
    private fun getAllTargetRoots(packageName: String): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == packageName) {
            roots.add(active)
        }
        try {
            val winList = windows
            if (winList != null) {
                for (w in winList) {
                    val r = w.root ?: continue
                    val pkg = r.packageName?.toString()
                    if (pkg == packageName && roots.none { it == r }) {
                        roots.add(r)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return roots
    }

    private fun processCurrentState() {
        val task = currentTask ?: return
        if (state == State.IDLE || stepActionDispatched) return

        if (!isDeviceUnlockedAndInteractive()) {
            finishCurrentTaskAndProceed(succeeded = false)
            return
        }

        val roots = getAllTargetRoots(task.packageName)
        if (roots.isEmpty()) {
            scheduleStep(350L)
            return
        }

        when (state) {
            State.OPENING_CHAT -> handleOpeningChat(roots, task)
            State.WAITING_DELETE_ACTION -> handleWaitingDeleteAction(roots)
            State.WAITING_CONFIRM_DIALOG -> handleWaitingConfirmDialog(roots, task)
            State.IDLE -> Unit
        }
    }

    private fun handleOpeningChat(roots: List<AccessibilityNodeInfo>, task: DeleteTask) {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels
        val screenWidth = metrics.widthPixels
        val chatRoot = roots.first()

        // التحقق من فتح المحادثة المستهدفة (عبر الاسم أو الرقم)
        if (!task.isManualTest && task.targetHeaderAliases.isNotEmpty()) {
            if (!isTargetChatOpen(chatRoot, task.targetHeaderAliases, task.targetPhoneDigits, screenHeight)) {
                scheduleStep(350L)
                return
            }
        }

        val elapsed = System.currentTimeMillis() - stateEnteredAt

        val messageNode: AccessibilityNodeInfo? = when {
            task.isManualTest -> findLatestMessageBubble(chatRoot, screenWidth, screenHeight)
            !task.isMediaOrTruncated && !task.messageText.isNullOrBlank() -> {
                val exact = findLatestMessageNodeByText(chatRoot, task.messageText, screenHeight)
                // إذا تأكدنا أننا داخل المحادثة الصحيحة ولم يتطابق النص بعد ثانيتين (بسبب تنسيق أو رد)، نختار آخر فقاعة
                if (exact != null) exact
                else if (elapsed > 2000L) findLatestMessageBubble(chatRoot, screenWidth, screenHeight)
                else null
            }
            else -> {
                val byText = task.messageText?.takeIf { it.length >= 6 }?.let {
                    findLatestMessageNodeByText(chatRoot, it.trimEnd('.', '…'), screenHeight)
                }
                byText ?: findLatestMessageBubble(chatRoot, screenWidth, screenHeight)
            }
        }

        if (messageNode == null) {
            if (task.isManualTest && elapsed > 3000L) {
                Toast.makeText(
                    this,
                    "لم يتم العثور على رسالة — تأكد أن المحادثة مفتوحة",
                    Toast.LENGTH_SHORT
                ).show()
                AppPrefs.appendLog(this, "فشل الاختبار اليدوي: لم تظهر أي فقاعة رسالة على الشاشة")
                finishCurrentTaskAndProceed(succeeded = false)
            } else {
                scheduleStep(350L)
            }
            return
        }

        val bounds = Rect()
        messageNode.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            scheduleStep(350L)
            return
        }

        lastLongPressBounds = Rect(bounds)
        stepActionDispatched = true
        AppPrefs.appendLog(this, "تم العثور على الرسالة — جارٍ تحديدها للحذف")

        // في واتساب الحديث غالباً تكون إيماءة الضغط المطول أدق من ACTION_LONG_CLICK؛ نجرّب ACTION_LONG_CLICK فقط إذا كانت العقدة قابلة له، وإلا إيماءة مباشرة
        val longClickable = findNearestLongClickable(messageNode)
        if (longClickable != null && longClickable.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            Log.d(TAG, "ACTION_LONG_CLICK dispatched")
            transitionTo(State.WAITING_DELETE_ACTION)
            scheduleStep(400L)
            return
        }

        usedGestureFallbackForLongPress = true
        dispatchLongPressGesture(bounds)
    }

    private fun dispatchLongPressGesture(bounds: Rect) {
        // نضغط داخل الفقاعة بنقطة آمنة بعيدة عن الحواف والروابط المحتملة
        val x = (bounds.left + bounds.width() * 0.35f).coerceIn(bounds.left + 8f, bounds.right - 8f)
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val longPress = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 750))
            .build()

        val dispatched = dispatchGesture(longPress, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Long press gesture completed at ($x, $y)")
                transitionTo(State.WAITING_DELETE_ACTION)
                scheduleStep(400L)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Long press gesture cancelled")
                AppPrefs.appendLog(this@AutoDeleteService, "تم إلغاء إيماءة الضغط المطول من النظام")
                finishCurrentTaskAndProceed(succeeded = false)
            }
        }, null)

        if (!dispatched) {
            AppPrefs.appendLog(this, "فشل تنفيذ إيماءة الضغط المطول")
            finishCurrentTaskAndProceed(succeeded = false)
        }
    }

    private fun handleWaitingDeleteAction(roots: List<AccessibilityNodeInfo>) {
        val screenHeight = resources.displayMetrics.heightPixels
        val topBarLimit = (screenHeight * 0.28f).toInt()
        val deleteKeywords = listOf("حذف", "الحذف", "delete")

        var deleteNode: AccessibilityNodeInfo? = null
        // 1. البحث المباشر بمعرّف أيقونة الحذف في واتساب (الأدق والأسرع)
        for (r in roots) {
            deleteNode = findNodeByResourceIds(r, DELETE_RESOURCE_IDS, screenHeight)
            if (deleteNode != null) break
        }

        // 2. البحث بالكلمات المفتاحية في الشريط العلوي
        if (deleteNode == null) {
            for (r in roots) {
                deleteNode = findClickableActionNode(r, deleteKeywords, topBarLimit)
                if (deleteNode != null) break
            }
        }

        // 3. البحث في القائمة المنسدلة إذا فُتحت
        if (deleteNode == null && triedOverflowMenu) {
            for (r in roots) {
                deleteNode = findClickableActionNode(r, deleteKeywords, screenHeight)
                if (deleteNode != null) break
            }
        }

        if (deleteNode == null) {
            val elapsed = System.currentTimeMillis() - stateEnteredAt
            val savedBounds = lastLongPressBounds

            // إذا فشل ACTION_LONG_CLICK في إظهار شريط الأدوات، ننفّذ إيماءة الضغط المطول الحقيقية فوراً
            if (!usedGestureFallbackForLongPress && savedBounds != null && elapsed > 900L) {
                usedGestureFallbackForLongPress = true
                stepActionDispatched = true
                AppPrefs.appendLog(this, "إعادة التحديد بإيماءة الضغط المطول")
                dispatchLongPressGesture(savedBounds)
                return
            }

            // على بعض الشاشات تكون أيقونة الحذف داخل قائمة النقاط الثلاث
            if (usedGestureFallbackForLongPress && !triedOverflowMenu && elapsed > 2000L) {
                val overflowKeywords = listOf("المزيد من الخيارات", "خيارات اضافيه", "more options")
                var overflowNode: AccessibilityNodeInfo? = null
                for (r in roots) {
                    overflowNode = findClickableActionNode(r, overflowKeywords, topBarLimit)
                    if (overflowNode != null) break
                }
                if (overflowNode != null) {
                    triedOverflowMenu = true
                    stepActionDispatched = true
                    performClickWithFallback(
                        node = overflowNode,
                        onDone = {
                            stepActionDispatched = false
                            scheduleStep(350L)
                        },
                        onFail = {
                            stepActionDispatched = false
                            scheduleStep(350L)
                        }
                    )
                    return
                }
            }

            scheduleStep(350L)
            return
        }

        stepActionDispatched = true
        AppPrefs.appendLog(this, "تم العثور على أيقونة الحذف — جارٍ النقر")
        performClickWithFallback(
            node = deleteNode,
            onDone = {
                transitionTo(State.WAITING_CONFIRM_DIALOG)
                scheduleStep(400L)
            },
            onFail = {
                AppPrefs.appendLog(this, "فشل النقر على أيقونة الحذف")
                finishCurrentTaskAndProceed(succeeded = false)
            }
        )
    }

    private fun handleWaitingConfirmDialog(roots: List<AccessibilityNodeInfo>, task: DeleteTask) {
        val specificKeywords = listOf("حذف لدي", "الحذف لدي", "delete for me", "حذف لي")
        val fallbackExactKeywords = listOf("حذف", "الحذف", "delete")

        var confirmNode: AccessibilityNodeInfo? = null
        // البحث في جميع نوافذ واتساب (مهم جداً لأن مربع التأكيد يفتح في نافذة مستقلة)
        for (r in roots) {
            confirmNode = findDialogButton(r, specificKeywords, exactOnly = false)
            if (confirmNode != null) break
        }
        if (confirmNode == null) {
            for (r in roots) {
                confirmNode = findDialogButton(r, fallbackExactKeywords, exactOnly = true)
                if (confirmNode != null) break
            }
        }

        if (confirmNode == null) {
            scheduleStep(300L)
            return
        }

        stepActionDispatched = true
        performClickWithFallback(
            node = confirmNode,
            onDone = {
                val label = task.targetDisplayLabel ?: "اختبار يدوي"
                Log.d(TAG, "Message deleted successfully ($label)")
                AppPrefs.appendLog(this, "تم حذف الرسالة بنجاح ($label)")
                NotificationListener.cancelNotificationByKey(task.notificationKey)
                finishCurrentTaskAndProceed(succeeded = true)
            },
            onFail = {
                AppPrefs.appendLog(this, "فشل النقر على زر تأكيد الحذف")
                finishCurrentTaskAndProceed(succeeded = false)
            }
        )
    }

    private fun performClickWithFallback(
        node: AccessibilityNodeInfo,
        onDone: () -> Unit,
        onFail: () -> Unit
    ) {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onDone()
            return
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            onFail()
            return
        }

        val path = Path().apply { moveTo(bounds.centerX().toFloat(), bounds.centerY().toFloat()) }
        val tap = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()

        val dispatched = dispatchGesture(tap, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onDone()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onFail()
            }
        }, null)

        if (!dispatched) {
            onFail()
        }
    }

    private fun finishCurrentTaskAndProceed(succeeded: Boolean) {
        handler.removeCallbacks(stepRunnable)
        handler.removeCallbacks(watchdogRunnable)
        val completedTask = currentTask
        currentTask = null
        state = State.IDLE
        stepActionDispatched = false
        lastLongPressBounds = null
        usedGestureFallbackForLongPress = false
        triedOverflowMenu = false

        if (taskQueue.isNotEmpty()) {
            handler.postDelayed({ startNextTask() }, 500L)
        } else if (succeeded &&
            completedTask != null &&
            !completedTask.isManualTest &&
            AppPrefs.isReturnHomeAfterDelete(this)
        ) {
            handler.postDelayed({
                performGlobalAction(GLOBAL_ACTION_HOME)
            }, 350L)
        }
    }

    private fun isTargetChatOpen(
        root: AccessibilityNodeInfo,
        aliases: List<String>,
        targetPhoneDigits: String?,
        screenHeight: Int
    ): Boolean {
        val headerLimitY = (screenHeight * 0.22f).toInt()
        val headerTexts = mutableListOf<String>()
        collectHeaderTexts(root, headerLimitY, headerTexts)

        for (header in headerTexts) {
            val normHeader = ContactMatcher.normalizeArabic(header)
            if (aliases.any { alias ->
                    val normAlias = ContactMatcher.normalizeArabic(alias)
                    normAlias.isNotEmpty() && (normHeader == normAlias || normHeader.contains(normAlias))
                }
            ) {
                return true
            }
            if (!targetPhoneDigits.isNullOrBlank() &&
                ContactMatcher.looksLikePhoneNumber(header) &&
                ContactMatcher.phoneNumbersMatch(header, targetPhoneDigits)
            ) {
                return true
            }
        }
        return false
    }

    private fun collectHeaderTexts(
        node: AccessibilityNodeInfo?,
        headerLimitY: Int,
        out: MutableList<String>
    ) {
        if (node == null) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val txt = ContactMatcher.cleanText(node.text?.toString() ?: "")
        if (txt.isNotEmpty() && !bounds.isEmpty && bounds.top >= 0 && bounds.bottom <= headerLimitY) {
            out.add(txt)
        }
        for (i in 0 until node.childCount) {
            collectHeaderTexts(node.getChild(i), headerLimitY, out)
        }
    }

    private fun findLatestMessageNodeByText(
        root: AccessibilityNodeInfo,
        targetText: String,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val normTarget = ContactMatcher.normalizeArabic(targetText)
        if (normTarget.isEmpty()) return null
        val prefixMatch = if (normTarget.length > 25) normTarget.take(25) else normTarget
        val minTopY = (screenHeight * 0.14f).toInt()
        val maxBottomY = (screenHeight * 0.88f).toInt()
        val matches = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()

        collectMatchingMessageNodes(root, normTarget, prefixMatch, minTopY, maxBottomY, matches)
        return matches.maxByOrNull { it.first }?.second
    }

    private fun collectMatchingMessageNodes(
        node: AccessibilityNodeInfo?,
        normTarget: String,
        prefixMatch: String,
        minTopY: Int,
        maxBottomY: Int,
        out: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val className = node.className?.toString() ?: ""
        val isInputField = className.contains("EditText", ignoreCase = true) || node.isEditable

        if (!isInputField) {
            val txt = ContactMatcher.normalizeArabic(node.text?.toString() ?: "")
            if (txt.isNotEmpty() &&
                (txt == normTarget || txt.contains(normTarget) || txt.contains(prefixMatch))
            ) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty && bounds.top >= minTopY && bounds.bottom <= maxBottomY) {
                    out.add(bounds.bottom to node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectMatchingMessageNodes(node.getChild(i), normTarget, prefixMatch, minTopY, maxBottomY, out)
        }
    }

    private fun findLatestMessageBubble(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val minTopY = (screenHeight * 0.14f).toInt()
        val maxBottomY = (screenHeight * 0.88f).toInt()
        val maxBubbleWidth = (screenWidth * 0.92f).toInt()
        val byResourceId = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        val fallbackCandidates = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()

        traverseForBubbles(root, minTopY, maxBottomY, maxBubbleWidth, byResourceId, fallbackCandidates)

        return byResourceId.maxByOrNull { it.first }?.second
            ?: fallbackCandidates.maxByOrNull { it.first }?.second
    }

    private fun traverseForBubbles(
        node: AccessibilityNodeInfo?,
        minTopY: Int,
        maxBottomY: Int,
        maxBubbleWidth: Int,
        byResourceId: MutableList<Pair<Int, AccessibilityNodeInfo>>,
        fallback: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node == null) return
        val className = node.className?.toString() ?: ""
        val isInputField = className.contains("EditText", ignoreCase = true) || node.isEditable
        val viewId = (node.viewIdResourceName ?: "").lowercase()
        val isExcludedId = EXCLUDED_VIEW_ID_HINTS.any { viewId.contains(it) }

        if (!isInputField && !isExcludedId) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val inChatRegion = !bounds.isEmpty &&
                bounds.top >= minTopY &&
                bounds.bottom <= maxBottomY &&
                bounds.width() in 36..maxBubbleWidth

            if (inChatRegion) {
                val isKnownBubbleId = BUBBLE_ID_HINTS.any { viewId.contains(it) }
                if (isKnownBubbleId && bounds.height() > 22) {
                    byResourceId.add(bounds.bottom to node)
                } else if (className.contains("TextView", ignoreCase = true)) {
                    val txt = ContactMatcher.cleanText(node.text?.toString() ?: "")
                    if (txt.isNotEmpty() && !TIMESTAMP_PATTERN.matches(txt) && bounds.height() >= 24) {
                        fallback.add(bounds.bottom to node)
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            traverseForBubbles(
                node.getChild(i),
                minTopY,
                maxBottomY,
                maxBubbleWidth,
                byResourceId,
                fallback
            )
        }
    }

    private fun findNodeByResourceIds(
        node: AccessibilityNodeInfo?,
        idHints: List<String>,
        maxBottomY: Int
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val vid = (node.viewIdResourceName ?: "").lowercase()
        if (vid.isNotEmpty() && idHints.any { vid.endsWith(":id/$it") || vid.contains(it) }) {
            val target = findNearestClickable(node) ?: node
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            if (!bounds.isEmpty && bounds.bottom <= maxBottomY) {
                return target
            }
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByResourceIds(node.getChild(i), idHints, maxBottomY)
            if (found != null) return found
        }
        return null
    }

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

        val normTxt = ContactMatcher.normalizeArabic(node.text?.toString() ?: "")
        val normDesc = ContactMatcher.normalizeArabic(node.contentDescription?.toString() ?: "")
        val normKw = ContactMatcher.normalizeArabic(kw)

        val matched = if (exactOnly) {
            normTxt == normKw || normDesc == normKw
        } else {
            (normTxt.isNotEmpty() && normTxt.contains(normKw)) ||
                (normDesc.isNotEmpty() && normDesc.contains(normKw))
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

    private fun findNearestLongClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= 4) {
            if (current.isLongClickable) return current
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

    override fun onInterrupt() {
        taskQueue.clear()
        finishCurrentTaskAndProceed(succeeded = false)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }
        taskQueue.clear()
        deferredQueue.clear()
        finishCurrentTaskAndProceed(succeeded = false)
        instance = null
        super.onDestroy()
    }
}
