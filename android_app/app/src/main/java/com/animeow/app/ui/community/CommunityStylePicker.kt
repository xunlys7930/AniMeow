package com.animeow.app.ui.community

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.components.animatedPressClick
import com.animeow.app.ui.theme.CommunityStyle

/**
 * 社区样式单选卡：设置「社区」区块与社区顶栏「社区样式」弹窗共用同一套选项，
 * 两处写入同一个 communityStyle 偏好。缩略预览用 Canvas 绘制，不引入图片资源。
 */
@Composable
internal fun CommunityStylePicker(
    selected: CommunityStyle,
    onSelect: (CommunityStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "选择社区的浏览方式",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        CommunityStyle.entries.forEach { style ->
            val isSelected = style == selected
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animatedPressClick(
                            role = Role.RadioButton,
                            onClickLabel = "选择${style.displayName}",
                            onClick = { onSelect(style) },
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onSelect(style) },
                    )
                    CommunityStyleThumbnail(
                        style = style,
                        modifier = Modifier
                            .width(56.dp)
                            .height(84.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            style.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            style.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            "切换只改变浏览方式；帖子、评论、账号、审核与通知在两种样式下完全一致。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 社区样式选择弹窗：社区顶栏「更多 → 社区样式」打开，与设置区块共用同一套单选卡。 */
@Composable
internal fun CommunityStyleDialog(
    selected: CommunityStyle,
    onSelect: (CommunityStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("社区样式") },
        text = {
            CommunityStylePicker(
                selected = selected,
                onSelect = onSelect,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 单选卡缩略预览：信息流为单列卡片堆叠，瀑布流为双列错位色块，
 * 用构图差异表达两种浏览形态，不依赖具体图片资源。
 */
@Composable
private fun CommunityStyleThumbnail(
    style: CommunityStyle,
    modifier: Modifier = Modifier,
) {
    val cardColor = MaterialTheme.colorScheme.surfaceVariant
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val accentBlock = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    val baseColor = MaterialTheme.colorScheme.surface
    Canvas(
        modifier = modifier.background(baseColor),
    ) {
        val pad = 5.dp.toPx()
        val gap = 4.dp.toPx()
        val radius = 4.dp.toPx()
        val smallRadius = 2.dp.toPx()
        when (style) {
            CommunityStyle.FEED -> {
                val cardHeight = (size.height - pad * 2 - gap * 2) / 3f
                val cardWidth = size.width - pad * 2
                repeat(3) { index ->
                    val top = pad + index * (cardHeight + gap)
                    drawRoundRect(
                        color = cardColor,
                        topLeft = Offset(pad, top),
                        size = Size(cardWidth, cardHeight),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    // 卡内示意：头像方块 + 两行文字。
                    val inner = 4.dp.toPx()
                    val avatar = 7.dp.toPx()
                    drawRoundRect(
                        color = accentBlock,
                        topLeft = Offset(pad + inner, top + inner),
                        size = Size(avatar, avatar),
                        cornerRadius = CornerRadius(smallRadius, smallRadius),
                    )
                    val lineX = pad + inner + avatar + inner
                    val lineWidth = (size.width - lineX - pad - inner)
                    drawRoundRect(
                        color = lineColor,
                        topLeft = Offset(lineX, top + inner + 1.dp.toPx()),
                        size = Size(lineWidth * 0.7f, 3.dp.toPx()),
                        cornerRadius = CornerRadius(smallRadius, smallRadius),
                    )
                    drawRoundRect(
                        color = lineColor,
                        topLeft = Offset(lineX, top + inner + 7.dp.toPx()),
                        size = Size(lineWidth * 0.45f, 3.dp.toPx()),
                        cornerRadius = CornerRadius(smallRadius, smallRadius),
                    )
                }
            }
            CommunityStyle.WATERFALL -> {
                val columnWidth = (size.width - pad * 2 - gap) / 2f
                val contentHeight = size.height - pad * 2 - gap
                // 左列：高块在上；右列：矮块在上，形成双列错位。
                val leftTall = contentHeight * 0.55f
                val rightShort = contentHeight * 0.4f
                drawRoundRect(
                    color = accentBlock,
                    topLeft = Offset(pad, pad),
                    size = Size(columnWidth, leftTall),
                    cornerRadius = CornerRadius(radius, radius),
                )
                drawRoundRect(
                    color = cardColor,
                    topLeft = Offset(pad, pad + leftTall + gap),
                    size = Size(columnWidth, contentHeight - leftTall),
                    cornerRadius = CornerRadius(radius, radius),
                )
                val rightX = pad + columnWidth + gap
                drawRoundRect(
                    color = cardColor,
                    topLeft = Offset(rightX, pad),
                    size = Size(columnWidth, rightShort),
                    cornerRadius = CornerRadius(radius, radius),
                )
                drawRoundRect(
                    color = accentBlock,
                    topLeft = Offset(rightX, pad + rightShort + gap),
                    size = Size(columnWidth, contentHeight - rightShort),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
        }
    }
}
