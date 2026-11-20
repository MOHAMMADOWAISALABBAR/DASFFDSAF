package com.example.autodelete

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var targetEdit: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        targetEdit = findViewById(R.id.targetEdit)
        statusText = findViewById(R.id.statusText)
        val saveBtn = findViewById<Button>(R.id.saveBtn)
        val openNlsBtn = findViewById<Button>(R.id.openNlsBtn)
        val openA11yBtn = findViewById<Button>(R.id.openA11yBtn)
        val openOverlayBtn = findViewById<Button>(R.id.openOverlayBtn)
        val testBtn = findViewById<Button>(R.id.testBtn)

        val prefs = getSharedPreferences("autodelete_prefs", MODE_PRIVATE)
        targetEdit.setText(prefs.getString("target_contact", "") ?: "")

        saveBtn.setOnClickListener {
            val name = targetEdit.text.toString().trim()
            prefs.edit().putString("target_contact", name).apply()
            Toast.makeText(this, "تم حفظ الجهة: $name", Toast.LENGTH_SHORT).show()
            updateStatus()
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

        testBtn.setOnClickListener {
            AutoDeleteService.requestManualTest(this)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("autodelete_prefs", MODE_PRIVATE)
        val savedTarget = prefs.getString("target_contact", "") ?: ""
        val nlsOk = isNotificationListenerEnabled()
        val a11yOk = isAccessibilityEnabled()
        val overlayOk = Settings.canDrawOverlays(this)

        statusText.text = buildString {
            append("جهة الاتصال المستهدفة: ")
            append(if (savedTarget.isNotBlank()) savedTarget else "غير محددة")
            append("\n")
            append("خدمة التقاط الإشعارات: ")
            append(if (nlsOk) "مفعّلة" else "غير مفعّلة")
            append("\n")
            append("خدمة الحذف (Accessibility): ")
            append(if (a11yOk) "مفعّلة" else "غير مفعّلة")
            append("\n")
            append("الظهور فوق التطبيقات: ")
            append(if (overlayOk) "مسموح" else "غير مسموح")
        }
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
