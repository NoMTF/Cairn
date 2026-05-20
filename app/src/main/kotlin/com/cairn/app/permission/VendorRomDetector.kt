package com.cairn.app.permission

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 厂商 ROM 检测 + 自启动/保活设置跳转。
 *
 * 支持：MIUI / HyperOS / EMUI / HarmonyOS / ColorOS / OriginOS / Flyme / Samsung OneUI
 */
object VendorRomDetector {

    enum class Vendor(val displayName: String) {
        XIAOMI("小米 MIUI / HyperOS"),
        HUAWEI("华为 EMUI / HarmonyOS"),
        OPPO("OPPO ColorOS"),
        VIVO("vivo OriginOS"),
        MEIZU("魅族 Flyme"),
        SAMSUNG("三星 OneUI"),
        ONEPLUS("一加 OxygenOS"),
        OTHER("其他");
    }

    /**
     * 检测当前设备厂商
     */
    fun detect(): Vendor {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        return when {
            manufacturer.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> Vendor.XIAOMI
            manufacturer.contains("huawei") || brand.contains("honor") -> Vendor.HUAWEI
            manufacturer.contains("oppo") || brand.contains("realme") -> Vendor.OPPO
            manufacturer.contains("vivo") || brand.contains("iqoo") -> Vendor.VIVO
            manufacturer.contains("meizu") -> Vendor.MEIZU
            manufacturer.contains("samsung") -> Vendor.SAMSUNG
            manufacturer.contains("oneplus") -> Vendor.ONEPLUS
            else -> Vendor.OTHER
        }
    }

    /**
     * 获取自启动管理页面的 Intent
     */
    fun getAutoStartIntent(context: Context): Intent? {
        val intents = when (detect()) {
            Vendor.XIAOMI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.powercenter.PowerSettings"
                ))
            )
            Vendor.HUAWEI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                ))
            )
            Vendor.OPPO -> listOf(
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ))
            )
            Vendor.VIVO -> listOf(
                Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                ))
            )
            Vendor.MEIZU -> listOf(
                Intent().setComponent(ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC"
                ))
            )
            Vendor.SAMSUNG -> listOf(
                Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.battery.ui.BatteryActivity"
                ))
            )
            Vendor.ONEPLUS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                ))
            )
            Vendor.OTHER -> emptyList()
        }

        // 返回第一个可 resolve 的 intent
        for (intent in intents) {
            if (context.packageManager.resolveActivity(intent, 0) != null) {
                return intent
            }
        }

        return null
    }

    /**
     * 获取厂商保活引导文案
     */
    fun getGuideText(): String {
        return when (detect()) {
            Vendor.XIAOMI -> "请在「设置 → 应用 → 自启动管理」中允许 Cairn 自启动\n并在「省电策略」中选择「无限制」"
            Vendor.HUAWEI -> "请在「设置 → 应用 → 应用启动管理」中关闭 Cairn 的自动管理\n手动打开「自启动」「关联启动」「后台活动」"
            Vendor.OPPO -> "请在「设置 → 电池 → 应用耗电管理」中设置 Cairn 为「允许后台运行」"
            Vendor.VIVO -> "请在「设置 → 电池 → 后台高耗电」中允许 Cairn"
            Vendor.SAMSUNG -> "请在「设置 → 电池 → 后台使用限制」中将 Cairn 设为「不受限」"
            Vendor.MEIZU -> "请在「安全中心 → 权限管理 → 后台管理」中允许 Cairn"
            Vendor.ONEPLUS -> "请在「设置 → 电池 → 电池优化」中将 Cairn 设为「不优化」"
            Vendor.OTHER -> "请在系统设置中允许 Cairn 后台运行、自启动"
        }
    }
}
