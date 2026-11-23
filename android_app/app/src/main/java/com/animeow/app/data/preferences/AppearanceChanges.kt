package com.animeow.app.data.preferences

import com.animeow.app.ui.theme.*
import org.json.JSONObject
import kotlin.math.roundToInt

data class AppearanceChange(val category: String, val label: String, val before: String, val after: String)

internal val appearanceSections = appearanceFields.filter { it.resettable }.groupBy { it.category }
    .mapValues { (_, fields) -> fields.mapTo(linkedSetOf()) { it.key } }
internal val trackerSections = trackerFields.filter { it.resettable }.groupBy { it.category }
    .mapValues { (_, fields) -> fields.mapTo(linkedSetOf()) { it.key } }

fun appearanceChanges(before: ConfigurationBundle, after: ConfigurationBundle): List<AppearanceChange> {
    val left = ConfigurationBundleCodec.toJson(before)
    val right = ConfigurationBundleCodec.toJson(after)
    return buildList {
        personalizationFields.forEach { (section, fields) ->
            val a = left.optJSONObject(section) ?: return@forEach
            val b = right.optJSONObject(section) ?: return@forEach
            fields.filter { it.compared }.forEach { field ->
                if (a.opt(field.key)?.toString() != b.opt(field.key)?.toString()) {
                    add(AppearanceChange(field.category, field.label, valueLabel(field.key, a.opt(field.key)), valueLabel(field.key, b.opt(field.key))))
                }
            }
        }
        // Old profiles omit newer sections; omission preserves the current device's preferences.
        for (section in listOf("discovery", "calendar", "editor", "branding")) {
            val a = left.optJSONObject(section) ?: continue
            val b = right.optJSONObject(section) ?: continue
            if (a.toString() != b.toString()) add(AppearanceChange(section, personalizationCategoryTitle(section), "当前配置", "配置已调整"))
        }
    }
}

internal fun resetAppearanceCategory(current: ConfigurationBundle, category: String): ConfigurationBundle {
    val json = ConfigurationBundleCodec.toJson(current)
    val defaults = ConfigurationBundleCodec.toJson(ConfigurationBundle(AppearanceSettings(), tracker = TrackerSettings()))
    personalizationFields.forEach { (section, fields) ->
        val target = json.optJSONObject(section) ?: return@forEach
        val fallback = defaults.getJSONObject(section)
        fields.filter { it.resettable && it.category == category }.forEach { target.put(it.key, fallback.opt(it.key)) }
    }
    return ConfigurationBundleCodec.fromJson(json)
}

private fun valueLabel(key: String, value: Any?): String = when {
    key == "backgroundImagePath" -> if (value == null || value == JSONObject.NULL || value.toString().isBlank()) "未设置" else "本机壁纸"
    key == "frontendMode" -> FrontendMode.fromStorage(value?.toString()).displayName
    key == "backgroundScaleMode" -> com.animeow.app.data.background.BackgroundScaleMode.fromStorage(value?.toString()).displayName
    key == "defaultStartStatus" -> when (value?.toString()) {
        TRACKER_START_LAST_STATUS -> "记住上次状态"
        TRACKER_START_ALL_STATUS -> "全部"
        else -> value.toString()
    }
    key == "sortAscending" -> if (value == true) "升序" else "降序"
    key == "coverProgressAlpha" && value is Number -> "${(value.toDouble() * 100).roundToInt()}%"
    key == "detailModuleOrder" -> DetailModule.parseOrder(value?.toString()).joinToString(" → ") { it.displayName }
    key == "hiddenDetailModules" -> DetailModule.parseSet(value?.toString()).joinToString("、") { it.displayName }.ifEmpty { "全部显示" }
    key == "statisticsModuleOrder" -> StatisticsModule.parseOrder(value?.toString()).joinToString(" → ") { it.displayName }
    key == "hiddenStatisticsModules" -> StatisticsModule.parseSet(value?.toString()).joinToString("、") { it.displayName }.ifEmpty { "全部显示" }
    key == "hiddenStatisticsMetrics" -> value.toString().split(',').mapNotNull { id -> StatisticsMetric.entries.firstOrNull { it.storageKey == id } }.joinToString("、") { it.displayName }.ifEmpty { "全部显示" }
    key == "statisticsPreset" -> StatisticsPreset.fromStorage(value?.toString()).displayName
    key == "statisticsLayoutStyle" -> StatisticsLayoutStyle.fromStorage(value?.toString()).displayName
    key == "statisticsStatusChart" || key == "statisticsTagChart" -> StatisticsChartStyle.fromStorage(value?.toString()).displayName
    key == "communityStyle" -> CommunityStyle.fromStorage(value?.toString()).displayName
    key == "communityGroupView" -> CommunityGroupView.fromStorage(value?.toString()).displayName
    key == "tierListStyle" -> TierListStyle.fromStorage(value?.toString()).displayName
    key == "tierDisplayLabels" && value is org.json.JSONArray -> (0 until value.length()).joinToString("、") { value.optString(it) }
    key == "characterLayout" -> CharacterLayout.fromStorage(value?.toString()).displayName
    key == "characterImageAlignment" -> CharacterImageAlignment.fromStorage(value?.toString()).displayName
    key == "lastSubjectType" -> when(value?.toString()) { "anime" -> "动画"; "book" -> "书籍"; "all" -> "全部"; else -> "其他分类" }
    key == "lastSortKey" -> com.animeow.app.ui.tracker.LibrarySort.fromStorage(value?.toString()).displayName
    key == "overlayStyle" -> OverlayStyle.fromStorage(value?.toString()).displayName
    key == "progressPlacement" -> CardProgressPlacement.fromStorage(value?.toString()).displayName
    key == "hiddenHomeStatusIds" || key == "hiddenActionStatusIds" -> "隐藏 ${parseStatusIds(value?.toString()).size} 个栏目"
    key == "themeColor" && (value == null || value == JSONObject.NULL) -> "跟随强调色"
    key == "accentGradient" && value is JSONObject -> AccentGradientCodec.fromJson(value).let {
        if (it.mode == AccentMode.SOLID) it.mode.label else
            "${it.mode.label} · ${maxOf(2, it.colors.size + 1)} 色 · ${it.angleDegrees.toInt()}°" +
                if (it.mode == AccentMode.FLOW) " · ${it.durationMillis / 1000} 秒" else ""
    }
    key == "icons" && value is JSONObject -> IconSettingsCodec.fromJson(value).let { icons ->
        listOf(icons.style.label, icons.variant.label, icons.color.mode.label, if (icons.animated) "交互动效开启" else "交互动效关闭",
            if (icons.overrides.isEmpty()) "全部跟随整套" else icons.overrides.keys.joinToString("、", limit = 3) { it.label } + " 单独设置")
            .joinToString(" · ")
    }
    value == null || value == JSONObject.NULL -> "跟随默认"
    key in setOf("fontScale", "cornerScale") && value is Number -> "${(value.toDouble() * 100).roundToInt()}%"
    value is Boolean -> if (value) "开启" else "关闭"
    key == "customFontId" -> if (value.toString().isBlank()) "未导入" else "本机字体"
    key == "themeMode" -> ThemeMode.fromStorage(value.toString()).displayName
    key == "appStyle" -> AppStyle.fromStorage(value.toString()).displayName
    key == "gameLayout" -> com.animeow.app.data.games.GameLayout.fromStorage(value.toString()).label
    key == "homeLayout" -> HomeLayout.fromStorage(value.toString()).displayName
    key == "detailLayout" -> DetailLayout.fromStorage(value.toString()).displayName
    key == "detailProgressMode" -> DetailProgressMode.fromStorage(value.toString()).displayName
    key == "detailCardStyle" -> DetailCardStyle.fromStorage(value.toString()).displayName
    key == "sectionSpacing" -> SectionSpacing.fromStorage(value.toString()).displayName
    key == "topBarSpacing" -> TopBarSpacing.fromStorage(value.toString()).displayName
    key == "motionLevel" -> MotionLevel.fromStorage(value.toString()).displayName
    key == "pageTransitionStyle" -> PageTransitionStyle.fromStorage(value.toString()).displayName
    key == "exitBehavior" -> ExitBehavior.fromStorage(value.toString()).displayName
    key == "swipeStartAction" || key == "swipeEndAction" -> SwipeAction.fromStorage(value.toString(), SwipeAction.NONE).displayName
    key == "coverBadgeStyle" -> CoverBadgeStyle.fromStorage(value.toString()).displayName
    key == "coverTitlePosition" -> CoverTitlePosition.fromStorage(value.toString()).displayName
    key == "coverAspectRatio" -> CoverAspectRatio.fromStorage(value.toString()).displayName
    key == "ratingIconStyle" -> RatingIconStyle.fromStorage(value.toString()).displayName
    key == "ratingBadgeColorStyle" -> RatingBadgeColorStyle.fromStorage(value.toString()).displayName
    key == "coverFitMode" -> CoverFitMode.fromStorage(value.toString()).displayName
    key == "homeBottomScope" -> HomeBottomScope.fromStorage(value.toString()).displayName
    key == "bentoCollectionLayout" -> BentoCollectionLayout.fromStorage(value.toString()).displayName
    key == "timelineGroupField" -> TimelineGroupField.fromStorage(value.toString()).displayName
    key == "profileSearchStyle" -> ProfileSearchStyle.fromStorage(value.toString()).displayName
    key == "calendarLayoutPreset" || key == "legacyCalendarLayoutPreset" -> CalendarLayoutPreset.fromStorage(value.toString()).displayName
    key == "navigationLabelMode" -> NavigationLabelMode.fromStorage(value.toString()).displayName
    key == "statusBadgeBackground" -> StatusBadgeBackground.fromStorage(value.toString()).displayName
    key == "pageDensities" && value is JSONObject -> pageDensitiesFromJson(value).entries.joinToString("、") { (page, density) -> "${DENSITY_PAGES[page]} ${density.displayName}" }.ifEmpty { "全部跟随默认" }
    key == "startDestination" -> destinationLabels[value.toString()] ?: "首页"
    key == "navigationOrder" -> value.toString().split(',').joinToString(" → ") { destinationLabels[it] ?: "其他入口" }
    key == "appFontChoice" -> AppFontChoice.fromStorage(value.toString()).displayName
    key.contains("Density") -> ContentDensity.fromStorage(value.toString()).displayName
    key.endsWith("NavigationBarStyle") || key == "navigationBarStyle" -> NavigationBarStyle.fromStorage(value.toString()).displayName
    key == "glassQuality" -> GlassQuality.fromStorage(value.toString()).label
    key == "homeModuleOrder" -> HomeModule.parse(value.toString()).joinToString(" → ") { it.label }
    key == "cardInfoOrder" -> CardInfoField.parseOrder(value.toString()).joinToString(" → ") { it.displayName }
    else -> value.toString().take(90)
}

private val destinationLabels = mapOf("tracker" to "首页", "discovery" to "发现", "calendar" to "日历", "community" to "社区", "statistics" to "统计", "profile" to "我的", "personal" to "我的", "settings" to "设置")
