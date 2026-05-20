package com.cairn.app.storage

/**
 * 文件夹注册表 — 管理 10 默认 + 100 极端候选。
 *
 * 用法：
 * - 普通模式：getActive() 返回默认 10 处
 * - 极端模式：根据用户勾选 + 默认 10 处 = 最多 100 路
 * - 恢复扫描：getAll() 始终返回全部 100 处
 */
class FolderRegistry(
    private val deviceSeed: String,
    private val extremeMode: Boolean,
    private val enabledExtremeIndices: Set<Int> = emptySet(),
    private val userOverrideForIndex9: String? = null
) {

    private val allLocations: List<StorageLocations.LocationSpec> by lazy {
        val base = StorageLocations.generateExtremeCandidates(deviceSeed).toMutableList()
        // 应用用户对 #9 的自定义路径
        if (userOverrideForIndex9 != null && base.size > 9) {
            val original = base[9]
            base[9] = original.copy(dirPath = userOverrideForIndex9)
        }
        base
    }

    /**
     * 当前录音应该写入的位置列表（10 或 多至 100）
     */
    fun getActive(): List<StorageLocations.LocationSpec> {
        if (!extremeMode) {
            return allLocations.take(10)
        }
        // 极端模式：默认 10 处 + 用户勾选的扩展
        val activeSet = mutableSetOf<Int>().apply {
            addAll(0..9)
            addAll(enabledExtremeIndices)
        }
        return allLocations.filter { it.index in activeSet }
    }

    /**
     * 全部 100 处（用于恢复扫描）
     */
    fun getAll(): List<StorageLocations.LocationSpec> = allLocations

    /**
     * 仅默认 10 处
     */
    fun getDefaults(): List<StorageLocations.LocationSpec> = allLocations.take(10)

    /**
     * 仅扩展的 90 处候选（用于极端模式 UI 勾选）
     */
    fun getExtremeCandidates(): List<StorageLocations.LocationSpec> = allLocations.drop(10)
}
