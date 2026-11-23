package com.animeow.app.data.preferences

enum class HomeModule(val storageKey: String, val label: String) {
    CONTINUE("continue", "阅读进度"), RECENT("recent", "最近添加"),
    INCOMPLETE("incomplete", "待补资料"), CALENDAR("calendar", "今日排期"), RECAP("recap", "本周回顾"), PLAYBACK("playback", "最近观看");
    companion object {
        fun parse(value: String?) = normalize(value.orEmpty().split(',').mapNotNull { key -> entries.find { it.storageKey == key } })
        fun normalize(value: List<HomeModule>) = (value + entries).distinct()
    }
}

fun TrackerSettings.shows(module: HomeModule): Boolean = when (module) {
    HomeModule.CONTINUE -> showContinueWatching
    HomeModule.RECENT -> showRecentAdded
    HomeModule.INCOMPLETE -> showIncompleteMetadata
    HomeModule.CALENDAR -> showHomeCalendar
    HomeModule.PLAYBACK -> showRecentPlayback
    HomeModule.RECAP -> showWeeklyRecap
}

fun TrackerSettings.withModuleVisible(module: HomeModule, visible: Boolean): TrackerSettings = when (module) {
    HomeModule.CONTINUE -> copy(showContinueWatching = visible)
    HomeModule.RECENT -> copy(showRecentAdded = visible)
    HomeModule.INCOMPLETE -> copy(showIncompleteMetadata = visible)
    HomeModule.CALENDAR -> copy(showHomeCalendar = visible)
    HomeModule.PLAYBACK -> copy(showRecentPlayback = visible)
    HomeModule.RECAP -> copy(showWeeklyRecap = visible)
}
