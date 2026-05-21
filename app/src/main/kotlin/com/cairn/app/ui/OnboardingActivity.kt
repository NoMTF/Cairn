package com.cairn.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.cairn.app.permission.OneClickPermissionFlow
import com.cairn.app.permission.VendorRomDetector
import com.cairn.app.storage.PowerMode
import com.cairn.app.storage.SettingsStore
import com.cairn.app.ui.theme.CairnTheme
import kotlinx.coroutines.launch

/**
 * 首次线路初始化。
 *
 * 先让用户选择运行方案、耗电策略和诊断模块，再进入权限授权清单。
 * 全部文案保持 VPN 伪装，实际含义在 README 中说明。
 */
class OnboardingActivity : ComponentActivity() {

    private lateinit var flow: OneClickPermissionFlow
    private lateinit var settings: SettingsStore

    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { recomputeState() }

    private var stateVersion by mutableIntStateOf(0)
    private fun recomputeState() { stateVersion++ }

    override fun onResume() {
        super.onResume()
        recomputeState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        flow = OneClickPermissionFlow(this)
        settings = SettingsStore(applicationContext)

        setContent {
            CairnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OnboardingScreen(
                        refreshToken = stateVersion,
                        flow = flow,
                        vendor = VendorRomDetector.detect(),
                        vendorGuide = VendorRomDetector.getGuideText(),
                        onSaveSetup = { config ->
                            lifecycleScope.launch {
                                settings.saveInitialSetup(
                                    powerMode = config.powerMode,
                                    audioSampleRate = config.audioSampleRate,
                                    gpsEnabled = config.gpsEnabled,
                                    photoEnabled = config.photoEnabled,
                                    sensorEnabled = config.sensorEnabled,
                                    photoIntervalSeconds = config.photoIntervalSeconds,
                                    photoQuality = config.photoQuality,
                                    extremeModeEnabled = config.extremeModeEnabled
                                )
                                recomputeState()
                            }
                        },
                        onRequestRuntime = {
                            runtimePermLauncher.launch(arrayOf(
                                android.Manifest.permission.RECORD_AUDIO,
                                android.Manifest.permission.CAMERA,
                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                            ).let {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    it + android.Manifest.permission.POST_NOTIFICATIONS
                                } else it
                            })
                        },
                        onLaunchIntent = { intent ->
                            try { startActivity(intent) } catch (_: Exception) {}
                        },
                        onFinish = {
                            lifecycleScope.launch {
                                settings.setInitialSetupDone(true)
                                startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }
}

data class InitialSetupConfig(
    val powerMode: PowerMode,
    val audioSampleRate: Int,
    val gpsEnabled: Boolean,
    val photoEnabled: Boolean,
    val sensorEnabled: Boolean,
    val photoIntervalSeconds: Int,
    val photoQuality: Int,
    val extremeModeEnabled: Boolean
)

private enum class SetupStage {
    CONFIG,
    PERMISSIONS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    refreshToken: Int,
    flow: OneClickPermissionFlow,
    vendor: VendorRomDetector.Vendor,
    vendorGuide: String,
    onSaveSetup: (InitialSetupConfig) -> Unit,
    onRequestRuntime: () -> Unit,
    onLaunchIntent: (Intent) -> Unit,
    onFinish: () -> Unit
) {
    var stage by remember { mutableStateOf(SetupStage.CONFIG) }
    var powerMode by remember { mutableStateOf(PowerMode.STANDARD) }
    var audioSampleRate by remember { mutableIntStateOf(SettingsStore.DEFAULT_AUDIO_SAMPLE_RATE) }
    var gpsEnabled by remember { mutableStateOf(true) }
    var photoEnabled by remember { mutableStateOf(true) }
    var sensorEnabled by remember { mutableStateOf(true) }
    var photoInterval by remember { mutableIntStateOf(SettingsStore.DEFAULT_PHOTO_INTERVAL) }
    var photoQuality by remember { mutableIntStateOf(SettingsStore.DEFAULT_PHOTO_QUALITY) }
    var extremeMode by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (stage == SetupStage.CONFIG) "线路初始化" else "系统通道授权",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { padding ->
        if (stage == SetupStage.CONFIG) {
            InitialConfigScreen(
                modifier = Modifier.padding(padding),
                powerMode = powerMode,
                onPowerModeChange = { mode ->
                    powerMode = mode
                    when (mode) {
                        PowerMode.STANDARD -> {
                            photoInterval = 10
                            photoQuality = 70
                            extremeMode = false
                        }
                        PowerMode.ENDURANCE -> {
                            photoInterval = 30
                            photoQuality = 55
                            extremeMode = false
                        }
                        PowerMode.EXTREME -> {
                            photoInterval = 10
                            photoQuality = 70
                            extremeMode = true
                        }
                    }
                },
                audioSampleRate = audioSampleRate,
                onAudioSampleRateChange = { audioSampleRate = it },
                gpsEnabled = gpsEnabled,
                onGpsEnabledChange = { gpsEnabled = it },
                photoEnabled = photoEnabled,
                onPhotoEnabledChange = { photoEnabled = it },
                sensorEnabled = sensorEnabled,
                onSensorEnabledChange = { sensorEnabled = it },
                photoInterval = photoInterval,
                onPhotoIntervalChange = { photoInterval = it },
                photoQuality = photoQuality,
                onPhotoQualityChange = { photoQuality = it },
                extremeMode = extremeMode,
                onExtremeModeChange = { extremeMode = it },
                onContinue = {
                    onSaveSetup(
                        InitialSetupConfig(
                            powerMode = powerMode,
                            audioSampleRate = audioSampleRate,
                            gpsEnabled = gpsEnabled,
                            photoEnabled = photoEnabled,
                            sensorEnabled = sensorEnabled,
                            photoIntervalSeconds = photoInterval,
                            photoQuality = photoQuality,
                            extremeModeEnabled = extremeMode
                        )
                    )
                    stage = SetupStage.PERMISSIONS
                }
            )
        } else {
            PermissionSetupScreen(
                refreshToken = refreshToken,
                modifier = Modifier.padding(padding),
                flow = flow,
                vendor = vendor,
                vendorGuide = vendorGuide,
                onRequestRuntime = onRequestRuntime,
                onLaunchIntent = onLaunchIntent,
                onBackToConfig = { stage = SetupStage.CONFIG },
                onFinish = onFinish
            )
        }
    }
}

@Composable
private fun InitialConfigScreen(
    modifier: Modifier,
    powerMode: PowerMode,
    onPowerModeChange: (PowerMode) -> Unit,
    audioSampleRate: Int,
    onAudioSampleRateChange: (Int) -> Unit,
    gpsEnabled: Boolean,
    onGpsEnabledChange: (Boolean) -> Unit,
    photoEnabled: Boolean,
    onPhotoEnabledChange: (Boolean) -> Unit,
    sensorEnabled: Boolean,
    onSensorEnabledChange: (Boolean) -> Unit,
    photoInterval: Int,
    onPhotoIntervalChange: (Int) -> Unit,
    photoQuality: Int,
    onPhotoQualityChange: (Int) -> Unit,
    extremeMode: Boolean,
    onExtremeModeChange: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "选择一套 FastLink 线路方案。之后仍可在设置里微调。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OnboardingCard(title = "运行模式") {
            PowerMode.entries.forEach { mode ->
                SelectableRadioRow(
                    selected = powerMode == mode,
                    title = mode.displayName,
                    desc = when (mode) {
                        PowerMode.STANDARD -> "均衡诊断质量、续航和恢复频率"
                        PowerMode.ENDURANCE -> "降低地区路由和诊断帧频率，适合长时间连接"
                        PowerMode.EXTREME -> "提高巡检频率并默认打开多线路冗余"
                    },
                    onClick = { onPowerModeChange(mode) }
                )
            }
        }

        OnboardingCard(title = "流质量") {
            listOf(
                8000 to "紧凑线路",
                16000 to "标准线路",
                44100 to "高保真线路"
            ).forEach { (rate, label) ->
                SelectableRadioRow(
                    selected = audioSampleRate == rate,
                    title = label,
                    desc = when (rate) {
                        8000 -> "缓存最小，适合超长连接"
                        16000 -> "推荐，清晰度和体积均衡"
                        else -> "体积更大，适合短时间高保真记录"
                    },
                    onClick = { onAudioSampleRateChange(rate) }
                )
            }
        }

        OnboardingCard(title = "加速模块") {
            ModuleToggleRow(
                label = "智能地区路由",
                desc = "记录地区信号与精度状态",
                checked = gpsEnabled,
                onChange = onGpsEnabledChange
            )
            Spacer(modifier = Modifier.height(12.dp))
            ModuleToggleRow(
                label = "连接诊断",
                desc = "周期性保存诊断帧",
                checked = photoEnabled,
                onChange = onPhotoEnabledChange
            )
            Spacer(modifier = Modifier.height(12.dp))
            ModuleToggleRow(
                label = "信号质量监控",
                desc = "记录设备运动与姿态变化",
                checked = sensorEnabled,
                onChange = onSensorEnabledChange
            )
        }

        OnboardingCard(title = "快照调校") {
            Text("诊断间隔：${photoInterval} 秒", fontSize = 14.sp)
            Slider(
                value = photoInterval.toFloat(),
                onValueChange = { onPhotoIntervalChange(it.toInt()) },
                valueRange = 5f..120f,
                steps = 22,
                enabled = photoEnabled,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("诊断质量：${photoQuality}%", fontSize = 14.sp)
            Slider(
                value = photoQuality.toFloat(),
                onValueChange = { onPhotoQualityChange(it.toInt()) },
                valueRange = 10f..100f,
                steps = 8,
                enabled = photoEnabled,
                modifier = Modifier.fillMaxWidth()
            )
        }

        OnboardingCard(title = "多线路冗余") {
            ModuleToggleRow(
                label = "启用 100 节点路由",
                desc = "增加本地副本候选位置，缓存和耗电也会增加",
                checked = extremeMode,
                onChange = onExtremeModeChange
            )
        }

        Button(
            onClick = onContinue,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("保存线路方案并继续授权", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionSetupScreen(
    refreshToken: Int,
    modifier: Modifier,
    flow: OneClickPermissionFlow,
    vendor: VendorRomDetector.Vendor,
    vendorGuide: String,
    onRequestRuntime: () -> Unit,
    onLaunchIntent: (Intent) -> Unit,
    onBackToConfig: () -> Unit,
    onFinish: () -> Unit
) {
    val steps = OneClickPermissionFlow.Step.entries
    val progress = remember(refreshToken) { flow.getProgress() }
    val allDone = remember(refreshToken) { flow.isAllCompleted() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "启用这些系统通道后，线路在极端情况下更稳定。无法立即完成的项目可以稍后回来补齐。",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
        Text(
            text = "${(progress * steps.size).toInt()} / ${steps.size}",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        steps.forEach { step ->
            val isCompleted = remember(refreshToken, step) { flow.isStepCompleted(step) }
            StepRow(
                step = step,
                isCompleted = isCompleted,
                onClick = {
                    when (step) {
                        OneClickPermissionFlow.Step.RUNTIME_PERMISSIONS -> onRequestRuntime()
                        else -> flow.getStepIntent(step)?.let(onLaunchIntent)
                    }
                }
            )
        }

        if (vendor != VendorRomDetector.Vendor.OTHER) {
            OnboardingCard(title = "厂商连接优化") {
                Text(
                    text = "检测到：${vendor.displayName}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = vendorGuide,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedButton(
            onClick = onBackToConfig,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Text("返回修改线路方案")
        }

        Button(
            onClick = onFinish,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(if (allDone) "完成 · 开始连接" else "稍后完成 · 进入 FastLink", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun OnboardingCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun SelectableRadioRow(
    selected: Boolean,
    title: String,
    desc: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StepRow(
    step: OneClickPermissionFlow.Step,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = if (isCompleted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
        } else CardDefaults.cardColors()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = if (isCompleted) "✓" else "${step.index + 1}",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isCompleted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
            Text(
                text = step.label,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            if (!isCompleted) {
                TextButton(onClick = onClick) { Text("启用") }
            }
        }
    }
}
