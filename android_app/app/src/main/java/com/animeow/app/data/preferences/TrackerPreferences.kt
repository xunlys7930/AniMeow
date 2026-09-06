package com.animeow.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.animeow.app.data.local.normalizeSubjectTypeFilter
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.trackerDataStore by preferencesDataStore(name = "tracker_preferences")

const val TRACKER_START_LAST_STATUS = "__last_status__"
const val TRACKER_START_ALL_STATUS = "__all_status__"

enum class HomeBottomScope(
    val storageKey: String,
    val displayName: String,
) {
    ANIME_ONLY("anime_only", "仅动画"),
    ALL("all", "全部作品");

    companion object {
        fun fromStorage(value: String?): HomeBottomScope =
            entries.firstOrNull { it.storageKey == value } ?: ANIME_ONLY
    }
}

enum class BentoCollectionLayout(
    val storageKey: String,
    val displayName: String,
) {
    GRID("grid", "网格"),
    LIST("list", "列表"),
    COMPACT("compact", "紧凑");

    companion object {
        fun fromStorage(value: String?): BentoCollectionLayout =
            entries.firstOrNull { it.storageKey == value } ?: GRID
    }
}

enum class CoverFitMode(
    val storageKey: String,
    val displayName: String,
) {
    CROP("crop", "裁切铺满"),
    FIT("fit", "完整显示"),
    STRETCH("stretch", "拉伸填满");

    companion object {
        fun fromStorage(value: String?): CoverFitMode =
            entries.firstOrNull { it.storageKey == value } ?: CROP
    }
}

enum class StatusBadgeBackground(val storageKey: String, val displayName: String) {
    SURFACE("surface", "跟随主题"),
    STATUS("status", "跟随状态色"),
    CUSTOM("custom", "自选颜色");

    companion object {
        fun fromStorage(value: String?): StatusBadgeBackground =
            entries.firstOrNull { it.storageKey == value } ?: SURFACE
    }
}

data class TrackerSettings(
    val defaultStartStatus: String = TRACKER_START_LAST_STATUS,
    val lastSelectedStatus: String = "全部",
    val lastSubjectType: String = "all",
    val lastSortKey: String = "recent",
    val sortAscending: Boolean = false,
    val groupSeriesOnHome: Boolean = true,
    val showStandaloneBookshelf: Boolean = true,
    val homeBottomScope: HomeBottomScope = HomeBottomScope.ANIME_ONLY,
    val bentoCollectionLayout: BentoCollectionLayout = BentoCollectionLayout.GRID,
    val showContinueWatching: Boolean = true,
    val showRecentAdded: Boolean = false,
    val showSubjectType: Boolean = false,
    val showSeriesCount: Boolean = true,
    val showUpdateWeekday: Boolean = false,
    val showCoverStatus: Boolean = true,
    val statusBadgeBackground: StatusBadgeBackground = StatusBadgeBackground.SURFACE,
    val statusBadgeCustomColor: Long = 0xFF466B59,
    val showCoverRating: Boolean = true,
    val showCoverProgress: Boolean = true,
    val showCoverSubjectType: Boolean = false,
    val showCoverSeriesCount: Boolean = true,
    val showCoverUpdateWeekday: Boolean = false,
    val showStatusCount: Boolean = true,
    val timelineGroupField: com.animeow.app.ui.theme.TimelineGroupField = com.animeow.app.ui.theme.TimelineGroupField.FOLLOW_SORT,
    val autoSyncNetworkCovers: Boolean = false,
    val infoScale: Float = 1f,
    val infoOpacity: Float = 0.88f,
    val infoTextOpacity: Float = 1f,
    val infoDarkBackgroundOpacity: Float = 0.88f,
    val infoLightBackgroundOpacity: Float = 0.88f,
    val infoCornerDp: Float = 8f,
    val coverCornerDp: Float = 12f,
    val coverImageOpacity: Float = 1f,
    val coverSaturation: Float = 1f,
    val coverFitMode: CoverFitMode = CoverFitMode.CROP,
) {
    fun normalized(): TrackerSettings = copy(
        defaultStartStatus = defaultStartStatus.trim().ifEmpty { TRACKER_START_LAST_STATUS },
        lastSelectedStatus = lastSelectedStatus.trim().ifEmpty { "全部" },
        lastSubjectType = normalizeSubjectTypeFilter(lastSubjectType),
        lastSortKey = lastSortKey.trim().ifEmpty { "recent" },
        infoScale = infoScale.coerceIn(0.75f, 1.35f),
        infoOpacity = infoOpacity.coerceIn(0.35f, 1f),
        infoTextOpacity = infoTextOpacity.coerceIn(0.35f, 1f),
        infoDarkBackgroundOpacity = infoDarkBackgroundOpacity.coerceIn(0f, 1f),
        infoLightBackgroundOpacity = infoLightBackgroundOpacity.coerceIn(0f, 1f),
        infoCornerDp = infoCornerDp.coerceIn(0f, 24f),
        coverCornerDp = coverCornerDp.coerceIn(0f, 32f),
        coverImageOpacity = coverImageOpacity.coerceIn(0.35f, 1f),
        coverSaturation = coverSaturation.coerceIn(0f, 1.5f),
        statusBadgeCustomColor = (statusBadgeCustomColor and 0x00FFFFFFL) or 0xFF000000L,
    )
}

class TrackerPreferences(context: Context) {
    private val dataStore = context.applicationContext.trackerDataStore

    val settings: Flow<TrackerSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { values ->
            val legacyInfoOpacity = values[Keys.INFO_OPACITY] ?: 0.88f
            TrackerSettings(
                defaultStartStatus = values[Keys.DEFAULT_START_STATUS] ?: TRACKER_START_LAST_STATUS,
                lastSelectedStatus = values[Keys.LAST_SELECTED_STATUS] ?: "全部",
                lastSubjectType = values[Keys.LAST_SUBJECT_TYPE] ?: "all",
                lastSortKey = values[Keys.LAST_SORT_KEY] ?: "recent",
                sortAscending = values[Keys.SORT_ASCENDING] ?: false,
                groupSeriesOnHome = values[Keys.GROUP_SERIES_ON_HOME] ?: true,
                showStandaloneBookshelf = values[Keys.SHOW_STANDALONE_BOOKSHELF] ?: true,
                homeBottomScope = HomeBottomScope.fromStorage(values[Keys.HOME_BOTTOM_SCOPE]),
                bentoCollectionLayout = BentoCollectionLayout.fromStorage(values[Keys.BENTO_COLLECTION_LAYOUT]),
                showContinueWatching = values[Keys.SHOW_CONTINUE_WATCHING] ?: true,
                showRecentAdded = values[Keys.SHOW_RECENT_ADDED] ?: false,
                showSubjectType = values[Keys.SHOW_SUBJECT_TYPE] ?: false,
                showSeriesCount = values[Keys.SHOW_SERIES_COUNT] ?: true,
                showUpdateWeekday = values[Keys.SHOW_UPDATE_WEEKDAY] ?: false,
                showCoverStatus = values[Keys.SHOW_COVER_STATUS] ?: true,
                statusBadgeBackground = StatusBadgeBackground.fromStorage(values[Keys.STATUS_BADGE_BACKGROUND]),
                statusBadgeCustomColor = values[Keys.STATUS_BADGE_CUSTOM_COLOR] ?: 0xFF466B59,
                showCoverRating = values[Keys.SHOW_COVER_RATING] ?: true,
                showCoverProgress = values[Keys.SHOW_COVER_PROGRESS] ?: true,
                showCoverSubjectType = values[Keys.SHOW_COVER_SUBJECT_TYPE] ?: false,
                showCoverSeriesCount = values[Keys.SHOW_COVER_SERIES_COUNT] ?: true,
                showCoverUpdateWeekday = values[Keys.SHOW_COVER_UPDATE_WEEKDAY] ?: false,
                showStatusCount = values[Keys.SHOW_STATUS_COUNT] ?: true,
                timelineGroupField = com.animeow.app.ui.theme.TimelineGroupField.fromStorage(values[Keys.TIMELINE_GROUP_FIELD]),
                autoSyncNetworkCovers = values[Keys.AUTO_SYNC_NETWORK_COVERS] ?: false,
                infoScale = values[Keys.INFO_SCALE] ?: 1f,
                infoOpacity = legacyInfoOpacity,
                infoTextOpacity = values[Keys.INFO_TEXT_OPACITY] ?: 1f,
                infoDarkBackgroundOpacity = values[Keys.INFO_DARK_BACKGROUND_OPACITY] ?: legacyInfoOpacity,
                infoLightBackgroundOpacity = values[Keys.INFO_LIGHT_BACKGROUND_OPACITY] ?: legacyInfoOpacity,
                infoCornerDp = values[Keys.INFO_CORNER_DP] ?: 8f,
                coverCornerDp = values[Keys.COVER_CORNER_DP] ?: 12f,
                coverImageOpacity = values[Keys.COVER_IMAGE_OPACITY] ?: 1f,
                coverSaturation = values[Keys.COVER_SATURATION] ?: 1f,
                coverFitMode = CoverFitMode.fromStorage(values[Keys.COVER_FIT_MODE]),
            ).normalized()
        }

    suspend fun snapshot(): TrackerSettings = settings.first()

    suspend fun save(value: TrackerSettings) {
        val normalized = value.normalized()
        dataStore.edit { values ->
            values[Keys.DEFAULT_START_STATUS] = normalized.defaultStartStatus
            values[Keys.LAST_SELECTED_STATUS] = normalized.lastSelectedStatus
            values[Keys.LAST_SUBJECT_TYPE] = normalized.lastSubjectType
            values[Keys.LAST_SORT_KEY] = normalized.lastSortKey
            values[Keys.SORT_ASCENDING] = normalized.sortAscending
            values[Keys.GROUP_SERIES_ON_HOME] = normalized.groupSeriesOnHome
            values[Keys.SHOW_STANDALONE_BOOKSHELF] = normalized.showStandaloneBookshelf
            values[Keys.HOME_BOTTOM_SCOPE] = normalized.homeBottomScope.storageKey
            values[Keys.BENTO_COLLECTION_LAYOUT] = normalized.bentoCollectionLayout.storageKey
            values[Keys.SHOW_CONTINUE_WATCHING] = normalized.showContinueWatching
            values[Keys.SHOW_RECENT_ADDED] = normalized.showRecentAdded
            values[Keys.SHOW_SUBJECT_TYPE] = normalized.showSubjectType
            values[Keys.SHOW_SERIES_COUNT] = normalized.showSeriesCount
            values[Keys.SHOW_UPDATE_WEEKDAY] = normalized.showUpdateWeekday
            values[Keys.SHOW_COVER_STATUS] = normalized.showCoverStatus
            values[Keys.STATUS_BADGE_BACKGROUND] = normalized.statusBadgeBackground.storageKey
            values[Keys.STATUS_BADGE_CUSTOM_COLOR] = normalized.statusBadgeCustomColor
            values[Keys.SHOW_COVER_RATING] = normalized.showCoverRating
            values[Keys.SHOW_COVER_PROGRESS] = normalized.showCoverProgress
            values[Keys.SHOW_COVER_SUBJECT_TYPE] = normalized.showCoverSubjectType
            values[Keys.SHOW_COVER_SERIES_COUNT] = normalized.showCoverSeriesCount
            values[Keys.SHOW_COVER_UPDATE_WEEKDAY] = normalized.showCoverUpdateWeekday
            values[Keys.SHOW_STATUS_COUNT] = normalized.showStatusCount
            values[Keys.TIMELINE_GROUP_FIELD] = normalized.timelineGroupField.storageKey
            values[Keys.AUTO_SYNC_NETWORK_COVERS] = normalized.autoSyncNetworkCovers
            values[Keys.INFO_SCALE] = normalized.infoScale
            values[Keys.INFO_OPACITY] = normalized.infoOpacity
            values[Keys.INFO_TEXT_OPACITY] = normalized.infoTextOpacity
            values[Keys.INFO_DARK_BACKGROUND_OPACITY] = normalized.infoDarkBackgroundOpacity
            values[Keys.INFO_LIGHT_BACKGROUND_OPACITY] = normalized.infoLightBackgroundOpacity
            values[Keys.INFO_CORNER_DP] = normalized.infoCornerDp
            values[Keys.COVER_CORNER_DP] = normalized.coverCornerDp
            values[Keys.COVER_IMAGE_OPACITY] = normalized.coverImageOpacity
            values[Keys.COVER_SATURATION] = normalized.coverSaturation
            values[Keys.COVER_FIT_MODE] = normalized.coverFitMode.storageKey
        }
    }

    suspend fun setDefaultStartStatus(value: String) {
        dataStore.edit { it[Keys.DEFAULT_START_STATUS] = value.trim().ifEmpty { TRACKER_START_LAST_STATUS } }
    }

    suspend fun setLastSelectedStatus(value: String) {
        dataStore.edit { it[Keys.LAST_SELECTED_STATUS] = value.trim().ifEmpty { "全部" } }
    }

    suspend fun setLastSubjectType(value: String) {
        dataStore.edit { it[Keys.LAST_SUBJECT_TYPE] = normalizeSubjectTypeFilter(value) }
    }

    suspend fun setLastSort(key: String, ascending: Boolean) {
        dataStore.edit { values ->
            values[Keys.LAST_SORT_KEY] = key.trim().ifEmpty { "recent" }
            values[Keys.SORT_ASCENDING] = ascending
        }
    }

    private object Keys {
        val DEFAULT_START_STATUS = stringPreferencesKey("default_start_status")
        val LAST_SELECTED_STATUS = stringPreferencesKey("last_selected_status")
        val LAST_SUBJECT_TYPE = stringPreferencesKey("last_subject_type")
        val LAST_SORT_KEY = stringPreferencesKey("last_sort_key")
        val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
        val GROUP_SERIES_ON_HOME = booleanPreferencesKey("group_series_on_home")
        val SHOW_STANDALONE_BOOKSHELF = booleanPreferencesKey("show_standalone_bookshelf")
        val HOME_BOTTOM_SCOPE = stringPreferencesKey("home_bottom_scope")
        val BENTO_COLLECTION_LAYOUT = stringPreferencesKey("bento_collection_layout")
        val SHOW_CONTINUE_WATCHING = booleanPreferencesKey("show_continue_watching")
        val SHOW_RECENT_ADDED = booleanPreferencesKey("show_recent_added")
        val SHOW_SUBJECT_TYPE = booleanPreferencesKey("show_subject_type")
        val SHOW_SERIES_COUNT = booleanPreferencesKey("show_series_count")
        val SHOW_UPDATE_WEEKDAY = booleanPreferencesKey("show_update_weekday")
        val SHOW_COVER_STATUS = booleanPreferencesKey("show_cover_status")
        val STATUS_BADGE_BACKGROUND = stringPreferencesKey("status_badge_background")
        val STATUS_BADGE_CUSTOM_COLOR = longPreferencesKey("status_badge_custom_color")
        val SHOW_COVER_RATING = booleanPreferencesKey("show_cover_rating")
        val SHOW_COVER_PROGRESS = booleanPreferencesKey("show_cover_progress")
        val SHOW_COVER_SUBJECT_TYPE = booleanPreferencesKey("show_cover_subject_type")
        val SHOW_COVER_SERIES_COUNT = booleanPreferencesKey("show_cover_series_count")
        val SHOW_COVER_UPDATE_WEEKDAY = booleanPreferencesKey("show_cover_update_weekday")
        val SHOW_STATUS_COUNT = booleanPreferencesKey("show_status_count")
        val TIMELINE_GROUP_FIELD = stringPreferencesKey("timeline_group_field")
        val AUTO_SYNC_NETWORK_COVERS = booleanPreferencesKey("auto_sync_network_covers")
        val INFO_SCALE = floatPreferencesKey("info_scale")
        val INFO_OPACITY = floatPreferencesKey("info_opacity")
        val INFO_TEXT_OPACITY = floatPreferencesKey("info_text_opacity")
        val INFO_DARK_BACKGROUND_OPACITY = floatPreferencesKey("info_dark_background_opacity")
        val INFO_LIGHT_BACKGROUND_OPACITY = floatPreferencesKey("info_light_background_opacity")
        val INFO_CORNER_DP = floatPreferencesKey("info_corner_dp")
        val COVER_CORNER_DP = floatPreferencesKey("cover_corner_dp")
        val COVER_IMAGE_OPACITY = floatPreferencesKey("cover_image_opacity")
        val COVER_SATURATION = floatPreferencesKey("cover_saturation")
        val COVER_FIT_MODE = stringPreferencesKey("cover_fit_mode")
    }
}
