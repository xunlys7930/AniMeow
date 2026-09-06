package com.animeow.app.ui.theme

const val APP_FONT_SCALE_MIN = 0.8f
const val APP_FONT_SCALE_MAX = 1.4f

enum class FrontendMode(
    val storageKey: String,
    val displayName: String,
) {
    MODERN("modern", "新版"),
    LEGACY("legacy", "经典版");

    companion object {
        fun fromStorage(value: String?): FrontendMode =
            entries.firstOrNull { it.storageKey == value } ?: MODERN
    }
}

enum class AppStyle(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    MIUIX(
        storageKey = "miuix",
        displayName = "MIUIX",
        description = "默认风格，大圆角、留白和单手友好的层级",
    ),
    ANIME_DYNAMIC(
        storageKey = "anime_dynamic",
        displayName = "二次元元气",
        description = "柔和渐变、封面氛围色和更活泼的反馈",
    ),
    CYBER_GLASS(
        storageKey = "cyber_glass",
        displayName = "赛博玻璃",
        description = "深色琉璃表面、霓虹强调色和克制的光效",
    ),
    RETRO_PIXEL(
        storageKey = "retro_pixel",
        displayName = "复古像素",
        description = "硬朗轮廓、复古配色和低装饰的信息布局",
    );

    companion object {
        fun fromStorage(value: String?): AppStyle = when (value) {
            "material_expressive", "cupertino_minimal" -> MIUIX
            else -> entries.firstOrNull { it.storageKey == value } ?: MIUIX
        }
    }
}

enum class HomeLayout(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    BENTO(
        storageKey = "bento",
        displayName = "智能聚合",
        description = "继续观看、最近更新和资料库模块自由组合",
    ),
    CARD_FEED(
        storageKey = "card_feed",
        displayName = "精致卡片流",
        description = "单列大卡片，显示完整标题、进度和信息",
    ),
    POSTER_WALL(
        storageKey = "poster_wall",
        displayName = "沉浸海报墙",
        description = "突出封面视觉，适合快速浏览",
    ),
    COMPACT_INDEX(
        storageKey = "compact_index",
        displayName = "紧凑索引",
        description = "高密度列表，适合大型资料库",
    ),
    RECOMMEND_GRID(
        storageKey = "recommend_grid",
        displayName = "推荐宫格",
        description = "规则多列卡片，兼顾封面和必要信息",
    ),
    TIME_LINE(
        storageKey = "time_line",
        displayName = "年月时间线",
        description = "按时间自动按年月分组，类似手机图库",
    );

    companion object {
        fun fromStorage(value: String?): HomeLayout =
            entries.firstOrNull { it.storageKey == value } ?: BENTO
    }
}

enum class ThemeMode(
    val storageKey: String,
    val displayName: String,
) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        fun fromStorage(value: String?): ThemeMode =
            entries.firstOrNull { it.storageKey == value } ?: SYSTEM
    }
}

enum class ContentDensity(
    val storageKey: String,
    val displayName: String,
    val scale: Float,
    val itemScale: Float,
    val description: String,
) {
    COMFORTABLE("comfortable", "舒适", 1f, 1f, "平衡留白、尺寸与信息量"),
    COMPACT("compact", "紧凑", 0.72f, 0.84f, "缩小卡片、封面与间距，一屏显示更多内容"),
    SPACIOUS("spacious", "宽松", 1.28f, 1.14f, "放大卡片、封面与留白，更适合轻松浏览");

    companion object {
        fun fromStorage(value: String?): ContentDensity =
            entries.firstOrNull { it.storageKey == value } ?: COMFORTABLE
    }
}

enum class SectionSpacing(
    val storageKey: String,
    val displayName: String,
    val dpValue: Int,
    val description: String,
) {
    COMPACT("compact", "紧凑", 4, "缩小栏目间距与列表边距，高密精简"),
    NORMAL("normal", "适中", 12, "平衡板块与工具栏间距"),
    RELAXED("relaxed", "宽松", 18, "宽大呼吸感留白");

    companion object {
        fun fromStorage(value: String?): SectionSpacing =
            entries.firstOrNull { it.storageKey == value } ?: COMPACT
    }
}

enum class TopBarSpacing(
    val storageKey: String,
    val displayName: String,
    val scale: Float,
    val description: String,
) {
    COMPACT("compact", "紧凑", 0.3f, "缩小搜索栏、排序与状态标签间距"),
    NORMAL("normal", "适中", 1f, "平衡顶部区域密度"),
    RELAXED("relaxed", "宽松", 1.45f, "加大顶部留白，呼吸感更强");

    companion object {
        fun fromStorage(value: String?): TopBarSpacing =
            entries.firstOrNull { it.storageKey == value } ?: COMPACT
    }
}

enum class ExitBehavior(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    DOUBLE_PRESS("double_press", "双击返回", "在首页再按一次返回键退出应用"),
    CONFIRM_DIALOG("confirm_dialog", "弹窗确认", "弹出确认对话框后再退出"),
    DIRECT("direct", "直接退出", "按一次返回键直接退出");

    companion object {
        fun fromStorage(value: String?): ExitBehavior =
            entries.firstOrNull { it.storageKey == value } ?: DOUBLE_PRESS
    }
}

enum class MotionLevel(
    val storageKey: String,
    val displayName: String,
    val durationScale: Float,
) {
    FULL("full", "完整动效", 1f),
    REDUCED("reduced", "精简动效", 0.55f),
    NONE("none", "关闭装饰动效", 0f);

    companion object {
        fun fromStorage(value: String?): MotionLevel =
            entries.firstOrNull { it.storageKey == value } ?: FULL
    }
}

enum class PageTransitionStyle(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    REFINED_SLIDE(
        "refined_slide",
        "精致滑动",
        "默认效果，整页水平推进并带轻微层级感",
    ),
    PARALLAX(
        "parallax",
        "层叠视差",
        "前后页面使用不同距离滑动，空间层次更明显",
    ),
    CARD_STACK(
        "card_stack",
        "卡片推入",
        "页面像卡片一样推入并轻微缩放，动感更强",
    ),
    SOFT_FADE(
        "soft_fade",
        "柔和淡化",
        "以淡化和轻微缩放切换，适合偏好克制效果的用户",
    );

    companion object {
        fun fromStorage(value: String?): PageTransitionStyle =
            entries.firstOrNull { it.storageKey == value } ?: REFINED_SLIDE
    }
}

enum class CoverBadgeStyle(
    val storageKey: String,
    val displayName: String,
) {
    FLOATING("floating", "悬浮徽章"),
    EDGE("edge", "贴边显示"),
    BOTTOM_BAR("bottom_bar", "底部信息条"),
    CORNER("corner", "角标极简");

    companion object {
        fun fromStorage(value: String?): CoverBadgeStyle =
            entries.firstOrNull { it.storageKey == value } ?: FLOATING
    }
}

enum class RatingIconStyle(
    val storageKey: String,
    val displayName: String,
    val symbol: String,
) {
    PAW("paw", "猫爪", "🐾"),
    CAT("cat", "猫脸", "😺"),
    STAR("star", "星星", "★"),
    GLOWING_STAR("glowing_star", "亮闪星", "🌟"),
    HEART("heart", "爱心", "♥"),
    FIRE("fire", "火焰", "🔥"),
    DIAMOND("diamond", "钻石", "◆"),
    SPARKLES("sparkles", "闪光", "✨"),
    THUMBS_UP("thumbs_up", "点赞", "👍"),
    CLOVER("clover", "四叶草", "🍀"),
    BLOSSOM("blossom", "樱花", "🌸"),
    TROPHY("trophy", "奖杯", "🏆"),
    NONE("none", "无图标", "");

    companion object {
        fun fromStorage(value: String?): RatingIconStyle =
            entries.firstOrNull { it.storageKey == value } ?: PAW
    }
}

enum class RatingBadgeColorStyle(
    val storageKey: String,
    val displayName: String,
) {
    ORANGE("orange", "橙色"),
    BLACK_WHITE("black_white", "黑框白字"),
    THEME_PRIMARY("theme_primary", "主题色"),
    TRANSPARENT_DARK("transparent_dark", "半透明深色"),
    CUSTOM("custom", "自定义");

    companion object {
        fun fromStorage(value: String?): RatingBadgeColorStyle =
            entries.firstOrNull { it.storageKey == value } ?: ORANGE
    }
}

enum class CoverTitlePosition(
    val storageKey: String,
    val displayName: String,
) {
    BELOW("below", "封面下方"),
    OVERLAY("overlay", "封面底部"),
    HIDDEN("hidden", "隐藏标题");

    companion object {
        fun fromStorage(value: String?): CoverTitlePosition =
            entries.firstOrNull { it.storageKey == value } ?: BELOW
    }
}

enum class TimelineGroupField(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    FOLLOW_SORT(
        storageKey = "follow_sort",
        displayName = "跟随排序",
        description = "按当前排序方式自动选择分组日期",
    ),
    AIR_DATE(
        storageKey = "air_date",
        displayName = "首播日期",
        description = "按番剧首日开播时间分组",
    ),
    FINISH_DATE(
        storageKey = "finish_date",
        displayName = "看完时间",
        description = "按标记看完的日期分组",
    ),
    ADDED_DATE(
        storageKey = "added_date",
        displayName = "添加时间",
        description = "按加入资料库的日期分组",
    ),
    WATCH_START_DATE(
        storageKey = "watch_start_date",
        displayName = "开始观看",
        description = "按开始追番的日期分组",
    );

    companion object {
        fun fromStorage(value: String?): TimelineGroupField =
            entries.firstOrNull { it.storageKey == value } ?: FOLLOW_SORT
    }
}

enum class CoverAspectRatio(
    val storageKey: String,
    val displayName: String,
    val ratio: Float,
) {
    POSTER("poster", "经典海报 2:3", 2f / 3f),
    LANDSCAPE("landscape", "横版剧照 16:9", 16f / 9f),
    SQUARE("square", "正方形 1:1", 1f);

    companion object {
        fun fromStorage(value: String?): CoverAspectRatio =
            entries.firstOrNull { it.storageKey == value } ?: POSTER
    }
}

enum class CalendarLayoutPreset(
    val storageKey: String,
    val displayName: String,
) {
    WEEK_AGENDA("week_agenda", "周跑道 + 议程"),
    MONTH("month", "月视图"),
    WEEK("week", "周视图"),
    AGENDA("agenda", "纯议程");

    companion object {
        fun fromStorage(value: String?): CalendarLayoutPreset =
            entries.firstOrNull { it.storageKey == value } ?: WEEK_AGENDA
    }
}

enum class DetailLayout(
    val storageKey: String,
    val displayName: String,
) {
    CLASSIC("classic", "经典纵向"),
    DASHBOARD("dashboard", "数据仪表盘"),
    MAGAZINE("magazine", "杂志叙事"),
    MINIMAL("minimal", "灵动极简");

    companion object {
        fun fromStorage(value: String?): DetailLayout =
            entries.firstOrNull { it.storageKey == value } ?: CLASSIC
    }
}

enum class DetailCardStyle(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    TONAL("tonal", "柔和色块", "默认 MIUIX 层级，使用轻量高程和主题容器色"),
    OUTLINED("outlined", "悬浮描边", "降低阴影，以细描边区分模块边界"),
    GLASS("glass", "通透玻璃", "半透明表面与高光边缘，低性能风格会自动保持克制");

    companion object {
        fun fromStorage(value: String?): DetailCardStyle =
            entries.firstOrNull { it.storageKey == value } ?: TONAL
    }
}

enum class ProfileSearchStyle(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    INLINE("inline", "常驻搜索框", "始终在“我的”页顶部展示设置搜索"),
    ON_DEMAND("on_demand", "按需搜索按钮", "平时保持精简，点击按钮后展开搜索框"),
    BOTH("both", "搜索框 + 按钮", "保留常驻搜索框，并提供快速聚焦按钮"),
    HIDDEN("hidden", "隐藏本页搜索", "隐藏“我的”页搜索入口；全局搜索仍可使用");

    val showsInline: Boolean get() = this == INLINE || this == BOTH
    val showsButton: Boolean get() = this == ON_DEMAND || this == BOTH

    companion object {
        fun fromStorage(value: String?): ProfileSearchStyle =
            entries.firstOrNull { it.storageKey == value } ?: INLINE
    }
}

enum class DetailModule(
    val storageKey: String,
    val displayName: String,
) {
    HEADER("header", "封面与标题"),
    PROGRESS("progress", "观看进度"),
    WATCH_DATES("watch_dates", "追番足迹"),
    METADATA("metadata", "作品资料"),
    SYNOPSIS("synopsis", "番剧详情"),
    TAGS("tags", "标签"),
    REMINDER("reminder", "追番提醒"),
    SERIES("series", "同系列作品"),
    CHARACTERS("characters", "角色"),
    REVIEW("review", "我的评价");

    companion object {
        fun parseOrder(value: String?): List<DetailModule> {
            val parsed = value.orEmpty().split(',')
                .mapNotNull { key -> entries.firstOrNull { it.storageKey == key } }
                .distinct()
            val result = (parsed + entries.filterNot(parsed::contains)).toMutableList()
            if (WATCH_DATES !in parsed && PROGRESS in parsed) {
                result.remove(WATCH_DATES)
                result.add(result.indexOf(PROGRESS) + 1, WATCH_DATES)
            }
            return result
        }

        fun parseSet(value: String?): Set<DetailModule> = value.orEmpty().split(',')
            .mapNotNull { key -> entries.firstOrNull { it.storageKey == key } }
            .toSet()
    }
}

enum class CharacterLayout(
    val storageKey: String,
    val displayName: String,
) {
    ADAPTIVE("adaptive", "自适应双栏"),
    LIST("list", "资料列表"),
    GRID("grid", "角色海报墙");

    companion object {
        fun fromStorage(value: String?): CharacterLayout {
            if (value == ADAPTIVE.storageKey) return LIST
            return entries.firstOrNull { it.storageKey == value } ?: LIST
        }
    }
}

enum class NavigationBarStyle(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    FLOATING("floating", "悬浮胶囊", "底部导航悬浮在内容之上，圆角胶囊造型更轻盈"),
    DEFAULT("default", "经典底栏", "贴近屏幕底部的传统导航栏，占用独立空间");

    companion object {
        fun fromStorage(value: String?): NavigationBarStyle =
            entries.firstOrNull { it.storageKey == value } ?: FLOATING
    }
}

enum class NavigationLabelMode(val storageKey: String, val displayName: String) {
    SELECTED("selected", "仅选中时显示"),
    ALWAYS("always", "始终显示"),
    ICONS_ONLY("icons_only", "仅图标");

    companion object {
        fun fromStorage(value: String?): NavigationLabelMode =
            entries.firstOrNull { it.storageKey == value } ?: SELECTED
    }
}

enum class CharacterImageAlignment(
    val storageKey: String,
    val displayName: String,
) {
    TOP("top", "顶部（显示头部）"),
    CENTER("center", "居中（默认）"),
    BOTTOM("bottom", "底部");

    companion object {
        fun fromStorage(value: String?): CharacterImageAlignment =
            entries.firstOrNull { it.storageKey == value } ?: TOP
    }
}

enum class SwipeAction(
    val storageKey: String,
    val displayName: String,
) {
    NONE("none", "关闭"),
    INCREMENT("increment", "+1 集"),
    CYCLE_STATUS("cycle_status", "切换状态"),
    EDIT("edit", "编辑"),
    TRASH("trash", "移入回收站");

    companion object {
        fun fromStorage(value: String?, fallback: SwipeAction): SwipeAction =
            entries.firstOrNull { it.storageKey == value } ?: fallback
    }
}

enum class StatisticsModule(
    val storageKey: String,
    val displayName: String,
) {
    OVERVIEW("overview", "总览"),
    STATUS("status", "状态分布"),
    RATING("rating", "评分分布"),
    TAGS("tags", "标签偏好"),
    ADAPTATION("adaptation", "改编类型"),
    GENRE("genre", "题材分布"),
    YEAR("year", "年份分布"),
    ACTIVITY("activity", "观看活动"),
    ANALYSIS("analysis", "AI 看番风格");

    companion object {
        fun parseOrder(value: String?): List<StatisticsModule> {
            val parsed = value.orEmpty().split(',')
                .mapNotNull { key -> entries.firstOrNull { it.storageKey == key } }
                .distinct()
            return parsed + entries.filterNot(parsed::contains)
        }

        fun parseSet(value: String?): Set<StatisticsModule> = value.orEmpty().split(',')
            .mapNotNull { key -> entries.firstOrNull { it.storageKey == key } }
            .toSet()
    }
}

enum class StatisticsLayoutStyle(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    CLASSIC("classic", "经典卡片", "保留当前统计页的卡片与图表布局"),
    DASHBOARD("dashboard", "档案仪表盘", "参考游戏资料页，用紧凑总览和分区卡片展示数据");

    companion object {
        fun fromStorage(value: String?): StatisticsLayoutStyle =
            entries.firstOrNull { it.storageKey == value } ?: CLASSIC
    }
}

enum class StatisticsMetric(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    TOTAL("total", "总收录", "资料库中的全部作品数量"),
    ANIME("anime", "动画", "动画类型作品数量"),
    BOOK("book", "书籍", "漫画、小说等书籍类型数量"),
    WATCHED_EPISODES("watched_episodes", "已看集数", "全部作品累计观看集数"),
    WATCH_HOURS("watch_hours", "估算时长", "按每集约 24 分钟估算的观看时长"),
    STREAK("streak", "连续打卡", "根据观看记录日期计算，默认不展示"),
    AVERAGE_RATING("average_rating", "平均评分", "仅使用已经评分的作品计算平均值");

    companion object {
        fun parseSet(value: String?): Set<StatisticsMetric> {
            if (value == null) return DEFAULT_HIDDEN_STATISTICS_METRICS
            return value.split(',')
                .mapNotNull { key -> entries.firstOrNull { it.storageKey == key } }
                .toSet()
        }
    }
}

val DEFAULT_HIDDEN_STATISTICS_METRICS: Set<StatisticsMetric> = setOf(StatisticsMetric.STREAK)

enum class StatisticsPreset(
    val storageKey: String,
    val displayName: String,
    val description: String,
) {
    CONCISE("concise", "精简", "核心概览与排行条，适合快速查看"),
    BALANCED("balanced", "均衡", "完整模块与舒适密度"),
    ANALYSIS("analysis", "分析", "宽松间距，突出图表与趋势"),
    CUSTOM("custom", "自定义", "使用当前的独立配置");

    companion object {
        fun fromStorage(value: String?): StatisticsPreset =
            entries.firstOrNull { it.storageKey == value } ?: BALANCED
    }
}

enum class StatisticsChartStyle(
    val storageKey: String,
    val displayName: String,
) {
    DONUT("donut", "环形图"),
    RANKED("ranked", "排行条");

    companion object {
        fun fromStorage(value: String?): StatisticsChartStyle =
            entries.firstOrNull { it.storageKey == value } ?: DONUT
    }
}

enum class CommunityGroupView(
    val storageKey: String,
    val displayName: String,
) {
    GRID("grid", "网格"),
    LIST("list", "列表");

    companion object {
        fun fromStorage(value: String?): CommunityGroupView =
            entries.firstOrNull { it.storageKey == value } ?: GRID
    }
}

enum class TierListStyle(
    val storageKey: String,
    val displayName: String,
) {
    MINIMAL("minimal", "极简排行");

    companion object {
        fun fromStorage(value: String?): TierListStyle = MINIMAL
    }
}

const val DEFAULT_TIER_BOARD_TITLE = "我的番剧趣味评级"
val DEFAULT_TIER_DISPLAY_LABELS = listOf("S", "A", "B", "C", "D")

fun normalizeTierBoardTitle(value: String?): String = value.orEmpty().trim()
    .takeIf { it.isNotEmpty() && it.length <= 32 }
    ?: DEFAULT_TIER_BOARD_TITLE

fun normalizeTierDisplayLabels(values: List<String>?): List<String> {
    val normalized = values.orEmpty().map(String::trim)
    val valid = normalized.size == DEFAULT_TIER_DISPLAY_LABELS.size &&
        normalized.all { it.isNotEmpty() && it.length <= 16 && '\n' !in it && '\r' !in it } &&
        normalized.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == normalized.size
    return if (valid) normalized else DEFAULT_TIER_DISPLAY_LABELS
}

data class AppearanceSettings(
    val frontendMode: FrontendMode = FrontendMode.MODERN,
    val frontendModeChoiceMade: Boolean = false,
    val appStyle: AppStyle = AppStyle.MIUIX,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val homeLayout: HomeLayout = HomeLayout.BENTO,
    val detailLayout: DetailLayout = DetailLayout.CLASSIC,
    val contentDensity: ContentDensity = ContentDensity.COMFORTABLE,
    val sectionSpacing: SectionSpacing = SectionSpacing.COMPACT,
    val topBarSpacing: TopBarSpacing = TopBarSpacing.COMPACT,
    val motionLevel: MotionLevel = MotionLevel.FULL,
    val pageTransitionStyle: PageTransitionStyle = PageTransitionStyle.REFINED_SLIDE,
    val predictiveBackEnabled: Boolean = true,
    val exitBehavior: ExitBehavior = ExitBehavior.DOUBLE_PRESS,
    val useDynamicColor: Boolean = false,
    val fontScale: Float = 1f,
    val cornerScale: Float = 1f,
    val gridColumns: Int = 3,
    val showTitle: Boolean = true,
    val showStatus: Boolean = true,
    val showRating: Boolean = true,
    val showProgress: Boolean = true,
    val coverBadgeStyle: CoverBadgeStyle = CoverBadgeStyle.FLOATING,
    val ratingIconStyle: RatingIconStyle = RatingIconStyle.PAW,
    val ratingBadgeColorStyle: RatingBadgeColorStyle = RatingBadgeColorStyle.ORANGE,
    val ratingBadgeCustomColor: Long = 0xFFFF9800,
    val coverTitlePosition: CoverTitlePosition = CoverTitlePosition.BELOW,
    val coverAspectRatio: CoverAspectRatio = CoverAspectRatio.POSTER,
    val detailCardStyle: DetailCardStyle = DetailCardStyle.TONAL,
    val profileSearchStyle: ProfileSearchStyle = ProfileSearchStyle.INLINE,
    val showDiscovery: Boolean = true,
    val showCalendar: Boolean = true,
    val showCommunity: Boolean = true,
    val showStatistics: Boolean = false,
    val detailModuleOrder: List<DetailModule> = DetailModule.entries,
    val hiddenDetailModules: Set<DetailModule> = emptySet(),
    val cardSwipeActionsEnabled: Boolean = false,
    val swipeStartAction: SwipeAction = SwipeAction.INCREMENT,
    val swipeEndAction: SwipeAction = SwipeAction.CYCLE_STATUS,
    val hapticFeedback: Boolean = true,
    val soundFeedback: Boolean = false,
    val autoCompleteStatus: Boolean = true,
    val completionStatus: String = "看完",
    val accentColor: Long? = null,
    val randomAccentOnLaunch: Boolean = false,
    val navigationOrder: List<String> = DEFAULT_NAVIGATION_ORDER,
    val startDestination: String = "tracker",
    val statisticsModuleOrder: List<StatisticsModule> = StatisticsModule.entries,
    val hiddenStatisticsModules: Set<StatisticsModule> = emptySet(),
    val statisticsPreset: StatisticsPreset = StatisticsPreset.BALANCED,
    val statisticsDensity: ContentDensity = ContentDensity.COMFORTABLE,
    val statisticsStatusChart: StatisticsChartStyle = StatisticsChartStyle.DONUT,
    val statisticsTagChart: StatisticsChartStyle = StatisticsChartStyle.DONUT,
    val statisticsLayoutStyle: StatisticsLayoutStyle = StatisticsLayoutStyle.CLASSIC,
    val hiddenStatisticsMetrics: Set<StatisticsMetric> = DEFAULT_HIDDEN_STATISTICS_METRICS,
    val communityGroupView: CommunityGroupView = CommunityGroupView.GRID,
    val communityDensity: ContentDensity = ContentDensity.COMFORTABLE,
    val communityShowDescription: Boolean = true,
    val communityShowDownloads: Boolean = true,
    val tierListStyle: TierListStyle = TierListStyle.MINIMAL,
    val tierListCount: Int = 5,
    val tierBoardTitle: String = DEFAULT_TIER_BOARD_TITLE,
    val tierDisplayLabels: List<String> = DEFAULT_TIER_DISPLAY_LABELS,
    val characterLayout: CharacterLayout = CharacterLayout.LIST,
    val characterImageAlignment: CharacterImageAlignment = CharacterImageAlignment.TOP,
    val characterShowMetadata: Boolean = true,
    val characterShowRating: Boolean = true,
    val navigationBarStyle: NavigationBarStyle = NavigationBarStyle.FLOATING,
    val floatingBarHorizontalMargin: Int = 20,
    val floatingBarBottomMargin: Int = 6,
    val floatingBarCornerRadius: Int = 32,
    val floatingBarShadowElevation: Int = 12,
    val floatingBarHeight: Int = 56,
    val navigationLabelMode: NavigationLabelMode = NavigationLabelMode.SELECTED,
    val calendarLayoutPreset: CalendarLayoutPreset = CalendarLayoutPreset.WEEK_AGENDA,
    val clipboardShareDetection: Boolean = false,
    val statusChipFilled: Boolean = true,
    val showVersionInProfile: Boolean = true,
)

val DEFAULT_NAVIGATION_ORDER = listOf("tracker", "discovery", "calendar", "community", "statistics", "profile")

const val MIN_VISIBLE_NAVIGATION_DESTINATIONS = 2
const val MAX_VISIBLE_NAVIGATION_DESTINATIONS = 5

private val OPTIONAL_NAVIGATION_ROUTES = listOf("discovery", "calendar", "community", "statistics")

fun AppearanceSettings.isNavigationDestinationVisible(route: String): Boolean = when (route) {
    "tracker", "profile" -> true
    "discovery" -> showDiscovery
    "calendar" -> showCalendar
    "community" -> showCommunity
    "statistics" -> showStatistics
    else -> false
}

fun AppearanceSettings.visibleNavigationDestinationCount(): Int =
    DEFAULT_NAVIGATION_ORDER.count { route -> isNavigationDestinationVisible(route) }

fun AppearanceSettings.canSetNavigationDestinationVisible(route: String, visible: Boolean): Boolean {
    if (route !in OPTIONAL_NAVIGATION_ROUTES) return false
    val current = isNavigationDestinationVisible(route)
    if (current == visible) return true
    val count = visibleNavigationDestinationCount()
    return if (visible) count < MAX_VISIBLE_NAVIGATION_DESTINATIONS
    else count > MIN_VISIBLE_NAVIGATION_DESTINATIONS
}

fun AppearanceSettings.withNavigationDestinationVisible(route: String, visible: Boolean): AppearanceSettings {
    if (!canSetNavigationDestinationVisible(route, visible)) return this
    return forceNavigationDestinationVisible(route, visible).normalizedNavigationVisibility()
}

fun AppearanceSettings.normalizedNavigationVisibility(): AppearanceSettings {
    val normalizedOrder = parseNavigationOrder(navigationOrder.joinToString(","))
    var normalized = copy(navigationOrder = normalizedOrder)
    val optionalInOrder = normalizedOrder.filter(OPTIONAL_NAVIGATION_ROUTES::contains)
    while (normalized.visibleNavigationDestinationCount() < MIN_VISIBLE_NAVIGATION_DESTINATIONS) {
        val route = optionalInOrder.firstOrNull { !normalized.isNavigationDestinationVisible(it) } ?: break
        normalized = normalized.forceNavigationDestinationVisible(route, true)
    }
    while (normalized.visibleNavigationDestinationCount() > MAX_VISIBLE_NAVIGATION_DESTINATIONS) {
        val route = optionalInOrder.lastOrNull { normalized.isNavigationDestinationVisible(it) } ?: break
        normalized = normalized.forceNavigationDestinationVisible(route, false)
    }
    val normalizedStart = normalized.startDestination
        .takeIf { normalized.isNavigationDestinationVisible(it) }
        ?: normalizedOrder.firstOrNull { normalized.isNavigationDestinationVisible(it) }
        ?: "tracker"
    return normalized.copy(startDestination = normalizedStart)
}

private fun AppearanceSettings.forceNavigationDestinationVisible(
    route: String,
    visible: Boolean,
): AppearanceSettings = when (route) {
    "discovery" -> copy(showDiscovery = visible)
    "calendar" -> copy(showCalendar = visible)
    "community" -> copy(showCommunity = visible)
    "statistics" -> copy(showStatistics = visible)
    else -> this
}

fun parseNavigationOrder(value: String?): List<String> {
    val parsed = value.orEmpty().split(',').filter(DEFAULT_NAVIGATION_ORDER::contains).distinct()
    return parsed + DEFAULT_NAVIGATION_ORDER.filterNot(parsed::contains)
}
