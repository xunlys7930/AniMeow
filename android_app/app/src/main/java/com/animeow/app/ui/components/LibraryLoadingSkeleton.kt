package com.animeow.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.theme.LocalMotionLevel
import com.animeow.app.ui.theme.MotionLevel

@Composable
fun LibraryLoadingSkeleton(modifier: Modifier = Modifier, rows: Int = 3) {
    val animated = LocalMotionLevel.current == MotionLevel.FULL
    val opacity = if (animated) {
        val transition = rememberInfiniteTransition(label = "loading_skeleton")
        val value by transition.animateFloat(0.35f, 0.65f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "loading_opacity")
        value
    } else 0.5f
    val fill = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = opacity)
    Column(modifier.fillMaxWidth().padding(12.dp).semantics { contentDescription = "正在加载作品" }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(rows.coerceIn(1, 5)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(66.dp, 88.dp).clip(MaterialTheme.shapes.medium).background(fill))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.fillMaxWidth(0.75f).height(17.dp).clip(MaterialTheme.shapes.small).background(fill))
                    Box(Modifier.fillMaxWidth(0.45f).height(12.dp).clip(MaterialTheme.shapes.small).background(fill))
                    Box(Modifier.fillMaxWidth(0.9f).height(8.dp).clip(MaterialTheme.shapes.small).background(fill))
                }
            }
        }
    }
}
