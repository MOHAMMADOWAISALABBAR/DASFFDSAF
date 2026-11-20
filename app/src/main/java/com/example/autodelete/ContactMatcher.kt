package com.example.autodelete

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ResolvedTarget(
    val rawRule: String,
    val isPhoneNumber: Boolean,
    val normalizedDigits: String?,
    val displayNames: List<String>
)

data class ContactMatchResult(
    val matchedRule: String,
    val displayLabel: String,
    val normalizedPhone: String?,
    val possibleHeaderAliases: List<String>
)

object ContactMatcher {
    private val BIDI_MARKS = Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val ARABIC_DIACRITICS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")

    fun hasContactsPermission(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            ctx,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun cleanText(input: String): String {
        return input.replace(BIDI_MARKS, "").trim()
    }

    fun normalizeArabic(input: String): String {
        val noBidi = cleanText(input).replace(ARABIC_DIACRITICS, "")
        val sb = StringBuilder(noBidi.length)
        for (ch in noBidi) {
            when (ch) {
                'أ', 'إ', 'آ' -> sb.append('ا')
                'ى' -> sb.append('ي')
                'ة' -> sb.append('ه')
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString().trim()
    }

    fun toAsciiDigits(input: String): String {
        val sb = StringBuilder()
        for (ch in input) {
            when (ch) {
                in '0'..'9' -> sb.append(ch)
                in '٠'..'٩' -> sb.append(('0'.code + (ch.code - '٠'.code)).toChar())
                in '۰'..'۹' -> sb.append(('0'.code + (ch.code - '۰'.code)).toChar())
            }
        }
        return sb.toString()
    }

    /** يعتبر الإدخال رقم هاتف إذا احتوى على 7 أرقام فأكثر ولم يحتوِ على حروف أبجدية */
    fun looksLikePhoneNumber(input: String): Boolean {
        val cleaned = cleanText(input)
        val digits = toAsciiDigits(cleaned)
        if (digits.length < 7) return false
        val hasLetters = cleaned.any { it.isLetter() }
        return !hasLetters
    }

    /** مقارنة رقمين بتطبيع رمز الدولة والصفر المحلي (مقارنة آخر 8-9 أرقام) */
    fun phoneNumbersMatch(a: String, b: String): Boolean {
        val da = toAsciiDigits(a).trimStart('0')
        val db = toAsciiDigits(b).trimStart('0')
        if (da.length < 7 || db.length < 7) return false
        if (da == db) return true
        val compareLen = minOf(da.length, db.length, 9)
        if (compareLen < 7) return false
        return da.takeLast(compareLen) == db.takeLast(compareLen)
    }

    /** استعلام دفتر جهات الاتصال لإيجاد جميع الأسماء المحفوظة لرقم معيّن */
    fun lookupDisplayNamesByPhone(ctx: Context, phoneInput: String): List<String> {
        if (!hasContactsPermission(ctx)) return emptyList()
        val results = LinkedHashSet<String>()
        val trimmed = cleanText(phoneInput)
        val digits = toAsciiDigits(trimmed)
        if (digits.length < 7) return emptyList()

        // 1. استعلام سريع عبر PhoneLookup
        try {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(trimmed)
            )
            ctx.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                while (idx >= 0 && cursor.moveToNext()) {
                    val name = cleanText(cursor.getString(idx) ?: "")
                    if (name.isNotEmpty()) results.add(name)
                }
            }
        } catch (_: Exception) {
        }

        // 2. مسح احتياطي على جدول الأرقام لمعالجة فروق رمز الدولة مثل +963 مقابل 09
        if (results.isEmpty()) {
            try {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    ),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (nameIdx >= 0 && numIdx >= 0 && cursor.moveToNext()) {
                        val number = cursor.getString(numIdx) ?: continue
                        if (phoneNumbersMatch(digits, number)) {
                            val name = cleanText(cursor.getString(nameIdx) ?: "")
                            if (name.isNotEmpty()) results.add(name)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        return results.toList()
    }

    /** استعلام جميع الأرقام المرتبطة بعنوان URI لجهة اتصال (إن أرفقه واتساب في الإشعار) */
    fun lookupPhonesByContactUri(ctx: Context, uriString: String): List<String> {
        if (!hasContactsPermission(ctx)) return emptyList()
        val phones = LinkedHashSet<String>()
        if (uriString.startsWith("tel:", ignoreCase = true)) {
            val d = toAsciiDigits(uriString.removePrefix("tel:"))
            if (d.length >= 7) phones.add(d)
            return phones.toList()
        }
        if (!uriString.startsWith("content://")) return emptyList()

        try {
            val contactUri = ContactsContract.Contacts.lookupContact(
                ctx.contentResolver,
                Uri.parse(uriString)
            ) ?: Uri.parse(uriString)

            var contactId: String? = null
            ctx.contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    contactId = c.getString(0)
                }
            }

            if (!contactId.isNullOrBlank()) {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                    arrayOf(contactId),
                    null
                )?.use { pc ->
                    while (pc.moveToNext()) {
                        val num = toAsciiDigits(pc.getString(0) ?: "")
                        if (num.length >= 7) phones.add(num)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return phones.toList()
    }

    /** تحويل جميع القواعد التي أدخلها المستخدم (أرقام أو أسماء) لعرضها في الواجهة أو مطابقتها */
    fun resolveAllTargets(ctx: Context): List<ResolvedTarget> {
        val rawTargets = AppPrefs.getTargetContacts(ctx)
        return rawTargets.map { rule ->
            if (looksLikePhoneNumber(rule)) {
                val digits = toAsciiDigits(rule)
                val names = lookupDisplayNamesByPhone(ctx, rule)
                ResolvedTarget(
                    rawRule = rule,
                    isPhoneNumber = true,
                    normalizedDigits = digits,
                    displayNames = names
                )
            } else {
                ResolvedTarget(
                    rawRule = rule,
                    isPhoneNumber = false,
                    normalizedDigits = null,
                    displayNames = listOf(cleanText(rule))
                )
            }
        }
    }

    /** مطابقة إشعار واتساب مع قائمة الأرقام أو الأسماء المستهدفة */
    fun matchNotification(
        ctx: Context,
        cleanTitle: String,
        personName: String?,
        personUris: List<String>
    ): ContactMatchResult? {
        val targets = resolveAllTargets(ctx)
        if (targets.isEmpty()) return null

        val notificationPhones = LinkedHashSet<String>()
        if (looksLikePhoneNumber(cleanTitle)) {
            notificationPhones.add(toAsciiDigits(cleanTitle))
        }
        if (!personName.isNullOrBlank() && looksLikePhoneNumber(personName)) {
            notificationPhones.add(toAsciiDigits(personName))
        }
        for (uri in personUris) {
            notificationPhones.addAll(lookupPhonesByContactUri(ctx, uri))
        }

        for (target in targets) {
            if (target.isPhoneNumber && target.normalizedDigits != null) {
                // أ) مطابقة مباشرة عبر الرقم (من عنوان الإشعار أو من Contact URI المرفق من واتساب)
                val directPhoneMatched = notificationPhones.any {
                    phoneNumbersMatch(it, target.normalizedDigits)
                }

                // ب) مطابقة عبر الاسم المحفوظ لهذا الرقم في دفتر جهات الاتصال
                val matchedSavedName = target.displayNames.firstOrNull { savedName ->
                    isMatchingTitle(cleanTitle, savedName) ||
                        (!personName.isNullOrBlank() && isMatchingTitle(personName, savedName))
                }

                if (directPhoneMatched || matchedSavedName != null) {
                    val aliases = LinkedHashSet<String>()
                    aliases.add(cleanTitle)
                    aliases.add(target.rawRule)
                    aliases.add(target.normalizedDigits)
                    aliases.addAll(target.displayNames)
                    if (!personName.isNullOrBlank()) aliases.add(personName)

                    val label = matchedSavedName
                        ?: target.displayNames.firstOrNull()
                        ?: target.rawRule

                    return ContactMatchResult(
                        matchedRule = target.rawRule,
                        displayLabel = label,
                        normalizedPhone = target.normalizedDigits,
                        possibleHeaderAliases = aliases.filter { it.isNotBlank() }
                    )
                }
            } else {
                // قاعدة بالاسم التقليدي
                val targetName = cleanText(target.rawRule)
                if (isMatchingTitle(cleanTitle, targetName) ||
                    (!personName.isNullOrBlank() && isMatchingTitle(personName, targetName))
                ) {
                    val aliases = LinkedHashSet<String>()
                    aliases.add(targetName)
                    aliases.add(cleanTitle)
                    if (!personName.isNullOrBlank()) aliases.add(personName)
                    return ContactMatchResult(
                        matchedRule = target.rawRule,
                        displayLabel = targetName,
                        normalizedPhone = notificationPhones.firstOrNull(),
                        possibleHeaderAliases = aliases.filter { it.isNotBlank() }
                    )
                }
            }
        }
        return null
    }

    fun isMatchingTitle(title: String, target: String): Boolean {
        val normTitle = normalizeArabic(title)
        val normTarget = normalizeArabic(target)
        if (normTarget.isEmpty() || normTitle.isEmpty()) return false
        if (normTitle == normTarget) return true
        if (normTitle.startsWith("$normTarget (")) return true
        if (normTitle.startsWith("$normTarget:")) return true
        if (normTitle.startsWith("$normTarget :")) return true
        return false
    }
}
