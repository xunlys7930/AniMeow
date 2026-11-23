package com.animeow.app.ui.discovery

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.animeow.app.data.preferences.DiscoveryNetworkSettings
import com.animeow.app.data.preferences.DiscoveryPreferences
import com.animeow.app.ui.screens.DiscoveryNetworkSheet
import kotlinx.coroutines.launch

@Composable
fun MetadataSourcesButton(enabled: Boolean = true) {
    val context = LocalContext.current
    val preferences = remember { DiscoveryPreferences(context) }
    val settings by preferences.settings.collectAsStateWithLifecycle(DiscoveryNetworkSettings())
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }, enabled = enabled) { Text("数据源与顺序 · ${settings.metadataLookupStrategy.label.substringBefore('·').trim()}") }
    if (open) DiscoveryNetworkSheet(settings,
        onSave = { scope.launch { preferences.save(it); open = false } },
        onReset = { scope.launch { preferences.resetNetwork(); open = false } },
        onDismiss = { open = false })
}
