package com.cairn.app.root

import android.content.Context

/**
 * 通过 root 绕过 Doze 模式 — 永远不被系统休眠。
 *
 * dumpsys deviceidle whitelist +<pkg> 加入电池白名单
 * am set-standby-bucket <pkg> active 锁定为 active 桶
 */
object RootDozeBypass {

    fun enable(context: Context) {
        val pkg = ShellEscaper.quote(context.packageName)
        RootDetector.execAsRoot(
            "dumpsys deviceidle whitelist +$pkg",
            "am set-standby-bucket $pkg active",
            // 关闭电池优化
            "cmd appops set $pkg RUN_IN_BACKGROUND allow",
            "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"
        )
    }

    fun disable(context: Context) {
        val pkg = ShellEscaper.quote(context.packageName)
        RootDetector.execAsRoot(
            "dumpsys deviceidle whitelist -$pkg",
            "am set-standby-bucket $pkg working_set"
        )
    }
}
