package com.justme.xtls_core_proxy.privacy

/** A resolved spoof identity: the three OS/version/model header values (locale handled separately). */
data class DeviceSpoof(val os: String, val verOs: String, val model: String)

/**
 * Curated, internally-plausible (version, model) tables plus a deterministic HWID-hash picker.
 * Plausibility is a property of the *pair* — pinning one axis filters the table so the free axis
 * stays plausible. "Auto" (null pin) derives a stable choice from [seed], so a device's reported
 * model never changes across fetches (which would look fake to a panel) yet varies between users.
 */
object SpoofIdentities {

    val ANDROID_VERSIONS = listOf("9", "11", "13", "14", "15", "16")
    val ANDROID_MODEL_FAMILIES = listOf("pixel", "samsung", "xiaomi", "huawei")
    val IOS_VERSIONS = listOf("16.7", "17.6", "18.5", "26.0", "26.1")
    val IOS_MODELS = listOf(
        "iPhone 12", "iPhone 13", "iPhone 14 Pro Max", "iPhone 15 Pro", "iPhone 16 Pro", "iPhone 17"
    )

    private data class AndroidId(val verOs: String, val model: String, val family: String)

    // Each entry is a real, shipping (Android version, marketing/model string) pair.
    private val ANDROID_TABLE = listOf(
        AndroidId("13", "Pixel 7", "pixel"),
        AndroidId("14", "Pixel 8 Pro", "pixel"),
        AndroidId("15", "Pixel 9 Pro", "pixel"),
        AndroidId("16", "Pixel 9", "pixel"),
        AndroidId("13", "SM-S918B", "samsung"),   // Galaxy S23 Ultra
        AndroidId("14", "SM-S928B", "samsung"),   // Galaxy S24 Ultra
        AndroidId("15", "SM-S921B", "samsung"),   // Galaxy S24
        AndroidId("16", "SM-S938B", "samsung"),   // Galaxy S25 Ultra
        AndroidId("13", "2201123G", "xiaomi"),    // Xiaomi 12
        AndroidId("14", "Redmi Note 13", "xiaomi"),
        AndroidId("15", "23127PN0CG", "xiaomi"),  // Xiaomi 14
        AndroidId("11", "ELS-NX9", "huawei"),     // P40 Pro
        AndroidId("9", "VOG-L29", "huawei"),      // P30 Pro
        AndroidId("11", "JAD-LX9", "huawei"),     // P50 Pro
    )

    private data class IosId(val verOs: String, val model: String)

    private val IOS_TABLE = listOf(
        IosId("16.7", "iPhone 12"),
        IosId("17.6", "iPhone 13"),
        IosId("18.5", "iPhone 14 Pro Max"),
        IosId("18.5", "iPhone 15 Pro"),
        IosId("26.0", "iPhone 16 Pro"),
        IosId("26.1", "iPhone 17"),
    )

    private val ANDROID_FAMILY_DEFAULT = mapOf(
        "pixel" to "Pixel 8 Pro",
        "samsung" to "SM-S928B",
        "xiaomi" to "Redmi Note 13",
        "huawei" to "ELS-NX9",
    )

    /** Deterministic 64-bit hash of the HWID string (stable across runs/platforms). */
    fun seed(hwid: String): Long {
        var h = 1125899906842597L // large prime
        for (c in hwid) h = 31 * h + c.code
        return h
    }

    fun resolveAndroid(hwid: String, versionPin: String?, modelFamilyPin: String?): DeviceSpoof {
        var pool = ANDROID_TABLE
        if (versionPin != null) pool = pool.filter { it.verOs == versionPin }
        if (modelFamilyPin != null) pool = pool.filter { it.family == modelFamilyPin }
        val entry = pool.getOrNull(pick(hwid, pool.size)) ?: AndroidId(
            verOs = versionPin ?: "14",
            model = ANDROID_FAMILY_DEFAULT[modelFamilyPin] ?: "Pixel 8 Pro",
            family = modelFamilyPin ?: "pixel",
        )
        return DeviceSpoof(os = "Android", verOs = entry.verOs, model = entry.model)
    }

    fun resolveIphone(hwid: String, versionPin: String?, modelPin: String?): DeviceSpoof {
        var pool = IOS_TABLE
        if (versionPin != null) pool = pool.filter { it.verOs == versionPin }
        if (modelPin != null) pool = pool.filter { it.model == modelPin }
        val entry = pool.getOrNull(pick(hwid, pool.size)) ?: IosId(
            verOs = versionPin ?: "18.5",
            model = modelPin ?: "iPhone 15 Pro",
        )
        return DeviceSpoof(os = "iOS", verOs = entry.verOs, model = entry.model)
    }

    /** Non-negative index into a pool of [size]; 0 when the pool is empty (caller falls back). */
    private fun pick(hwid: String, size: Int): Int =
        if (size <= 0) 0 else Math.floorMod(seed(hwid), size.toLong()).toInt()
}
