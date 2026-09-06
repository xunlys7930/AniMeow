package com.animeow.app.ui.components

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CustomizableColorSelector(
    color: Long,
    presets: List<Long>,
    onColorChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    dialogTitle: String = "自定义颜色",
) {
    var showPicker by remember { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(presets, key = { it }) { option ->
                val selected = normalizeOpaqueColor(color) == normalizeOpaqueColor(option)
                Box(
                    modifier = Modifier
                        .size(if (selected) 34.dp else 30.dp)
                        .clip(CircleShape)
                        .background(Color(option))
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onColorChanged(normalizeOpaqueColor(option)) },
                )
            }
            item {
                OutlinedButton(onClick = { showPicker = true }) {
                    Icon(
                        Icons.Outlined.Colorize,
                        contentDescription = null,
                        tint = Color(normalizeOpaqueColor(color)),
                    )
                    Text("自定义", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier.size(18.dp).clip(CircleShape)
                    .background(Color(normalizeOpaqueColor(color))),
            )
            Text(
                formatHexColor(color),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showPicker) {
        HsvColorPickerDialog(
            title = dialogTitle,
            initialColor = color,
            onDismiss = { showPicker = false },
            onConfirm = {
                onColorChanged(it)
                showPicker = false
            },
        )
    }
}

@Composable
fun HsvColorPickerDialog(
    title: String,
    initialColor: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    val initialHsv = remember(initialColor) { colorToHsv(initialColor) }
    var hue by remember(initialColor) { mutableStateOf(initialHsv[0]) }
    var saturation by remember(initialColor) { mutableStateOf(initialHsv[1]) }
    var brightness by remember(initialColor) { mutableStateOf(initialHsv[2]) }
    var hex by remember(initialColor) { mutableStateOf(formatHexColor(initialColor)) }
    var hexValid by remember(initialColor) { mutableStateOf(true) }
    val currentColor = hsvToColor(hue, saturation, brightness)

    fun updateHexFromHsv(nextHue: Float = hue, nextSaturation: Float = saturation, nextBrightness: Float = brightness) {
        hex = formatHexColor(hsvToColor(nextHue, nextSaturation, nextBrightness))
        hexValid = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(Color(currentColor)),
                )
                OutlinedTextField(
                    value = hex,
                    onValueChange = { input ->
                        val normalizedInput = input.trim().uppercase(Locale.ROOT).take(7)
                        hex = normalizedInput
                        val parsed = parseHexColor(normalizedInput)
                        hexValid = parsed != null
                        if (parsed != null) {
                            val hsv = colorToHsv(parsed)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    label = { Text("十六进制 #RRGGBB") },
                    supportingText = {
                        Text(if (hexValid) "可精确复制自己的主题色" else "请输入 6 位十六进制颜色")
                    },
                    isError = !hexValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                    ),
                )
                ColorChannelSlider(
                    label = "色相",
                    valueText = "${hue.roundToInt()}°",
                    value = hue,
                    range = 0f..360f,
                    onValueChanged = {
                        hue = it
                        updateHexFromHsv(nextHue = it)
                    },
                )
                ColorChannelSlider(
                    label = "饱和度",
                    valueText = "${(saturation * 100).roundToInt()}%",
                    value = saturation,
                    range = 0f..1f,
                    onValueChanged = {
                        saturation = it
                        updateHexFromHsv(nextSaturation = it)
                    },
                )
                ColorChannelSlider(
                    label = "明度",
                    valueText = "${(brightness * 100).roundToInt()}%",
                    value = brightness,
                    range = 0f..1f,
                    onValueChanged = {
                        brightness = it
                        updateHexFromHsv(nextBrightness = it)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentColor) }, enabled = hexValid) { Text("使用此颜色") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ColorChannelSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChanged: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChanged, valueRange = range)
    }
}

private fun normalizeOpaqueColor(color: Long): Long = 0xFF000000L or (color and 0x00FFFFFFL)

private fun formatHexColor(color: Long): String =
    "#%06X".format(Locale.ROOT, normalizeOpaqueColor(color) and 0x00FFFFFFL)

private fun parseHexColor(value: String): Long? {
    val clean = value.removePrefix("#")
    if (clean.length != 6 || clean.any { it !in "0123456789ABCDEFabcdef" }) return null
    return runCatching { 0xFF000000L or clean.toLong(16) }.getOrNull()
}

private fun colorToHsv(color: Long): FloatArray = FloatArray(3).also { hsv ->
    AndroidColor.colorToHSV(normalizeOpaqueColor(color).toInt(), hsv)
}

private fun hsvToColor(hue: Float, saturation: Float, brightness: Float): Long =
    AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFFFL
