package com.cairn.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cairn.app.ui.theme.CairnBlue
import com.cairn.app.ui.theme.CairnBlueDark
import com.cairn.app.ui.theme.CairnPink

/**
 * 大圆形主录音按钮。
 *
 * 视觉：
 * - 静止 = 雾粉渐变，中间白色圆圈 + ● 图标
 * - 录音中 = 蓝色脉冲呼吸动效
 *
 * 交互：单击切换录音状态
 */
@Composable
fun RecordButton(
    isRecording: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 脉冲呼吸动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val outerScale = if (isRecording) pulseScale else 1f

    // 渐变色
    val gradient = if (isRecording) {
        Brush.radialGradient(
            colors = listOf(CairnBlue, CairnBlueDark)
        )
    } else {
        Brush.radialGradient(
            colors = listOf(CairnPink, CairnPink.copy(alpha = 0.7f))
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(160.dp)
            .scale(outerScale)
            .clip(CircleShape)
            .background(gradient)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // 无涟漪，用自己的动效
                onClick = onClick
            )
    ) {
        // 内圈白色
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(if (isRecording) 48.dp else 56.dp)
                .clip(if (isRecording) androidx.compose.foundation.shape.RoundedCornerShape(8.dp) else CircleShape)
                .background(Color.White.copy(alpha = 0.95f))
        ) {
            if (!isRecording) {
                // 静止：显示圆形录音图标
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(CairnPink)
                )
            }
            // 录���中：内圈变方形（停止图标），无需额外内容
        }
    }
}

/**
 * 录音时长显示
 */
@Composable
fun DurationText(
    durationMs: Long,
    modifier: Modifier = Modifier
) {
    val totalSeconds = (durationMs / 1000).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60

    val text = if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }

    Text(
        text = text,
        fontSize = 28.sp,
        fontWeight = FontWeight.Light,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        modifier = modifier
    )
}

/**
 * 状态指示文字
 */
@Composable
fun StatusIndicator(
    isRecording: Boolean,
    aliveWriters: Int,
    totalWriters: Int,
    modifier: Modifier = Modifier
) {
    val text = if (isRecording) {
        "全部副本 · 同步中  $aliveWriters/$totalWriters"
    } else {
        "就绪"
    }

    val dotColor = when {
        !isRecording -> MaterialTheme.colorScheme.onSurfaceVariant
        aliveWriters == totalWriters -> Color(0xFF86EFAC) // 全绿
        aliveWriters > 0 -> Color(0xFFFCD34D) // 部分黄
        else -> Color(0xFFF87171) // 全红
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
