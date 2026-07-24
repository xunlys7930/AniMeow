# 追番喵 (AniMeow) 代码百科

> 萌萌的二次元追番进度管理工具

**项目版本**: v1.3.1+18  
**Flutter SDK**: ≥3.24 | **Dart SDK**: ≥3.5  
**官方支持平台**: Android, Windows

---

## 目录

1. [项目概览](#1-项目概览)
2. [技术架构](#2-技术架构)
3. [目录结构](#3-目录结构)
4. [核心模块详解](#4-核心模块详解)
5. [数据库设计](#5-数据库设计)
6. [依赖关系](#6-依赖关系)
7. [运行与构建](#7-运行与构建)
8. [附录](#8-附录)

---

## 1. 项目概览

### 1.1 项目简介

追番喵 (AniMeow) 是一款面向二次元爱好者的动漫/漫画/小说追番进度管理工具，支持多源数据同步、灵活的状态管理、多样化的布局展示以及完善的本地数据备份机制。

### 1.2 核心功能

| 功能模块 | 说明 |
|---------|------|
| 多源数据同步 | 支持 Bangumi API 和 AniList GraphQL API 双源搜索匹配 |
| 状态管理 | 默认状态（在看/看完/未看/弃坑）+ 自定义无限状态 |
| 首页布局 | 4种布局模式：智能聚合 Bento、卡片流、海报墙、紧凑索引 |
| 详情页布局 | 4种预设布局 + 模块拖拽重排 + 显隐切换 |
| 系列聚合 | 同系列番剧自动聚合，支持系列详情页管理 |
| 可视化统计 | 月度观看节奏、制作公司分布等图表 |
| 追番日历 | 每周放送番剧一目了然 |
| 追番提醒 | 每周定时通知推送 |
| 数据备份 | SQLite 本地存储，支持全量导出/恢复 |
| Excel 导入 | 支持从旧清单一键迁移数据 |

---

## 2. 技术架构

### 2.1 技术栈

```
┌─────────────────────────────────────────────┐
│                  Flutter UI                  │
│  Material 3 Expressive Design System        │
├─────────────────────────────────────────────┤
│              State Management               │
│  Provider + ValueNotifier                  │
├──────────────────┬──────────────────────────┤
│    Data Layer    │     Network Layer        │
│  SQLite (sqflite)│  http / dio              │
├──────────────────┴──────────────────────────┤
│           Platform Integration              │
│  iOS / Android / Windows / macOS / Linux   │
└─────────────────────────────────────────────┘
```

### 2.2 架构模式

采用 **Clean Architecture** 分层架构，各层职责清晰：

- **UI Layer** (`lib/ui/`)：界面展示、视图组件、布局逻辑
- **Business Layer** (`lib/providers/`、业务逻辑页)：状态管理、页面业务逻辑
- **Data Layer** (`lib/api/`、`lib/db/`、`lib/utils/`)：数据获取、持久化、工具函数

### 2.3 状态管理模式

使用 **Provider** 作为全局状态管理方案，结合 **ValueNotifier** 实现细粒度响应式更新：

```dart
// Provider 根配置 (main.dart)
MultiProvider(
  providers: [
    ChangeNotifierProvider(create: (_) => DataRefreshProvider()),
  ],
  child: MyApp(),
)
```

### 2.4 单例模式

核心服务采用单例模式确保全局唯一实例：

```dart
class DatabaseHelper {
  static final DatabaseHelper _instance = DatabaseHelper._internal();
  factory DatabaseHelper() => _instance;
  DatabaseHelper._internal();
}

class SettingsManager {
  static final SettingsManager _instance = SettingsManager._internal();
  factory SettingsManager() => _instance;
  SettingsManager._internal();
}

class NotificationService {
  static final NotificationService _instance = NotificationService._internal();
  factory NotificationService() => _instance;
  NotificationService._internal();
}
```

---

## 3. 目录结构

```
anime_tracker/
├── lib/                          # Flutter 应用核心代码
│   ├── main.dart                 # 应用入口
│   ├── main_shell.dart           # 4-Tab 主壳层
│   │
│   ├── api/                      # API 服务层
│   │   ├── anilist_service.dart  # AniList GraphQL API
│   │   ├── bangumi_service.dart  # Bangumi REST API
│   │   └── update_service.dart   # 版本更新检查
│   │
│   ├── db/                       # 数据持久化层
│   │   └── database_helper.dart  # SQLite 数据库操作
│   │
│   ├── providers/                # 全局状态管理
│   │   └── data_refresh_provider.dart
│   │
│   ├── settings/                 # 设置页面
│   │   ├── settings_general_page.dart
│   │   ├── settings_display_page.dart
│   │   ├── settings_data_page.dart
│   │   └── ...
│   │
│   ├── ui/                       # 界面层
│   │   ├── anime_detail/         # 番剧详情页
│   │   │   ├── anime_detail_page.dart
│   │   │   ├── detail_layout.dart
│   │   │   ├── detail_props.dart
│   │   │   ├── layouts/           # 4种详情布局
│   │   │   │   ├── classic_layout.dart
│   │   │   │   ├── dashboard_layout.dart
│   │   │   │   ├── magazine_layout.dart
│   │   │   │   └── minimal_layout.dart
│   │   │   └── modules/           # 详情页可定制模块
│   │   │       ├── header_module.dart
│   │   │       ├── detail_meta_module.dart
│   │   │       ├── status_progress_module.dart
│   │   │       └── ...
│   │   │
│   │   ├── views/                # 首页视图
│   │   │   ├── home_layout.dart  # 布局枚举和工厂
│   │   │   ├── bento_home_view.dart
│   │   │   ├── card_feed_view.dart
│   │   │   ├── poster_wall_view.dart
│   │   │   ├── compact_index_view.dart
│   │   │   └── _shared/          # 共享卡片组件
│   │   │       ├── anime_poster_card.dart
│   │   │       └── anime_list_card.dart
│   │   │
│   │   ├── components/           # 通用组件
│   │   │   ├── anime_cover_image.dart
│   │   │   ├── status_badge.dart
│   │   │   └── empty_state.dart
│   │   │
│   │   ├── app_theme.dart        # M3 主题配置
│   │   └── design_tokens.dart    # 设计令牌
│   │
│   ├── utils/                    # 工具函数
│   │   ├── api_config.dart       # API 配置（编译时注入）
│   │   ├── notification_service.dart
│   │   ├── logger.dart           # 日志工具
│   │   └── error_logger.dart
│   │
│   ├── settings_manager.dart      # 设置管理器（单例）
│   ├── theme_manager.dart         # 主题管理器（单例）
│   ├── anime_list_page.dart       # 追番列表页
│   ├── add_anime_page.dart        # 添加/编辑番剧
│   ├── calendar_page.dart         # 追番日历
│   └── discovery_page.dart        # 发现页
│
├── assets/                       # 静态资源
│   └── icon.png
│
├── android/                      # Android 平台代码
├── ios/                          # iOS 平台代码
├── windows/                      # Windows 平台代码
├── macos/                        # macOS 平台代码
├── linux/                        # Linux 平台代码
├── web/                          # Web 平台代码
│
└── pubspec.yaml                  # Flutter 依赖
```

---

## 4. 核心模块详解

### 4.1 API 服务层 (`lib/api/`)

#### 4.1.1 AniListService

AniList GraphQL API 服务，负责从 AniList 获取番剧元数据。

**文件**: [anilist_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/api/anilist_service.dart)

**主要方法**:

| 方法 | 说明 | 返回值 |
|------|------|--------|
| `searchAnime(query)` | 通过关键词搜索动漫 | `List<BangumiSearchResult>` |
| `parseAnilistJson(json)` | 解析 AniList JSON 为统一格式 | `BangumiSearchResult` |

**GraphQL 端点**: `https://graphql.anilist.co`

```dart
class AnilistService {
  static const String _graphqlUrl = 'https://graphql.anilist.co';

  static Future<List<BangumiSearchResult>> searchAnime(String query) async {
    // GraphQL 查询：标题、封面、集数、描述、首播日期、制作公司
    const String queryDoc = r'''
      query ($search: String) {
        Page(page: 1, perPage: 10) {
          media(search: $search, type: ANIME, sort: SEARCH_MATCH) {
            id
            title { romaji english native }
            coverImage { large medium }
            averageScore
            episodes
            description
            startDate { year month day }
            studios(isMain: true) { nodes { name } }
          }
        }
      }
    ''';
    // ...
  }
}
```

#### 4.1.2 BangumiService

Bangumi API 服务，支持 REST API 搜索和网页爬虫两种方式获取数据。

**文件**: [bangumi_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/api/bangumi_service.dart)

**主要方法**:

| 方法 | 说明 |
|------|------|
| `searchAnime(query)` | 搜索动画条目（type=2） |
| `searchBook(query)` | 搜索书籍/漫画（type=1） |
| `searchSubject(query, type)` | 通用条目搜索 |
| `searchByTag(tag, offset, limit)` | 通过标签搜索（网页爬虫） |
| `getAnimeDetail(id)` | 获取番剧详情（制作公司、评分、简介等） |
| `getServerAnimes(keyword, tag, year, month)` | 从自建服务器获取番剧列表 |
| `updateServerCover(title, coverUrl)` | 同步封面到自建服务器 |

**API 基础地址**: `https://api.bgm.tv`

#### 4.1.3 BangumiSearchResult

搜索结果统一数据模型，兼容 Bangumi 和 AniList 两个数据源。

```dart
class BangumiSearchResult {
  final int id;                      // 条目 ID
  final String nameCn;               // 中文标题
  final String nameOriginal;         // 原始标题
  final String? coverUrl;            // 封面图片 URL
  final String? airDate;             // 首播日期
  final int? eps;                   // 总集数
  final String source;                // 数据来源：'bangumi' / 'anilist'
  final String? summary;             // 剧情简介
  final String? studio;              // 制作公司
  final int? tvCount;               // TV 集数
  final int? spCount;               // SP 集数
  final double? score;              // 评分
}
```

#### 4.1.4 UpdateService

版本更新检查服务，连接自建后端获取最新版本信息。

**文件**: [update_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/api/update_service.dart)

**主要方法**:

| 方法 | 说明 |
|------|------|
| `checkUpdate(context, {showNoUpdate, isAutoCheck})` | 检查更新，支持自动/手动检查 |
| `_showUpdateDialog(...)` | 显示更新对话框 |
| `_showLatestVersionDialog(...)` | 显示"已是最新版本"对话框 |
| `_showErrorDialog(...)` | 显示检查失败对话框 |

---

### 4.2 数据持久化层 (`lib/db/`)

#### 4.2.1 DatabaseHelper

SQLite 数据库操作单例类，提供完整的 CRUD 操作和高级查询功能。

**文件**: [database_helper.dart](file:///d:/Projects/cursor/anime_tracker/lib/db/database_helper.dart)

**数据库配置**:

| 配置项 | 值 |
|--------|-----|
| 数据库文件名 | `anime_tracker_v5.db` |
| 数据库版本 | 9 |
| 外键支持 | 已启用 (`PRAGMA foreign_keys = ON`) |
| 桌面端支持 | sqflite_common_ffi |

**核心方法**:

##### 番剧记录操作

| 方法 | 说明 |
|------|------|
| `insertAnime(row)` | 插入新番剧记录 |
| `updateAnime(row)` | 更新番剧记录 |
| `deleteAnime(id)` | 删除番剧记录 |
| `queryAllAnimes()` | 查询所有番剧（按 ID 倒序） |
| `getAnimeById(id)` | 获取单个番剧详情 |
| `getAnimeByTitle(title)` | 根据标题查重 |

##### 系列管理

| 方法 | 说明 |
|------|------|
| `createSeries(name, {description})` | 创建新系列 |
| `getSeriesById(id)` | 获取系列详情 |
| `getAllSeries()` | 获取所有系列（含统计信息） |
| `updateSeries(id, row)` | 更新系列信息 |
| `deleteSeries(id)` | 删除系列（关联番剧 series_id 置空） |
| `getAnimesInSeries(seriesId)` | 获取系列下所有番剧 |
| `batchUpdateAnimesSeries(animeIds, seriesId)` | 批量更新番剧所属系列 |

##### 标签管理

| 方法 | 说明 |
|------|------|
| `insertTag(name)` | 创建标签（自动去重） |
| `updateTag(id, newName)` | 更新标签名称 |
| `deleteTag(id)` | 删除标签（含级联删除关联） |
| `getAllTags()` | 获取所有标签 |
| `addTagToAnime(animeId, tagId)` | 为番剧添加标签 |
| `updateAnimeTags(animeId, tagIds)` | 更新番剧标签集合 |
| `getTagsByAnimeId(animeId)` | 获取番剧的所有标签 |
| `batchAddTagToAnimes(animeIds, tagId)` | 批量添加标签 |
| `batchRemoveTagFromAnimes(animeIds, tagId)` | 批量移除标签 |

##### 状态管理

| 方法 | 说明 |
|------|------|
| `getAllStatuses()` | 获取所有观看状态 |
| `insertStatus(name, color)` | 插入新状态 |
| `updateStatus(id, name, color)` | 更新状态（含同步 animes 表） |
| `deleteStatus(id)` | 删除状态（被使用时返回 -2） |
| `updateStatusesOrder(ids)` | 批量更新状态排序 |

##### 观看记录

| 方法 | 说明 |
|------|------|
| `insertWatchRecord({animeId, episode, status, date})` | 插入观看记录（含防重复逻辑） |
| `getAllWatchRecords()` | 获取所有观看记录（含完成次数统计） |
| `deleteWatchRecordsByAnimeId(animeId)` | 删除番剧的所有记录 |
| `deleteWatchRecord(id)` | 删除单条记录 |

##### 提醒管理

| 方法 | 说明 |
|------|------|
| `getAnimesWithReminders()` | 获取所有设置了提醒的番剧 |

##### 高级搜索

```dart
Future<List<Map<String, dynamic>>> searchAnimes({
  String? query,           // 关键词（标题/公司/评论）
  List<int>? tagIds,       // 标签筛选
  String? status,          // 状态筛选
  String? subjectType,     // 类型筛选（anime/book）
  List<String>? years,     // 年份筛选（支持多选）
  bool isAndMode = false,  // 标签筛选模式：AND/OR
  String sortOption = 'a.id DESC',  // 排序规则
})
```

##### 统计功能

| 方法 | 说明 |
|------|------|
| `getStatusCounts()` | 统计各状态番剧数量 |
| `getSubjectTypeCounts()` | 统计番剧/小说数量 |
| `getTagCounts()` | 统计标签使用次数 |
| `getAllStudios()` | 获取所有不重复的制作公司 |

##### 日志管理

| 方法 | 说明 |
|------|------|
| `insertLog(message, stackTrace)` | 插入应用日志 |
| `getLogs()` | 获取所有日志（按时间倒序） |
| `clearOldLogs()` | 清理 24 小时前的日志 |

---

### 4.3 状态管理层 (`lib/providers/`)

#### 4.3.1 DataRefreshProvider

全局数据刷新通知器，用于在数据变更时通知相关页面刷新。

**文件**: [data_refresh_provider.dart](file:///d:/Projects/cursor/anime_tracker/lib/providers/data_refresh_provider.dart)

```dart
class DataRefreshProvider extends ChangeNotifier {
  void refreshAll() {
    notifyListeners();
  }
}
```

**使用场景**:
- 添加/删除番剧后通知列表页刷新
- 编辑番剧后通知详情页刷新
- 批量操作后通知相关页面刷新

---

### 4.4 设置管理层 (`lib/settings_manager.dart`)

SettingsManager 是应用设置的核心管理单例，负责设置的加载、保存和通知。

**文件**: [settings_manager.dart](file:///d:/Projects/cursor/anime_tracker/lib/settings_manager.dart)

**ValueNotifier 配置项**:

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `fontScaleNotifier` | `double` | 1.0 | 字体缩放比例 |
| `gridColumnsNotifier` | `int` | 3 | 网格列数 |
| `titlePositionNotifier` | `String` | 'on_cover' | 标题位置 |
| `homeLayoutNotifier` | `HomeLayout` | bentoHome | 首页布局 |
| `badgeStyleNotifier` | `BadgeStyle` | floating | 角标样式 |
| `ratingIconNotifier` | `RatingIcon` | catPaw | 评分图标 |
| `detailLayoutNotifier` | `DetailLayout` | classic | 详情页布局 |
| `detailModuleOrderNotifier` | `List<DetailModule>` | defaultOrder | 模块顺序 |
| `detailHiddenModulesNotifier` | `Set<DetailModule>` | {} | 隐藏模块 |
| `showTitleNotifier` | `bool` | true | 显示标题 |
| `showRatingNotifier` | `bool` | true | 显示评分 |
| `showProgressNotifier` | `bool` | true | 显示进度 |
| `showStatusNotifier` | `bool` | true | 显示状态 |
| `showCalendarNotifier` | `bool` | true | 显示日历入口 |
| `showStatisticsNotifier` | `bool` | true | 显示统计入口 |
| `showDiscoveryNotifier` | `bool` | true | 显示发现入口 |
| `showServerSearchNotifier` | `bool` | false | 显示资料库入口 |
| `autoSaveDetailNotifier` | `bool` | false | 详情页自动保存 |
| `lastSelectedStatusNotifier` | `String` | '全部' | 上次选择状态 |
| `defaultStartStatusNotifier` | `String` | '上次退出前' | 启动默认状态 |
| `completionStatusNotifier` | `String` | '看完' | 看完后归纳状态 |
| `autoStatusTransitionNotifier` | `bool` | true | 自动归纳状态 |
| `enableCustomSplashNotifier` | `bool` | false | 自定义启动封面 |
| `splashImagePathNotifier` | `String` | '' | 启动封面路径 |
| `splashDurationNotifier` | `int` | 1000 | 启动封面时长(ms) |

**存储方式**: SharedPreferences

---

### 4.5 界面层 (`lib/ui/`)

#### 4.5.1 首页布局系统

**文件**: [home_layout.dart](file:///d:/Projects/cursor/anime_tracker/lib/ui/views/home_layout.dart)

##### HomeLayout 枚举

4 种首页布局模式：

| 枚举值 | 中文名 | 说明 |
|--------|--------|------|
| `bentoHome` | 智能聚合 | 在看 Hero + 最近添加 + 全部 Grid |
| `cardFeed` | 精致卡片流 | Letterboxd 式单列大卡片 |
| `posterWall` | 沉浸海报墙 | 无 spacing 封面铺满 |
| `compactIndex` | 紧凑索引 | 缩略 + 拼音锚点，适合超大库 |

##### BadgeStyle 枚举

封面角标样式：

| 枚举值 | 中文名 | 说明 |
|--------|--------|------|
| `floating` | 悬浮显示 | 胶囊距封面边缘 8px 悬浮（默认） |
| `flush` | 贴边显示 | 紧贴封面四角 |
| `bottomBar` | 底部信息条 | 底部一条状态色块+评分 |
| `minimal` | 角标极简 | 小角块+纯文字评分 |

##### HomeViewProps

传递给首页布局的渲染参数封装类：

```dart
class HomeViewProps {
  final List<Map<String, dynamic>> items;      // 当前分页显示的 item 列表
  final Map<String, Color> statusColors;         // 状态色映射
  final Directory? appDocDir;                   // App 文档目录
  final bool isSelectionMode;                   // 多选模式
  final Set<int> selectedIds;                   // 已选中 ID 集合
  final void Function(Map) onItemTap;           // 点击回调
  final void Function(int) onItemLongPress;     // 长按回调
  final Future<void> Function() onRefresh;      // 下拉刷新
  final VoidCallback? onLoadMore;               // 加载更多
  final bool hasMore;                           // 是否有更多
  final int gridColumns;                        // 列数
  final String titlePosition;                  // 标题位置
  final bool showTitle;                        // 显示标题
  final bool showRating;                       // 显示评分
  final bool showProgress;                     // 显示进度
  final bool showStatus;                       // 显示状态
  final bool showSubjectType;                  // 显示类型图标
  final BadgeStyle badgeStyle;                  // 角标样式
  final String selectedStatus;                 // 当前筛选状态
  final bool isInDefaultMode;                  // 默认浏览模式
  final bool isSortedByPinyin;                 // 按拼音排序
  final String Function(Map) progressTextOf;   // 进度文案计算
}
```

##### 布局工厂函数

```dart
Widget buildHomeView(HomeLayout layout, HomeViewProps props) {
  switch (layout) {
    case HomeLayout.bentoHome:
      return BentoHomeView(props: props);
    case HomeLayout.cardFeed:
      return CardFeedView(props: props);
    case HomeLayout.posterWall:
      return PosterWallView(props: props);
    case HomeLayout.compactIndex:
      return CompactIndexView(props: props);
  }
}
```

#### 4.5.2 详情页布局系统

**文件**: [detail_layout.dart](file:///d:/Projects/cursor/anime_tracker/lib/ui/anime_detail/detail_layout.dart)

##### DetailLayout 枚举

4 种详情页布局模式：

| 枚举值 | 说明 |
|--------|------|
| `classic` | 经典布局 |
| `magazine` | 杂志布局（带 SliverAppBar） |
| `dashboard` | 仪表盘布局 |
| `minimal` | 极简布局 |

##### DetailModule 枚举

详情页可定制模块：

| 枚举值 | 模块名 | 默认显示 |
|--------|--------|----------|
| `header` | 封面头部 | ✓ |
| `meta` | 元信息（日期/公司/集数） | ✓ |
| `statusProgress` | 状态与进度 | ✓ |
| `tags` | 标签 | ✓ |
| `review` | 感想/评论 | ✓ |
| `siblings` | 系列关联 | ✓ |
| `reminder` | 提醒设置 | ✓ |

#### 4.5.3 主要页面

##### AnimeListPage

追番列表主页，负责番剧列表展示、筛选、排序、多选操作。

**文件**: [anime_list_page.dart](file:///d:/Projects/cursor/anime_tracker/lib/anime_list_page.dart)

**核心功能**:
- 列表展示（分页加载，每页 250 条）
- 筛选功能（类型/状态/年份/标签）
- 排序功能（默认/日期/评分/拼音）
- 多选模式（批量修改状态/标签/删除/自动匹配）
- 系列聚合展示
- 搜索功能（防抖 500ms）

##### AnimeDetailPage

番剧详情查看页，只读模式展示番剧信息。

**文件**: [anime_detail_page.dart](file:///d:/Projects/cursor/anime_tracker/lib/ui/anime_detail/anime_detail_page.dart)

**特性**:
- 支持 4 种布局切换
- 支持模块顺序定制和显隐控制
- 点击编辑按钮跳转到 AddAnimePage

##### AddAnimePage

添加/编辑番剧页面。

**文件**: [add_anime_page.dart](file:///d:/Projects/cursor/anime_tracker/lib/add_anime_page.dart)

**功能**:
- 手动添加/编辑
- 从 Bangumi/AniList 搜索回填
- 标签管理
- 系列管理
- 提醒设置

##### MainShell

应用主壳层，包含 4 个底部 Tab。

**文件**: [main_shell.dart](file:///d:/Projects/cursor/anime_tracker/lib/main_shell.dart)

**Tab 结构**:
| Tab | 页面 | 说明 |
|-----|------|------|
| 追番 | AnimeListPage | 番剧列表 |
| 发现 | DiscoveryPage | 资源发现（可选） |
| 日历 | CalendarPage | 追番日历（可选） |
| 我的 | MyPage | 统计/设置/关于 |

---

### 4.6 工具层 (`lib/utils/`)

#### 4.6.1 ApiConfig

编译时注入的 API 配置。

**文件**: [api_config.dart](file:///d:/Projects/cursor/anime_tracker/lib/utils/api_config.dart)

```dart
class ApiConfig {
  // 云端 API 根地址（通过 --dart-define=CLOUD_API_BASE=... 注入）
  static const String cloudApiBase = String.fromEnvironment(
    'CLOUD_API_BASE',
    defaultValue: '',
  );

  // API Token（通过 --dart-define=API_TOKEN=... 注入）
  static const String apiToken = String.fromEnvironment(
    'API_TOKEN',
    defaultValue: '',
  );

  static bool get hasCloudBase => cloudApiBase.trim().isNotEmpty;
  static bool get hasToken => apiToken.isNotEmpty;

  // 构造云端 API URI
  static Uri? cloudUri(String path, [Map<String, String>? queryParameters]);
}
```

#### 4.6.2 NotificationService

本地通知服务，负责追番提醒的调度和取消。

**文件**: [notification_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/utils/notification_service.dart)

```dart
class NotificationService {
  static final NotificationService _instance = NotificationService._internal();
  factory NotificationService() => _instance;
  NotificationService._internal();

  Future<void> init();                                          // 初始化
  Future<void> scheduleWeeklyNotification({    // 调度每周提醒
    required int id,
    required String title,
    required String body,
    required int day,    // 1-7 (周一到周日)
    required TimeOfDay time,
  });
  Future<void> cancelNotification(int id);   // 取消通知
}
```

**支持平台**: Android, iOS, Windows

#### 4.6.3 Logger

日志工具类，统一日志输出。

**文件**: [logger.dart](file:///d:/Projects/cursor/anime_tracker/lib/utils/logger.dart)

---

## 5. 数据库设计

### 5.1 ER 图

```
┌─────────────────┐       ┌─────────────────┐
│     animes      │       │      tags        │
├─────────────────┤       ├─────────────────┤
│ id (PK)         │       │ id (PK)         │
│ title           │       │ name            │
│ cover_url       │       │ color           │
│ status          │       └────────┬────────┘
│ rating          │                │
│ review          │       ┌─────────┴────────┐
│ series_id (FK) ─┼──────>│   anime_tags     │
│ ...             │       ├─────────────────┤
└────────┬────────┘       │ anime_id (FK)   │
         │                │ tag_id (FK)     │
         │                └─────────────────┘
         │
         │         ┌─────────────────┐
         └─────────>│     series       │
                    ├─────────────────┤
                    │ id (PK)         │
                    │ name            │
                    │ description      │
                    │ custom_cover_url │
                    │ created_at       │
                    └─────────────────┘

┌─────────────────┐       ┌─────────────────┐
│ watch_statuses  │       │  watch_records  │
├─────────────────┤       ├─────────────────┤
│ id (PK)         │       │ id (PK)         │
│ name            │       │ anime_id (FK)   │
│ color           │       │ episode         │
│ sort_order      │       │ status          │
└─────────────────┘       │ record_date     │
                           └─────────────────┘

┌─────────────────┐
│    app_logs     │
├─────────────────┤
│ id (PK)         │
│ message         │
│ stack_trace     │
│ timestamp       │
└─────────────────┘
```

### 5.2 表结构详解

#### animes（番剧记录表）

| 字段 | 类型 | 说明 | 示例 |
|------|------|------|------|
| id | INTEGER | 主键自增 | 1 |
| title | TEXT | 标题 | "鬼灭之刃" |
| cover_url | TEXT | 封面 URL | "https://..." |
| status | TEXT | 观看状态 | "在看" |
| rating | INTEGER | 评分（1-10） | 8 |
| review | TEXT | 感想/评论 | "战斗很燃..." |
| series_id | INTEGER | 系列 ID（FK） | 1 |
| created_at | TEXT | 创建时间 ISO8601 | "2024-01-01T00:00:00" |
| air_date | TEXT | 首播日期 | "2019-04-06" |
| studio | TEXT | 制作公司 | "ufotable" |
| watch_start_date | TEXT | 开始观看日期 | "2024-01-01" |
| watch_finish_date | TEXT | 看完日期 | "2024-01-15" |
| watched_episodes | INTEGER | 已看集数 | 8 |
| total_episodes | INTEGER | 总集数 | 26 |
| tv_episodes | INTEGER | TV 集数 | 26 |
| sp_episodes | INTEGER | SP 集数 | 0 |
| subject_type | TEXT | 作品类型 | "anime" / "book" |
| reminder_day | INTEGER | 提醒星期几 | 6 |
| reminder_time | TEXT | 提醒时间 | "20:00" |

#### tags（标签表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键自增 |
| name | TEXT | 标签名（唯一） |
| color | INTEGER | 颜色值（0xAARRGGBB） |

#### anime_tags（番剧-标签关联表）

多对多关系表，通过外键关联 animes 和 tags 表。

| 字段 | 类型 | 说明 |
|------|------|------|
| anime_id | INTEGER | 番剧 ID（FK） |
| tag_id | INTEGER | 标签 ID（FK） |
| **主键** | (anime_id, tag_id) | 复合主键 |

#### series（系列表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键自增 |
| name | TEXT | 系列名称 |
| description | TEXT | 系列描述 |
| custom_cover_url | TEXT | 自定义封面 URL |
| created_at | TEXT | 创建时间 ISO8601 |

#### watch_statuses（观看状态表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键自增 |
| name | TEXT | 状态名（唯一） |
| color | INTEGER | 颜色值 |
| sort_order | INTEGER | 排序顺序 |

**默认状态**:
| 名称 | 颜色 | 排序 |
|------|------|------|
| 在看 | 0xFF2196F3 (蓝) | 0 |
| 看完 | 0xFF4CAF50 (绿) | 1 |
| 未看 | 0xFFFF9800 (橙) | 2 |
| 弃坑 | 0xFFF44336 (红) | 3 |

#### watch_records（观看记录表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键自增 |
| anime_id | INTEGER | 番剧 ID（FK） |
| episode | INTEGER | 集数 |
| status | TEXT | 记录类型 ('watched'/'completed') |
| record_date | TEXT | 记录日期 ISO8601 |

#### app_logs（应用日志表）

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER | 主键自增 |
| message | TEXT | 日志消息 |
| stack_trace | TEXT | 堆栈跟踪 |
| timestamp | TEXT | 时间戳 ISO8601 |

### 5.3 数据库迁移

数据库版本从 1 升级到 9 的迁移历史：

| 版本 | 升级内容 |
|------|----------|
| 2 | 增加 tv_episodes、sp_episodes 字段 |
| 3 | 新增 series 系列表 |
| 4 | 新增 watch_statuses 自定义状态表 |
| 5 | series 表增加 custom_cover_url |
| 6 | 新增 watch_records 观看记录表 |
| 8 | animes 表增加 subject_type 字段 |
| 9 | animes 表增加 reminder_day、reminder_time |

---

## 6. 依赖关系

### 6.1 Flutter 依赖（pubspec.yaml）

#### 核心依赖

| 包名 | 版本 | 用途 |
|------|------|------|
| flutter | sdk | Flutter 框架 |
| flutter_localizations | sdk | 国际化支持 |

#### 数据存储

| 包名 | 版本 | 用途 |
|------|------|------|
| sqflite_common_ffi | ^2.3.0 | 桌面端 SQLite 支持 |
| sqlite3_flutter_libs | ^0.5.18 | SQLite 原生库 |
| sqflite | ^2.3.0 | 移动端 SQLite |
| shared_preferences | ^2.2.2 | 轻量级键值存储 |
| path_provider | ^2.1.2 | 文件路径获取 |
| path | ^1.8.3 | 路径操作 |

#### 网络请求

| 包名 | 版本 | 用途 |
|------|------|------|
| http | ^1.2.0 | HTTP 请求 |
| dio | ^5.4.0 | HTTP 客户端（高级功能） |

#### 图片处理

| 包名 | 版本 | 用途 |
|------|------|------|
| image_picker | ^1.0.7 | 图片选择 |
| image_cropper | ^8.0.2 | 图片裁剪 |
| cached_network_image | ^3.3.0 | 图片缓存 |
| flutter_cache_manager | ^3.3.1 | 缓存管理 |
| saver_gallery | ^3.0.1 | 保存图片到相册 |

#### UI 组件

| 包名 | 版本 | 用途 |
|------|------|------|
| cupertino_icons | ^1.0.8 | iOS 风格图标 |
| table_calendar | ^3.0.9 | 日历组件 |
| fl_chart | 0.70.2 | 图表组件 |
| flutter_colorpicker | any | 颜色选择器 |
| html | ^0.15.6 | HTML 解析 |

#### 功能库

| 包名 | 版本 | 用途 |
|------|------|------|
| lpinyin | ^2.0.3 | 拼音转换 |
| intl | ^0.20.2 | 日期格式化 |
| url_launcher | ^6.2.5 | URL 打开 |
| archive | ^3.4.10 | 压缩解压 |
| file_picker | ^8.0.0 | 文件选择 |
| share_plus | ^9.0.0 | 分享功能 |
| spreadsheet_decoder | ^2.3.0 | Excel 解析 |
| excel | ^4.0.0 | Excel 生成 |
| permission_handler | ^11.3.1 | 权限处理 |
| package_info_plus | ^9.0.0 | 应用信息 |
| flutter_local_notifications | ^21.0.0 | 本地通知 |
| timezone | ^0.11.0 | 时区处理 |

#### 状态管理

| 包名 | 版本 | 用途 |
|------|------|------|
| provider | ^6.1.5+1 | 状态管理 |

#### 日志

| 包名 | 版本 | 用途 |
|------|------|------|
| logger | ^2.7.0 | 日志工具 |

### 6.2 依赖关系图

```
┌─────────────────────────────────────────────────────────────┐
│                        应用层                                │
│   AnimeListPage / AddAnimePage / AnimeDetailPage / ...     │
└─────────────────────────────┬───────────────────────────────┘
                              │
┌─────────────────────────────▼───────────────────────────────┐
│                      业务逻辑层                             │
│  SettingsManager │ DataRefreshProvider │ ThemeManager      │
└─────────────────────────────┬───────────────────────────────┘
                              │
        ┌─────────────────────┼─────────────────────┐
        │                     │                     │
┌───────▼───────┐     ┌───────▼───────┐     ┌─────▼─────┐
│   API 层      │     │   DB 层       │     │  Utils    │
│               │     │               │     │           │
│ AnilistService│     │DatabaseHelper │     │ApiConfig  │
│ BangumiService│     │               │     │Notification│
│ UpdateService │     │               │     │Logger     │
└───────┬───────┘     └───────────────┘     └───────────┘
        │                     ▲
        │                     │
┌───────▼───────┐     ┌───────▼───────┐
│   http/dio    │     │   sqflite     │
│   网络请求    │     │   SQLite DB   │
└───────────────┘     └───────────────┘
```

---

## 7. 运行与构建

### 7.1 环境要求

- Flutter SDK ≥ 3.24
- Dart SDK ≥ 3.5
- Node.js 20+（仅后端部署）
- MySQL 8.x（仅后端部署）

### 7.2 客户端运行

```bash
# 1. 克隆仓库
git clone https://github.com/xunlys7930/AniMeow.git
cd AniMeow

# 2. 安装依赖
flutter pub get

# 3. 运行（无后端）
flutter run

# 4. 运行（连接自建后端）
flutter run \
  --dart-define=CLOUD_API_BASE=https://your-api.example.com \
  --dart-define=API_TOKEN=your_token_here
```

### 7.3 打包发布

```bash
# Android
flutter build apk --release \
  --dart-define=CLOUD_API_BASE=https://your-api.example.com \
  --dart-define=API_TOKEN=your_token_here

# Windows
flutter build windows --release \
  --dart-define=CLOUD_API_BASE=https://your-api.example.com \
  --dart-define=API_TOKEN=your_token_here
```

### 7.4 后端部署与协议（自建参考）

仓库 `backend/` 包含经过脱敏的 Node.js + MySQL 参考实现。部署前复制 `backend/.env.example` 为 `backend/.env`，填写独立随机密钥并执行 `npm install && npm start`；详细安全要求见 `backend/README.md`。

- **鉴权**：`Authorization: Bearer <API_TOKEN>`，客户端通过 `--dart-define=API_TOKEN=...` 编译期注入同样的值
- **根地址**：`--dart-define=CLOUD_API_BASE=https://your-api.example.com`（无末尾 `/`）
- **接口形态**：参考 `bangumi_service.dart` / `anilist_service.dart` / `update_service.dart` 中 `Uri.parse('$base/api/...')` 的调用列表自行实现

### 7.5 IDE 配置（VS Code）

```json
// .vscode/launch.json
{
  "configurations": [
    {
      "name": "AniMeow (debug)",
      "request": "launch",
      "type": "dart",
      "toolArgs": [
        "--dart-define=CLOUD_API_BASE=https://your-api.example.com",
        "--dart-define=API_TOKEN=your_token_here"
      ]
    }
  ]
}
```

---

## 8. 附录

### 8.1 关键文件速查表

| 功能 | 文件路径 |
|------|----------|
| 应用入口 | [lib/main.dart](file:///d:/Projects/cursor/anime_tracker/lib/main.dart) |
| 主壳层 | [lib/main_shell.dart](file:///d:/Projects/cursor/anime_tracker/lib/main_shell.dart) |
| 数据库操作 | [lib/db/database_helper.dart](file:///d:/Projects/cursor/anime_tracker/lib/db/database_helper.dart) |
| 设置管理 | [lib/settings_manager.dart](file:///d:/Projects/cursor/anime_tracker/lib/settings_manager.dart) |
| Bangumi API | [lib/api/bangumi_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/api/bangumi_service.dart) |
| AniList API | [lib/api/anilist_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/api/anilist_service.dart) |
| 更新检查 | [lib/api/update_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/api/update_service.dart) |
| 追番列表 | [lib/anime_list_page.dart](file:///d:/Projects/cursor/anime_tracker/lib/anime_list_page.dart) |
| 添加/编辑 | [lib/add_anime_page.dart](file:///d:/Projects/cursor/anime_tracker/lib/add_anime_page.dart) |
| 详情页 | [lib/ui/anime_detail/anime_detail_page.dart](file:///d:/Projects/cursor/anime_tracker/lib/ui/anime_detail/anime_detail_page.dart) |
| 首页布局 | [lib/ui/views/home_layout.dart](file:///d:/Projects/cursor/anime_tracker/lib/ui/views/home_layout.dart) |
| 通知服务 | [lib/utils/notification_service.dart](file:///d:/Projects/cursor/anime_tracker/lib/utils/notification_service.dart) |
| API 配置 | [lib/utils/api_config.dart](file:///d:/Projects/cursor/anime_tracker/lib/utils/api_config.dart) |

### 8.2 配置文件

| 文件 | 说明 |
|------|------|
| pubspec.yaml | Flutter 依赖配置 |
| analysis_options.yaml | Dart 代码分析规则 |

### 8.3 资源目录

| 目录 | 说明 |
|------|------|
| assets/icon.png | 应用图标 |

### 8.4 许可证

MIT License · Copyright © 2026 XunLys

---

*文档生成时间: 2026-05-14*  
*项目仓库: [github.com/xunlys7930/AniMeow](https://github.com/xunlys7930/AniMeow)*
