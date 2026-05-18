import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'ui/views/home_layout.dart';
import 'ui/views/_shared/rating_icon.dart';
import 'ui/anime_detail/detail_layout.dart';

/// 设置管理器，负责应用设置的保存和通知
/// 使用单例模式确保全局只有一个实例
class SettingsManager {
  // 单例实例
  static final SettingsManager _instance = SettingsManager._internal();

  /// 单例工厂构造函数
  factory SettingsManager() => _instance;

  /// 私有构造函数
  SettingsManager._internal();

  /// 字体大小缩放比例（默认 1.0）
  final ValueNotifier<double> fontScaleNotifier = ValueNotifier(1.0);

  /// 网格模式下的列数（默认 3）
  final ValueNotifier<int> gridColumnsNotifier = ValueNotifier(3);

  /// 标题位置：'on_cover' (封面上)、'below_cover' (封面下)
  final ValueNotifier<String> titlePositionNotifier = ValueNotifier('on_cover');

  /// 显示模式：'poster' (海报式) 或 'card' (卡片式)
  ///
  /// @deprecated 仅为兼容旧版数据保留，新代码请使用 [homeLayoutNotifier]。
  /// `loadSettings()` 启动时会自动把旧值迁移为对应的 [HomeLayout]。
  @Deprecated('Use homeLayoutNotifier instead. 仅保留用于读取旧版 SharedPreferences。')
  final ValueNotifier<String> viewModeNotifier = ValueNotifier('poster');

  /// 首页布局枚举（替代旧 [viewModeNotifier]）
  ///
  /// 4 选 1，参见 [HomeLayout]。默认 [HomeLayout.bentoHome]。
  final ValueNotifier<HomeLayout> homeLayoutNotifier = ValueNotifier(
    HomeLayout.bentoHome,
  );

  /// 首页封面角标样式（评分/状态的显示方式）
  ///
  /// 4 选 1，参见 [BadgeStyle]。默认 [BadgeStyle.floating]（与 v1 行为一致）。
  final ValueNotifier<BadgeStyle> badgeStyleNotifier = ValueNotifier(
    BadgeStyle.floating,
  );

  /// 评分前置图标（首页 / 紧凑索引 / 卡片流 都遵循这个设置）
  ///
  /// 默认 [RatingIcon.catPaw]。
  final ValueNotifier<RatingIcon> ratingIconNotifier = ValueNotifier(
    RatingIcon.catPaw,
  );

  /// 番剧详情查看页布局（4 选 1，参见 [DetailLayout]）
  final ValueNotifier<DetailLayout> detailLayoutNotifier = ValueNotifier(
    DetailLayout.classic,
  );

  /// 详情页模块顺序（持久化为 String list，对应 [DetailModule.persistKey]）
  final ValueNotifier<List<DetailModule>> detailModuleOrderNotifier =
      ValueNotifier(DetailModule.defaultOrder);

  /// 详情页被隐藏的模块集合
  final ValueNotifier<Set<DetailModule>> detailHiddenModulesNotifier =
      ValueNotifier(<DetailModule>{});

  /// 是否显示标题
  final ValueNotifier<bool> showTitleNotifier = ValueNotifier(true);

  /// 是否显示评分
  final ValueNotifier<bool> showRatingNotifier = ValueNotifier(true);

  /// 是否显示进度（第x集）
  final ValueNotifier<bool> showProgressNotifier = ValueNotifier(true);

  /// 是否显示状态标签
  final ValueNotifier<bool> showStatusNotifier = ValueNotifier(true);

  /// 是否显示日历入口
  final ValueNotifier<bool> showCalendarNotifier = ValueNotifier(true);

  /// 是否显示统计入口
  final ValueNotifier<bool> showStatisticsNotifier = ValueNotifier(true);

  /// 是否显示发现入口
  final ValueNotifier<bool> showDiscoveryNotifier = ValueNotifier(true);

  /// 是否显示作品类型标签 (番剧/漫画)
  final ValueNotifier<bool> showSubjectTypeNotifier = ValueNotifier(true);

  /// 是否显示资源库入口（服务器搜索）
  final ValueNotifier<bool> showServerSearchNotifier = ValueNotifier(false);

  /// 详情页修改后是否自动保存
  final ValueNotifier<bool> autoSaveDetailNotifier = ValueNotifier(false);

  /// 详情页修改后是否直接放弃
  final ValueNotifier<bool> discardDetailChangesNotifier = ValueNotifier(false);

  /// 上次选择的观看状态（默认 '全部'）
  final ValueNotifier<String> lastSelectedStatusNotifier = ValueNotifier('全部');

  /// 启动时默认状态（默认 '上次退出前'）
  final ValueNotifier<String> defaultStartStatusNotifier = ValueNotifier(
    '上次退出前',
  );

  /// 番剧看完后自动归纳到的状态（默认 '看完'）
  final ValueNotifier<String> completionStatusNotifier = ValueNotifier('看完');

  /// 是否开启番剧看完自动归纳状态
  final ValueNotifier<bool> autoStatusTransitionNotifier = ValueNotifier(true);

  /// 是否在「发现」/「资源库」顶部显示「云端资源发现」入口（默认 true，保持原行为）
  final ValueNotifier<bool> showServerDiscoveryNotifier = ValueNotifier(true);

  /// 是否启用自定义启动封面（默认 false）
  final ValueNotifier<bool> enableCustomSplashNotifier = ValueNotifier(false);

  /// 自定义启动封面图片本地路径（为空表示未设置）
  final ValueNotifier<String> splashImagePathNotifier = ValueNotifier('');

  /// 自定义启动封面展示时长（毫秒），默认 1000ms（1 秒）
  final ValueNotifier<int> splashDurationNotifier = ValueNotifier(1000);

  // ============== 初始化与加载 ==============

  /// 从本地存储加载设置值
  Future<void> loadSettings() async {
    final prefs = await SharedPreferences.getInstance();

    // 读取字体缩放比例，如果不存在则使用默认值 1.0
    fontScaleNotifier.value = prefs.getDouble('font_scale') ?? 1.0;

    // 读取网格列数，如果不存在则使用默认值 3
    gridColumnsNotifier.value = prefs.getInt('grid_columns') ?? 3;

    // 读取标题位置，如果不存在则使用默认值 'on_cover'
    titlePositionNotifier.value =
        prefs.getString('title_position') ?? 'on_cover';

    // 显示模式（兼容旧字段）
    // ignore: deprecated_member_use_from_same_package
    viewModeNotifier.value = prefs.getString('view_mode') ?? 'poster';

    // 首页布局（v2 新字段）—— 优先读新 key，没有则尝试从旧 view_mode 迁移
    final persistedLayout = prefs.getString('home_layout');
    if (persistedLayout != null) {
      homeLayoutNotifier.value = HomeLayout.fromPersistKey(persistedLayout);
    } else {
      final migrated = HomeLayout.fromLegacyViewMode(
        prefs.getString('view_mode'),
      );
      if (migrated != null) {
        homeLayoutNotifier.value = migrated;
        await prefs.setString('home_layout', migrated.persistKey);
      } else {
        homeLayoutNotifier.value = HomeLayout.bentoHome;
      }
    }

    // 角标样式（v2 新字段，未存则用默认 floating）
    badgeStyleNotifier.value = BadgeStyle.fromPersistKey(
      prefs.getString('home_badge_style'),
    );

    // 评分前置图标（v2 新字段，未存则用默认 catPaw）
    ratingIconNotifier.value = RatingIcon.fromPersistKey(
      prefs.getString('rating_icon'),
    );

    // 详情页布局 + 模块顺序 + 隐藏集合
    detailLayoutNotifier.value = DetailLayout.fromPersistKey(
      prefs.getString('detail_layout'),
    );
    detailModuleOrderNotifier.value = DetailModule.deserializeOrder(
      prefs.getStringList('detail_module_order'),
    );
    detailHiddenModulesNotifier.value = DetailModule.deserializeHidden(
      prefs.getStringList('detail_hidden_modules'),
    );

    // 读取显示项设置
    showTitleNotifier.value = prefs.getBool('show_title') ?? true;
    showRatingNotifier.value = prefs.getBool('show_rating') ?? true;
    showProgressNotifier.value = prefs.getBool('show_progress') ?? true;
    showStatusNotifier.value = prefs.getBool('show_status') ?? true;
    showCalendarNotifier.value = prefs.getBool('show_calendar') ?? true;
    showStatisticsNotifier.value = prefs.getBool('show_statistics') ?? true;
    showDiscoveryNotifier.value = prefs.getBool('show_discovery') ?? true;
    showSubjectTypeNotifier.value = prefs.getBool('show_subject_type') ?? true;
    autoSaveDetailNotifier.value = prefs.getBool('auto_save_detail') ?? false;
    discardDetailChangesNotifier.value =
        prefs.getBool('discard_detail_changes') ?? false;
    lastSelectedStatusNotifier.value =
        prefs.getString('last_selected_status') ?? '全部';
    defaultStartStatusNotifier.value =
        prefs.getString('default_start_status') ?? '上次退出前';
    completionStatusNotifier.value =
        prefs.getString('completion_status') ?? '看完';
    autoStatusTransitionNotifier.value =
        prefs.getBool('auto_status_transition') ?? true;
    showServerSearchNotifier.value =
        prefs.getBool('show_server_search') ?? false;
    showServerDiscoveryNotifier.value =
        prefs.getBool('show_server_discovery') ?? true;
    enableCustomSplashNotifier.value =
        prefs.getBool('enable_custom_splash') ?? false;
    splashImagePathNotifier.value =
        prefs.getString('splash_image_path') ?? '';
    splashDurationNotifier.value =
        prefs.getInt('splash_duration_ms') ?? 1000;
  }

  // ============== 设置更新方法 ==============

  /// 更新字体缩放比例并保存到本地
  Future<void> setFontScale(double value) async {
    // 更新通知器
    fontScaleNotifier.value = value;

    // 持久化存储
    final prefs = await SharedPreferences.getInstance();
    await prefs.setDouble('font_scale', value);
  }

  /// 更新网格列数并保存到本地
  Future<void> setGridColumns(int value) async {
    // 更新通知器
    gridColumnsNotifier.value = value;

    // 持久化存储
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('grid_columns', value);
  }

  /// 更新标题位置并保存到本地
  Future<void> setTitlePosition(String value) async {
    // 更新通知器
    titlePositionNotifier.value = value;

    // 持久化存储
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('title_position', value);
  }

  /// 更新显示模式并保存到本地（@deprecated，新代码请用 [setHomeLayout]）
  @Deprecated('Use setHomeLayout instead.')
  Future<void> setViewMode(String value) async {
    // ignore: deprecated_member_use_from_same_package
    viewModeNotifier.value = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('view_mode', value);
  }

  /// 更新首页布局并保存到本地
  Future<void> setHomeLayout(HomeLayout layout) async {
    homeLayoutNotifier.value = layout;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('home_layout', layout.persistKey);
  }

  /// 更新封面角标样式并保存到本地
  Future<void> setBadgeStyle(BadgeStyle style) async {
    badgeStyleNotifier.value = style;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('home_badge_style', style.persistKey);
  }

  /// 更新评分前置图标并保存到本地
  Future<void> setRatingIcon(RatingIcon icon) async {
    ratingIconNotifier.value = icon;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('rating_icon', icon.persistKey);
  }

  /// 更新详情页布局
  Future<void> setDetailLayout(DetailLayout layout) async {
    detailLayoutNotifier.value = layout;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('detail_layout', layout.persistKey);
  }

  /// 更新详情页模块顺序
  Future<void> setDetailModuleOrder(List<DetailModule> order) async {
    detailModuleOrderNotifier.value = List.unmodifiable(order);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(
      'detail_module_order',
      order.map((e) => e.persistKey).toList(),
    );
  }

  /// 更新详情页被隐藏的模块集合
  Future<void> setDetailHiddenModules(Set<DetailModule> hidden) async {
    detailHiddenModulesNotifier.value = Set.unmodifiable(hidden);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(
      'detail_hidden_modules',
      hidden.map((e) => e.persistKey).toList(),
    );
  }

  /// 切换某个模块的隐藏态
  Future<void> toggleDetailModuleHidden(DetailModule module) async {
    final current = Set<DetailModule>.from(detailHiddenModulesNotifier.value);
    if (!current.add(module)) current.remove(module);
    await setDetailHiddenModules(current);
  }

  /// 更新显示项设置
  Future<void> setShowItem(String key, bool value) async {
    final prefs = await SharedPreferences.getInstance();
    if (key == 'title') {
      showTitleNotifier.value = value;
      await prefs.setBool('show_title', value);
    } else if (key == 'rating') {
      showRatingNotifier.value = value;
      await prefs.setBool('show_rating', value);
    } else if (key == 'progress') {
      showProgressNotifier.value = value;
      await prefs.setBool('show_progress', value);
    } else if (key == 'status') {
      showStatusNotifier.value = value;
      await prefs.setBool('show_status', value);
    } else if (key == 'calendar') {
      showCalendarNotifier.value = value;
      await prefs.setBool('show_calendar', value);
    } else if (key == 'statistics') {
      showStatisticsNotifier.value = value;
      await prefs.setBool('show_statistics', value);
    } else if (key == 'discovery') {
      showDiscoveryNotifier.value = value;
      await prefs.setBool('show_discovery', value);
    } else if (key == 'subject_type') {
      showSubjectTypeNotifier.value = value;
      await prefs.setBool('show_subject_type', value);
    } else if (key == 'detail_auto_save') {
      autoSaveDetailNotifier.value = value;
      await prefs.setBool('auto_save_detail', value);
    } else if (key == 'detail_discard_changes') {
      discardDetailChangesNotifier.value = value;
      await prefs.setBool('discard_detail_changes', value);
    } else if (key == 'auto_status_transition') {
      autoStatusTransitionNotifier.value = value;
      await prefs.setBool('auto_status_transition', value);
    } else if (key == 'server_search') {
      showServerSearchNotifier.value = value;
      await prefs.setBool('show_server_search', value);
    } else if (key == 'server_discovery') {
      showServerDiscoveryNotifier.value = value;
      await prefs.setBool('show_server_discovery', value);
    } else if (key == 'enable_custom_splash') {
      enableCustomSplashNotifier.value = value;
      await prefs.setBool('enable_custom_splash', value);
    }
  }

  /// 设置自定义启动封面的本地路径（空字符串表示清除）
  Future<void> setSplashImagePath(String path) async {
    splashImagePathNotifier.value = path;
    final prefs = await SharedPreferences.getInstance();
    if (path.isEmpty) {
      await prefs.remove('splash_image_path');
    } else {
      await prefs.setString('splash_image_path', path);
    }
  }

  /// 设置启动封面的展示时长（毫秒，500~5000 之间）
  Future<void> setSplashDuration(int ms) async {
    final clamped = ms.clamp(500, 5000);
    splashDurationNotifier.value = clamped;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt('splash_duration_ms', clamped);
  }

  /// 更新上次选择的状态
  Future<void> setLastSelectedStatus(String value) async {
    lastSelectedStatusNotifier.value = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('last_selected_status', value);
  }

  /// 更新默认启动状态
  Future<void> setDefaultStartStatus(String value) async {
    defaultStartStatusNotifier.value = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('default_start_status', value);
  }

  /// 更新看完后的自动归纳状态
  Future<void> setCompletionStatus(String value) async {
    completionStatusNotifier.value = value;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('completion_status', value);
  }
}
