package com.animeow.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.animeow.app.ui.icons.AppIcon as Icon
import com.animeow.app.ui.icons.AppIcons

/** A stable, full-height workspace for lists that should not be squeezed into an alert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManagementListDialog(
    title: String,
    onDismiss: () -> Unit,
    bottomBar: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        Scaffold(Modifier.fillMaxSize().imePadding(),
            topBar = { TopAppBar(title = { Text(title) },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(AppIcons.Back, "返回管理工作台") } },
                actions = actions) },
            bottomBar = bottomBar,
            content = content,
        )
    }
}
