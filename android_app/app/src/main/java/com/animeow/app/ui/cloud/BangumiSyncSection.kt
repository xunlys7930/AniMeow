package com.animeow.app.ui.cloud

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.data.bangumi.bangumiLastRunText
import com.animeow.app.data.bangumi.bangumiQueueStatusText
import com.animeow.app.data.cloud.CloudSyncSettings
import com.animeow.app.ui.components.motionAnimateContentSize

/**
 * 前往 Bangumi 官网授权前的网络提示（登录、绑定、重新授权三个入口共用）。
 * 文案只描述真实行为：授权页在 bgm.tv，国内网络一般打不开；授权完成后导入与同步在 App 内进行，
 * 手机连不上 Bangumi 时由 AniMeow 服务器转交（服务器未开通时会明确提示，不承诺一定可用）。
 */
@Composable
internal fun BangumiLoginTipDialog(
    onContinue: (hideNextTime: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var hideNextTime by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("前往 Bangumi 官网授权") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("接下来会在浏览器中打开 Bangumi 官网（bgm.tv）完成登录与授权。")
                Text(
                    "受网络环境影响，部分地区可能无法直接打开 bgm.tv；若页面一直无法加载，请切换网络环境后再试。",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "授权只需完成一次，完成后会自动跳回 AniMeow。后续导入收藏、同步进度与评分均在应用内直接完成，无需再打开网页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) { hideNextTime = !hideNextTime },
                ) {
                    Checkbox(checked = hideNextTime, onCheckedChange = null)
                    Text("不再提示", modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = { Button(onClick = { onContinue(hideNextTime) }) { Text("继续前往") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 系统没有可打开授权页的浏览器：给出复制链接的办法，而不是静默失败。 */
@Composable
internal fun BangumiAuthorizeOpenFailedDialog(
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("无法打开浏览器") },
        text = {
            Text(
                "没有找到能打开 Bangumi 授权页的浏览器。可以复制授权链接，粘贴到本机能访问 bgm.tv 的浏览器中打开；" +
                    "链接 10 分钟内有效，过期后回到这里重新发起即可。",
            )
        },
        confirmButton = { Button(onClick = onCopy) { Text("复制链接") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 开启了「仅不限流量网络」但当前是计费网络时，手动同步前询问。 */
@Composable
internal fun BangumiMeteredSyncDialog(
    pendingCount: Int,
    onSyncAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("现在使用移动数据同步？") },
        text = {
            Text(
                "已开启「仅不限流量网络」，当前是移动数据等计费网络，待上报 $pendingCount 项改动。" +
                    "可以现在同步，也可以等连接 Wi-Fi 后自动上报。",
            )
        },
        confirmButton = { Button(onClick = onSyncAnyway) { Text("仍然同步") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("等 Wi-Fi") } },
    )
}

@Composable
internal fun BangumiSyncSection(
    settings: CloudSyncSettings,
    pendingCount: Int,
    busy: Boolean,
    syncing: Boolean,
    viewModel: CloudAccountViewModel,
    bangumi: com.animeow.app.data.cloud.BangumiBinding? = null,
    onBindRequested: () -> Unit = {},
    onUploadRequested: () -> Unit = {},
    onImportRequested: () -> Unit = {},
) {
    fun save(
        progress: Boolean = settings.bangumiProgressSync,
        rating: Boolean = settings.bangumiRatingSync,
        wifiOnly: Boolean = settings.bangumiSyncWifiOnly,
    ) = viewModel.updateBangumiSync(progress, rating, wifiOnly)

    val report = settings.bangumiLastReport
    ElevatedCard(modifier = Modifier.fillMaxWidth().motionAnimateContentSize()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bangumi 同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (bangumi != null) {
                    Text(
                        "已连接 @${bangumi.username}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (bangumi == null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("第 1 步：连接 Bangumi 账号", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "连接一次后，即可把本机收藏与进度上传到 Bangumi，并在追番时自动上报",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        FilledTonalButton(onClick = onBindRequested, enabled = !busy) {
                            Text("连接账号")
                        }
                    }
                }
            } else {
                Text(
                    "日常修改进度、状态与评分可自动上报；已有的本机历史收藏可通过下方「同步本机收藏到 Bangumi」批量对比上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onUploadRequested)
                    .padding(vertical = 4.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("同步本机收藏到 Bangumi", fontWeight = FontWeight.SemiBold)
                    Text(
                        "对比本机与 Bangumi 收藏差异，批量上传状态、评分与进度",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "去同步",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onImportRequested)
                    .padding(vertical = 4.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("导入 Bangumi 收藏", fontWeight = FontWeight.SemiBold)
                    Text(
                        "把 Bangumi 收藏拉回本机合并；绑定后免输用户名并可导入私密收藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "前往",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("自动同步进度与状态", fontWeight = FontWeight.SemiBold)
                    Text("看过的集数与「看完」等状态自动上报，看完自动标记「看过」", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.bangumiProgressSync,
                    onCheckedChange = { save(progress = it) },
                    enabled = !busy,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("同步评分", fontWeight = FontWeight.SemiBold)
                    Text("评分改动写回 Bangumi；清空评分即删除 Bangumi 评分", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = settings.bangumiRatingSync,
                    onCheckedChange = { save(rating = it) },
                    enabled = !busy,
                )
            }
            if (settings.bangumiProgressSync || settings.bangumiRatingSync) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("仅不限流量网络", fontWeight = FontWeight.SemiBold)
                        Text(
                            "开启后后台只在 Wi-Fi 等网络下上报；手动「立即同步」时可选择仍用移动数据",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.bangumiSyncWifiOnly,
                        onCheckedChange = { save(wifiOnly = it) },
                        enabled = !busy,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("在首页显示同步状态", fontWeight = FontWeight.SemiBold)
                        Text(
                            "在收藏页顶部显示待上报数量或同步未完成提示；关闭后后台同步照常运行",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = settings.bangumiShowHomeStatus,
                        onCheckedChange = viewModel::setBangumiShowHomeStatus,
                        enabled = !busy,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            bangumiQueueStatusText(pendingCount, report),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        bangumiLastRunText(report)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (pendingCount > 0) {
                            report?.reason?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (report != null && report.dropped > 0) {
                            report.droppedItems.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp).size(20.dp), strokeWidth = 2.dp)
                    } else if (pendingCount > 0) {
                        TextButton(onClick = { viewModel.syncBangumiNow() }, enabled = !busy) { Text("立即同步") }
                    }
                }
            }
        }
    }
}
