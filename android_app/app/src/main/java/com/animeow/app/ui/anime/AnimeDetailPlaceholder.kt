package com.animeow.app.ui.anime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AnimeDetailPlaceholder(state: AnimeDetailState, onBack: () -> Unit, onRetry: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text("作品详情") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = "返回") }
        })
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            when (state) {
                AnimeDetailState.Loading -> { CircularProgressIndicator(); Text("正在加载作品") }
                AnimeDetailState.Missing -> {
                    Text("作品不存在或已移入回收站", textAlign = TextAlign.Center)
                    Button(onClick = onBack) { Text("返回上一页") }
                }
                AnimeDetailState.Failed -> {
                    Text("作品加载失败，请重试", textAlign = TextAlign.Center)
                    Button(onClick = onRetry) { Text("重新加载") }
                }
                is AnimeDetailState.Loaded -> Unit
            }
        }
    }
}
