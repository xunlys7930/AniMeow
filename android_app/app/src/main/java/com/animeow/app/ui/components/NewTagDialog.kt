package com.animeow.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun NewTagDialog(
    onConfirm: (name: String, color: Long) -> Unit,
    onDismiss: () -> Unit,
    title: String = "新建标签并添加",
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableLongStateOf(DEFAULT_NEW_TAG_COLOR) }
    val normalizedName = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("标签名称") },
                    supportingText = { Text("同名标签会直接复用，不会重复创建") },
                    singleLine = true,
                )
                Text("标签颜色", style = MaterialTheme.typography.labelLarge)
                CustomizableColorSelector(
                    color = color,
                    presets = NEW_TAG_COLOR_PRESETS,
                    onColorChanged = { color = it },
                    dialogTitle = "自定义标签颜色",
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(normalizedName, color) },
                enabled = normalizedName.isNotEmpty(),
            ) { Text("创建并添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private const val DEFAULT_NEW_TAG_COLOR = 0xFF8A4FD0

private val NEW_TAG_COLOR_PRESETS = listOf(
    0xFF8A4FD0,
    0xFF3482FF,
    0xFFE85D75,
    0xFFFF8A34,
    0xFF18A999,
    0xFF3E9B55,
    0xFF6C6CE5,
    0xFF707680,
)
