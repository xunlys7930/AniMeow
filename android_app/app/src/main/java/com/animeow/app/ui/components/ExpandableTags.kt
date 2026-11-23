package com.animeow.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.data.local.TagEntity
import com.animeow.app.ui.theme.LocalMotionLevel

/** The same ordered tags and callbacks in a compact row or a wrapping, editable group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExpandableTags(
    tags: List<TagEntity>,
    onTagClick: (Long) -> Unit,
    selectedIds: Set<Long>? = null,
    workId: Long? = null,
) {
    var expanded by rememberSaveable(workId) { mutableStateOf(false) }
    if (tags.isEmpty()) {
        Text("还没有标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val chip: @Composable (TagEntity) -> Unit = { tag ->
        val label: @Composable () -> Unit = {
            Text(tag.name, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
        }
        if (selectedIds == null) AssistChip(
            onClick = { onTagClick(tag.id) }, label = label,
            modifier = Modifier.heightIn(min = 48.dp), border = null,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        else FilterChip(selected = tag.id in selectedIds, onClick = { onTagClick(tag.id) }, label = label, modifier = Modifier.heightIn(min = 48.dp))
    }
    Column(Modifier.fillMaxWidth().motionAnimateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val motionLevel = LocalMotionLevel.current
        AnimatedContent(
            targetState = expanded,
            transitionSpec = { motionFadeThroughSpec(motionLevel, 180, 130) },
            label = "expandable_tags",
        ) { isExpanded ->
            if (isExpanded) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                tags.forEach { tag -> key(tag.id) { chip(tag) } }
            } else LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tags, key = { it.id }) { tag -> chip(tag) }
            }
        }
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        }) { Text(if (expanded) "收起标签" else "展开全部标签（${tags.size}）") }
    }
}
