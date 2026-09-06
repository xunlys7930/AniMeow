package com.animeow.app.ui.community

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HowToVote
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.animeow.app.BuildConfig
import com.animeow.app.data.community.PresetImage

/**
 * 预设图片选择对话框。
 *
 * 用于头像选择（单选模式）和贴纸/表情选择（多选模式）。
 *
 * @param images      可选的预设图片列表。
 * @param disclaimer  底部免责声明文本。
 * @param multiSelect true 表示贴纸多选模式（显示勾选标记和「完成」按钮）；
 *                    false 表示头像单选模式（点击后立即确认）。
 * @param selectedNames 多选模式下已选中的图片名称集合，用于初始勾选状态。
 * @param onSelected  选中回调。单选模式下点击即触发并关闭对话框；
 *                    多选模式下点击「完成」后对本次选中的每张图片各触发一次。
 * @param onDismiss   关闭对话框回调。
 */
@Composable
internal fun PresetImagePickerDialog(
    images: List<PresetImage>,
    disclaimer: String,
    multiSelect: Boolean,
    selectedNames: Set<String> = emptySet(),
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirmSelection: ((Set<String>) -> Unit)? = null,
) {
    // 多选模式下维护内部选中状态；单选模式无需持久状态。
    var internalSelection by remember {
        mutableStateOf(selectedNames.toMutableSet())
    }

    // 排序：已选中的图片排在前面，方便用户查看和管理。
    val orderedImages = remember(images, internalSelection) {
        images.sortedByDescending { it.name in internalSelection }
    }

    val context = LocalContext.current
    val voteUrl = remember {
        val base = Uri.parse(BuildConfig.CLOUD_API_BASE)
        val origin = "${base.scheme ?: "https"}://${base.host}" +
            if (base.port > 0) ":${base.port}" else ""
        "$origin/vote.html"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (multiSelect) "选择贴纸/表情" else "选择预设头像",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "关闭")
                    }
                }

                // 投票提示横幅
                Surface(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(voteUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            Icons.Outlined.HowToVote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "期待正式上架？来投票吧！",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            )
                            Text(
                                "为你喜欢的头像和表情包投票，票数最高的将入选内置素材库",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                if (images.isEmpty()) {
                    // 空状态
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "正在加载预设图片…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 96.dp),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(orderedImages, key = { it.name }) { image ->
                            val isSelected = image.name in internalSelection
                            PresetImageCell(
                                image = image,
                                isSelected = isSelected,
                                multiSelect = multiSelect,
                                onClick = {
                                    if (multiSelect) {
                                        internalSelection = if (isSelected) {
                                            internalSelection - image.name
                                        } else {
                                            internalSelection + image.name
                                        }.toMutableSet()
                                    } else {
                                        onSelected(image.name)
                                        onDismiss()
                                    }
                                },
                            )
                        }
                    }
                }

                // 底部：免责声明 + 完成按钮
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (disclaimer.isNotBlank()) {
                        Text(
                            text = "免责声明：$disclaimer",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (multiSelect) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "已选 ${internalSelection.size} 张",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (onConfirmSelection != null) {
                                        onConfirmSelection(internalSelection.toSet())
                                    } else {
                                        internalSelection.forEach(onSelected)
                                    }
                                    onDismiss()
                                },
                                enabled = internalSelection.isNotEmpty(),
                            ) { Text("完成") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetImageCell(
    image: PresetImage,
    isSelected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(12.dp),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = image.url,
            contentDescription = image.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (multiSelect && isSelected) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp),
            ) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "已选中",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(4.dp),
                )
            }
        }
    }
}
