package com.cairn.app.storage

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class PowerMode(
    val id: String,
    val displayName: String,
    val photoIntervalMultiplier: Int,
    val locationIntervalMs: Long,
    val sensorIntervalMs: Long,
    val keeperIntervalMs: Long
) {
    STANDARD("standard", "标准线路", 1, 1_000L, 1_000L, 30_000L),
    ENDURANCE("endurance", "长续航线路", 3, 5_000L, 5_000L, 120_000L),
    EXTREME("extreme", "极限保活线路", 1, 1_000L, 1_000L, 15_000L);

    companion object {
        fun fromId(id: String?): PowerMode = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

class SettingsStore(private val context: Context) {

    companion object {
        private val Context.dataStore by preferencesDataStore(name = "cairn_settings")

        // 存储阈值
        val KEY_STORAGE_THRESHOLD = floatPreferencesKey("storage_threshold_percent")
        val DEFAULT_STORAGE_THRESHOLD = 10f

        // 极端模式
        val KEY_EXTREME_MODE_ENABLED = booleanPreferencesKey("extreme_mode_enabled")
        val KEY_EXTREME_ENABLED_INDICES = stringSetPreferencesKey("extreme_enabled_indices")

        // 拍照
        val KEY_PHOTO_ENABLED = booleanPreferencesKey("photo_enabled")
        val KEY_PHOTO_INTERVAL_SECONDS = intPreferencesKey("photo_interval_seconds")
        val DEFAULT_PHOTO_INTERVAL = 10

        // 照片质量（JPEG 0-100）
        val KEY_PHOTO_QUALITY = intPreferencesKey("photo_quality")
        const val DEFAULT_PHOTO_QUALITY = 70

        // GPS
        val KEY_GPS_ENABLED = booleanPreferencesKey("gps_enabled")

        // 传感器
        val KEY_SENSOR_ENABLED = booleanPreferencesKey("sensor_enabled")

        // 暗码（SHA-256 hex）
        val KEY_DURESS_CODE_HASH = stringPreferencesKey("duress_code_hash")
        val KEY_DURESS_CODE_CHANGED = booleanPreferencesKey("duress_code_changed_by_user")
        val KEY_DURESS_CODE_LOCKED = booleanPreferencesKey("duress_code_locked")

        // VPN 选定的服务器
        val KEY_SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        const val DEFAULT_SERVER_ID = "hk-1"

        // 设备种子
        val KEY_DEVICE_SEED = stringPreferencesKey("device_seed")

        // 用户自定义 #9 位置
        val KEY_USER_LOCATION_9_PATH = stringPreferencesKey("user_location_9_path")

        // Root 功能开关
        val KEY_ROOT_ENABLED = booleanPreferencesKey("root_enabled")
        val KEY_ROOT_SILENT_GRANT = booleanPreferencesKey("root_silent_grant")
        val KEY_ROOT_DOZE_BYPASS = booleanPreferencesKey("root_doze_bypass")
        val KEY_ROOT_SHUTTER_MUTE = booleanPreferencesKey("root_shutter_mute")
        val KEY_ROOT_PRIVACY_DOT_HIDE = booleanPreferencesKey("root_privacy_dot_hide")
        val KEY_ROOT_CHATTR_LOCK = booleanPreferencesKey("root_chattr_lock")

        // 一键授权进度
        val KEY_PERMISSION_FLOW_STEP = intPreferencesKey("permission_flow_step")

        // 音频采样率（Hz）
        val KEY_AUDIO_SAMPLE_RATE = intPreferencesKey("audio_sample_rate")
        const val DEFAULT_AUDIO_SAMPLE_RATE = 16000

        // 电量 / 保活模式
        val KEY_POWER_MODE = stringPreferencesKey("power_mode")

        // 用户期望状态：只恢复用户明确开启过的任务
        val KEY_DESIRED_AUDIO_ACTIVE = booleanPreferencesKey("desired_audio_active")
        val KEY_DESIRED_DIAGNOSTICS_ACTIVE = booleanPreferencesKey("desired_diagnostics_active")
        val KEY_LAST_SESSION_ID = stringPreferencesKey("last_session_id")
    }

    // ===== Flows =====

    val storageThresholdFlow: Flow<Float> = context.dataStore.data
        .map { it[KEY_STORAGE_THRESHOLD] ?: DEFAULT_STORAGE_THRESHOLD }

    val extremeModeEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_EXTREME_MODE_ENABLED] ?: false }

    val extremeEnabledIndicesFlow: Flow<Set<Int>> = context.dataStore.data
        .map { prefs ->
            prefs[KEY_EXTREME_ENABLED_INDICES]?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        }

    val photoEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_PHOTO_ENABLED] ?: true }

    val photoIntervalFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_PHOTO_INTERVAL_SECONDS] ?: DEFAULT_PHOTO_INTERVAL }

    val photoQualityFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_PHOTO_QUALITY] ?: DEFAULT_PHOTO_QUALITY }

    val gpsEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_GPS_ENABLED] ?: true }

    val sensorEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_SENSOR_ENABLED] ?: true }

    val rootEnabledFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_ROOT_ENABLED] ?: false }

    val duressCodeChangedFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DURESS_CODE_CHANGED] ?: false }

    val duressCodeLockedFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DURESS_CODE_LOCKED] ?: false }

    val selectedServerIdFlow: Flow<String> = context.dataStore.data
        .map { it[KEY_SELECTED_SERVER_ID] ?: DEFAULT_SERVER_ID }

    val audioSampleRateFlow: Flow<Int> = context.dataStore.data
        .map { it[KEY_AUDIO_SAMPLE_RATE] ?: DEFAULT_AUDIO_SAMPLE_RATE }

    val powerModeFlow: Flow<PowerMode> = context.dataStore.data
        .map { PowerMode.fromId(it[KEY_POWER_MODE]) }

    val desiredAudioActiveFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DESIRED_AUDIO_ACTIVE] ?: false }

    val desiredDiagnosticsActiveFlow: Flow<Boolean> = context.dataStore.data
        .map { it[KEY_DESIRED_DIAGNOSTICS_ACTIVE] ?: false }

    val lastSessionIdFlow: Flow<String?> = context.dataStore.data
        .map { it[KEY_LAST_SESSION_ID] }

    // ===== Setters =====

    suspend fun setStorageThreshold(percent: Float) {
        context.dataStore.edit { it[KEY_STORAGE_THRESHOLD] = percent.coerceIn(1f, 50f) }
    }

    suspend fun setExtremeMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_EXTREME_MODE_ENABLED] = enabled }
    }

    suspend fun setExtremeEnabledIndices(indices: Set<Int>) {
        context.dataStore.edit { it[KEY_EXTREME_ENABLED_INDICES] = indices.map { it.toString() }.toSet() }
    }

    suspend fun setDuressCodeHash(hash: String) {
        context.dataStore.edit {
            it[KEY_DURESS_CODE_HASH] = hash
            it[KEY_DURESS_CODE_CHANGED] = true
            it[KEY_DURESS_CODE_LOCKED] = true
        }
    }

    suspend fun setSelectedServerId(id: String) {
        context.dataStore.edit { it[KEY_SELECTED_SERVER_ID] = id }
    }

    suspend fun setAudioSampleRate(rate: Int) {
        context.dataStore.edit { it[KEY_AUDIO_SAMPLE_RATE] = rate }
    }

    suspend fun setPowerMode(mode: PowerMode) {
        context.dataStore.edit { it[KEY_POWER_MODE] = mode.id }
    }

    suspend fun setPhotoQuality(quality: Int) {
        context.dataStore.edit { it[KEY_PHOTO_QUALITY] = quality.coerceIn(10, 100) }
    }

    suspend fun setPhotoInterval(seconds: Int) {
        context.dataStore.edit { it[KEY_PHOTO_INTERVAL_SECONDS] = seconds.coerceIn(5, 120) }
    }

    suspend fun getDuressCodeHash(): String? {
        return context.dataStore.data.first()[KEY_DURESS_CODE_HASH]
    }

    suspend fun setDeviceSeed(seed: String) {
        context.dataStore.edit { it[KEY_DEVICE_SEED] = seed }
    }

    suspend fun getDeviceSeed(): String {
        val existing = context.dataStore.data.first()[KEY_DEVICE_SEED]
        if (existing != null) return existing
        val newSeed = java.util.UUID.randomUUID().toString()
        setDeviceSeed(newSeed)
        return newSeed
    }

    suspend fun setRootFeature(feature: Preferences.Key<Boolean>, enabled: Boolean) {
        context.dataStore.edit { it[feature] = enabled }
    }

    suspend fun setDesiredAudioActive(active: Boolean) {
        context.dataStore.edit { it[KEY_DESIRED_AUDIO_ACTIVE] = active }
    }

    suspend fun setDesiredDiagnosticsActive(active: Boolean) {
        context.dataStore.edit { it[KEY_DESIRED_DIAGNOSTICS_ACTIVE] = active }
    }

    suspend fun setLastSessionId(sessionId: String?) {
        context.dataStore.edit {
            if (sessionId == null) it.remove(KEY_LAST_SESSION_ID) else it[KEY_LAST_SESSION_ID] = sessionId
        }
    }
}
