package com.cairn.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cairn.app.service.RecordingService
import com.cairn.app.storage.SettingsStore
import com.cairn.app.ui.theme.CairnBlue
import com.cairn.app.ui.theme.CairnBlueDark
import com.cairn.app.ui.theme.CairnPink
import com.cairn.app.ui.theme.CairnSuccess
import com.cairn.app.ui.theme.CairnTheme
import com.cairn.app.vpn.VpnServerData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 主屏 — 完全伪装成 VPN 客户端。
 *
 * 视觉欺骗设计：
 *   - 品牌 "FastLink VPN"（自有，避商标）
 *   - 顶部当前服务器卡片（可点开切换）
 *   - 中央大圆 POWER 按钮（仿 VPN App 风格）
 *   - 下方流量统计（假数据，连接后波动）
 *   - 连接时长（真 Chronometer，对应实际录音时长）
 *
 * 按钮行为（关键设计）：
 *   - 未连接 → 点击 → 启动录音 + UI 变"已连接"
 *   - 已连接 → 点击 → **仅关闭 VPN UI 显示**，录音继续在后台跑
 *   - 真正停止录音只能在「设置」里操作
 *
 * 这样在被胁迫场景下：
 *   "把你的 VPN 关了" → 用户点击 → UI 显示已断开 → 对方满意
 *   实际录音还在录，证据继续保存
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 权限授予后如果点过连接，启动录音
        if (results.values.all { it } && pendingStartAfterPermission) {
            pendingStartAfterPermission = false
            RecordingService.start(this)
        }
    }

    private var pendingStartAfterPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CairnTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    VpnMainScreen(
                        onConnectAttempt = ::onConnectAttempt,
                        onDisconnect = ::onDisconnect,
                        onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
                    )
                }
            }
        }
    }

    private fun onConnectAttempt() {
        if (!hasRuntimePermissions()) {
            pendingStartAfterPermission = true
            requestEssentialPermissions()
            return
        }
        RecordingService.start(this)
    }

    /**
     * 关键：点"断开"只切换 UI 显示状态，不真正停录音。
     * 真正停止只能在 Settings 中。
     */
    private fun onDisconnect() {
        // 不做任何后端操作 — UI 自管理状态
    }

    private fun hasRuntimePermissions(): Boolean {
        val essential = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        return essential.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestEssentialPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

// ===== UI =====

@Composable
fun VpnMainScreen(
    onConnectAttempt: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context.applicationContext) }

    // 真实录音状态（仅供按钮逻辑判断，不显示给用户）
    var serviceRunning by remember { mutableStateOf(RecordingService.isRunning) }

    // UI 显示状态 — 用户视角的"VPN 已连接"
    // 启动时与 serviceRunning 同步；之后用户可独立控制
    var uiConnected by remember { mutableStateOf(RecordingService.isRunning) }

    var serverId by remember { mutableStateOf(SettingsStore.DEFAULT_SERVER_ID) }
    var server by remember { mutableStateOf(VpnServerData.byId(serverId)) }
    var showServerList by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        serverId = settings.selectedServerIdFlow.first()
        server = VpnServerData.byId(serverId)
    }

    // 周期刷新真实状态
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val wasRunning = serviceRunning
            serviceRunning = RecordingService.isRunning
            // 真实录音停止 → UI 也跟着断开（覆盖用户假断开）
            if (wasRunning && !serviceRunning) uiConnected = false
            // 真实录音开始 → UI 跟着连接（除非用户已假断开）
            if (!wasRunning && serviceRunning) uiConnected = true
            durationMs = RecordingService.instance?.durationMs ?: 0
        }
    }

    if (showServerList) {
        ServerListSheet(
            currentId = serverId,
            onSelect = { id ->
                serverId = id
                server = VpnServerData.byId(id)
                scope.launch { settings.setSelectedServerId(id) }
                showServerList = false
            },
            onDismiss = { showServerList = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // 顶栏 — 品牌名 + 设置齿轮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FastLink",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = " VPN",
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 服务器卡片
        ServerCard(
            server = server,
            connected = uiConnected,
            onClick = { showServerList = true }
        )

        Spacer(modifier = Modifier.weight(0.8f))

        // 中央大圆 POWER 按钮
        Box(modifier = Modifier.align(Alignment.CenterHorizontally)) {
            PowerButton(
                connected = uiConnected,
                onClick = {
                    if (uiConnected) {
                        // 假断开 — 只切换 UI 状态，不真正停录音
                        uiConnected = false
                        onDisconnect()
                    } else {
                        uiConnected = true
                        onConnectAttempt()
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 状态文字
        Text(
            text = when {
                uiConnected -> "已连接"
                else -> "未连接"
            },
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = if (uiConnected) CairnSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.weight(1f))

        // 底部流量统计 + 时长
        BandwidthStatsCard(uiConnected, durationMs)
    }
}

@Composable
fun ServerCard(
    server: VpnServerData.Server,
    connected: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = server.flag, fontSize = 36.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.city,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (server.ping == 0) "—" else "${server.ping}ms",
                        fontSize = 13.sp,
                        color = if (connected) CairnSuccess
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(server.tier.color).copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = server.tier.displayName,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(server.tier.color)
                        )
                    }
                }
            }
            Text(
                text = "›",
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PowerButton(
    connected: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "vpnPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val ringScale = if (connected) pulseScale else 1f

    val gradient = if (connected) {
        Brush.radialGradient(colors = listOf(CairnSuccess, CairnBlue, CairnBlueDark))
    } else {
        Brush.radialGradient(colors = listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant
        ))
    }

    // 外圈光环
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(220.dp)
            .scale(ringScale)
            .clip(CircleShape)
            .background(
                if (connected) Brush.radialGradient(
                    colors = listOf(
                        CairnSuccess.copy(alpha = 0.25f),
                        CairnSuccess.copy(alpha = 0f)
                    )
                ) else Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        Color.Transparent
                    )
                )
            )
    ) {
        // 主按钮
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(170.dp)
                .clip(CircleShape)
                .background(gradient)
                .clickable(onClick = onClick)
        ) {
            // 内圈白色
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(if (connected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = if (connected) "断开" else "连接",
                    tint = if (connected) Color.White else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                )
            }
        }
    }
}

@Composable
fun BandwidthStatsCard(connected: Boolean, durationMs: Long) {
    val stats = remember(connected, durationMs / 1000) {
        VpnServerData.fakeStats(if (connected) durationMs else 0)
    }

    val durationText = run {
        val s = (durationMs / 1000).toInt()
        if (connected) "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "—"
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = "下行",
                    value = if (connected) formatRate(stats.downKbps) else "0 B/s",
                    icon = "↓",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "上行",
                    value = if (connected) formatRate(stats.upKbps) else "0 B/s",
                    icon = "↑",
                    color = CairnPink,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatItem(
                    label = "已用流量",
                    value = if (connected) "${stats.totalDownMb} MB" else "0 MB",
                    icon = "▼",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                StatItem(
                    label = "连接时长",
                    value = durationText,
                    icon = "⏱",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    icon: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 14.sp, color = color)
            Spacer(modifier = Modifier.width(4.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            value,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

fun formatRate(kbps: Long): String = when {
    kbps >= 1024 -> "%.1f MB/s".format(kbps / 1024f)
    else -> "$kbps KB/s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListSheet(
    currentId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(
                text = "选择线路",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            LazyColumn {
                items(VpnServerData.SERVERS) { server ->
                    ServerRow(server, selected = server.id == currentId, onSelect = onSelect)
                }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
fun ServerRow(
    server: VpnServerData.Server,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(server.id) }
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(server.flag, fontSize = 28.sp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(server.city, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                server.country,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (server.ping == 0) "—" else "${server.ping}ms",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(server.tier.color).copy(alpha = 0.18f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = server.tier.displayName,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(server.tier.color)
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(8.dp))
            Text("✓", fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
