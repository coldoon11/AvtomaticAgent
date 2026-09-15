package com.example.autonomousai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.util.Locale

class AppLauncher(private val context: Context) {
    private val openRegex = Regex("^\\s*(?:открой|запусти|open|launch)\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE)

    fun tryHandle(text: String): String? {
        val match = openRegex.find(text) ?: return null
        val target = match.groupValues[1].trim().trim('"', '\'', '.', ' ')
        return open(target)
    }

    fun open(targetRaw: String): String {
        val target = targetRaw.trim()
        if (target.matches(Regex("https?://\\S+", RegexOption.IGNORE_CASE))) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Открыл ссылку."
        }

        val normalized = normalize(target)
        if (normalized in setOf("настройки", "settings", "настройкителефона")) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return "Открыл настройки."
        }

        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(query, 0)
            .mapNotNull { info ->
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                val pkg = info.activityInfo?.packageName.orEmpty()
                if (label.isBlank() || pkg.isBlank() || pkg == context.packageName) null else Triple(label, pkg, score(normalized, normalize(label)))
            }
            .filter { it.third > 0 }
            .sortedByDescending { it.third }

        val best = apps.firstOrNull()
            ?: return "Не нашёл приложение «$target». Попробуй произнести его название так, как оно подписано на телефоне."

        val launchIntent = pm.getLaunchIntentForPackage(best.second)
            ?: return "Нашёл «${best.first}», но Android не дал запустить его обычным способом."
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return "Открыл «${best.first}»."
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-zа-яё0-9]+"), "")

    private fun score(query: String, label: String): Int {
        if (query.isBlank() || label.isBlank()) return 0
        if (query == label) return 100
        if (label.startsWith(query) || query.startsWith(label)) return 80
        if (label.contains(query) || query.contains(label)) return 60
        return 0
    }
}
