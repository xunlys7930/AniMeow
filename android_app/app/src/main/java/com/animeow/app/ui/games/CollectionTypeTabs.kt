package com.animeow.app.ui.games

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CollectionTypeTabs(selected: String, onSelected: (String) -> Unit, visible: Set<String> = setOf("anime", "book", "game")) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("all" to "总览", "anime" to "动画", "book" to "书籍", "game" to "游戏", "audio" to "音声")
            .filter { it.first == "all" || it.first in visible }
            .forEach { (key, label) ->
                FilterChip(selected = selected == key, onClick = { onSelected(key) }, label = { Text(label) })
            }
    }
}
