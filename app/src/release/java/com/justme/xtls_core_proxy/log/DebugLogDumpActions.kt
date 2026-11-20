package com.justme.xtls_core_proxy.log

import androidx.compose.runtime.Composable

/**
 * Release stub beside the debug-source-set dump feature. Keeps `LogsActivity`'s call
 * sites compiling in release builds with bodies that render nothing and hold no dump
 * logic — the actions, their labels (debug-source-set resources), and the dump
 * implementation must never be packaged or visible in release.
 */
@Composable
fun DebugLogDumpMenuItems(logs: List<String>, closeMenu: () -> Unit) {
}

@Composable
fun DebugLogDumpStatus() {
}
