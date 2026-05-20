package com.cairn.app.root

import android.content.Context
import android.util.Log

/**
 * 通过 root 静默授予全部权限。
 *
 * 比普通模式快 60 倍，无任何对话框。
 *
 * 命令：pm grant <pkg> <permission>
 */
object RootPermissionGranter {

    private const val TAG = "RootPermissionGranter"

    private val ALL_PERMISSIONS = listOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.MANAGE_EXTERNAL_STORAGE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.WAKE_LOCK",
        "android.permission.RECEIVE_BOOT_COMPLETED",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.MODIFY_AUDIO_SETTINGS",
    )

    private val APP_OPS = listOf(
        "MANAGE_EXTERNAL_STORAGE",
        "SYSTEM_ALERT_WINDOW",
    )

    /**
     * 一键静默授予所有权限 + appops + 电池白名单
     */
    fun grantAll(context: Context): GrantResult {
        if (!RootDetector.isRootAvailable()) {
            return GrantResult(false, 0, "No root access")
        }

        val pkg = ShellEscaper.quote(context.packageName)
        var granted = 0
        val errors = mutableListOf<String>()

        // 1. 普通权限 pm grant
        for (perm in ALL_PERMISSIONS) {
            val result = RootDetector.execAsRoot("pm grant $pkg ${ShellEscaper.quote(perm)}")
            if (result?.isSuccess == true) {
                granted++
            } else {
                errors.add("$perm: ${result?.err?.joinToString() ?: "unknown"}")
            }
        }

        // 2. AppOps（特殊权限）
        for (op in APP_OPS) {
            RootDetector.execAsRoot("appops set $pkg ${ShellEscaper.quote(op)} allow")
        }

        // 3. 电池白名单
        RootDetector.execAsRoot("dumpsys deviceidle whitelist +$pkg")

        // 4. 锁定为 active 桶
        RootDetector.execAsRoot("am set-standby-bucket $pkg active")

        Log.i(TAG, "Granted $granted / ${ALL_PERMISSIONS.size} permissions")
        return GrantResult(true, granted, errors.joinToString("\n").takeIf { it.isNotEmpty() })
    }

    data class GrantResult(
        val success: Boolean,
        val grantedCount: Int,
        val errors: String?
    )
}
