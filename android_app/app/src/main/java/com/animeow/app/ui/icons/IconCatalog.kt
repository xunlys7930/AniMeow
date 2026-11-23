// Generated from design/icons/catalog.xml. Do not hand-edit.
package com.animeow.app.ui.icons

import androidx.compose.runtime.Immutable

enum class IconStyle(val storageKey: String, val label: String, val description: String) {
    LINE("line", "清晰线条", "纤细直线，舒展而利落"),
    SOFT("soft", "柔和圆角", "圆润粗线，紧凑而饱满"),
    DUO("duo", "双色层次", "清楚衬面，轻描边与色块相映"),
    ;
    companion object {
        fun fromStorage(value: String?): IconStyle? = entries.firstOrNull { it.storageKey == value }
    }
}

enum class IconCategory(val label: String) {
    NAVIGATION("导航与入口"),
    ACTIONS("常用操作"),
    LIBRARY("资料与整理"),
    MEDIA("图片与内容"),
    PEOPLE("角色与社区"),
    SYSTEM("设置与状态"),
    RATING("评分与心情"),
}

enum class IconMotion { PULSE, ROTATE, BELL, NUDGE }

enum class AppIconId(
    val storageKey: String,
    val label: String,
    val category: IconCategory,
    val keywords: String,
    val motion: IconMotion = IconMotion.PULSE,
    val supportsActive: Boolean = false,
) {
    TRACKER("tracker", "追番与动画", IconCategory.NAVIGATION, "MovieFilter Movie Tracker", IconMotion.PULSE, false),
    DISCOVER("discover", "发现", IconCategory.NAVIGATION, "Explore Discover", IconMotion.PULSE, false),
    CALENDAR("calendar", "日历与排期", IconCategory.NAVIGATION, "CalendarMonth Event Today Calendar", IconMotion.PULSE, false),
    COMMUNITY("community", "社区", IconCategory.NAVIGATION, "Forum Community", IconMotion.PULSE, false),
    STATISTICS("statistics", "统计", IconCategory.NAVIGATION, "Insights BarChart Statistics", IconMotion.PULSE, false),
    PROFILE("profile", "我的与个人", IconCategory.NAVIGATION, "Person Profile", IconMotion.PULSE, false),
    SEARCH("search", "搜索", IconCategory.ACTIONS, "Search Search", IconMotion.PULSE, false),
    ADD("add", "添加", IconCategory.ACTIONS, "Add Add", IconMotion.PULSE, false),
    ADD_CIRCLE("add_circle", "新建项目", IconCategory.ACTIONS, "AddCircleOutline AddCircle", IconMotion.PULSE, false),
    CLOSE("close", "关闭与清除", IconCategory.ACTIONS, "Close Clear Close", IconMotion.PULSE, false),
    BACK("back", "返回", IconCategory.ACTIONS, "ArrowBack Back", IconMotion.NUDGE, false),
    FORWARD("forward", "前往", IconCategory.ACTIONS, "ArrowForward Forward", IconMotion.NUDGE, false),
    UP("up", "向上移动", IconCategory.ACTIONS, "ArrowUpward Up", IconMotion.PULSE, false),
    DOWN("down", "向下移动", IconCategory.ACTIONS, "ArrowDownward Down", IconMotion.PULSE, false),
    CHEVRON_RIGHT("chevron_right", "展开详情", IconCategory.ACTIONS, "ChevronRight KeyboardArrowRight ChevronRight", IconMotion.PULSE, false),
    CHEVRON_LEFT("chevron_left", "上一项", IconCategory.ACTIONS, "KeyboardArrowLeft ChevronLeft", IconMotion.PULSE, false),
    EXPAND("expand", "展开", IconCategory.ACTIONS, "KeyboardArrowDown ExpandMore Expand", IconMotion.PULSE, false),
    COLLAPSE("collapse", "收起", IconCategory.ACTIONS, "KeyboardArrowUp ExpandLess Collapse", IconMotion.PULSE, false),
    EDIT("edit", "编辑", IconCategory.ACTIONS, "Edit Edit", IconMotion.PULSE, false),
    EDIT_NOTE("edit_note", "编辑备注", IconCategory.ACTIONS, "EditNote RateReview EditNote", IconMotion.PULSE, false),
    DELETE("delete", "删除", IconCategory.ACTIONS, "DeleteOutline Delete", IconMotion.PULSE, false),
    DELETE_FOREVER("delete_forever", "永久删除", IconCategory.ACTIONS, "DeleteForever DeleteForever", IconMotion.PULSE, false),
    DELETE_SWEEP("delete_sweep", "批量清理", IconCategory.ACTIONS, "DeleteSweep DeleteSweep", IconMotion.PULSE, false),
    CHECK("check", "确认", IconCategory.ACTIONS, "Check Check", IconMotion.PULSE, false),
    CHECK_CIRCLE("check_circle", "完成", IconCategory.ACTIONS, "CheckCircle TaskAlt CheckCircle", IconMotion.PULSE, false),
    REFRESH("refresh", "刷新", IconCategory.ACTIONS, "Refresh Cached RestartAlt Refresh", IconMotion.ROTATE, false),
    SYNC("sync", "同步", IconCategory.ACTIONS, "Sync SyncAlt Sync", IconMotion.ROTATE, false),
    UNDO("undo", "撤销", IconCategory.ACTIONS, "Undo Undo", IconMotion.PULSE, false),
    HISTORY("history", "历史与恢复", IconCategory.ACTIONS, "History Restore History", IconMotion.PULSE, false),
    DOWNLOAD("download", "下载与导入", IconCategory.ACTIONS, "Download FileDownload Download", IconMotion.PULSE, false),
    UPLOAD("upload", "上传与导出", IconCategory.ACTIONS, "Upload FileUpload Upload", IconMotion.PULSE, false),
    SHARE("share", "分享", IconCategory.ACTIONS, "Share Share", IconMotion.PULSE, false),
    COPY("copy", "复制", IconCategory.ACTIONS, "ContentCopy Copy", IconMotion.PULSE, false),
    SAVE("save", "保存", IconCategory.ACTIONS, "Save Save", IconMotion.PULSE, false),
    MORE_VERTICAL("more_vertical", "更多操作", IconCategory.ACTIONS, "MoreVert MoreVertical", IconMotion.PULSE, false),
    MORE_HORIZONTAL("more_horizontal", "更多选项", IconCategory.ACTIONS, "MoreHoriz MoreHorizontal", IconMotion.PULSE, false),
    SORT("sort", "排序", IconCategory.ACTIONS, "Sort Sort", IconMotion.PULSE, false),
    FILTER("filter", "筛选", IconCategory.ACTIONS, "FilterList FilterAlt Filter", IconMotion.PULSE, false),
    TUNE("tune", "页面调整", IconCategory.ACTIONS, "Tune Tune", IconMotion.PULSE, false),
    SWAP_VERTICAL("swap_vertical", "上下交换", IconCategory.ACTIONS, "SwapVert SwapVertical", IconMotion.PULSE, false),
    SWAP_HORIZONTAL("swap_horizontal", "切换来源", IconCategory.ACTIONS, "SwapHoriz SwapHorizontal", IconMotion.PULSE, false),
    SELECT_ALL("select_all", "全选", IconCategory.ACTIONS, "SelectAll SelectAll", IconMotion.PULSE, false),
    DRAG("drag", "拖动排序", IconCategory.ACTIONS, "DragIndicator DragHandle Drag", IconMotion.PULSE, false),
    OPEN_EXTERNAL("open_external", "在外部打开", IconCategory.ACTIONS, "OpenInNew OpenExternal", IconMotion.PULSE, false),
    LINK("link", "关联与链接", IconCategory.ACTIONS, "Link Link", IconMotion.PULSE, false),
    UNLINK("unlink", "取消关联", IconCategory.ACTIONS, "LinkOff Unlink", IconMotion.PULSE, false),
    MERGE("merge", "合并", IconCategory.ACTIONS, "Merge FindReplace Merge", IconMotion.PULSE, false),
    RESTORE_TRASH("restore_trash", "从回收站恢复", IconCategory.LIBRARY, "RestoreFromTrash RestoreTrash", IconMotion.PULSE, false),
    ARCHIVE("archive", "归档", IconCategory.LIBRARY, "Archive Inventory2 Archive", IconMotion.PULSE, false),
    COLLECTION("collection", "系列与收藏集", IconCategory.LIBRARY, "CollectionsBookmark Collection", IconMotion.PULSE, false),
    BOOKMARK("bookmark", "书签", IconCategory.LIBRARY, "BookmarkBorder Bookmark", IconMotion.PULSE, true),
    TAG("tag", "标签", IconCategory.LIBRARY, "Label LocalOffer Tag", IconMotion.PULSE, false),
    HASH("hash", "话题与编号", IconCategory.LIBRARY, "Tag Hash", IconMotion.PULSE, false),
    FOLDER_SHARED("folder_shared", "共享文件夹", IconCategory.LIBRARY, "FolderShared FolderShared", IconMotion.PULSE, false),
    BOOK("book", "书籍与手册", IconCategory.LIBRARY, "MenuBook Book", IconMotion.PULSE, false),
    FILE_OPEN("file_open", "打开文件", IconCategory.LIBRARY, "FileOpen FileOpen", IconMotion.PULSE, false),
    GRID("grid", "网格布局", IconCategory.LIBRARY, "GridView ViewModule Grid", IconMotion.PULSE, false),
    LIST("list", "列表与议程", IconCategory.LIBRARY, "ViewAgenda List", IconMotion.PULSE, false),
    TABLE("table", "表格", IconCategory.LIBRARY, "TableView Table", IconMotion.PULSE, false),
    PIN("pin", "置顶", IconCategory.LIBRARY, "PushPin Pin", IconMotion.PULSE, false),
    STORAGE("storage", "数据与存储", IconCategory.LIBRARY, "Storage Storage", IconMotion.PULSE, false),
    HUB("hub", "关系网络", IconCategory.LIBRARY, "Hub Workspaces Hub", IconMotion.PULSE, false),
    IMAGE("image", "图片", IconCategory.MEDIA, "Image Image", IconMotion.PULSE, false),
    PHOTO_LIBRARY("photo_library", "相册", IconCategory.MEDIA, "PhotoLibrary PhotoLibrary", IconMotion.PULSE, false),
    IMAGE_SEARCH("image_search", "以图搜番", IconCategory.MEDIA, "ImageSearch ImageSearch", IconMotion.PULSE, false),
    ADD_PHOTO("add_photo", "添加图片", IconCategory.MEDIA, "AddPhotoAlternate AddPhoto", IconMotion.PULSE, false),
    CROP("crop", "裁剪", IconCategory.MEDIA, "Crop Crop", IconMotion.PULSE, false),
    PLAY("play", "播放与进度", IconCategory.MEDIA, "PlayCircle Play", IconMotion.PULSE, false),
    SKIP("skip", "跳过", IconCategory.MEDIA, "SkipNext Skip", IconMotion.NUDGE, false),
    TELEVISION("television", "电视动画", IconCategory.MEDIA, "Tv Television", IconMotion.PULSE, false),
    SPARKLES("sparkles", "智能补全与闪光", IconCategory.MEDIA, "AutoAwesome Sparkles", IconMotion.PULSE, false),
    MAGIC("magic", "自动优化", IconCategory.MEDIA, "AutoFixHigh Magic", IconMotion.PULSE, false),
    GROUP("group", "角色组与群组", IconCategory.PEOPLE, "Group Groups People Group", IconMotion.PULSE, false),
    ACCOUNT("account", "账号", IconCategory.PEOPLE, "AccountCircle Account", IconMotion.PULSE, false),
    PERSON_ADD("person_add", "添加角色", IconCategory.PEOPLE, "PersonAdd PersonAdd", IconMotion.PULSE, false),
    PERSON_SEARCH("person_search", "搜索角色", IconCategory.PEOPLE, "PersonSearch PersonSearch", IconMotion.PULSE, false),
    CHAT("chat", "评论与消息", IconCategory.PEOPLE, "Chat ChatBubbleOutline Chat", IconMotion.PULSE, false),
    SEND("send", "发送", IconCategory.PEOPLE, "Send Send", IconMotion.NUDGE, false),
    HEART("heart", "喜欢与点赞", IconCategory.PEOPLE, "FavoriteBorder Heart", IconMotion.PULSE, true),
    STAR("star", "星标与评分", IconCategory.PEOPLE, "StarBorder StarOutline Star", IconMotion.PULSE, true),
    VOTE("vote", "投票", IconCategory.PEOPLE, "HowToVote Vote", IconMotion.PULSE, false),
    SMILE("smile", "表情", IconCategory.PEOPLE, "EmojiEmotions Smile", IconMotion.PULSE, false),
    MALE("male", "男性", IconCategory.PEOPLE, "Male Male", IconMotion.PULSE, false),
    FEMALE("female", "女性", IconCategory.PEOPLE, "Female Female", IconMotion.PULSE, false),
    BIRTHDAY("birthday", "生日", IconCategory.PEOPLE, "Cake Birthday", IconMotion.PULSE, false),
    BLOOD("blood", "血型", IconCategory.PEOPLE, "Bloodtype Blood", IconMotion.PULSE, false),
    LOCATION("location", "地区与地点", IconCategory.PEOPLE, "LocationOn Location", IconMotion.PULSE, false),
    SETTINGS("settings", "设置", IconCategory.SYSTEM, "Settings Settings", IconMotion.PULSE, false),
    SETTINGS_DEPTH("settings_depth", "进阶定制", IconCategory.SYSTEM, "Density UnfoldMore FormatLineSpacing SettingsDepth", IconMotion.NUDGE, false),
    PALETTE("palette", "主题与配色", IconCategory.SYSTEM, "Palette Palette", IconMotion.PULSE, false),
    COLOR_PICKER("color_picker", "自定义颜色", IconCategory.SYSTEM, "Colorize ColorPicker", IconMotion.PULSE, false),
    DARK_MODE("dark_mode", "深色模式", IconCategory.SYSTEM, "DarkMode NightlightRound DarkMode", IconMotion.PULSE, false),
    WIDGETS("widgets", "组件与布局", IconCategory.SYSTEM, "Widgets Widgets", IconMotion.PULSE, false),
    BRANDING("branding", "启动与品牌", IconCategory.SYSTEM, "BrandingWatermark Branding", IconMotion.PULSE, false),
    TOUCH("touch", "触觉与手势", IconCategory.SYSTEM, "TouchApp Touch", IconMotion.PULSE, false),
    VISIBILITY("visibility", "显示与预览", IconCategory.SYSTEM, "Visibility Visibility", IconMotion.PULSE, false),
    VISIBILITY_OFF("visibility_off", "隐藏", IconCategory.SYSTEM, "VisibilityOff VisibilityOff", IconMotion.PULSE, false),
    NOTIFICATION("notification", "提醒与通知", IconCategory.SYSTEM, "NotificationsNone NotificationsActive Notification", IconMotion.BELL, false),
    NOTIFICATION_OFF("notification_off", "关闭提醒", IconCategory.SYSTEM, "NotificationsOff NotificationOff", IconMotion.PULSE, false),
    ALARM("alarm", "定时提醒", IconCategory.SYSTEM, "Alarm Alarm", IconMotion.BELL, false),
    CLOCK("clock", "时间与观看时长", IconCategory.SYSTEM, "AccessTime Schedule Clock", IconMotion.PULSE, false),
    CALENDAR_OFF("calendar_off", "暂停排期", IconCategory.SYSTEM, "EventBusy CalendarOff", IconMotion.PULSE, false),
    CLOUD("cloud", "云端", IconCategory.SYSTEM, "Cloud Cloud", IconMotion.PULSE, false),
    CLOUD_DOWNLOAD("cloud_download", "云端下载", IconCategory.SYSTEM, "CloudDownload CloudDownload", IconMotion.PULSE, false),
    CLOUD_UPLOAD("cloud_upload", "云端备份", IconCategory.SYSTEM, "CloudUpload CloudUpload", IconMotion.PULSE, false),
    CLOUD_DONE("cloud_done", "云端已同步", IconCategory.SYSTEM, "CloudDone CloudDone", IconMotion.PULSE, false),
    CLOUD_OFF("cloud_off", "云端不可用", IconCategory.SYSTEM, "CloudOff CloudOff", IconMotion.PULSE, false),
    PUBLIC("public", "网络与语言", IconCategory.SYSTEM, "Public Public", IconMotion.PULSE, false),
    LOCK("lock", "隐私与锁定", IconCategory.SYSTEM, "Lock Lock", IconMotion.PULSE, false),
    UNLOCK("unlock", "解锁", IconCategory.SYSTEM, "LockOpen Unlock", IconMotion.PULSE, false),
    PASSWORD_RESET("password_reset", "重置密码", IconCategory.SYSTEM, "LockReset PasswordReset", IconMotion.PULSE, false),
    KEY("key", "密钥与密码", IconCategory.SYSTEM, "Key Key", IconMotion.PULSE, false),
    LOGOUT("logout", "退出登录", IconCategory.SYSTEM, "Logout Logout", IconMotion.NUDGE, false),
    VERIFIED("verified", "验证与安全", IconCategory.SYSTEM, "VerifiedUser Verified", IconMotion.PULSE, false),
    ADMIN("admin", "管理员", IconCategory.SYSTEM, "AdminPanelSettings Admin", IconMotion.PULSE, false),
    MODERATION("moderation", "审核管理", IconCategory.SYSTEM, "Gavel Moderation", IconMotion.PULSE, false),
    REPORT("report", "举报", IconCategory.SYSTEM, "Report Report", IconMotion.PULSE, false),
    BLOCK("block", "屏蔽与禁止", IconCategory.SYSTEM, "Block Block", IconMotion.PULSE, false),
    INFO("info", "信息与说明", IconCategory.SYSTEM, "Info Info", IconMotion.PULSE, false),
    HELP("help", "帮助", IconCategory.SYSTEM, "HelpOutline Help", IconMotion.PULSE, false),
    ERROR("error", "错误与注意", IconCategory.SYSTEM, "ErrorOutline Error", IconMotion.PULSE, false),
    BUG("bug", "问题反馈", IconCategory.SYSTEM, "BugReport Bug", IconMotion.PULSE, false),
    CODE("code", "开发与开源", IconCategory.SYSTEM, "Code Code", IconMotion.PULSE, false),
    CLEAN("clean", "清理缓存", IconCategory.SYSTEM, "CleaningServices Clean", IconMotion.PULSE, false),
    UPDATE("update", "应用更新", IconCategory.SYSTEM, "SystemUpdate Update", IconMotion.PULSE, false),
    IDEA("idea", "建议与灵感", IconCategory.SYSTEM, "Lightbulb Idea", IconMotion.PULSE, false),
    TROPHY("trophy", "奖杯与成就", IconCategory.RATING, "EmojiEvents Trophy", IconMotion.PULSE, false),
    GLOWING_STAR("glowing_star", "亮闪星评分", IconCategory.RATING, " GlowingStar", IconMotion.PULSE, false),
    PAW("paw", "猫爪评分", IconCategory.RATING, " Paw", IconMotion.PULSE, false),
    CAT("cat", "猫脸评分", IconCategory.RATING, " Cat", IconMotion.PULSE, false),
    FIRE("fire", "火焰评分", IconCategory.RATING, " Fire", IconMotion.PULSE, false),
    DIAMOND("diamond", "钻石评分", IconCategory.RATING, " Diamond", IconMotion.PULSE, false),
    THUMBS_UP("thumbs_up", "点赞评分", IconCategory.RATING, " ThumbsUp", IconMotion.PULSE, false),
    CLOVER("clover", "四叶草评分", IconCategory.RATING, " Clover", IconMotion.PULSE, false),
    BLOSSOM("blossom", "樱花评分", IconCategory.RATING, " Blossom", IconMotion.PULSE, false),
    ;
    companion object {
        private val byStorageKey = entries.associateBy { it.storageKey }
        fun fromStorage(value: String?): AppIconId? = byStorageKey[value]
    }
}

/** Active state is independent of the user-selected A/B design. */
@Immutable
data class IconGlyph(val id: AppIconId, val active: Boolean = false) {
    fun selected(value: Boolean = true): IconGlyph = copy(active = value)
}

object AppIcons {
    val Tracker = IconGlyph(AppIconId.TRACKER)
    val Discover = IconGlyph(AppIconId.DISCOVER)
    val Calendar = IconGlyph(AppIconId.CALENDAR)
    val Community = IconGlyph(AppIconId.COMMUNITY)
    val Statistics = IconGlyph(AppIconId.STATISTICS)
    val Profile = IconGlyph(AppIconId.PROFILE)
    val Search = IconGlyph(AppIconId.SEARCH)
    val Add = IconGlyph(AppIconId.ADD)
    val AddCircle = IconGlyph(AppIconId.ADD_CIRCLE)
    val Close = IconGlyph(AppIconId.CLOSE)
    val Back = IconGlyph(AppIconId.BACK)
    val Forward = IconGlyph(AppIconId.FORWARD)
    val Up = IconGlyph(AppIconId.UP)
    val Down = IconGlyph(AppIconId.DOWN)
    val ChevronRight = IconGlyph(AppIconId.CHEVRON_RIGHT)
    val ChevronLeft = IconGlyph(AppIconId.CHEVRON_LEFT)
    val Expand = IconGlyph(AppIconId.EXPAND)
    val Collapse = IconGlyph(AppIconId.COLLAPSE)
    val Edit = IconGlyph(AppIconId.EDIT)
    val EditNote = IconGlyph(AppIconId.EDIT_NOTE)
    val Delete = IconGlyph(AppIconId.DELETE)
    val DeleteForever = IconGlyph(AppIconId.DELETE_FOREVER)
    val DeleteSweep = IconGlyph(AppIconId.DELETE_SWEEP)
    val Check = IconGlyph(AppIconId.CHECK)
    val CheckCircle = IconGlyph(AppIconId.CHECK_CIRCLE)
    val Refresh = IconGlyph(AppIconId.REFRESH)
    val Sync = IconGlyph(AppIconId.SYNC)
    val Undo = IconGlyph(AppIconId.UNDO)
    val History = IconGlyph(AppIconId.HISTORY)
    val Download = IconGlyph(AppIconId.DOWNLOAD)
    val Upload = IconGlyph(AppIconId.UPLOAD)
    val Share = IconGlyph(AppIconId.SHARE)
    val Copy = IconGlyph(AppIconId.COPY)
    val Save = IconGlyph(AppIconId.SAVE)
    val MoreVertical = IconGlyph(AppIconId.MORE_VERTICAL)
    val MoreHorizontal = IconGlyph(AppIconId.MORE_HORIZONTAL)
    val Sort = IconGlyph(AppIconId.SORT)
    val Filter = IconGlyph(AppIconId.FILTER)
    val Tune = IconGlyph(AppIconId.TUNE)
    val SwapVertical = IconGlyph(AppIconId.SWAP_VERTICAL)
    val SwapHorizontal = IconGlyph(AppIconId.SWAP_HORIZONTAL)
    val SelectAll = IconGlyph(AppIconId.SELECT_ALL)
    val Drag = IconGlyph(AppIconId.DRAG)
    val OpenExternal = IconGlyph(AppIconId.OPEN_EXTERNAL)
    val Link = IconGlyph(AppIconId.LINK)
    val Unlink = IconGlyph(AppIconId.UNLINK)
    val Merge = IconGlyph(AppIconId.MERGE)
    val RestoreTrash = IconGlyph(AppIconId.RESTORE_TRASH)
    val Archive = IconGlyph(AppIconId.ARCHIVE)
    val Collection = IconGlyph(AppIconId.COLLECTION)
    val Bookmark = IconGlyph(AppIconId.BOOKMARK)
    val BookmarkFilled = Bookmark.selected()
    val Tag = IconGlyph(AppIconId.TAG)
    val Hash = IconGlyph(AppIconId.HASH)
    val FolderShared = IconGlyph(AppIconId.FOLDER_SHARED)
    val Book = IconGlyph(AppIconId.BOOK)
    val FileOpen = IconGlyph(AppIconId.FILE_OPEN)
    val Grid = IconGlyph(AppIconId.GRID)
    val List = IconGlyph(AppIconId.LIST)
    val Table = IconGlyph(AppIconId.TABLE)
    val Pin = IconGlyph(AppIconId.PIN)
    val Storage = IconGlyph(AppIconId.STORAGE)
    val Hub = IconGlyph(AppIconId.HUB)
    val Image = IconGlyph(AppIconId.IMAGE)
    val PhotoLibrary = IconGlyph(AppIconId.PHOTO_LIBRARY)
    val ImageSearch = IconGlyph(AppIconId.IMAGE_SEARCH)
    val AddPhoto = IconGlyph(AppIconId.ADD_PHOTO)
    val Crop = IconGlyph(AppIconId.CROP)
    val Play = IconGlyph(AppIconId.PLAY)
    val Skip = IconGlyph(AppIconId.SKIP)
    val Television = IconGlyph(AppIconId.TELEVISION)
    val Sparkles = IconGlyph(AppIconId.SPARKLES)
    val Magic = IconGlyph(AppIconId.MAGIC)
    val Group = IconGlyph(AppIconId.GROUP)
    val Account = IconGlyph(AppIconId.ACCOUNT)
    val PersonAdd = IconGlyph(AppIconId.PERSON_ADD)
    val PersonSearch = IconGlyph(AppIconId.PERSON_SEARCH)
    val Chat = IconGlyph(AppIconId.CHAT)
    val Send = IconGlyph(AppIconId.SEND)
    val Heart = IconGlyph(AppIconId.HEART)
    val HeartFilled = Heart.selected()
    val Star = IconGlyph(AppIconId.STAR)
    val StarFilled = Star.selected()
    val Vote = IconGlyph(AppIconId.VOTE)
    val Smile = IconGlyph(AppIconId.SMILE)
    val Male = IconGlyph(AppIconId.MALE)
    val Female = IconGlyph(AppIconId.FEMALE)
    val Birthday = IconGlyph(AppIconId.BIRTHDAY)
    val Blood = IconGlyph(AppIconId.BLOOD)
    val Location = IconGlyph(AppIconId.LOCATION)
    val Settings = IconGlyph(AppIconId.SETTINGS)
    val SettingsDepth = IconGlyph(AppIconId.SETTINGS_DEPTH)
    val Palette = IconGlyph(AppIconId.PALETTE)
    val ColorPicker = IconGlyph(AppIconId.COLOR_PICKER)
    val DarkMode = IconGlyph(AppIconId.DARK_MODE)
    val Widgets = IconGlyph(AppIconId.WIDGETS)
    val Branding = IconGlyph(AppIconId.BRANDING)
    val Touch = IconGlyph(AppIconId.TOUCH)
    val Visibility = IconGlyph(AppIconId.VISIBILITY)
    val VisibilityOff = IconGlyph(AppIconId.VISIBILITY_OFF)
    val Notification = IconGlyph(AppIconId.NOTIFICATION)
    val NotificationOff = IconGlyph(AppIconId.NOTIFICATION_OFF)
    val Alarm = IconGlyph(AppIconId.ALARM)
    val Clock = IconGlyph(AppIconId.CLOCK)
    val CalendarOff = IconGlyph(AppIconId.CALENDAR_OFF)
    val Cloud = IconGlyph(AppIconId.CLOUD)
    val CloudDownload = IconGlyph(AppIconId.CLOUD_DOWNLOAD)
    val CloudUpload = IconGlyph(AppIconId.CLOUD_UPLOAD)
    val CloudDone = IconGlyph(AppIconId.CLOUD_DONE)
    val CloudOff = IconGlyph(AppIconId.CLOUD_OFF)
    val Public = IconGlyph(AppIconId.PUBLIC)
    val Lock = IconGlyph(AppIconId.LOCK)
    val Unlock = IconGlyph(AppIconId.UNLOCK)
    val PasswordReset = IconGlyph(AppIconId.PASSWORD_RESET)
    val Key = IconGlyph(AppIconId.KEY)
    val Logout = IconGlyph(AppIconId.LOGOUT)
    val Verified = IconGlyph(AppIconId.VERIFIED)
    val Admin = IconGlyph(AppIconId.ADMIN)
    val Moderation = IconGlyph(AppIconId.MODERATION)
    val Report = IconGlyph(AppIconId.REPORT)
    val Block = IconGlyph(AppIconId.BLOCK)
    val Info = IconGlyph(AppIconId.INFO)
    val Help = IconGlyph(AppIconId.HELP)
    val Error = IconGlyph(AppIconId.ERROR)
    val Bug = IconGlyph(AppIconId.BUG)
    val Code = IconGlyph(AppIconId.CODE)
    val Clean = IconGlyph(AppIconId.CLEAN)
    val Update = IconGlyph(AppIconId.UPDATE)
    val Idea = IconGlyph(AppIconId.IDEA)
    val Trophy = IconGlyph(AppIconId.TROPHY)
    val GlowingStar = IconGlyph(AppIconId.GLOWING_STAR)
    val Paw = IconGlyph(AppIconId.PAW)
    val Cat = IconGlyph(AppIconId.CAT)
    val Fire = IconGlyph(AppIconId.FIRE)
    val Diamond = IconGlyph(AppIconId.DIAMOND)
    val ThumbsUp = IconGlyph(AppIconId.THUMBS_UP)
    val Clover = IconGlyph(AppIconId.CLOVER)
    val Blossom = IconGlyph(AppIconId.BLOSSOM)
}
