package com.animeow.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.animeow.app.ui.icons.AppIcons
import com.animeow.app.ui.icons.AppIcon as Icon

@Composable
internal fun BrowseSearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSearch: () -> Unit = {},
    searchAction: @Composable RowScope.() -> Unit = {},
    controls: @Composable RowScope.() -> Unit = {},
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQueryChanged, enabled = enabled,
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder },
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(AppIcons.Search, null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = motionFadeIn(140),
                        exit = motionFadeOut(120),
                    ) {
                        IconButton(onClick = { onQueryChanged("") }, enabled = enabled) {
                            Icon(AppIcons.Close, "清除搜索")
                        }
                    }
                    searchAction()
                }
            },
            singleLine = true, shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus(); onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
        controls()
    }
}

@Composable
internal fun BrowseFilterButton(count: Int, onClick: () -> Unit, label: String = "筛选") {
    FilledTonalButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).semantics {
        contentDescription = if (count > 0) "$label，已选 $count 项条件" else label
    }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Icon(AppIcons.Filter, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (count > 0) "$label · $count" else label, maxLines = 1)
    }
}
