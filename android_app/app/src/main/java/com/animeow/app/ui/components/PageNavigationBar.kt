package com.animeow.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun PageNavigationBar(
    currentPage: Int,
    totalPages: Int?,
    hasNext: Boolean,
    loading: Boolean,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
    onCancel: (() -> Unit)? = null,
) {
    var showJump by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxWidth().testTag("page-navigation"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onPageSelected(currentPage - 1) }, enabled = currentPage > 1 && !loading,
                modifier = Modifier.weight(1f)) { Text("上一页") }
            TextButton(onClick = { draft = currentPage.toString(); showJump = true }, enabled = !loading,
                modifier = Modifier.weight(1.4f).testTag("page-jump")) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(currentPage.toString(), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(if (totalPages != null) "/ ${totalPages.coerceAtLeast(1)} 页" else "点击跳页", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            TextButton(onClick = { onPageSelected(currentPage + 1) }, enabled = hasNext && !loading,
                modifier = Modifier.weight(1f)) { Text("下一页") }
        }
        if (loading && onCancel != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(status ?: "正在读取页面…", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onCancel) { Text("取消") }
            }
        } else Text(status ?: "点击页码可跳转", Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showJump) {
        val page = draft.toIntOrNull()
        val valid = page != null && page > 0 && (totalPages == null || page <= totalPages.coerceAtLeast(1))
        fun jump() { if (valid) { showJump = false; onPageSelected(page!!) } }
        AlertDialog(onDismissRequest = { showJump = false }, title = { Text("跳转到页面") }, text = {
            OutlinedTextField(draft, { draft = it.filter(Char::isDigit).take(6) }, label = { Text("页码") },
                modifier = Modifier.fillMaxWidth().testTag("page-number-input"), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { jump() }),
                isError = draft.isNotBlank() && !valid,
                supportingText = { Text(if (totalPages != null) "请输入 1–${totalPages.coerceAtLeast(1)}"
                    else "首次跳转到较远页面可能稍慢，可随时取消。") })
        }, confirmButton = { TextButton(onClick = ::jump, enabled = valid) { Text("跳转") } },
            dismissButton = { TextButton(onClick = { showJump = false }) { Text("取消") } })
    }
}
