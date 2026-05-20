package com.cairn.app.nuke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.cairn.app.storage.SettingsStore
import com.cairn.app.ui.theme.CairnTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 伪评论 Activity — 核爆暗码入口。
 *
 * 视觉：完全正常的「给我们评分」UI
 *   - 5 星评分
 *   - 评论文本框
 *   - 提交按钮
 *
 * 行为：
 *   - 普通评论 → 显示 "感谢反馈！"
 *   - 评论 = 用户预设暗码 → 静默触发 NukeEngine，同时显示 "感谢反馈！"
 *
 * 关键：对方看到的 UI 反应完全一致，无法察觉核爆已发生。
 */
class FakeReviewActivity : ComponentActivity() {

    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(applicationContext)

        setContent {
            CairnTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FakeReviewScreen(
                        onSubmit = { comment ->
                            checkAndMaybeNuke(comment)
                        }
                    )
                }
            }
        }
    }

    private fun checkAndMaybeNuke(comment: String) {
        lifecycleScope.launch {
            val isMatch = DuressCodeMatcher.isMatch(comment, settings)
            if (isMatch) {
                // 静默核爆，不在 UI 显示任何异常
                NukeEngine.detonate(this@FakeReviewActivity)
            }
            // 无论是否核爆，UI 显示一致的"感谢反馈"
        }
    }
}

@Composable
fun FakeReviewScreen(
    onSubmit: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rating by remember { mutableIntStateOf(5) }
    var comment by remember { mutableStateOf("") }
    var showThanks by remember { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        Text(
            text = "给我们评分",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "您的反馈帮助我们做得更好",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // 5 星评分
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 1..5) {
                IconButton(onClick = { rating = i }) {
                    Text(
                        text = if (i <= rating) "★" else "☆",
                        fontSize = 32.sp,
                        color = if (i <= rating) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 评论框
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text("说说您的感受") },
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            shape = RoundedCornerShape(16.dp),
            maxLines = 5
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 提交按钮
        Button(
            onClick = {
                val submitted = comment
                onSubmit(submitted)
                comment = ""
                rating = 5
                showThanks = true

                scope.launch {
                    delay(2500)
                    showThanks = false
                }
            },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("提交反馈", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 「感谢反馈」提示（无论是否核爆都显示）
        if (showThanks) {
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "✓ 感谢反馈！我们已收到您的评价",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}
