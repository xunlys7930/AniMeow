import 'dart:io';
import 'package:flutter/material.dart';
import 'poster_wall_view.dart';
import 'card_feed_view.dart';
import 'bento_home_view.dart';
import 'compact_index_view.dart';
import 'recommend_grid_view.dart';

/// 首页布局模式枚举（5 选 1）
///
/// 替换旧的 `viewMode: 'poster'|'card'` 字符串。
/// 老用户旧值会在 `SettingsManager.loadSettings()` 中自动迁移：
/// - `'poster'` → [HomeLayout.posterWall]
/// - `'card'`   → [HomeLayout.cardFeed]
///
/// 新用户首启默认 [HomeLayout.bentoHome]。
enum HomeLayout {
  /// 智能 Bento 首页（在看 Hero + 最近添加 + 全部 Grid）
  bentoHome,

  /// 精致卡片流（Letterboxd 式单列大卡片）
  cardFeed,

  /// 沉浸海报墙（无 spacing 封面铺满）
  posterWall,

  /// 紧凑索引（缩略 + 拼音锚点）
  compactIndex,

  /// 推荐宫格（多列封面推荐流，角标样式跟随封面设置）
  recommendGrid;

  /// 是否支持通过首页宫格列数设置调整封面排列。
  bool get supportsGridColumns {
    switch (this) {
      case HomeLayout.bentoHome:
      case HomeLayout.posterWall:
      case HomeLayout.recommendGrid:
        return true;
      case HomeLayout.cardFeed:
      case HomeLayout.compactIndex:
        return false;
    }
  }

  /// 用于显示的中文名
  String get label {
    switch (this) {
      case HomeLayout.bentoHome:
        return '智能聚合';
      case HomeLayout.cardFeed:
        return '精致卡片流';
      case HomeLayout.posterWall:
        return '沉浸海报墙';
      case HomeLayout.compactIndex:
        return '紧凑索引';
      case HomeLayout.recommendGrid:
        return '推荐宫格';
    }
  }

  /// 一句话描述
  String get description {
    switch (this) {
      case HomeLayout.bentoHome:
        return '聚合"在看 / 最近添加 / 全部"模块';
      case HomeLayout.cardFeed:
        return '单列大卡片，信息丰富';
      case HomeLayout.posterWall:
        return '无文字干扰的封面墙';
      case HomeLayout.compactIndex:
        return '高密度文本列表，超大库适用';
      case HomeLayout.recommendGrid:
        return '多列封面宫格，适合快速浏览';
    }
  }

  /// 选择器中的代表图标
  IconData get icon {
    switch (this) {
      case HomeLayout.bentoHome:
        return Icons.dashboard_rounded;
      case HomeLayout.cardFeed:
        return Icons.view_agenda_rounded;
      case HomeLayout.posterWall:
        return Icons.grid_view_rounded;
      case HomeLayout.compactIndex:
        return Icons.list_rounded;
      case HomeLayout.recommendGrid:
        return Icons.apps_rounded;
    }
  }

  /// 持久化到 SharedPreferences 的字符串 key
  String get persistKey {
    switch (this) {
      case HomeLayout.bentoHome:
        return 'bentoHome';
      case HomeLayout.cardFeed:
        return 'cardFeed';
      case HomeLayout.posterWall:
        return 'posterWall';
      case HomeLayout.compactIndex:
        return 'compactIndex';
      case HomeLayout.recommendGrid:
        return 'recommendGrid';
    }
  }

  /// 反序列化（未知值 fallback 到默认 [bentoHome]）
  static HomeLayout fromPersistKey(String? key) {
    if (key == null) return HomeLayout.bentoHome;
    return values.firstWhere(
      (e) => e.persistKey == key,
      orElse: () => HomeLayout.bentoHome,
    );
  }

  /// 从 v1 的 `view_mode` 字符串迁移
  /// 返回 null 表示未识别（不应进行迁移）
  static HomeLayout? fromLegacyViewMode(String? legacy) {
    switch (legacy) {
      case 'poster':
        return HomeLayout.posterWall;
      case 'card':
        return HomeLayout.cardFeed;
      default:
        return null;
    }
  }
}

/// 首页封面的信息层样式。
///
/// 样式只负责“信息如何排版”，显示哪些信息由
/// [HomeViewProps.showCoverStatus] 等独立开关决定。这样用户可以在保持
/// 视觉秩序的同时，自由组合状态、评分、进度和作品类型。
enum BadgeStyle {
  /// 一块统一的半透明信息面板，适合日常浏览。
  overlay,

  /// 左右角标分区，封面本身保持最干净。
  corners,

  /// 底部整条信息栏，适合需要快速扫读状态和进度的用户。
  bottomBar,

  /// 只保留点、数字和细进度线，干扰最少。
  minimal;

  String get label {
    switch (this) {
      case BadgeStyle.overlay:
        return '统一信息面板';
      case BadgeStyle.corners:
        return '双角分区';
      case BadgeStyle.bottomBar:
        return '底部信息条';
      case BadgeStyle.minimal:
        return '极简标记';
    }
  }

  String get description {
    switch (this) {
      case BadgeStyle.overlay:
        return '把状态、进度和评分收进一块半透明面板';
      case BadgeStyle.corners:
        return '状态在左上、评分在右上，底部留给标题';
      case BadgeStyle.bottomBar:
        return '底部整条信息带，适合高密度浏览';
      case BadgeStyle.minimal:
        return '只保留必要的点、数字和细线';
    }
  }

  String get persistKey {
    switch (this) {
      case BadgeStyle.overlay:
        return 'overlay';
      case BadgeStyle.corners:
        return 'corners';
      case BadgeStyle.bottomBar:
        return 'bottomBar';
      case BadgeStyle.minimal:
        return 'minimal';
    }
  }

  static BadgeStyle fromPersistKey(String? key) {
    // 兼容早期版本的 six-style key：旧样式迁移到最接近的基础样式，
    // 不会让升级后的用户突然得到一个空白设置。
    switch (key) {
      case 'floating':
        return BadgeStyle.overlay;
      case 'flush':
        return BadgeStyle.corners;
      case 'blueRibbon':
      case 'scoreTitleBar':
        return BadgeStyle.bottomBar;
      case 'corners':
        return BadgeStyle.corners;
      case 'bottomBar':
        return BadgeStyle.bottomBar;
      case 'minimal':
        return BadgeStyle.minimal;
      case 'overlay':
      default:
        return BadgeStyle.overlay;
    }
  }
}

/// 首页布局共享的渲染参数
///
/// [AnimeListPage] 把数据和回调打包成此对象，交给布局工厂 [buildHomeView] 分发。
class HomeViewProps {
  /// 当前分页显示的 item 列表（混合 'anime' / 'series' 两种 type）
  final List<Map<String, dynamic>> items;

  /// 当前筛选条件下的完整条目数，不受首页分页大小影响。
  final int totalItemCount;

  /// 状态色映射（来自 watch_statuses 表）
  final Map<String, Color> statusColors;

  /// App 文档目录（用于本地相对路径封面）
  final Directory? appDocDir;

  /// 是否处于多选模式
  final bool isSelectionMode;

  /// 已选中的 anime/series id 集合
  final Set<int> selectedIds;

  /// 点击 item
  final void Function(Map<String, dynamic> item) onItemTap;

  /// 长按 item（id 是 anime 或 series 的 id）
  final void Function(int id) onItemLongPress;

  /// 下拉刷新
  final Future<void> Function() onRefresh;

  /// 滚动接近底部时触发加载更多（可选）
  final VoidCallback? onLoadMore;

  /// 是否还有更多分页可加载
  final bool hasMore;

  /// 首页宫格偏好列数（2~5；窄窗口会自动取可容纳的最大值）
  final int gridColumns;

  /// 海报墙模式专用：标题位置（'on_cover' / 'below_cover'）
  final String titlePosition;

  /// 封面圆角（逻辑像素）
  final double coverBorderRadius;

  /// 封面角标大小缩放
  final double badgeScale;

  /// 封面信息面板透明度
  final double badgeOpacity;

  /// 封面信息面板圆角
  final double badgeRadius;

  /// 显隐开关：标题
  final bool showTitle;

  /// 显隐开关：评分
  final bool showRating;

  /// 显隐开关：进度
  final bool showProgress;

  /// 显隐开关：状态
  final bool showStatus;

  /// 显隐开关：作品类型图标
  final bool showSubjectType;

  /// 封面角标样式（影响 [AnimePosterCard]）
  final BadgeStyle badgeStyle;

  /// 封面上是否显示状态文字/状态点
  final bool showCoverStatus;

  /// 封面上是否显示评分
  final bool showCoverRating;

  /// 封面上是否显示观看进度
  final bool showCoverProgress;

  /// 封面上是否显示作品类型图标
  final bool showCoverType;

  /// 系列封面上是否显示作品数量
  final bool showCoverSeriesCount;

  /// 主筛选状态（'全部' / '在看' / '看完' / ...）
  /// Bento 用它判断是否展开 smart sections
  final String selectedStatus;

  /// 是否处于"默认浏览"模式（无筛选、无搜索）
  /// Bento 用它决定是否显示 smart sections
  final bool isInDefaultMode;

  /// 当前是否按拼音排序
  /// CompactIndex 用它决定是否显示 A-Z 字母锚点 + 分组标题
  final bool isSortedByPinyin;

  /// 计算番剧进度文案（如 "8/12"）的辅助函数
  final String Function(Map<String, dynamic> anime) progressTextOf;

  const HomeViewProps({
    required this.items,
    required this.totalItemCount,
    required this.statusColors,
    required this.appDocDir,
    required this.isSelectionMode,
    required this.selectedIds,
    required this.onItemTap,
    required this.onItemLongPress,
    required this.onRefresh,
    required this.onLoadMore,
    required this.hasMore,
    required this.gridColumns,
    required this.titlePosition,
    required this.coverBorderRadius,
    required this.badgeScale,
    required this.badgeOpacity,
    required this.badgeRadius,
    required this.showTitle,
    required this.showRating,
    required this.showProgress,
    required this.showStatus,
    required this.showSubjectType,
    required this.badgeStyle,
    required this.showCoverStatus,
    required this.showCoverRating,
    required this.showCoverProgress,
    required this.showCoverType,
    required this.showCoverSeriesCount,
    required this.selectedStatus,
    required this.isInDefaultMode,
    required this.isSortedByPinyin,
    required this.progressTextOf,
  });
}

/// 根据 [HomeLayout] 选择对应的视图 Widget
///
/// 在 `AnimeListPage._buildContentBody()` 中调用：
/// ```dart
/// buildHomeView(layout, props)
/// ```
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
    case HomeLayout.recommendGrid:
      return RecommendGridView(props: props);
  }
}
