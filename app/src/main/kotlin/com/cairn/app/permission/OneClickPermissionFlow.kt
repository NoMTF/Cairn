package com.cairn.app.permission

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * 一键授权流程 — 7 步状态机。
 *
 * 普通模式：串行请求每个权限，每步显示进度和引导教程。
 * Root 模式：全部静默授予，2 秒完成。
 *
 * 状态持久化到 DataStore，App 中途被杀回来仍能续完成。
 */
class OneClickPermissionFlow(private val context: Context) {

    enum class Step(val index: Int, val label: String) {
        RUNTIME_PERMISSIONS(0, "线路诊断模块"),
        STORAGE_ACCESS(1, "本地缓存访问"),
        ACCESSIBILITY(2, "快捷连接助手"),
        BATTERY_OPTIMIZATION(3, "后台连接保活"),
        NOTIFICATION(4, "连接状态通知"),
        EXACT_ALARM(5, "定时线路巡检"),
        VENDOR_ROM(6, "厂商连接优化");

        val next: Step? get() = entries.getOrNull(index + 1)
    }

    /**
     * 获取当前应该执行的步骤
     */
    fun getCurrentStep(): Step? {
        for (step in Step.entries) {
            if (!isStepCompleted(step)) return step
        }
        return null // 全部完成
    }

    /**
     * 获取总体进度 (0.0 ~ 1.0)
     */
    fun getProgress(): Float {
        val completed = Step.entries.count { isStepCompleted(it) }
        return completed.toFloat() / Step.entries.size
    }

    /**
     * 检查某步是否已完成
     */
    fun isStepCompleted(step: Step): Boolean {
        return when (step) {
            Step.RUNTIME_PERMISSIONS -> hasRuntimePermissions()
            Step.STORAGE_ACCESS -> hasStorageAccess()
            Step.ACCESSIBILITY -> isAccessibilityEnabled()
            Step.BATTERY_OPTIMIZATION -> isBatteryOptimizationIgnored()
            Step.NOTIFICATION -> hasNotificationPermission()
            Step.EXACT_ALARM -> hasExactAlarmPermission()
            Step.VENDOR_ROM -> true // 无法检测，标记为完成
        }
    }

    /**
     * 获取某步的跳转 Intent（用于非 Root 模式）
     */
    fun getStepIntent(step: Step): Intent? {
        return when (step) {
            Step.STORAGE_ACCESS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                } else null
            }
            Step.ACCESSIBILITY -> {
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            Step.BATTERY_OPTIMIZATION -> {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:${context.packageName}".toUri()
                }
            }
            Step.EXACT_ALARM -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                } else null
            }
            Step.VENDOR_ROM -> {
                VendorRomDetector.getAutoStartIntent(context)
            }
            else -> null
        }
    }

    /**
     * 全部完成？
     */
    fun isAllCompleted(): Boolean = getCurrentStep() == null

    // ===== 内部检查方法 =====

    private fun hasRuntimePermissions(): Boolean {
        val perms = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val serviceName = "${context.packageName}/.trigger.VolumeKeyAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.contains(serviceName)
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.canScheduleExactAlarms()
        } else true
    }
}
