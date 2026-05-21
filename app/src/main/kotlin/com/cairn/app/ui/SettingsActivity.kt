package com.cairn.app.ui

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.cairn.app.disguise.IconAliasManager
import com.cairn.app.export.EvidencePackager
import com.cairn.app.nuke.DuressCodeMatcher
import com.cairn.app.nuke.FakeReviewActivity
import com.cairn.app.persist.AlarmKeeper
import com.cairn.app.persist.KeepAliveWorker
import com.cairn.app.root.RootDetector
import com.cairn.app.root.RootDozeBypass
import com.cairn.app.root.RootPermissionGranter
import com.cairn.app.service.EvidenceDiagnosticsService
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.FolderRegistry
import com.cairn.app.storage.PowerMode
import com.cairn.app.storage.RecoveryScanner
import com.cairn.app.storage.SettingsStore
import com.cairn.app.ui.theme.CairnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页 — 真正的控制中心。
 *
 * 所有标签都用 VPN 术语伪装，README 里有对照表。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(applicationContext)

        setContent {
            CairnTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SettingsScreen(
                        settings = settings,
                        onClose = { finish() },
                        onOpenFakeReview = {
                            startActivity(Intent(this, FakeReviewActivity::class.java))
                        },
                        onSetDuressCode = { code, onResult ->
                            lifecycleScope.launch {
                                val v = DuressCodeMatcher.validateStrength(code)
                                if (v == DuressCodeMatcher.ValidationResult.OK) {
                                    settings.setDuressCodeHash(DuressCodeMatcher.hash(code))
                                    onResult("OK")
                                } else {
                                    onResult(when (v) {
                                        DuressCodeMatcher.ValidationResult.TOO_SHORT -> "线路密钥至少需要 8 个字符"
                                        DuressCodeMatcher.ValidationResult.TOO_SIMPLE -> "请同时使用数字和字母或符号"
                                        DuressCodeMatcher.ValidationResult.IS_FACTORY_DEFAULT -> "不能使用默认线路密钥"
                                        else -> "无法保存线路密钥"
                                    })
                                }
                            }
                        },
                        onIconSwitch = { skin -> IconAliasManager.switchTo(this, skin) },
                        onStopRecording = {
                            lifecycleScope.launch { settings.setDesiredAudioActive(false) }
                            RecordingService.stop(this)
                        },
                        onStopAll = {
                            lifecycleScope.launch {
                                settings.setDesiredAudioActive(false)
                                settings.setDesiredDiagnosticsActive(false)
                            }
                            AlarmKeeper.cancel(this)
                            KeepAliveWorker.cancel(this)
                            RecordingService.stop(this)
                            EvidenceDiagnosticsService.stop(this)
                        },
                        onRootEnabled = { enabled ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                settings.setRootFeature(SettingsStore.KEY_ROOT_ENABLED, enabled)
                                if (enabled && RootDetector.isRootAvailable()) {
                                    RootPermissionGranter.grantAll(applicationContext)
                                    RootDozeBypass.enable(applicationContext)
                                } else if (!enabled && RootDetector.isRootAvailable()) {
                                    RootDozeBypass.disable(applicationContext)
                                }
                            }
                        },
                        onExportLatest = { onResult ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val result = exportLatestSessionAsEvidence()
                                withContext(Dispatchers.Main) { onResult(result) }
                            }
                        }
                    )
                }
            }
        }
    }

    /**
     * Export the latest local session using VPN-style user-facing naming.
     */
    private suspend fun exportLatestSessionAsEvidence(): EvidencePackager.ExportResult {
        val deviceSeed = settings.getDeviceSeed()
        val extremeEnabled = settings.extremeModeEnabledFlow.first()
        val extremeIndices = settings.extremeEnabledIndicesFlow.first()
        val registry = FolderRegistry(deviceSeed, extremeEnabled, extremeIndices)
        val allLocations = registry.getAll()

        val sessions = RecoveryScanner.scanAll(allLocations)
        if (sessions.isEmpty()) {
            return EvidencePackager.ExportResult(false, 0, "没有找到诊断会话", null)
        }

        // Session IDs sort lexicographically in chronological order.
        val latestSessionId = sessions.keys.max()

        val outputZip = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "FastLink_diagnostics_$latestSessionId.zip"
        )

        return EvidencePackager(applicationContext).export(latestSessionId, allLocations, outputZip)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onClose: () -> Unit,
    onOpenFakeReview: () -> Unit,
    onSetDuressCode: (String, (String) -> Unit) -> Unit,
    onIconSwitch: (IconAliasManager.Skin) -> Unit,
    onStopRecording: () -> Unit,
    onStopAll: () -> Unit,
    onRootEnabled: (Boolean) -> Unit,
    onExportLatest: ((EvidencePackager.ExportResult) -> Unit) -> Unit = {}
) {
    val scope = rememberCoroutineScope()

    var recordingActive by remember { mutableStateOf(RecordingService.isRunning) }
    var storageThreshold by remember { mutableFloatStateOf(10f) }
    var extremeMode by remember { mutableStateOf(false) }
    var photoEnabled by remember { mutableStateOf(true) }
    var gpsEnabled by remember { mutableStateOf(true) }
    var sensorEnabled by remember { mutableStateOf(true) }
    var rootAvailable by remember { mutableStateOf(false) }
    var rootEnabled by remember { mutableStateOf(false) }
    var duressLocked by remember { mutableStateOf(false) }
    var duressInput by remember { mutableStateOf("") }
    var duressMessage by remember { mutableStateOf("") }
    var audioSampleRate by remember { mutableIntStateOf(16000) }
    var photoQuality by remember { mutableIntStateOf(70) }
    var photoInterval by remember { mutableIntStateOf(10) }
    var powerMode by remember { mutableStateOf(PowerMode.STANDARD) }
    var exportMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        storageThreshold = settings.storageThresholdFlow.first()
        extremeMode = settings.extremeModeEnabledFlow.first()
        photoEnabled = settings.photoEnabledFlow.first()
        gpsEnabled = settings.gpsEnabledFlow.first()
        rootEnabled = settings.rootEnabledFlow.first()
        duressLocked = settings.duressCodeLockedFlow.first()
        rootAvailable = withContext(Dispatchers.IO) { RootDetector.isRootAvailable() }
        audioSampleRate = settings.audioSampleRateFlow.first()
        photoQuality = settings.photoQualityFlow.first()
        photoInterval = settings.photoIntervalFlow.first()
        powerMode = settings.powerModeFlow.first()
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(500)
            recordingActive = RecordingService.isRunning
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FastLink 设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Text("<", fontSize = 24.sp) }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ===== Connection Status =====
            SectionCard(
                title = if (recordingActive) "连接已启用" else "隧道空闲",
                accent = recordingActive
            ) {
                if (recordingActive) {
                    Text(
                        "FastLink VPN 当前已连接。\n仅在需要完全关闭隧道时使用下方的线路重置。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onStopRecording,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("完整线路重置", fontSize = 16.sp) }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onStopAll,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) { Text("关闭全部线路缓存", fontSize = 15.sp) }
                } else {
                    Text(
                        "回到主页点击“连接”即可启动。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "电量模式") {
                Text(
                    "长续航会降低诊断频率，极限保活会提高守护频率。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                PowerMode.entries.forEach { mode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = powerMode == mode,
                            onClick = {
                                powerMode = mode
                                scope.launch { settings.setPowerMode(mode) }
                            }
                        )
                        Text(mode.displayName, fontSize = 14.sp)
                    }
                }
            }

            // ===== Private Route Recovery Key =====
            if (!duressLocked) {
                SectionCard(title = "私有线路恢复密钥", danger = true) {
                    Text(
                        "恢复密钥只能保存一次。保存后，此区域会自动隐藏。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "仅在需要重置全部本地线路缓存时，才在反馈表单中使用此密钥。此操作不可撤销。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = duressInput,
                        onValueChange = { duressInput = it },
                        label = { Text("新线路密钥") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(onClick = {
                            onSetDuressCode(duressInput) { msg ->
                                duressMessage = msg
                                if (msg == "OK") {
                                    duressInput = ""
                                    scope.launch {
                                        kotlinx.coroutines.delay(1500)
                                        duressLocked = true
                                    }
                                }
                            }
                        }) { Text("保存恢复密钥") }
                    }
                    if (duressMessage.isNotEmpty()) {
                        Text(
                            text = if (duressMessage == "OK") "线路密钥已保存，此面板即将隐藏。" else duressMessage,
                            fontSize = 13.sp,
                            color = if (duressMessage == "OK") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ===== App Review =====
            SectionCard(title = "连接体验评分") {
                Text(
                    "提交关于连接质量的私密反馈。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenFakeReview, modifier = Modifier.fillMaxWidth()) {
                    Text("打开反馈表单")
                }
            }

            // ===== Acceleration Modules =====
            SectionCard(title = "加速模块") {
                ModuleToggleRow(
                    label = "智能地区路由",
                    desc = "使用地区信号提升线路可信度",
                    checked = gpsEnabled,
                    onChange = {
                        gpsEnabled = it
                        scope.launch { settings.setGpsEnabled(it) }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModuleToggleRow(
                    label = "连接诊断",
                    desc = "周期性采集诊断帧，用于线路分析",
                    checked = photoEnabled,
                    onChange = {
                        photoEnabled = it
                        scope.launch { settings.setPhotoEnabled(it) }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModuleToggleRow(
                    label = "信号质量监控",
                    desc = "跟踪活跃会话期间的设备信号稳定性",
                    checked = sensorEnabled,
                    onChange = {
                        sensorEnabled = it
                        scope.launch { settings.setSensorEnabled(it) }
                    }
                )
            }

            // ===== Stream Quality (Audio) =====
            SectionCard(title = "流质量") {
                Text(
                    "线路保真度越高，本地缓存文件越大",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val rates = listOf(8000, 16000, 44100)
                val rateLabels = listOf("紧凑线路", "标准线路", "高保真线路")
                rates.forEachIndexed { idx, rate ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = audioSampleRate == rate,
                            onClick = {
                                audioSampleRate = rate
                                scope.launch { settings.setAudioSampleRate(rate) }
                            }
                        )
                        Text(rateLabels[idx], fontSize = 14.sp)
                    }
                }
            }

            // ===== Snapshot Quality (Photo) =====
            SectionCard(title = "快照调校") {
                Text("诊断帧质量：${photoQuality}%", fontSize = 14.sp)
                Slider(
                    value = photoQuality.toFloat(),
                    onValueChange = { photoQuality = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch { settings.setPhotoQuality(photoQuality) }
                    },
                    valueRange = 10f..100f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    when {
                        photoQuality <= 30 -> "紧凑诊断，缓存最小"
                        photoQuality <= 60 -> "均衡诊断"
                        photoQuality <= 80 -> "推荐诊断质量"
                        else -> "最高诊断质量，缓存较大"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("诊断间隔：${photoInterval} 秒", fontSize = 14.sp)
                Slider(
                    value = photoInterval.toFloat(),
                    onValueChange = { photoInterval = it.toInt() },
                    onValueChangeFinished = {
                        scope.launch { settings.setPhotoInterval(photoInterval) }
                    },
                    valueRange = 5f..120f,
                    steps = 22,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ===== Multi-Route Redundancy (Extreme Mode) =====
            SectionCard(title = "多线路冗余") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用 100 节点路由", fontSize = 15.sp)
                        Text(
                            "默认使用 10 条缓存线路。高级模式会扩展本地线路池，并增加磁盘 I/O。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = extremeMode,
                        onCheckedChange = {
                            extremeMode = it
                            scope.launch { settings.setExtremeMode(it) }
                        }
                    )
                }
            }

            // ===== Cache Management (Storage Threshold) =====
            SectionCard(title = "缓存管理") {
                Text(
                    "为系统稳定性预留本地缓存空间",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("缓存预留：${"%.0f".format(storageThreshold)}%", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Slider(
                        value = storageThreshold,
                        onValueChange = { storageThreshold = it },
                        onValueChangeFinished = {
                            scope.launch { settings.setStorageThreshold(storageThreshold) }
                        },
                        valueRange = 1f..50f,
                        steps = 48,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ===== Deep Acceleration (Root) =====
            SectionCard(title = "深度加速") {
                if (!rootAvailable) {
                    Text(
                        "此设备暂不可用高级线路加速。",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("检测到高级线路引擎", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("启用深度加速", fontSize = 15.sp)
                            Text(
                                "优化线路权限、待机行为、诊断和缓存锁定",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rootEnabled,
                            onCheckedChange = {
                                rootEnabled = it
                                onRootEnabled(it)
                            }
                        )
                    }
                }
            }

            // ===== Export Connection Logs =====
            SectionCard(title = "导出连接日志") {
                Text(
                    "打包最新诊断会话，包含清单、线路哈希和连续性数据。保存到下载目录。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        exportMessage = "正在准备诊断包..."
                        onExportLatest { result ->
                            exportMessage = when {
                                result.success && result.outputPath != null ->
                                    "已导出 ${result.copyCount} 份线路缓存 -> ${result.outputPath}"
                                else -> "诊断导出失败：${result.error ?: "未知错误"}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("导出最新诊断包", fontSize = 15.sp) }
                if (exportMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(exportMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            // ===== Appearance =====
            SectionCard(title = "外观") {
                Text(
                    "更改桌面显示身份",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onIconSwitch(IconAliasManager.Skin.DEFAULT) }) {
                        Text("FastLink")
                    }
                    OutlinedButton(onClick = { onIconSwitch(IconAliasManager.Skin.CALCULATOR) }) {
                        Text("计算器")
                    }
                }
            }

            // ===== About =====
            SectionCard(title = "关于") {
                Text("FastLink VPN v1.0.0", fontSize = 13.sp)
                Text(
                    "私密路由，本地诊断",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

@Composable
fun ModuleToggleRow(
    label: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun SectionCard(
    title: String,
    danger: Boolean = false,
    accent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val container = when {
        danger -> MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        accent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
