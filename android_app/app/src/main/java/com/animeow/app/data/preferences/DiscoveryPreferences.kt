package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
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
    AUTO("auto", "智能切换（推荐）", "优先直连官方，连接受阻时自动改走备用线路"),
    OFFICIAL_FIRST("official_first", "官方优先", "官方连接失败后自动切换备用线路"),
    PROXY_FIRST("proxy_first", "代理优先", "备用线路失败后自动切换官方"),
    OFFICIAL_ONLY("official_only", "仅官方", "只访问 Bangumi 官方站点"),
    PROXY_ONLY("proxy_only", "仅代理", "只访问自定义备用线路");

    companion object {
        fun fromStorage(value: String?): BangumiApiMode = entries.firstOrNull { it.storageKey == value } ?: AUTO
    }
}

enum class MetadataLookupStep(val storageKey: String, val label: String) {
    SERVER("server", "资料库服务器"),
    BANGUMI("bangumi", "Bangumi 官方"),
    ANILIST("anilist", "AniList 官方"),
    KITSU("kitsu", "Kitsu 官方"),
    TVMAZE("tvmaze", "TVmaze 官方"),
    PROXY("proxy", "备用代理线路");

    companion object {
        fun fromStorage(value: String?): List<MetadataLookupStep> = normalize(
            value.orEmpty().split(',').mapNotNull { key -> entries.find { it.storageKey == key } },
        )
        fun normalize(value: List<MetadataLookupStep>): List<MetadataLookupStep> {
            val unique = value.distinct()
            // Upgrade the old default without changing a deliberately customized priority.
            val oldDefault = listOf(SERVER, BANGUMI, ANILIST, PROXY)
            return if (unique == oldDefault) entries.toList() else (unique + entries).distinct()
        }
        fun enabledFromStorage(value: String?): Set<MetadataLookupStep> = if (value == null) entries.toSet()
            else value.split(',').mapNotNull { key -> entries.find { it.storageKey == key } }.toSet().ifEmpty { setOf(SERVER) }
    }
}

enum class MetadataLookupStrategy(val storageKey: String, val label: String) {
    RACE("race", "并发优先 · 更快"),
    ORDERED("ordered", "依次查找 · 省流量");
    companion object {
        fun fromStorage(value: String?) = entries.find { it.storageKey == value } ?: RACE
    }
}

const val SHARED_PROXY_QUOTA_NOTICE = "代理额度由所有用户共享，每日刷新。服务器模式优先复用已保存的资料；手机直连模式可自行选择官方或代理。额度用尽后请稍后重试。"

enum class PublicDataRoute(val label: String) {
    SERVER("服务器优先（推荐）"), DIRECT("手机直连（高级）");
    companion object { fun fromStorage(value: String?) = entries.firstOrNull { it.name == value } ?: SERVER }
}

data class DiscoveryNetworkSettings(
    val publicDataRoute: PublicDataRoute = PublicDataRoute.SERVER,
    val bangumiApiMode: BangumiApiMode = BangumiApiMode.AUTO,
    val bangumiApiProxyBase: String = DEFAULT_BANGUMI_API_PROXY,
    val useBangumiImageProxy: Boolean = true,
    val bangumiImageProxyBase: String = DEFAULT_BANGUMI_IMAGE_PROXY,
    val metadataLookupOrder: List<MetadataLookupStep> = MetadataLookupStep.entries.toList(),
    val enabledMetadataSources: Set<MetadataLookupStep> = MetadataLookupStep.entries.toSet(),
    val metadataLookupStrategy: MetadataLookupStrategy = MetadataLookupStrategy.RACE,
    val metadataTimeoutSeconds: Int = 6,
    val aniListProxyUrl: String = "",
    val allowAniListProxy: Boolean = true,
    val contributePublicMetadata: Boolean = true,
    val showServerSource: Boolean = true,
    val includeServerInAllSources: Boolean = true,
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
            val upgradeHiddenServerDefault = values[Keys.SERVER_DEFAULTS_VERSION] == null && values[Keys.SHOW_SERVER_SOURCE] != true
            DiscoveryNetworkSettings(
                publicDataRoute = PublicDataRoute.fromStorage(values[Keys.PUBLIC_DATA_ROUTE]),
                bangumiApiMode = BangumiApiMode.fromStorage(values[Keys.API_MODE]),
                bangumiApiProxyBase = normalizeBase(values[Keys.API_PROXY], DEFAULT_BANGUMI_API_PROXY),
                useBangumiImageProxy = values[Keys.IMAGE_PROXY_ENABLED] ?: true,
                bangumiImageProxyBase = normalizeBase(values[Keys.IMAGE_PROXY], DEFAULT_BANGUMI_IMAGE_PROXY),
                metadataLookupOrder = MetadataLookupStep.fromStorage(values[Keys.METADATA_ORDER]),
                enabledMetadataSources = MetadataLookupStep.enabledFromStorage(values[Keys.METADATA_ENABLED]),
                metadataLookupStrategy = MetadataLookupStrategy.fromStorage(values[Keys.METADATA_STRATEGY]),
                metadataTimeoutSeconds = (values[Keys.METADATA_TIMEOUT] ?: 6).coerceIn(3, 12),
                aniListProxyUrl = values[Keys.ANILIST_PROXY].orEmpty(),
                allowAniListProxy = values[Keys.ANILIST_PROXY_ENABLED] ?: true,
                contributePublicMetadata = values[Keys.CONTRIBUTE_METADATA] ?: true,
                showServerSource = upgradeHiddenServerDefault || (values[Keys.SHOW_SERVER_SOURCE] ?: true),
                includeServerInAllSources = upgradeHiddenServerDefault || (values[Keys.INCLUDE_SERVER_IN_ALL] ?: true),
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
            values[Keys.PUBLIC_DATA_ROUTE] = settings.publicDataRoute.name
            values[Keys.SERVER_DEFAULTS_VERSION] = 1
            values[Keys.API_MODE] = settings.bangumiApiMode.storageKey
            values[Keys.API_PROXY] = normalizeBase(settings.bangumiApiProxyBase, DEFAULT_BANGUMI_API_PROXY)
            values[Keys.IMAGE_PROXY_ENABLED] = settings.useBangumiImageProxy
            values[Keys.IMAGE_PROXY] = normalizeBase(settings.bangumiImageProxyBase, DEFAULT_BANGUMI_IMAGE_PROXY)
            values[Keys.METADATA_ORDER] = MetadataLookupStep.normalize(settings.metadataLookupOrder).joinToString(",") { it.storageKey }
            values[Keys.METADATA_ENABLED] = settings.enabledMetadataSources.ifEmpty { setOf(MetadataLookupStep.SERVER) }.joinToString(",") { it.storageKey }
            values[Keys.METADATA_STRATEGY] = settings.metadataLookupStrategy.storageKey
            values[Keys.METADATA_TIMEOUT] = settings.metadataTimeoutSeconds.coerceIn(3, 12)
            values[Keys.ANILIST_PROXY] = settings.aniListProxyUrl.trim().trimEnd('/')
            values[Keys.ANILIST_PROXY_ENABLED] = settings.allowAniListProxy
            values[Keys.CONTRIBUTE_METADATA] = settings.contributePublicMetadata
            values[Keys.SHOW_SERVER_SOURCE] = settings.showServerSource
            values[Keys.INCLUDE_SERVER_IN_ALL] = settings.showServerSource && settings.includeServerInAllSources
            values.writeDisplay(settings.display)
        }
    }

    suspend fun saveDisplay(display: DiscoveryDisplayConfig) {
        dataStore.edit { it.writeDisplay(display) }
    }

    private fun MutablePreferences.writeDisplay(display: DiscoveryDisplayConfig) {
        this[Keys.DISPLAY_LAYOUT] = display.layout.storageKey
        this[Keys.DISPLAY_GRID_COLUMNS] = display.gridColumns.coerceIn(2, 5)
        this[Keys.DISPLAY_DENSITY] = display.density.storageKey
        this[Keys.DISPLAY_SHOW_SOURCE] = display.showSource
        this[Keys.DISPLAY_SHOW_SCORE] = display.showScore
        this[Keys.DISPLAY_SHOW_AIR_DATE] = display.showAirDate
        this[Keys.DISPLAY_SHOW_TAGS] = display.showTags
    }

    suspend fun reset() {
        dataStore.edit { it.clear() }
    }

    suspend fun resetNetwork() {
        val display = snapshot().display
        save(DiscoveryNetworkSettings(display = display))
    }

    suspend fun resetDisplay() {
        saveDisplay(DiscoveryDisplayConfig())
    }

    private object Keys {
        val SERVER_DEFAULTS_VERSION = intPreferencesKey("server_defaults_version")
        val METADATA_ORDER = stringPreferencesKey("metadata_lookup_order")
        val METADATA_ENABLED = stringPreferencesKey("metadata_enabled_sources")
        val METADATA_STRATEGY = stringPreferencesKey("metadata_lookup_strategy")
        val METADATA_TIMEOUT = intPreferencesKey("metadata_timeout_seconds")
        val ANILIST_PROXY = stringPreferencesKey("anilist_proxy_url")
        val ANILIST_PROXY_ENABLED = booleanPreferencesKey("anilist_proxy_enabled")
        val CONTRIBUTE_METADATA = booleanPreferencesKey("contribute_public_metadata")
        val PUBLIC_DATA_ROUTE = stringPreferencesKey("public_data_route")
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
