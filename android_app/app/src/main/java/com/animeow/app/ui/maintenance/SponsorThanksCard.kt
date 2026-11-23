package com.animeow.app.ui.maintenance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.animeow.app.data.remote.RemoteHttpException
import com.animeow.app.data.remote.SponsorThanks
import com.animeow.app.data.remote.SponsorThanksRepository
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon
import kotlinx.coroutines.CancellationException

@Composable
internal fun SponsorThanksCard() {
    val context = LocalContext.current
    val repository = remember(context) { SponsorThanksRepository(context) }
    var snapshot by remember { mutableStateOf<SponsorThanks?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var request by remember { mutableIntStateOf(0) }
    LaunchedEffect(repository, request) {
        loading = true
        error = null
        if (snapshot == null) snapshot = repository.cached()
        try { snapshot = repository.refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = if (failure is RemoteHttpException && failure.statusCode in listOf(404, 405)) "云端名单暂未开放，请稍后刷新"
                else "名单暂时加载失败，请检查网络后重试"
        } finally { loading = false }
    }
    SponsorThanksContent(snapshot, loading, error) { request++ }
}

@Composable
internal fun SponsorThanksContent(snapshot: SponsorThanks?, loading: Boolean, error: String?, onRefresh: () -> Unit) {
    var page by remember(snapshot) { mutableIntStateOf(0) }
    val items = snapshot?.items.orEmpty()
    Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.HeartFilled, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("赞助鸣谢", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp)) { Text("刷新") }
            }
            Text("感谢你支持服务器维护与后续开发。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (snapshot == null) "正在加载名单…" else "正在更新名单…", style = MaterialTheme.typography.bodySmall)
            }
            if (error != null) Text(if (snapshot == null) error else "$error，以下为上次加载的名单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot != null && items.isEmpty()) Text("名单尚未公布，感谢每一份支持。", style = MaterialTheme.typography.bodyMedium)
            items.drop(page * 16).take(16).forEach { person ->
                key(person.id) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(AppIcons.HeartFilled, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                if (person.avatarUrl.isNotEmpty()) AsyncImage(person.avatarUrl, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(person.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            if (person.supportMonth.isNotEmpty()) Text("${person.supportMonth} · 感谢支持", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (items.size > 16) {
                Text("第 ${page + 1} / ${(items.size + 15) / 16} 页", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 0) { Text("上一页") }
                    OutlinedButton(onClick = { page++ }, enabled = (page + 1) * 16 < items.size) { Text("下一页") }
                }
            }
        }
    }
}
