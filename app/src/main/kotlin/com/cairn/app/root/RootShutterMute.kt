package com.cairn.app.root

/**
 * Root 模式下系统级关闭快门音。
 *
 * 优于 ShutterSilencer（非 root）的 STREAM_SYSTEM 静音技巧 —
 * 这里直接改系统属性，100% 无声。
 */
object RootShutterMute {

    /**
     * 关闭快门音（持久，需录音中调用一次即可）
     */
    fun mute() {
        RootDetector.execAsRoot(
            "setprop audio.camera.shutter.disable 1",
            "setprop ro.camera.sound.forced 0"
        )
    }

    /**
     * 恢复快门音
     */
    fun unmute() {
        RootDetector.execAsRoot(
            "setprop audio.camera.shutter.disable 0"
        )
    }
}
