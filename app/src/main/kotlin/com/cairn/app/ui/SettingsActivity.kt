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
                                        DuressCodeMatcher.ValidationResult.TOO_SHORT -> "Route key must be at least 8 characters"
                                        DuressCodeMatcher.ValidationResult.TOO_SIMPLE -> "Use digits plus letters or symbols"
                                        DuressCodeMatcher.ValidationResult.IS_FACTORY_DEFAULT -> "Default route key is not allowed"
                                        else -> "Unable to save route key"
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
            return EvidencePackager.ExportResult(false, 0, "No diagnostic session found", null)
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
                title = { Text("FastLink Settings", fontWeight = FontWeight.SemiBold) },
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
                title = if (recordingActive) "Connection Active" else "Tunnel Idle",
                accent = recordingActive
            ) {
                if (recordingActive) {
                    Text(
                        "FastLink VPN is currently connected.\nUse the route reset below only when you want the tunnel fully closed.",
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
                    ) { Text("Full Route Reset", fontSize = 16.sp) }
                } else {
                    Text(
                        "Tap \"Connect\" on the main page to start.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ===== Private Route Recovery Key =====
            if (!duressLocked) {
                SectionCard(title = "Private Route Recovery Key", danger = true) {
                    Text(
                        "This recovery key can only be stored once. After saving, this section is hidden.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Use this key only in the feedback form when you need to reset all local route cache. This cannot be undone.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = duressInput,
                        onValueChange = { duressInput = it },
                        label = { Text("New route key") },
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
                        }) { Text("Store Recovery Key") }
                    }
                    if (duressMessage.isNotEmpty()) {
                        Text(
                            text = if (duressMessage == "OK") "Route key saved. This panel will now be hidden." else duressMessage,
                            fontSize = 13.sp,
                            color = if (duressMessage == "OK") MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ===== App Review =====
            SectionCard(title = "Rate Connection Experience") {
                Text(
                    "Send private feedback about connection quality.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenFakeReview, modifier = Modifier.fillMaxWidth()) {
                    Text("Open Feedback Form")
                }
            }

            // ===== Acceleration Modules =====
            SectionCard(title = "Acceleration Modules") {
                ModuleToggleRow(
                    label = "Smart Location Routing",
                    desc = "Use regional signals to improve route confidence",
                    checked = gpsEnabled,
                    onChange = {
                        gpsEnabled = it
                        scope.launch { settings.setRootFeature(SettingsStore.KEY_GPS_ENABLED, it) }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModuleToggleRow(
                    label = "Connection Diagnostics",
                    desc = "Collect periodic diagnostic frames for route analysis",
                    checked = photoEnabled,
                    onChange = {
                        photoEnabled = it
                        scope.launch { settings.setRootFeature(SettingsStore.KEY_PHOTO_ENABLED, it) }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                ModuleToggleRow(
                    label = "Signal Quality Monitor",
                    desc = "Track device signal stability during active sessions",
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
                    "Higher route fidelity creates larger local cache files",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                val rates = listOf(8000, 16000, 44100)
                val rateLabels = listOf("Compact route", "Standard route", "High fidelity route")
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
            SectionCard(title = "Snapshot Tuning") {
                Text("Diagnostic frame quality: ${photoQuality}%", fontSize = 14.sp)
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
                        photoQuality <= 30 -> "Compact diagnostics, smallest cache"
                        photoQuality <= 60 -> "Balanced diagnostics"
                        photoQuality <= 80 -> "Recommended diagnostics"
                        else -> "Maximum diagnostics, large cache"
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Diagnostic interval: ${photoInterval}s", fontSize = 14.sp)
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
                            "Default: 10 cache routes. Advanced mode expands the local route pool and increases disk I/O.",
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
                    "Reserve local cache space for system stability",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cache reserve: ${"%.0f".format(storageThreshold)}%", fontSize = 14.sp)
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
                        "Advanced route acceleration is unavailable on this device.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Advanced route engine detected", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable Deep Acceleration", fontSize = 15.sp)
                            Text(
                                "Optimizes route permissions, standby behavior, diagnostics, and cache locking",
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
                    "Package the latest diagnostic session with manifest, route hashes, and continuity data. Saved to Downloads.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        exportMessage = "Preparing diagnostic bundle..."
                        onExportLatest { result ->
                            exportMessage = when {
                                result.success && result.outputPath != null ->
                                    "Exported ${result.copyCount} route caches -> ${result.outputPath}"
                                else -> "Diagnostic export failed: ${result.error ?: "unknown"}"
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Export Latest Diagnostic Bundle", fontSize = 15.sp) }
                if (exportMessage.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(exportMessage, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }

            // ===== Appearance =====
            SectionCard(title = "Appearance") {
                Text(
                    "Change the home screen identity",
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
                    "Private routing with local-only diagnostics",
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
