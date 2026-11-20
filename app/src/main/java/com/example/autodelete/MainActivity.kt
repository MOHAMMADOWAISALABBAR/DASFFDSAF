package com.example.autodelete

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_READ_CONTACTS = 1001
    }

    private lateinit var autoDeleteSwitch: SwitchCompat
    private lateinit var deferLockedSwitch: SwitchCompat
    private lateinit var hideNotifLockedSwitch: SwitchCompat
    private lateinit var returnHomeSwitch: SwitchCompat
    private lateinit var targetEdit: EditText
    private lateinit var lanUploadSwitch: SwitchCompat
    private lateinit var lanUrlEdit: EditText
    private lateinit var lanTokenEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        autoDeleteSwitch = findViewById(R.id.autoDeleteSwitch)
        deferLockedSwitch = findViewById(R.id.deferLockedSwitch)
        hideNotifLockedSwitch = findViewById(R.id.hideNotifLockedSwitch)
        returnHomeSwitch = findViewById(R.id.returnHomeSwitch)
        targetEdit = findViewById(R.id.targetEdit)
        lanUploadSwitch = findViewById(R.id.lanUploadSwitch)
        lanUrlEdit = findViewById(R.id.lanUrlEdit)
        lanTokenEdit = findViewById(R.id.lanTokenEdit)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        val saveBtn = findViewById<Button>(R.id.saveBtn)
        val testLanBtn = findViewById<Button>(R.id.testLanBtn)
        val openNlsBtn = findViewById<Button>(R.id.openNlsBtn)
        val openA11yBtn = findViewById<Button>(R.id.openA11yBtn)
        val openOverlayBtn = findViewById<Button>(R.id.openOverlayBtn)
        val openBatteryBtn = findViewById<Button>(R.id.openBatteryBtn)
        val openContactsBtn = findViewById<Button>(R.id.openContactsBtn)
        val testBtn = findViewById<Button>(R.id.testBtn)
        val refreshLogBtn = findViewById<Button>(R.id.refreshLogBtn)
        val clearLogBtn = findViewById<Button>(R.id.clearLogBtn)

        loadSettingsIntoViews()

        autoDeleteSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setAutoDeleteEnabled(this, isChecked)
            updateStatus()
        }

        deferLockedSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setDeferWhenLockedEnabled(this, isChecked)
            updateStatus()
        }

        hideNotifLockedSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setHideNotifWhenLockedEnabled(this, isChecked)
        }

        returnHomeSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setReturnHomeAfterDelete(this, isChecked)
        }

        lanUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            AppPrefs.setLanUploadEnabled(this, isChecked)
            if (isChecked && LanUploader.getPendingCount(this) > 0) {
                LanUploader.scheduleBackgroundRetry(this)
            }
            updateStatus()
        }

        saveBtn.setOnClickListener {
            saveInputFields()
            val count = AppPrefs.getTargetContacts(this).size
            Toast.makeText(this, "تم حفظ الإعدادات ($count مستهدف)", Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        testLanBtn.setOnClickListener {
            saveInputFields()
            testLanBtn.isEnabled = false
            Toast.makeText(this, "جارٍ اختبار الاتصال بالخادم المحلي...", Toast.LENGTH_SHORT).show()
            LanUploader.testAndSync(this) { _, msg ->
                if (!isFinishing && !isDestroyed) {
                    testLanBtn.isEnabled = true
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    updateStatus()
                }
            }
        }

        openNlsBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        openA11yBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        openOverlayBtn.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        openBatteryBtn.setOnClickListener {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "التطبيق مستثنى بالفعل من قيود البطارية", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        openContactsBtn.setOnClickListener {
            if (ContactMatcher.hasContactsPermission(this)) {
                Toast.makeText(this, "إذن جهات الاتصال ممنوح بالفعل", Toast.LENGTH_SHORT).show()
                updateStatus()
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_CONTACTS),
                    REQ_READ_CONTACTS
                )
            }
        }

        testBtn.setOnClickListener {
            AutoDeleteService.requestManualTest(this)
        }

        refreshLogBtn.setOnClickListener {
            updateStatus()
        }

        clearLogBtn.setOnClickListener {
            AppPrefs.clearLogs(this)
            updateStatus()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_CONTACTS) {
            updateStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun loadSettingsIntoViews() {
        autoDeleteSwitch.isChecked = AppPrefs.isAutoDeleteEnabled(this)
        deferLockedSwitch.isChecked = AppPrefs.isDeferWhenLockedEnabled(this)
        hideNotifLockedSwitch.isChecked = AppPrefs.isHideNotifWhenLockedEnabled(this)
        returnHomeSwitch.isChecked = AppPrefs.isReturnHomeAfterDelete(this)
        targetEdit.setText(AppPrefs.getTargetContactsRaw(this))
        lanUploadSwitch.isChecked = AppPrefs.isLanUploadEnabled(this)
        lanUrlEdit.setText(AppPrefs.getLanServerUrl(this))
        lanTokenEdit.setText(AppPrefs.getLanApiToken(this))
    }

    private fun saveInputFields() {
        AppPrefs.setTargetContactsRaw(this, targetEdit.text.toString())
        val normalized = LanUploader.normalizeServerUrl(lanUrlEdit.text.toString())
        AppPrefs.setLanServerUrl(this, normalized)
        lanUrlEdit.setText(normalized)
        AppPrefs.setLanApiToken(this, lanTokenEdit.text.toString())
    }

    private fun updateStatus() {
        val resolvedTargets = ContactMatcher.resolveAllTargets(this)
        val nlsOk = isNotificationListenerEnabled()
        val a11yOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)
        val batteryOk = isBatteryOptimizationIgnored()
        val contactsOk = ContactMatcher.hasContactsPermission(this)
        val pendingUploads = LanUploader.getPendingCount(this)
        val deferredDeletes = AutoDeleteService.getDeferredCount()

        val targetsSummary = if (resolvedTargets.isEmpty()) {
            "غير محددة"
        } else {
            resolvedTargets.joinToString(" | ") { t ->
                if (t.isPhoneNumber) {
                    if (t.displayNames.isNotEmpty()) {
                        "${t.rawRule} ← ${t.displayNames.joinToString("، ")}"
                    } else if (!contactsOk) {
                        "${t.rawRule} (امنح إذن جهات الاتصال للتعرّف على الاسم)"
                    } else {
                        "${t.rawRule} (غير محفوظ بالأسماء — سيُطابق بالرقم)"
                    }
                } else {
                    t.rawRule
                }
            }
        }

        statusText.text = buildString {
            append("المستهدفون: ")
            append(targetsSummary)
            append("\nالحذف التلقائي: ")
            append(if (AppPrefs.isAutoDeleteEnabled(this@MainActivity)) "مفعّل" else "متوقف مؤقتاً")
            append(" (مؤجّل لحين فتح القفل: $deferredDeletes)")
            append("\nخدمة التقاط الإشعارات: ")
            append(if (nlsOk) "مفعّلة" else "غير مفعّلة")
            append("\nخدمة الحذف (Accessibility): ")
            append(if (a11yOk) "مفعّلة" else "غير مفعّلة")
            append("\nالظهور فوق التطبيقات: ")
            append(if (overlayOk) "مسموح" else "غير مسموح")
            append("\nاستثناء البطارية: ")
            append(if (batteryOk) "مفعّل" else "غير مفعّل")
            append("\nإذن جهات الاتصال (للرقم): ")
            append(if (contactsOk) "مسموح" else "غير مسموح")
            append("\nالرفع للشبكة المحلية: ")
            append(if (AppPrefs.isLanUploadEnabled(this@MainActivity)) "مفعّل" else "معطّل")
            append(" (معلّق في الطابور: $pendingUploads)")
        }

        val logs = AppPrefs.getLogs(this)
        logText.text = if (logs.isEmpty()) {
            "لا توجد عمليات مسجّلة بعد."
        } else {
            logs.joinToString("\n")
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabled.split(":").any { it.startsWith("$packageName/") }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val fullComponent = "$packageName/${AutoDeleteService::class.java.name}"
        val shortComponent = "$packageName/.${AutoDeleteService::class.java.simpleName}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any {
            it.equals(fullComponent, ignoreCase = true) ||
                it.equals(shortComponent, ignoreCase = true)
        }
    }
}
