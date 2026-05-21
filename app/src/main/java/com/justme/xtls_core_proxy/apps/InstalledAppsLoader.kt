package com.justme.xtls_core_proxy.apps

import android.content.Context
import com.justme.xtls_core_proxy.split.AppEntry
import java.text.Collator
import java.util.LinkedHashMap

object InstalledAppsLoader {
    fun loadInstalled(context: Context): List<AppEntry> {
        val packageManager = context.packageManager
        val installed = packageManager.getInstalledApplications(0)
        val unique = LinkedHashMap<String, AppEntry>()

        for (appInfo in installed) {
            val packageName = appInfo.packageName ?: continue
            val appName = appInfo.loadLabel(packageManager)
                .toString()
                .ifBlank { packageName }
            val icon = runCatching { appInfo.loadIcon(packageManager) }.getOrNull()
            unique[packageName] = AppEntry(packageName = packageName, appName = appName, icon = icon)
        }

        val collator = Collator.getInstance()
        return unique.values.sortedWith(compareBy(collator) { it.appName })
    }
}
