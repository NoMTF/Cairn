package com.cairn.app.disguise

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标皮肤切换。
 *
 * 通过 activity-alias + 启用/禁用切换：
 * - 默认：FastLink VPN 图标
 * - 极端皮肤：计算器
 *
 * 切换后桌面 launcher 会在几秒内刷新显示新图标。
 * 注意：同一时间只能有一个 alias 启用，否则会显示多个图标。
 */
object IconAliasManager {

    enum class Skin(val componentName: String, val displayName: String) {
        DEFAULT("com.cairn.app.ui.MainActivity", "FastLink"),
        CALCULATOR("com.cairn.app.disguise.CalculatorAlias", "计算器");
    }

    /**
     * 切换到指定皮肤
     */
    fun switchTo(context: Context, skin: Skin) {
        val pm = context.packageManager
        val pkg = context.packageName

        for (s in Skin.entries) {
            val component = ComponentName(pkg, s.componentName)
            val state = if (s == skin) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    fun getCurrentSkin(context: Context): Skin {
        val pm = context.packageManager
        val pkg = context.packageName

        for (s in Skin.entries) {
            val component = ComponentName(pkg, s.componentName)
            val state = pm.getComponentEnabledSetting(component)
            if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return s
            }
        }
        return Skin.DEFAULT
    }
}
