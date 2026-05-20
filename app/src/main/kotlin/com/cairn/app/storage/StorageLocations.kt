package com.cairn.app.storage

import android.os.Environment
import java.io.File
import java.security.MessageDigest

/**
 * 管理 10 处默认 + 100 处极端候选目录。
 *
 * ★ 默认 10 处公开在 docs/STORAGE_LOCATIONS.md，App 内部硬编码。
 * ★ 设置页只暴露 #9 给用户改路径。
 * ★ 恢复时扫全部 100 处。
 */
object StorageLocations {

    private val root = Environment.getExternalStorageDirectory().absolutePath

    // ===== 默认 10 处（docs/STORAGE_LOCATIONS.md 中公开） =====
    data class LocationSpec(
        val index: Int,
        val dirPath: String,
        val filePrefix: String,
        val fileSuffix: String,
        val userConfigurable: Boolean = false
    )

    val DEFAULT_LOCATIONS: List<LocationSpec> = listOf(
        LocationSpec(0, "$root/Download/.cache",                "dl_thumb_",       ".dat"),
        LocationSpec(1, "$root/DCIM/.thumbnails",               ".thumbdata4--",   ""),
        LocationSpec(2, "$root/Pictures/.metadata",             "cover_",          ".bin"),
        LocationSpec(3, "$root/Music/.albumart",                "albumart_",       ".cache"),
        LocationSpec(4, "$root/Movies/.thumbs",                 "video_thumb_",    ".dat"),
        LocationSpec(5, "$root/Documents/.tmp",                 "doc_cache_",      ".tmp"),
        LocationSpec(6, "$root/Alarms/.cache",                  "alarm_log_",      ".bin"),
        LocationSpec(7, "$root/Notifications/.history",         "notif_",          ".bak"),
        LocationSpec(8, "$root/Recordings/.system",             "voicemail_",      ".amr", userConfigurable = true),
        LocationSpec(9, "$root/Android/media/com.android.providers.downloads/.bak", "media_", ".nomedia"),
    )

    // ===== 极端模式 100 处（首次启动时基于设备指纹随机生成） =====

    // 公共目录基底
    private val PUBLIC_DIRS = listOf(
        "Download", "DCIM", "Pictures", "Music", "Movies", "Documents",
        "Recordings", "Podcasts", "Audiobooks", "Notifications", "Alarms", "Ringtones"
    )

    // 隐藏子目录名候选
    private val HIDDEN_SUBDIRS = listOf(
        ".cache", ".tmp", ".thumbs", ".meta", ".system",
        ".backup", ".local", ".config", ".bak", ".history"
    )

    // 伪装包名
    private val FAKE_PACKAGES = listOf(
        "com.tencent.tim.cache",
        "com.android.providers.media.module",
        "com.android.vending.cache",
        "com.google.android.gms.persistent",
        "com.android.systemui.cache",
        "com.miui.gallery.cloud",
        "com.huawei.systemmanager",
        "com.coloros.safecenter.cache",
    )

    // 伪装文件前缀
    private val FILE_PREFIXES = listOf(
        "thumb_", "cache_", ".sys_", "log_", "bak_",
        "data_", "meta_", "idx_", "tmp_", "cfg_"
    )

    // 伪装后缀
    private val FILE_SUFFIXES = listOf(
        ".dat", ".bin", ".cache", ".tmp", ".bak",
        ".log", ".idx", ".sys", "", ".nomedia"
    )

    /**
     * 生成 100 处极端候选目录。
     * 基于设备指纹生成确定性随机路径 — 同一台设备同一版本 App 生成完全一致。
     * 重装后可通过 manifest 或种子重建。
     */
    fun generateExtremeCandidates(deviceSeed: String): List<LocationSpec> {
        val digest = MessageDigest.getInstance("SHA-256")
        val candidates = mutableListOf<LocationSpec>()

        // 前 10 个 = 默认位置
        candidates.addAll(DEFAULT_LOCATIONS)

        // 后 90 个 = 随机生成
        var index = 10
        for (pubDir in PUBLIC_DIRS) {
            for (hidDir in HIDDEN_SUBDIRS) {
                if (index >= 100) break
                // 用 seed + index 做确定性随机
                val hash = digest.digest("$deviceSeed:$index".toByteArray())
                val hexShort = hash.take(4).joinToString("") { "%02x".format(it) }

                val dir = "$root/$pubDir/$hidDir/$hexShort"
                val prefix = FILE_PREFIXES[index % FILE_PREFIXES.size]
                val suffix = FILE_SUFFIXES[index % FILE_SUFFIXES.size]

                candidates.add(LocationSpec(index, dir, prefix, suffix))
                index++
            }
        }

        // 填充 Android/data 路径
        while (index < 100) {
            val hash = digest.digest("$deviceSeed:pkg:$index".toByteArray())
            val hexShort = hash.take(4).joinToString("") { "%02x".format(it) }
            val pkg = FAKE_PACKAGES[index % FAKE_PACKAGES.size]

            candidates.add(
                LocationSpec(
                    index,
                    "$root/Android/data/$pkg/$hexShort",
                    FILE_PREFIXES[index % FILE_PREFIXES.size],
                    FILE_SUFFIXES[index % FILE_SUFFIXES.size]
                )
            )
            index++
        }

        return candidates.take(100)
    }

    /**
     * 确保目录存在 + 放 .nomedia
     */
    fun ensureDirectory(spec: LocationSpec): File {
        val dir = File(spec.dirPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) {
            nomedia.createNewFile()
        }
        return dir
    }

    /**
     * 给录音生成伪装文件名
     */
    fun generateFileName(spec: LocationSpec, sessionId: String): String {
        return "${spec.filePrefix}${sessionId}${spec.fileSuffix}"
    }

    /**
     * 给 GPS sidecar 生成文件名
     */
    fun generateGpsFileName(spec: LocationSpec, sessionId: String): String {
        return "${spec.filePrefix}${sessionId}.gps"
    }

    /**
     * 给照片生成文件名
     */
    fun generatePhotoFileName(spec: LocationSpec, sessionId: String, photoIndex: Int): String {
        return "IMG_${sessionId}_${"%04d".format(photoIndex)}.dat"
    }

    /**
     * 给哈希链 sidecar 生成文件名
     */
    fun generateChainFileName(spec: LocationSpec, sessionId: String): String {
        return "${spec.filePrefix}${sessionId}.chain"
    }
}
