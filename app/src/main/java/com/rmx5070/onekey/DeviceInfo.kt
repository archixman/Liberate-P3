package com.rmx5070.onekey

import android.os.Build

/** Static device facts, read without any privileged backend. */
object DeviceInfo {

    /** Kernel release this build's exploit constants were compiled against. */
    const val EXPECTED_KERNEL = "6.1.157-android14-11-o-g00d81d97d759"

    /** KernelSU KMI string accepted by ksud's embedded module set. */
    const val KMI = "android14-6.1"

    /** KernelSU manager package installed on this device. */
    const val MANAGER_PKG = "me.weishu.kernelsu"

    fun getSystemProperty(key: String): String = try {
        val props = Class.forName("android.os.SystemProperties")
        props.getMethod("get", String::class.java).invoke(null, key) as? String ?: ""
    } catch (_: Throwable) {
        ""
    }

    fun kernelRelease(): String = runCatching { android.system.Os.uname().release }.getOrDefault("unknown")

    /** Boot identifier; lets the UI remember "this boot is dirty" across relaunches. */
    fun bootId(): String = runCatching {
        java.io.File("/proc/sys/kernel/random/boot_id").readText().trim()
    }.getOrDefault("")

    fun model(): String {
        val candidates = listOf(
            "ro.vendor.oplus.market.name",
            "ro.product.vendor.model",
            "ro.product.odm.model",
            "ro.product.model",
            "ro.product.device",
            "ro.product.vendor.device",
            "ro.build.product",
        )
        return candidates.asSequence()
            .map { getSystemProperty(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.equals("unknown", true) && it != "null" }
            ?: Build.MODEL
    }

    fun allModelHints(): String {
        val keys = listOf(
            "ro.vendor.oplus.market.name",
            "ro.product.vendor.model",
            "ro.product.model",
            "ro.product.device",
            "ro.product.vendor.device",
            "ro.build.product",
            "ro.build.display.id",
        )
        return keys.joinToString(" | ") { "$it=${getSystemProperty(it)}" }
    }

    /**
     * True only when every static identifier points at a Realme P3 (RMX5070 /
     * RE608). The exploit's link-time constants are image specific, so this app
     * intentionally refuses to run on anything else.
     */
    fun isTargetDevice(): Boolean {
        val propKeys = listOf(
            "ro.vendor.oplus.market.name",
            "ro.product.vendor.model",
            "ro.product.odm.model",
            "ro.product.model",
            "ro.product.device",
            "ro.product.vendor.device",
            "ro.product.name",
            "ro.build.product",
            "ro.build.display.id",
            "ro.boot.product.hardware.sku",
            "ro.boot.hardware.sku",
        )
        val hay = buildString {
            append(propKeys.joinToString(" ") { getSystemProperty(it) })
            append(' ')
            append(
                listOf(
                    Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.BOARD,
                    Build.HARDWARE, Build.MANUFACTURER, Build.BRAND,
                ).joinToString(" ")
            )
        }.lowercase()
        return hay.contains("rmx5070") || hay.contains("re608")
    }

    fun kernelMatches(): Boolean = kernelRelease().contains(EXPECTED_KERNEL)

    fun androidRelease(): String = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun fingerprint(): String = Build.FINGERPRINT ?: "unknown"

    fun isManagerInstalled(context: android.content.Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(MANAGER_PKG, 0) }.isSuccess
}
