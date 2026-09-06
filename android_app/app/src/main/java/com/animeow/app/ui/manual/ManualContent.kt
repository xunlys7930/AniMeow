package com.animeow.app.ui.manual

/**
 * 用户手册内容数据模型。
 *
 * 每个条目记录功能名称、简述、在 App 中的位置路径以及搜索关键词，
 * 供 [UserManualScreen] 展示和搜索过滤。
 */

/** 手册中的一条条目。 */
data class ManualEntry(
    val title: String,
    val description: String,
    /** 用户从哪里找到这个功能，格式如 "我的 > 帮助与维护 > 关于与维护"。 */
    val location: String,
    /** 搜索匹配关键词（逗号分隔）。 */
    val keywords: String = "",
)

/** 手册的一个分组。 */
data class ManualSection(
    val title: String,
    val entries: List<ManualEntry>,
)

/** 返回用户手册的全部分组。 */
fun manualSections(): List<ManualSection> = listOf(
    ManualSection(
        title = "入门指南",
        entries = listOf(
            ManualEntry(
                title = "底部导航栏",
                description = "底部导航栏提供追番、发现、日历、社区、统计、我的六个主要入口。可以在「我的 > 导航与启动」中隐藏不需要的标签或调整顺序。",
                location = "App 底部导航栏",
                keywords = "导航,底部,Tab,标签栏,主页,首页,入口",
            ),
            ManualEntry(
                title = "快速添加作品",
                description = "在追番页点击右下角的浮动 + 按钮打开快速添加面板，可选择在线搜索、以图搜番、手动新建、Bangumi 导入或 Excel/CSV 搬家。",
                location = "追番页 > 右下角 + 按钮",
                keywords = "添加,新建,导入,快速,添加,FAB,加号,追番",
            ),
            ManualEntry(
                title = "全局搜索",
                description = "在任意主页面顶栏点击搜索图标进入全局搜索。搜索页内置 16 个快捷动作卡片，可直达各种功能页面。支持搜索历史记录。",
                location = "任意主页面 > 顶栏搜索图标",
                keywords = "搜索,全局,查找,快捷,搜索框",
            ),
            ManualEntry(
                title = "左右滑动切换页面",
                description = "在主页面上左右滑动可以快速切换底部导航栏的标签页。可在「我的 > 导航与启动」中查看相关设置。",
                location = "主页面左右滑动",
                keywords = "滑动,切换,手势,左右,页面",
            ),
        ),
    ),
    ManualSection(
        title = "外观与个性化",
        entries = listOf(
            ManualEntry(
                title = "整体风格与界面版本",
                description = "在「我的」页面顶栏点击调色板图标，或展开「常用设置」中的「整体风格与界面版本」，可切换新版/经典版前端，以及选择 MIUIX / 二次元元气 / 赛博玻璃 / 复古像素 等视觉套件。",
                location = "我的 > 顶栏调色板图标 / 我的 > 常用设置 > 整体风格与界面版本",
                keywords = "风格,主题,界面,版本,新版,经典,MIUIX,二次元,赛博,复古,外观",
            ),
            ManualEntry(
                title = "外观定制",
                description = "深度定制外观：主题模式（浅色/深色/跟随系统）、动态取色、字体缩放、圆角缩放、网格列数、封面角标样式、评分图标、封面宽高比、卡片样式、动效等级、页面切换动画等数十项参数。",
                location = "我的 > 常用设置 > 外观定制",
                keywords = "定制,外观,主题,深色,夜间,字体,圆角,网格,封面,角标,动画,动效,颜色",
            ),
            ManualEntry(
                title = "黑夜模式",
                description = "快速切换深色/浅色/跟随系统主题。也可在外观定制中设置。",
                location = "我的 > 常用设置 > 黑夜模式",
                keywords = "夜间,深色,黑暗,主题,模式,Dark",
            ),
            ManualEntry(
                title = "品牌定制",
                description = "自定义启动闪屏图片、显示时长、缩放模式、背景模式，以及切换启动器图标。",
                location = "我的 > 常用设置 > 外观定制 > 品牌定制",
                keywords = "品牌,闪屏,启动图,图标,Launcher,启动器",
            ),
            ManualEntry(
                title = "封面信息显示",
                description = "在追番页可以自定义封面是否显示标题、状态、评分、进度、系列数、更新星期等。还可调整信息透明度、背景不透明度、圆角大小、封面饱和度等。",
                location = "追番页 > 顶栏 Tune 图标 > 封面信息",
                keywords = "封面,信息,标题,状态,评分,进度,透明度,圆角,饱和度",
            ),
            ManualEntry(
                title = "启用卡片滑动快捷操作",
                description = "在追番页启用卡片左右滑动操作，可自定义滑动起始动作（如增加集数）和结束动作（如切换状态）。",
                location = "我的 > 常用设置 > 外观定制 > 启用卡片滑动快捷操作",
                keywords = "滑动,卡片,手势,左滑,右滑,操作",
            ),
            ManualEntry(
                title = "预测性返回手势",
                description = "启用 Android 预测性返回手势，返回时可预览上一页面。",
                location = "我的 > 常用设置 > 预测性返回手势",
                keywords = "返回,手势,预测,Back,返回",
            ),
            ManualEntry(
                title = "触觉与声音反馈",
                description = "在外观定制中可开启操作时的触觉反馈（震动）和声音反馈。",
                location = "我的 > 常用设置 > 外观定制 > 触觉/声音反馈",
                keywords = "震动,触觉,声音,反馈,振动",
            ),
        ),
    ),
    ManualSection(
        title = "资料库管理",
        entries = listOf(
            ManualEntry(
                title = "系列书架",
                description = "将同一系列的作品归组展示，方便管理多季/多部关联作品。",
                location = "我的 > 资料库工具 > 系列书架",
                keywords = "系列,书架,分组,关联,多季",
            ),
            ManualEntry(
                title = "标签索引",
                description = "按标签浏览和筛选资料库中的作品，支持标签集合查看。",
                location = "我的 > 资料库工具 > 标签索引",
                keywords = "标签,索引,分类,筛选,Tag",
            ),
            ManualEntry(
                title = "回收站",
                description = "已删除的作品会进入回收站，可恢复或彻底清除。",
                location = "我的 > 资料库工具 > 回收站",
                keywords = "回收站,删除,恢复,已删除,Trash",
            ),
            ManualEntry(
                title = "查重与合并",
                description = "检测资料库中的重复作品，支持合并条目以消除冗余。",
                location = "我的 > 资料库工具 > 查重与合并",
                keywords = "查重,重复,合并,去重,Duplicate",
            ),
            ManualEntry(
                title = "状态与标签管理",
                description = "管理自定义状态标签（如在看、看完、搁置等），编辑状态名称和排序。",
                location = "全局搜索 > 快捷动作 > 状态标签管理 / 我的 > 搜索",
                keywords = "状态,标签,管理,自定义,状态管理",
            ),
            ManualEntry(
                title = "追番提醒",
                description = "为作品设置更新提醒，到时间会发送通知。可管理所有提醒规则。",
                location = "我的 > 常用设置 > 追番提醒",
                keywords = "提醒,通知,更新,Reminder,推送",
            ),
        ),
    ),
    ManualSection(
        title = "数据与迁移",
        entries = listOf(
            ManualEntry(
                title = "导出完整备份",
                description = "将资料库完整导出为 ZIP 备份文件，包含所有作品、角色、标签等数据。",
                location = "我的 > 数据与迁移 > 导出完整备份",
                keywords = "导出,备份,Backup,ZIP,保存",
            ),
            ManualEntry(
                title = "恢复原生备份",
                description = "从 ZIP 备份文件恢复数据。",
                location = "我的 > 数据与迁移 > 恢复原生备份",
                keywords = "恢复,导入,备份,Restore,还原",
            ),
            ManualEntry(
                title = "导入原版数据",
                description = "兼容导入原版 AniMeow 的 ZIP/DB 数据文件。",
                location = "我的 > 数据与迁移 > 导入原版数据",
                keywords = "导入,原版,旧版,迁移,兼容",
            ),
            ManualEntry(
                title = "导入 Bangumi 收藏",
                description = "通过 Bangumi 用户名导入公开收藏列表，批量匹配并添加到资料库。",
                location = "我的 > 数据与迁移 > 导入 Bangumi / 追番页 > + > 导入 Bangumi",
                keywords = "Bangumi,导入,收藏,批量,bgm",
            ),
            ManualEntry(
                title = "Excel / CSV 搬家",
                description = "从 Excel/CSV 表格文件批量导入作品列表，支持从其他追番应用迁移数据。",
                location = "我的 > 数据与迁移 > Excel/CSV 搬家 / 追番页 > + > 表格搬家",
                keywords = "Excel,CSV,表格,搬家,迁移,导入",
            ),
        ),
    ),
    ManualSection(
        title = "云账号与同步",
        entries = listOf(
            ManualEntry(
                title = "云账号",
                description = "注册/登录云账号后可使用社区、角色社区、云同步等功能。在社区页或「我的」顶部 Banner 可进入。",
                location = "我的 > 顶部 Banner > 云账号 / 社区页 > 登录",
                keywords = "云账号,登录,注册,账号,云,Cloud",
            ),
            ManualEntry(
                title = "自动云同步",
                description = "开启后自动在后台同步资料库到云端，支持设置同步频率（如每 24 小时）、仅在 Wi-Fi 下同步、冲突处理策略等。",
                location = "我的 > 顶部 Banner > 云账号 > 云同步设置",
                keywords = "同步,云同步,自动,备份,设备,冲突",
            ),
            ManualEntry(
                title = "编辑社区资料",
                description = "登录后可编辑社区昵称、签名、头像、隐私设置和统计名片展示。",
                location = "我的 > 常用设置 > 编辑个人资料",
                keywords = "资料,昵称,签名,头像,社区资料,个人",
            ),
        ),
    ),
    ManualSection(
        title = "社区功能",
        entries = listOf(
            ManualEntry(
                title = "社区群聊",
                description = "官方社区群聊，支持发帖（文字+图片）、互动。登录云账号后可发言，未登录可浏览。",
                location = "底部导航栏 > 社区 / 发现页 > 社区按钮",
                keywords = "社区,群聊,发帖,帖子,聊天,交流",
            ),
            ManualEntry(
                title = "社区图片保存",
                description = "在社区帖子中点击图片可全屏查看，支持双指缩放，点击下载按钮可保存到手机相册。",
                location = "社区 > 点击帖子图片 > 下载按钮",
                keywords = "图片,保存,下载,相册,社区图片",
            ),
            ManualEntry(
                title = "社区消息通知",
                description = "在社区页可开启消息接收，每 30 秒自动刷新最新消息。",
                location = "社区 > 顶栏 > 使用群码 / 消息设置",
                keywords = "消息,通知,接收,群码,社区",
            ),
            ManualEntry(
                title = "角色社区",
                description = "浏览和分享角色群组到社区，查看其他用户分享的角色组。",
                location = "我的 > 探索与扩展 > 角色管理 > 顶栏群组图标 > 详情页云上传图标",
                keywords = "角色社区,分享,角色组,上传,社区",
            ),
        ),
    ),
    ManualSection(
        title = "探索与扩展",
        entries = listOf(
            ManualEntry(
                title = "AI 看番风格分析",
                description = "基于你的看番记录生成偏好分析报告，包括类型偏好、评分趋势等。每日限生成一次。",
                location = "我的 > 探索与扩展 > AI 看番风格 / 统计页",
                keywords = "AI,分析,风格,偏好,报告,统计",
            ),
            ManualEntry(
                title = "趣味评级 Tier List",
                description = "拖动作品分档（S/A/B/C/D），创建趣味排行榜并分享。可自定义档位数量和标签。",
                location = "我的 > 探索与扩展 > 趣味评级 Tier List",
                keywords = "Tier,List,评级,排行,趣味,分档,SABCD",
            ),
            ManualEntry(
                title = "角色管理与关系星图",
                description = "管理作品角色、角色之间的关系网络。支持角色图片、评分、元数据展示。点击作品详情页中的角色入口也可进入。",
                location = "我的 > 探索与扩展 > 角色管理 / 作品详情 > 角色",
                keywords = "角色,管理,星图,关系,网络,人物",
            ),
            ManualEntry(
                title = "角色群组",
                description = "创建角色组（如某部作品的所有角色），可上传到社区分享。入口在角色管理页顶栏的群组图标。",
                location = "我的 > 探索与扩展 > 角色管理 > 顶栏群组图标",
                keywords = "角色组,群组,角色,创建,分享",
            ),
            ManualEntry(
                title = "以图搜番",
                description = "上传或拍摄图片，通过反向搜索找到对应的作品。",
                location = "发现页 > 顶栏以图搜番图标 / 追番页 > + > 以图搜番",
                keywords = "以图搜番,图片搜索,反搜,搜图,Image Search",
            ),
        ),
    ),
    ManualSection(
        title = "导航与启动设置",
        entries = listOf(
            ManualEntry(
                title = "显示/隐藏导航标签",
                description = "可隐藏发现、日历、社区、统计四个底部标签。隐藏后仍可从其他入口访问。",
                location = "我的 > 导航与启动 > 显示发现/日历/社区/统计",
                keywords = "显示,隐藏,导航,标签,Tab,底部",
            ),
            ManualEntry(
                title = "导航顺序调整",
                description = "通过上移/下移按钮自定义底部导航栏标签的排列顺序。",
                location = "我的 > 导航与启动 > 导航顺序",
                keywords = "顺序,导航,排列,上移,下移,排序",
            ),
            ManualEntry(
                title = "启动落点",
                description = "选择打开 App 时默认显示的页面（如追番、发现、社区等）。",
                location = "我的 > 导航与启动 > 启动落点",
                keywords = "启动,落点,默认,首页,打开,起始页",
            ),
        ),
    ),
    ManualSection(
        title = "统计与数据",
        entries = listOf(
            ManualEntry(
                title = "数据统计",
                description = "查看资料库的统计数据，包括作品总数、评分分布、状态分布、类型分布、标签词云等。支持自定义统计模块顺序和显示。",
                location = "底部导航栏 > 统计 / 我的 > 资料库工具 > 数据统计",
                keywords = "统计,数据,分析,分布,词云,图表",
            ),
            ManualEntry(
                title = "统计模块自定义",
                description = "可调整统计页模块的顺序、隐藏不需要的模块、选择图表样式（环形/柱状等）。",
                location = "统计页 > 顶栏 Tune 图标",
                keywords = "统计,模块,顺序,隐藏,图表,样式,自定义",
            ),
            ManualEntry(
                title = "日历",
                description = "按日历查看每日更新番剧，支持周视图和月视图。可自定义显示封面、标记样式及默认打开视图（月/周/议程）。",
                location = "底部导航栏 > 日历",
                keywords = "日历,更新,日程,每周,每月,放送",
            ),
        ),
    ),
    ManualSection(
        title = "发现页",
        entries = listOf(
            ManualEntry(
                title = "在线搜索作品",
                description = "在发现页搜索 Bangumi 条目库，按评分、日期、标签等筛选，添加到资料库。",
                location = "底部导航栏 > 发现",
                keywords = "发现,搜索,在线,Bangumi,条目,查找",
            ),
            ManualEntry(
                title = "Bangumi API 代理设置",
                description = "由于 Bangumi API 在部分网络环境下可能无法直接访问，可配置 API 代理和图片代理。默认使用代理优先策略确保图片加载。",
                location = "发现页 > 顶栏 Tune 图标 > 网络设置",
                keywords = "代理,Bangumi,API,网络,图片,加载失败,proxy",
            ),
            ManualEntry(
                title = "发现页显示设置",
                description = "自定义发现页的布局（列表/网格）、列数、密度、是否显示来源/评分/开播日期/标签等。",
                location = "发现页 > 顶栏 Tune 图标",
                keywords = "发现,显示,布局,网格,列表,列数,密度",
            ),
        ),
    ),
    ManualSection(
        title = "帮助与维护",
        entries = listOf(
            ManualEntry(
                title = "用户使用手册",
                description = "本手册！可在设置中查看完整手册，也可设置每日首次打开 App 时弹出提示。",
                location = "我的 > 帮助与维护 > 用户使用手册",
                keywords = "手册,使用,帮助,指南,教程,说明",
            ),
            ManualEntry(
                title = "每日手册提示",
                description = "设置每日首次打开 App 时是否弹出手册提示。可选每日显示、今日不显示、永不显示。",
                location = "我的 > 帮助与维护 > 用户使用手册 > 每日提示设置",
                keywords = "每日,手册,提示,弹窗,显示,设置",
            ),
            ManualEntry(
                title = "开放反馈池",
                description = "浏览公开的用户建议和问题反馈，提交自己的功能想法或 Bug 报告。",
                location = "我的 > 帮助与维护 > 开放反馈池",
                keywords = "反馈,建议,Bug,问题,反馈池,意见",
            ),
            ManualEntry(
                title = "帮助与常见问题",
                description = "使用指南、排障与数据安全说明。",
                location = "我的 > 帮助与维护 > 帮助与常见问题",
                keywords = "帮助,FAQ,常见问题,排障,指南",
            ),
            ManualEntry(
                title = "关于与维护",
                description = "查看版本信息、检查更新、缓存管理、更新日志、免责声明与开源鸣谢。",
                location = "我的 > 帮助与维护 > 关于与维护",
                keywords = "关于,版本,更新,缓存,日志,声明,鸣谢,维护",
            ),
            ManualEntry(
                title = "诊断与操作轨迹",
                description = "查看本地日志、崩溃恢复记录，支持脱敏导出用于问题排查。",
                location = "我的 > 帮助与维护 > 诊断与操作轨迹",
                keywords = "诊断,日志,崩溃,轨迹,调试,导出",
            ),
            ManualEntry(
                title = "缓存管理",
                description = "查看和清理图片缓存、数据库等占用空间。",
                location = "我的 > 帮助与维护 > 关于与维护 > 缓存管理",
                keywords = "缓存,清理,空间,存储,Cache",
            ),
            ManualEntry(
                title = "更新日志",
                description = "查看每个版本的更新内容和改动记录。",
                location = "我的 > 帮助与维护 > 关于与维护 > 更新日志",
                keywords = "更新日志,版本,Changelog,更新内容,改动",
            ),
        ),
    ),
    ManualSection(
        title = "快捷操作与小技巧",
        entries = listOf(
            ManualEntry(
                title = "全局搜索快捷动作",
                description = "全局搜索页内置 16 个快捷动作卡片，可直达外观定制、资料库管理、云账号、社区、诊断等各种功能。忘记功能在哪时，先去搜索页看看。",
                location = "任意主页面 > 顶栏搜索图标 > 快捷动作卡片",
                keywords = "搜索,快捷,动作,卡片,直达,入口,快捷方式",
            ),
            ManualEntry(
                title = "作品详情页模块定制",
                description = "在作品详情页可自定义模块顺序、隐藏不需要的模块。",
                location = "作品详情页 > 顶栏 Tune 图标",
                keywords = "详情,模块,顺序,隐藏,定制",
            ),
            ManualEntry(
                title = "自动完成状态",
                description = "达到总集数后自动归纳到指定状态（如「看完」），可在设置中自定义归纳状态标签。",
                location = "追番页 > 顶栏 Tune 图标 > 达到总集数后自动归纳",
                keywords = "自动,完成,状态,看完,标签,归纳",
            ),
            ManualEntry(
                title = "剪贴板分享码识别",
                description = "开启后，复制包含分享码的内容并打开 App 时自动检测并提示跳转。",
                location = "我的 > 常用设置 > 外观定制 > 剪贴板分享码识别",
                keywords = "剪贴板,分享,检测,自动,跳转,复制",
            ),
            ManualEntry(
                title = "每次启动随机强调色",
                description = "开启后每次启动 App 随机更换主题强调色，也可手动设置固定强调色。",
                location = "我的 > 常用设置 > 外观定制 > 每次启动随机强调色",
                keywords = "强调色,随机,颜色,主题色,变色",
            ),
        ),
    ),
)

/** 用于每日提示的精选条目（从所有条目中挑选适合做每日小贴士的）。 */
fun dailyTips(): List<ManualEntry> = manualSections()
    .flatMap { it.entries }
    .filter { entry ->
        entry.keywords.contains("快捷") ||
            entry.keywords.contains("滑动") ||
            entry.keywords.contains("搜索") ||
            entry.keywords.contains("社区") ||
            entry.keywords.contains("角色") ||
            entry.keywords.contains("备份") ||
            entry.keywords.contains("同步") ||
            entry.keywords.contains("手册") ||
            entry.keywords.contains("Tier") ||
            entry.keywords.contains("AI") ||
            entry.keywords.contains("以图搜番") ||
            entry.keywords.contains("提醒") ||
            entry.keywords.contains("回收站") ||
            entry.keywords.contains("统计") ||
            entry.keywords.contains("标签") ||
            entry.keywords.contains("导入") ||
            entry.keywords.contains("定制")
    }
