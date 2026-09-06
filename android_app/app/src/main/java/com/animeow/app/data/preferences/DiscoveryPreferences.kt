package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.animeow.app.ui.theme.ContentDensity
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.discoveryDataStore by preferencesDataStore(name = "discovery_preferences")

enum class BangumiApiMode(
    val storageKey: String,
    val label: String,
    val description: String,
) {
    AUTO("auto", "自动探测", "并发探测，官方不可用后本次运行优先代理"),
    OFFICIAL_FIRST("official_first", "官方优先", "官方失败后自动切换代理"),
    PROXY_FIRST("proxy_first", "代理优先", "代理失败后自动切换官方"),
    OFFICIAL_ONLY("official_only", "仅官方", "只访问 Bangumi 官方接口"),
    PROXY_ONLY("proxy_only", "仅代理", "只访问自定义代理接口");

    companion object {
        fun fromStorage(value: String?): BangumiApiMode = entries.firstOrNull { it.storageKey == value } ?: AUTO
    }
}

data class DiscoveryNetworkSettings(
    val bangumiApiMode: BangumiApiMode = BangumiApiMode.AUTO,
    val bangumiApiProxyBase: String = DEFAULT_BANGUMI_API_PROXY,
    val useBangumiImageProxy: Boolean = true,
    val bangumiImageProxyBase: String = DEFAULT_BANGUMI_IMAGE_PROXY,
    val showServerSource: Boolean = false,
    val includeServerInAllSources: Boolean = false,
    val display: DiscoveryDisplayConfig = DiscoveryDisplayConfig(),
)

enum class DiscoveryResultLayout(
    val storageKey: String,
    val displayName: String,
) {
    LIST("list", "信息列表"),
    GRID("grid", "封面网格");

    companion object {
        fun fromStorage(value: String?): DiscoveryResultLayout =
            entries.firstOrNull { it.storageKey == value } ?: LIST
    }
}

data class DiscoveryDisplayConfig(
    val layout: DiscoveryResultLayout = DiscoveryResultLayout.LIST,
    val gridColumns: Int = 3,
    val density: ContentDensity = ContentDensity.COMFORTABLE,
    val showSource: Boolean = true,
    val showScore: Boolean = true,
    val showAirDate: Boolean = true,
    val showTags: Boolean = true,
)

class DiscoveryPreferences(context: Context) {
    private val dataStore = context.applicationContext.discoveryDataStore

    val settings: Flow<DiscoveryNetworkSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            DiscoveryNetworkSettings(
                bangumiApiMode = BangumiApiMode.fromStorage(values[Keys.API_MODE]),
                bangumiApiProxyBase = normalizeBase(values[Keys.API_PROXY], DEFAULT_BANGUMI_API_PROXY),
                useBangumiImageProxy = values[Keys.IMAGE_PROXY_ENABLED] ?: true,
                bangumiImageProxyBase = normalizeBase(values[Keys.IMAGE_PROXY], DEFAULT_BANGUMI_IMAGE_PROXY),
                showServerSource = values[Keys.SHOW_SERVER_SOURCE] ?: false,
                includeServerInAllSources = values[Keys.INCLUDE_SERVER_IN_ALL] ?: false,
                display = DiscoveryDisplayConfig(
                    layout = DiscoveryResultLayout.fromStorage(values[Keys.DISPLAY_LAYOUT]),
                    gridColumns = (values[Keys.DISPLAY_GRID_COLUMNS] ?: 3).coerceIn(2, 5),
                    density = ContentDensity.fromStorage(values[Keys.DISPLAY_DENSITY]),
                    showSource = values[Keys.DISPLAY_SHOW_SOURCE] ?: true,
                    showScore = values[Keys.DISPLAY_SHOW_SCORE] ?: true,
                    showAirDate = values[Keys.DISPLAY_SHOW_AIR_DATE] ?: true,
                    showTags = values[Keys.DISPLAY_SHOW_TAGS] ?: true,
                ),
            )
        }

    suspend fun snapshot(): DiscoveryNetworkSettings = settings.first()

    suspend fun save(settings: DiscoveryNetworkSettings) {
        dataStore.edit { values ->
            values[Keys.API_MODE] = settings.bangumiApiMode.storageKey
            values[Keys.API_PROXY] = normalizeBase(settings.bangumiApiProxyBase, DEFAULT_BANGUMI_API_PROXY)
            values[Keys.IMAGE_PROXY_ENABLED] = settings.useBangumiImageProxy
            values[Keys.IMAGE_PROXY] = normalizeBase(settings.bangumiImageProxyBase, DEFAULT_BANGUMI_IMAGE_PROXY)
            values[Keys.SHOW_SERVER_SOURCE] = settings.showServerSource
            values[Keys.INCLUDE_SERVER_IN_ALL] = settings.showServerSource && settings.includeServerInAllSources
            values[Keys.DISPLAY_LAYOUT] = settings.display.layout.storageKey
            values[Keys.DISPLAY_GRID_COLUMNS] = settings.display.gridColumns.coerceIn(2, 5)
            values[Keys.DISPLAY_DENSITY] = settings.display.density.storageKey
            values[Keys.DISPLAY_SHOW_SOURCE] = settings.display.showSource
            values[Keys.DISPLAY_SHOW_SCORE] = settings.display.showScore
            values[Keys.DISPLAY_SHOW_AIR_DATE] = settings.display.showAirDate
            values[Keys.DISPLAY_SHOW_TAGS] = settings.display.showTags
        }
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    suspend fun resetNetwork() {
        val display = snapshot().display
        save(DiscoveryNetworkSettings(display = display))
    }

    suspend fun resetDisplay() {
        save(snapshot().copy(display = DiscoveryDisplayConfig()))
    }

    private object Keys {
        val API_MODE = stringPreferencesKey("bangumi_api_mode")
        val API_PROXY = stringPreferencesKey("bangumi_api_proxy")
        val IMAGE_PROXY_ENABLED = booleanPreferencesKey("bangumi_image_proxy_enabled")
        val IMAGE_PROXY = stringPreferencesKey("bangumi_image_proxy")
        val SHOW_SERVER_SOURCE = booleanPreferencesKey("show_server_source")
        val INCLUDE_SERVER_IN_ALL = booleanPreferencesKey("include_server_in_all")
        val DISPLAY_LAYOUT = stringPreferencesKey("display_layout")
        val DISPLAY_GRID_COLUMNS = intPreferencesKey("display_grid_columns")
        val DISPLAY_DENSITY = stringPreferencesKey("display_density")
        val DISPLAY_SHOW_SOURCE = booleanPreferencesKey("display_show_source")
        val DISPLAY_SHOW_SCORE = booleanPreferencesKey("display_show_score")
        val DISPLAY_SHOW_AIR_DATE = booleanPreferencesKey("display_show_air_date")
        val DISPLAY_SHOW_TAGS = booleanPreferencesKey("display_show_tags")
    }
}

const val DEFAULT_BANGUMI_API_PROXY = "https://api-bgm.xunlys.top"
const val DEFAULT_BANGUMI_IMAGE_PROXY = "https://img-bgm.xunlys.top"

private fun normalizeBase(value: String?, fallback: String): String =
    value?.trim()?.trimEnd('/')?.takeIf(String::isNotEmpty) ?: fallback
