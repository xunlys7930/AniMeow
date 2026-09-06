package com.animeow.app.ui.manual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.animeow.app.data.preferences.ManualDisplayMode
import kotlin.random.Random

/**
 * 每日首次打开 App 时弹出的手册提示对话框。
 *
 * @param tipIndex 用于选择今日提示的索引（避免 LaunchedEffect 中产生重组）。
 * @param onMode  用户选择的显示模式。
 * @param onOpenManual 用户点击「查看完整手册」。
 * @param onDismiss 关闭对话框（等同于今日不显示）。
 */
@Composable
fun DailyManualDialog(
    tipIndex: Int,
    onMode: (ManualDisplayMode) -> Unit,
    onOpenManual: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tips = remember { dailyTips() }
    val tip = remember(tipIndex) {
        tips.getOrElse(tipIndex.coerceIn(0, tips.lastIndex)) { tips.first() }
    }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null) },
        title = { Text("每日小贴士") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tip.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    tip.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  ${tip.location}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    onMode(ManualDisplayMode.NEVER)
                    onDismiss()
                    Toast.makeText(context, "已关闭每日小贴士，可在「用户手册」中重新开启", Toast.LENGTH_SHORT).show()
                }) {
                    Text("永不显示")
                }
                TextButton(onClick = { onMode(ManualDisplayMode.DAILY); onDismiss() }) {
                    Text("每日显示")
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("今日不显示")
                }
                TextButton(onClick = onOpenManual) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("查看手册")
                    }
                }
            }
        },
    )
}

/** 随机生成一个提示索引。 */
fun randomTipIndex(): Int = Random.nextInt(dailyTips().size)
