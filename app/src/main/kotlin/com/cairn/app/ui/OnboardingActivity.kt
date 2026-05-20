package com.cairn.app.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.app.permission.OneClickPermissionFlow
import com.cairn.app.permission.VendorRomDetector
import com.cairn.app.ui.theme.CairnTheme

/**
 * 一键授权引导页 — 首次启动 / 从主屏齿轮入口。
 *
 * 显示 7 步进度条 + 每步介绍 + 跳转按钮。
 * 用户点「下一步」自动跳到对应权限设置页。
 */
class OnboardingActivity : ComponentActivity() {

    private lateinit var flow: OneClickPermissionFlow

    private val runtimePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 刷新 UI */ recomputeState() }

    private var stateVersion by mutableIntStateOf(0)
    private fun recomputeState() { stateVersion++ }

    override fun onResume() {
        super.onResume()
        recomputeState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        flow = OneClickPermissionFlow(this)

        setContent {
            CairnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // stateVersion key 让整个 UI 在权限变化时重组
                    val key = stateVersion
                    OnboardingScreen(
                        flow = flow,
                        vendor = VendorRomDetector.detect(),
                        vendorGuide = VendorRomDetector.getGuideText(),
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
                            startActivity(Intent(this, MainActivity::class.java))
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    flow: OneClickPermissionFlow,
    vendor: VendorRomDetector.Vendor,
    vendorGuide: String,
    onRequestRuntime: () -> Unit,
    onLaunchIntent: (Intent) -> Unit,
    onFinish: () -> Unit
) {
    val steps = OneClickPermissionFlow.Step.entries
    val progress = flow.getProgress()
    val allDone = flow.isAllCompleted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一键授权", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(
                text = "为确保紧急情况下能完整记录，",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "需要授予以下权限",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Text(
                text = "${(progress * steps.size).toInt()} / ${steps.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 步骤列表
            for (step in steps) {
                val isCompleted = flow.isStepCompleted(step)
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
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.weight(1f))

            // 厂商提示
            if (vendor != VendorRomDetector.Vendor.OTHER) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 完成按钮
            Button(
                onClick = onFinish,
                enabled = allDone,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(
                    text = if (allDone) "完成 · 开始使用" else "完成上述步骤",
                    fontSize = 16.sp
                )
            }
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
                TextButton(onClick = onClick) { Text("授权") }
            }
        }
    }
}
