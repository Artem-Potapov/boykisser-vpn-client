package com.justme.xtls_core_proxy.config

/** Xray's `log.loglevel` ladder. Xray has no `trace`. NONE = logging off (used by the ping probe). */
enum class XrayLogLevel(val wire: String) {
    DEBUG("debug"),
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    NONE("none");

    companion object {
        /** Parse a persisted enum name; unknown/legacy/null falls back to WARNING (today's default). */
        fun fromName(name: String?): XrayLogLevel =
            entries.firstOrNull { it.name == name } ?: WARNING
    }
}

/** Runtime log posture forced onto every config by [ConfigBuilder.buildRuntimeConfig].
 *  errorFilePath == null → no `error` file key emitted (logs go nowhere / stderr). */
data class LogSettings(val level: XrayLogLevel, val errorFilePath: String?)
