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
import com.cairn.app.root.RootDetector
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.FolderRegistry
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
                                        DuressCodeMatcher.ValidationResult.TOO_SHORT -> "At least 8 characters"
                                        DuressCodeMatcher.ValidationResult.TOO_SIMPLE -> "Needs digits + letters/symbols"
                                        DuressCodeMatcher.ValidationResult.IS_FACTORY_DEFAULT -> "Cannot use factory default"
                                        else -> "Error"
                                    })
                                }
                            }
                        },
                        onIconSwitch = { skin -> IconAliasManager.switchTo(this, skin) },
                        onStopRecording = { RecordingService.stop(this) },
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
     * 把最近一次录音打包成取证 zip。
     */
    private suspend fun exportLatestSessionAsEvidence(): EvidencePackager.ExportResult {
        val deviceSeed = settings.getDeviceSeed()
        val extremeEnabled = settings.extremeModeEnabledFlow.first()
        val extremeIndices = settings.extremeEnabledIndicesFlow.first()
        val registry = FolderRegistry(deviceSeed, extremeEnabled, extremeIndices)
        val allLocations = registry.getAll()

        val sessions = RecoveryScanner.scanAll(allLocations)
        if (sessions.isEmpty()) {
            return EvidencePackager.ExportResult(false, 0, "No recording found", null)
        }

        // sessionId 格式 yyyyMMdd_HHmmss，字典序 == 时序，取最大即最近
        val latestSessionId = sessions.keys.max()

        val outputZip = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "cairn_evidence_$latestSessionId.zip"
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
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) { Text("←", fontSize = 24.sp) }
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
                title = if (recordingActive) "Connection Active" else "Disconnected",
                accent = recordingActive
            ) {
                if (recordingActive) {
                    Text(
                        "FastLink VPN is currently connected.\nTap below to fully disconnect.",
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
                    ) { Text("Full Disconnect", fontSize = 16.sp) }
                } else {
                    Text(
                        "Tap \"Connect\" on the main page to start.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ===== Emergency Security Code =====
            if (!duressLocked) {
                SectionCard(title = "Emergency Security Code", danger = true) {
                    Text(
                        "This can only be set once. After setting, this section is permanently removed.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Enter this code on the Review page to perform a secure data wipe (DoD 3-pass overwrite). Cannot be undone.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = duressInput,
                        onValueChange = { duressInput = it },
                        label = { Text("New code (8+ chars, alphanumeric)") },
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
                        }) { Text("Set (one-time)") }
                    }
                    if (duressMessage.isNotEmpty()) {
                        Text(
                            text = if (duressMessage == "OK") "Set successfully. This section will now disappear." else duressMessage,
                            fontSize = 13.sp,
                            color = if (duressMessage == "OK") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ===== App Review =====
            SectionCard(title = "Rate This App") {
                Text(
                    "Open the rating page.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenFakeReview, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Rating Page")
                }
            }

            // ===== Acceleration Modules =====
            SectionCard(title = "Acceleration Modules") {
                ModuleToggleRow(
                    label = "Smart Location Routing",
                    desc = "Optimize node selection based on real-time positioning",
                    checked = gpsEnabled,
                    onChange = {
                        gpsEnabled = it
                        scope.launch { settings.setRootFeature(SettingsStore.KEY_GPS_ENABLED, it) }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModuleToggleRow(
                    label = "Connection Diagnostics",
                    desc = "Periodic visual snapshots for quality analysis",
                    checked = photoEnabled,
                    onChange = {
                        photoEnabled = it
                        scope.launch { settings.setRootFeature(SettingsStore.KEY_PHOTO_ENABLED, it) }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModuleToggleRow(
                    label = "Signal Quality Monitor",
                    desc = "Track accelerometer / gyroscope / magnetometer",
                    checked = sensorEnabled,
                    onChange = {
                        sensorEnabled = it
                        scope.launch { settings.setRootFeature(SettingsStore.KEY_SENSOR_ENABLED, it) }
                    }
                )
            }

            // ===== Stream Quality (Audio) =====
            SectionCard(title = "Stream Quality") {
                Text(
                    "Higher sample rate = better fidelity, larger cache files",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val rates = listOf(8000, 16000, 44100)
                val rateLabels = listOf("8 kHz (compact)", "16 kHz (standard)", "44.1 kHz (high fidelity)")
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
            SectionCard(title = "Snapshot Settings") {
                Text("Image quality: ${photoQuality}%", fontSize = 14.sp)
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
                        photoQuality <= 30 -> "Low quality — smallest file size"
                        photoQuality <= 60 -> "Medium quality — balanced"
                        photoQuality <= 80 -> "Good quality — recommended"
                        else -> "Maximum quality — large files"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Capture interval: ${photoInterval}s", fontSize = 14.sp)
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
            SectionCard(title = "Multi-Route Redundancy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable 100-node routing", fontSize = 15.sp)
                        Text(
                            "Default: 10 nodes. Enabling writes to 100 distributed cache locations. 10x disk I/O.",
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
            SectionCard(title = "Cache Management") {
                Text(
                    "Auto-pause when available storage drops below threshold",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Threshold: ${"%.0f".format(storageThreshold)}%", fontSize = 14.sp)
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
            SectionCard(title = "Deep Acceleration") {
                if (!rootAvailable) {
                    Text(
                        "Advanced acceleration requires root access (not detected).",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Root access detected", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Deep Acceleration", fontSize = 15.sp)
                            Text(
                                "Auto-grant / bypass Doze / mute shutter / hide indicator / lock config",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rootEnabled,
                            onCheckedChange = {
                                rootEnabled = it
                                scope.launch {
                                    settings.setRootFeature(SettingsStore.KEY_ROOT_ENABLED, it)
                                }
                            }
                        )
                    }
                }
            }

            // ===== Export Connection Logs =====
            SectionCard(title = "Export Connection Logs") {
                Text(
                    "Package the most recent session as a verifiable evidence zip (manifest + per-copy SHA-256 + integrity chain). Saved to Downloads.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        exportMessage = "Exporting..."
                        onExportLatest { result ->
                            exportMessage = when {
                                result.success && result.outputPath != null ->
                                    "Exported ${result.copyCount} copies → ${result.outputPath}"
                                else -> "Export failed: ${result.error ?: "unknown"}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Export Latest Session", fontSize = 15.sp) }
                if (exportMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(exportMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            // ===== Appearance =====
            SectionCard(title = "Appearance") {
                Text(
                    "Change app icon on home screen",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onIconSwitch(IconAliasManager.Skin.DEFAULT) }) {
                        Text("FastLink")
                    }
                    OutlinedButton(onClick = { onIconSwitch(IconAliasManager.Skin.CALCULATOR) }) {
                        Text("Calculator")
                    }
                }
            }

            // ===== About =====
            SectionCard(title = "About") {
                Text("FastLink VPN v1.0.0", fontSize = 13.sp)
                Text(
                    "Secure tunneling with zero data collection",
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
