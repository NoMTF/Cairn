package com.cairn.app.root

/**
 * 关闭 Android 12+ 隐私指示器（录音/拍照时屏幕右上角的绿点/橙点）。
 *
 * 通过修改 SystemUI 设置实现：
 * - device_config.put privacy enable_camera_mic_icons false
 */
object RootPrivacyDotHide {

    fun hide() {
        RootDetector.execAsRoot(
            // Android 12+
            "device_config put privacy camera_mic_icons_enabled false",
            "settings put secure camera_mic_indicators_enabled 0",
            // SystemUI 重启使生效
            // 注意：不要重启 SystemUI 太频繁，会让用户察觉
        )
    }

    fun restore() {
        RootDetector.execAsRoot(
            "device_config put privacy camera_mic_icons_enabled true",
            "settings put secure camera_mic_indicators_enabled 1"
        )
    }
}
