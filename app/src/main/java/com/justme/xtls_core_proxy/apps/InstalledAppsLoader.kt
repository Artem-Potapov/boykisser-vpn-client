package com.justme.xtls_core_proxy.apps

import android.content.Context
import android.content.pm.PackageManager
import com.justme.xtls_core_proxy.split.AppEntry
import java.text.Collator
import java.util.LinkedHashMap

/**
 * Enumerates every installed package via PackageManager.getInstalledApplications.
 * Includes launchable apps, background services, system apps — everything visible
 * to the package manager. Visibility on Android 11+ requires QUERY_ALL_PACKAGES
 * (declared in AndroidManifest.xml).
 *
 * Used by Split-Tunnel and Kill-Switch settings to populate their pickers. The
 * caller is responsible for any per-feature filtering (e.g. Kill-Switch could
 * choose to hide non-launchable system apps in the future).
 */
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
            unique[packageName] = AppEntry(packageName = packageName, appName = appName, icon = null)
        }

        val collator = Collator.getInstance()
        return unique.values.sortedWith(compareBy(collator) { it.appName })
    }
}
