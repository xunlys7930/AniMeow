package com.animeow.app.data.preferences

import org.json.JSONObject

/** Portable profiles and native backups share the same keys and legacy defaults. */
internal object TrackerSettingsCodec {
    fun toJson(value: TrackerSettings): JSONObject = JSONObject()
        .put("hiddenHomeStatusIds", value.hiddenHomeStatusIds.joinToString(","))
        .put("hiddenActionStatusIds", value.hiddenActionStatusIds.joinToString(","))
        .put("progressPlacement", value.progressPlacement.name)
        .put("defaultStartStatus", value.defaultStartStatus)
        .put("lastSelectedStatus", value.lastSelectedStatus)
        .put("lastSubjectType", value.lastSubjectType)
        .put("lastSortKey", value.lastSortKey)
        .put("sortAscending", value.sortAscending)
        .put("groupSeriesOnHome", value.groupSeriesOnHome)
        .put("showStandaloneBookshelf", value.showStandaloneBookshelf)
        .put("homeBottomScope", value.homeBottomScope.storageKey)
        .put("bentoCollectionLayout", value.bentoCollectionLayout.storageKey)
        .put("showContinueWatching", value.showContinueWatching)
        .put("showRecentAdded", value.showRecentAdded)
        .put("homeModuleOrder", value.homeModuleOrder.joinToString(",") { it.storageKey })
        .put("showIncompleteMetadata", value.showIncompleteMetadata)
        .put("showHomeCalendar", value.showHomeCalendar)
        .put("showWeeklyRecap", value.showWeeklyRecap)
        .put("showRecentPlayback", value.showRecentPlayback)
        .put("showSubjectType", value.showSubjectType)
        .put("showSeriesCount", value.showSeriesCount)
        .put("showStatusCount", value.showStatusCount)
        .put("timelineGroupField", value.timelineGroupField.storageKey)
        .put("showUpdateWeekday", value.showUpdateWeekday)
        .put("showCoverStatus", value.showCoverStatus)
        .put("statusBadgeBackground", value.statusBadgeBackground.storageKey)
        .put("statusBadgeCustomColor", value.statusBadgeCustomColor)
        .put("showCoverRating", value.showCoverRating)
        .put("showCoverProgress", value.showCoverProgress)
        .put("showCoverSubjectType", value.showCoverSubjectType)
        .put("showCoverSeriesCount", value.showCoverSeriesCount)
        .put("showCoverUpdateWeekday", value.showCoverUpdateWeekday)
        .put("autoSyncNetworkCovers", value.autoSyncNetworkCovers)
        .put("infoScale", value.infoScale)
        .put("infoOpacity", value.infoOpacity)
        .put("infoTextOpacity", value.infoTextOpacity)
        .put("infoDarkBackgroundOpacity", value.infoDarkBackgroundOpacity)
        .put("infoLightBackgroundOpacity", value.infoLightBackgroundOpacity)
        .put("infoCornerDp", value.infoCornerDp)
        .put("coverCornerDp", value.coverCornerDp)
        .put("coverImageOpacity", value.coverImageOpacity)
        .put("coverSaturation", value.coverSaturation)
        .put("coverFitMode", value.coverFitMode.storageKey)
        .put("coverProgressAlpha", value.coverProgressAlpha)

    fun fromJson(json: JSONObject): TrackerSettings = TrackerSettings(
        hiddenHomeStatusIds = parseStatusIds(json.optString("hiddenHomeStatusIds")),
        hiddenActionStatusIds = parseStatusIds(json.optString("hiddenActionStatusIds")),
        progressPlacement = CardProgressPlacement.fromStorage(json.optString("progressPlacement")),
        defaultStartStatus = json.optString("defaultStartStatus", TRACKER_START_LAST_STATUS),
        lastSelectedStatus = json.optString("lastSelectedStatus", "全部"),
        lastSubjectType = json.optString("lastSubjectType", "all"),
        lastSortKey = json.optString("lastSortKey", "recent"),
        sortAscending = json.optBoolean("sortAscending", false),
        groupSeriesOnHome = json.optBoolean("groupSeriesOnHome", true),
        showStandaloneBookshelf = json.optBoolean("showStandaloneBookshelf", true),
        homeBottomScope = HomeBottomScope.fromStorage(json.optString("homeBottomScope")),
        bentoCollectionLayout = BentoCollectionLayout.fromStorage(json.optString("bentoCollectionLayout")),
        showContinueWatching = json.optBoolean("showContinueWatching", true),
        showRecentAdded = json.optBoolean("showRecentAdded", false),
        homeModuleOrder = com.animeow.app.data.preferences.HomeModule.parse(json.optString("homeModuleOrder")),
        showIncompleteMetadata = json.optBoolean("showIncompleteMetadata", false),
        showHomeCalendar = json.optBoolean("showHomeCalendar", false),
        showWeeklyRecap = json.optBoolean("showWeeklyRecap", false),
        showRecentPlayback = json.optBoolean("showRecentPlayback", true),
        showSubjectType = json.optBoolean("showSubjectType", false),
        showSeriesCount = json.optBoolean("showSeriesCount", true),
        showStatusCount = json.optBoolean("showStatusCount", true),
        timelineGroupField = com.animeow.app.ui.theme.TimelineGroupField.fromStorage(json.optString("timelineGroupField")),
        showUpdateWeekday = json.optBoolean("showUpdateWeekday", false),
        showCoverStatus = json.optBoolean("showCoverStatus", true),
        statusBadgeBackground = StatusBadgeBackground.fromStorage(json.optString("statusBadgeBackground")),
        statusBadgeCustomColor = json.optLong("statusBadgeCustomColor", 0xFF466B59),
        showCoverRating = json.optBoolean("showCoverRating", true),
        showCoverProgress = json.optBoolean("showCoverProgress", true),
        showCoverSubjectType = json.optBoolean("showCoverSubjectType", false),
        showCoverSeriesCount = json.optBoolean("showCoverSeriesCount", true),
        showCoverUpdateWeekday = json.optBoolean("showCoverUpdateWeekday", false),
        autoSyncNetworkCovers = json.optBoolean("autoSyncNetworkCovers", false),
        infoScale = json.optDouble("infoScale", 1.0).toFloat(),
        infoOpacity = json.optDouble("infoOpacity", 0.88).toFloat(),
        infoTextOpacity = json.optDouble("infoTextOpacity", 1.0).toFloat(),
        infoDarkBackgroundOpacity = json.optDouble(
            "infoDarkBackgroundOpacity",
            json.optDouble("infoOpacity", 0.88),
        ).toFloat(),
        infoLightBackgroundOpacity = json.optDouble(
            "infoLightBackgroundOpacity",
            json.optDouble("infoOpacity", 0.88),
        ).toFloat(),
        infoCornerDp = json.optDouble("infoCornerDp", 8.0).toFloat(),
        coverCornerDp = json.optDouble("coverCornerDp", 12.0).toFloat(),
        coverImageOpacity = json.optDouble("coverImageOpacity", 1.0).toFloat(),
        coverSaturation = json.optDouble("coverSaturation", 1.0).toFloat(),
        coverFitMode = CoverFitMode.fromStorage(json.optString("coverFitMode")),
        coverProgressAlpha = json.optDouble("coverProgressAlpha", 0.55).toFloat(),
    ).normalized()

}
