package com.example.autonomousai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

class PhoneCallHelper(private val context: Context) {
    fun looksLikeNumber(value: String): Boolean {
        val digits = value.count { it.isDigit() }
        return digits >= 5
    }

    fun placeCall(targetRaw: String): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "Нужно разрешение на телефонные звонки."
        }

        val number = resolveNumber(targetRaw)
            ?: return "Не нашёл номер для «${targetRaw.trim()}». Разреши доступ к контактам или скажи номер цифрами."

        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Звоню ${targetRaw.trim()}."
    }

    fun resolveNumber(targetRaw: String): String? {
        val target = targetRaw.trim()
        val direct = target.filter { it.isDigit() || it == '+' }
        if (looksLikeNumber(target) && direct.count { it.isDigit() } >= 5) return direct

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%$target%")

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return null
    }
}
