import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as p;
import '../settings_manager.dart';
import '../ui/anime_detail/detail_layout.dart';
import '../ui/design_tokens.dart';
import '../ui/image_crop_page.dart';
import '../ui/views/_shared/rating_icon.dart';
import '../ui/views/home_layout.dart';
import 'detail_module_management_page.dart';

class SettingsDisplayPage extends StatefulWidget {
  const SettingsDisplayPage({super.key});

  @override
  State<SettingsDisplayPage> createState() => _SettingsDisplayPageState();
}

class _SettingsDisplayPageState extends State<SettingsDisplayPage> {
  // 本地设置值
  double _fontScale = 1.0;
  double _coverBorderRadius = SettingsManager.defaultCoverBorderRadius;
  double _badgeScale = SettingsManager.defaultBadgeScale;
  double _badgeOpacity = SettingsManager.defaultBadgeOpacity;
  double _badgeRadius = SettingsManager.defaultBadgeRadius;
  int _gridColumns = 3;
  String _titlePosition = 'on_cover';

  @override
  void initState() {
    super.initState();
    _fontScale = SettingsManager().fontScaleNotifier.value;
    _coverBorderRadius = SettingsManager().coverBorderRadiusNotifier.value;
    _badgeScale = SettingsManager().badgeScaleNotifier.value;
    _badgeOpacity = SettingsManager().badgeOpacityNotifier.value;
    _badgeRadius = SettingsManager().badgeRadiusNotifier.value;
    _gridColumns = SettingsManager().gridColumnsNotifier.value;
    _titlePosition = SettingsManager().titlePositionNotifier.value;
  }

  void _updateSetting(String key, dynamic value) {
    setState(() {
      if (key == 'font_scale') {
        _fontScale = value;
        SettingsManager().setFontScale(value);
      }
      if (key == 'cover_border_radius') {
        _coverBorderRadius = value;
        SettingsManager().setCoverBorderRadius(value);
      }
      if (key == 'badge_scale') {
        _badgeScale = value;
        SettingsManager().setBadgeScale(value);
      }
      if (key == 'badge_opacity') {
        _badgeOpacity = value;
        SettingsManager().setBadgeOpacity(value);
      }
      if (key == 'badge_radius') {
        _badgeRadius = value;
        SettingsManager().setBadgeRadius(value);
      }
      if (key == 'grid_columns') {
        _gridColumns = value;
        SettingsManager().setGridColumns(value);
      }
      if (key == 'title_position') {
        _titlePosition = value;
        SettingsManager().setTitlePosition(value);
      }
    });
  }

  Future<void> _resetHomeDisplaySettings() async {
    await SettingsManager().resetHomeDisplaySettings();
    if (!mounted) return;
    setState(() {
      _badgeScale = SettingsManager.defaultBadgeScale;
      _badgeOpacity = SettingsManager.defaultBadgeOpacity;
      _badgeRadius = SettingsManager.defaultBadgeRadius;
      _gridColumns = 3;
      _titlePosition = 'on_cover';
    });
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('已恢复首页推荐设置')));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).colorScheme.surface,
      appBar: AppBar(
        title: const Text('展示定制'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new, size: 20),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: ValueListenableBuilder<HomeLayout>(
        valueListenable: SettingsManager().homeLayoutNotifier,
        builder: (context, layout, _) {
          final showGridSettings =
              layout == HomeLayout.posterWall ||
              layout == HomeLayout.bentoHome ||
              layout == HomeLayout.recommendGrid;
          return Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 960),
              child: ListView(
                padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.lg,
                  vertical: AppSpacing.sm,
                ),
                children: [
                  _buildSectionTitle('首页样式'),
                  _buildSettingsCard([
                    _buildLayoutTile(layout),
                    _buildDivider(),
                    _buildBadgeStyleTile(),
                    _buildDivider(),
                    _buildRatingIconTile(),
                  ]),
                  const SizedBox(height: AppSpacing.lg),

                  _buildSectionTitle('封面信息层'),
                  _buildSettingsCard([
                    _buildDisplayItemToggle(
                      '封面显示状态',
                      SettingsManager().showCoverStatusNotifier,
                      'cover_status',
                      subtitle: '状态文字或状态点',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '封面显示评分',
                      SettingsManager().showCoverRatingNotifier,
                      'cover_rating',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '封面显示进度',
                      SettingsManager().showCoverProgressNotifier,
                      'cover_progress',
                      subtitle: '如 8/12 或已看集数',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '封面显示类型',
                      SettingsManager().showCoverTypeNotifier,
                      'cover_type',
                      subtitle: '番剧 / 漫画图标，默认关闭以减少噪音',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '系列显示作品数',
                      SettingsManager().showCoverSeriesCountNotifier,
                      'cover_series_count',
                    ),
                    _buildDivider(),
                    _buildSliderItem(
                      icon: Icons.format_size_rounded,
                      color: Colors.pinkAccent,
                      title: '信息层大小',
                      value: '${(_badgeScale * 100).round()}%',
                      slider: Slider(
                        value: _badgeScale,
                        min: 0.8,
                        max: 1.3,
                        divisions: 10,
                        label: '${(_badgeScale * 100).round()}%',
                        onChanged: (val) => _updateSetting('badge_scale', val),
                      ),
                    ),
                    _buildDivider(),
                    _buildSliderItem(
                      icon: Icons.opacity_outlined,
                      color: Colors.blue,
                      title: '信息层透明度',
                      value: '${(_badgeOpacity * 100).round()}%',
                      slider: Slider(
                        value: _badgeOpacity,
                        min: 0.35,
                        max: 1.0,
                        divisions: 13,
                        label: '${(_badgeOpacity * 100).round()}%',
                        onChanged: (val) =>
                            _updateSetting('badge_opacity', val),
                      ),
                    ),
                    _buildDivider(),
                    _buildSliderItem(
                      icon: Icons.rounded_corner,
                      color: Colors.deepPurple,
                      title: '信息层圆角',
                      value: '${_badgeRadius.round()} px',
                      slider: Slider(
                        value: _badgeRadius,
                        min: 0,
                        max: 24,
                        divisions: 12,
                        label: '${_badgeRadius.round()} px',
                        onChanged: (val) => _updateSetting('badge_radius', val),
                      ),
                    ),
                    _buildDivider(),
                    _buildMenuTile(
                      icon: Icons.restart_alt_rounded,
                      color: Colors.redAccent,
                      title: '恢复首页推荐设置',
                      subtitle: '仅重置首页布局与封面信息层',
                      trailing: const Icon(
                        Icons.chevron_right,
                        color: Colors.grey,
                      ),
                      onTap: _resetHomeDisplaySettings,
                    ),
                  ]),
                  const SizedBox(height: AppSpacing.xl),

                  _buildSectionTitle('详情页定制'),
                  _buildSettingsCard([
                    _buildDetailLayoutTile(),
                    _buildDivider(),
                    _buildModuleManagementTile(),
                  ]),
                  const SizedBox(height: AppSpacing.xl),

                  _buildSectionTitle('界面展示'),
                  _buildSettingsCard([
                    _buildSliderItem(
                      icon: Icons.text_fields_outlined,
                      color: Colors.teal,
                      title: '字体大小',
                      value: '${(_fontScale * 100).round()}%',
                      slider: Slider(
                        value: _fontScale,
                        min: 0.8,
                        max: 1.4,
                        divisions: 6,
                        onChanged: (val) => _updateSetting('font_scale', val),
                      ),
                    ),
                    _buildDivider(),
                    _buildSliderItem(
                      icon: Icons.rounded_corner,
                      color: Colors.deepPurple,
                      title: '封面圆角',
                      value: '${_coverBorderRadius.round()} px',
                      slider: Slider(
                        value: _coverBorderRadius,
                        min: 0,
                        max: 32,
                        divisions: 16,
                        label: '${_coverBorderRadius.round()} px',
                        onChanged: (val) =>
                            _updateSetting('cover_border_radius', val),
                      ),
                    ),
                    if (showGridSettings) ...[
                      _buildDivider(),
                      _buildSliderItem(
                        icon: Icons.grid_on_outlined,
                        color: Colors.orange,
                        title: '首页宫格列数',
                        value: '$_gridColumns 列',
                        slider: Slider(
                          value: _gridColumns.toDouble(),
                          min: 2,
                          max: 5,
                          divisions: 3,
                          onChanged: (val) =>
                              _updateSetting('grid_columns', val.toInt()),
                        ),
                      ),
                      _buildDivider(),
                      _buildMenuTile(
                        icon: Icons.subtitles_outlined,
                        color: Colors.blueGrey,
                        title: '封面标题位置',
                        trailing: DropdownButtonHideUnderline(
                          child: DropdownButton<String>(
                            value: _titlePosition,
                            onChanged: (String? newValue) {
                              if (newValue != null) {
                                _updateSetting('title_position', newValue);
                              }
                            },
                            items: const [
                              DropdownMenuItem(
                                value: 'on_cover',
                                child: Text(
                                  '封面底部',
                                  style: TextStyle(fontSize: 14),
                                ),
                              ),
                              DropdownMenuItem(
                                value: 'below_cover',
                                child: Text(
                                  '封面外下方',
                                  style: TextStyle(fontSize: 14),
                                ),
                              ),
                            ],
                          ),
                        ),
                        onTap: () {},
                      ),
                    ],
                  ]),
                  const SizedBox(height: AppSpacing.xl),
                  _buildSectionTitle('启动封面'),
                  _buildSettingsCard([_buildSplashSection()]),
                  const SizedBox(height: AppSpacing.xl),
                  _buildSectionTitle('数据项显隐'),
                  _buildSettingsCard([
                    _buildDisplayItemToggle(
                      '显示标题',
                      SettingsManager().showTitleNotifier,
                      'title',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '显示评分',
                      SettingsManager().showRatingNotifier,
                      'rating',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '显示进度',
                      SettingsManager().showProgressNotifier,
                      'progress',
                    ),
                    _buildDivider(),
                    _buildDisplayItemToggle(
                      '显示类型图标',
                      SettingsManager().showSubjectTypeNotifier,
                      'subject_type',
                      subtitle: '番剧 / 漫画标识',
                    ),
                  ]),
                  const SizedBox(height: AppSpacing.xxl),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  /// 通用底部弹层选择器：用户点击 tile 弹出，展示所有选项
  Future<void> _showOptionPicker<T>({
    required String title,
    required List<T> options,
    required T selected,
    required String Function(T) labelOf,
    required String Function(T) descOf,
    required Widget Function(T option) leadingOf,
    required ValueChanged<T> onSelect,
  }) async {
    final cs = Theme.of(context).colorScheme;
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (ctx) {
        return SafeArea(
          top: false,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.lg,
                  AppSpacing.sm,
                  AppSpacing.lg,
                  AppSpacing.md,
                ),
                child: Text(
                  title,
                  style: Theme.of(
                    ctx,
                  ).textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w800),
                ),
              ),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  padding: const EdgeInsets.only(bottom: AppSpacing.md),
                  children: [
                    for (final option in options)
                      _OptionSheetTile(
                        leading: leadingOf(option),
                        title: labelOf(option),
                        description: descOf(option),
                        isSelected: option == selected,
                        onTap: () {
                          onSelect(option);
                          Navigator.of(ctx).pop();
                        },
                        accent: cs.primary,
                      ),
                  ],
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  /// 首页布局 tile（点击弹层）
  Widget _buildLayoutTile(HomeLayout selected) {
    return _buildMenuTile(
      icon: selected.icon,
      color: Colors.indigo,
      title: '首页布局',
      subtitle: selected.label,
      trailing: const Icon(Icons.chevron_right, color: Colors.grey),
      onTap: () => _showOptionPicker<HomeLayout>(
        title: '选择首页布局',
        options: HomeLayout.values,
        selected: selected,
        labelOf: (l) => l.label,
        descOf: (l) => l.description,
        leadingOf: (l) => _SheetLeadingIcon(icon: l.icon, color: Colors.indigo),
        onSelect: SettingsManager().setHomeLayout,
      ),
    );
  }

  /// 封面角标样式 tile（点击弹层）
  Widget _buildBadgeStyleTile() {
    return ValueListenableBuilder<BadgeStyle>(
      valueListenable: SettingsManager().badgeStyleNotifier,
      builder: (context, selected, _) {
        return _buildMenuTile(
          icon: Icons.style_outlined,
          color: Colors.pinkAccent,
          title: '封面角标样式',
          subtitle: selected.label,
          trailing: const Icon(Icons.chevron_right, color: Colors.grey),
          onTap: () => _showOptionPicker<BadgeStyle>(
            title: '选择封面角标样式',
            options: BadgeStyle.values,
            selected: selected,
            labelOf: (s) => s.label,
            descOf: (s) => s.description,
            leadingOf: (s) => _BadgeStylePreview(style: s),
            onSelect: SettingsManager().setBadgeStyle,
          ),
        );
      },
    );
  }

  /// 评分前置图标 tile（点击弹层）
  Widget _buildRatingIconTile() {
    return ValueListenableBuilder<RatingIcon>(
      valueListenable: SettingsManager().ratingIconNotifier,
      builder: (context, selected, _) {
        final subtitle = selected == RatingIcon.none
            ? '不显示，只看数字'
            : '${selected.emoji}  ${selected.label}';
        return _buildMenuTile(
          icon: Icons.emoji_emotions_outlined,
          color: Colors.amber,
          title: '评分图标',
          subtitle: subtitle,
          trailing: const Icon(Icons.chevron_right, color: Colors.grey),
          onTap: () => _showOptionPicker<RatingIcon>(
            title: '选择评分前置图标',
            options: RatingIcon.values,
            selected: selected,
            labelOf: (i) => i.label,
            descOf: (i) =>
                i == RatingIcon.none ? '只显示评分数字，不带前缀' : '示例：${i.emoji} 8.5',
            leadingOf: (i) => _RatingIconLeading(icon: i),
            onSelect: SettingsManager().setRatingIcon,
          ),
        );
      },
    );
  }

  /// 详情页布局 tile（点击弹层）
  Widget _buildDetailLayoutTile() {
    return ValueListenableBuilder<DetailLayout>(
      valueListenable: SettingsManager().detailLayoutNotifier,
      builder: (context, selected, _) {
        return _buildMenuTile(
          icon: selected.icon,
          color: Colors.deepOrange,
          title: '详情页布局',
          subtitle: selected.label,
          trailing: const Icon(Icons.chevron_right, color: Colors.grey),
          onTap: () => _showOptionPicker<DetailLayout>(
            title: '选择详情页布局',
            options: DetailLayout.values,
            selected: selected,
            labelOf: (l) => l.label,
            descOf: (l) => l.description,
            leadingOf: (l) =>
                _SheetLeadingIcon(icon: l.icon, color: Colors.deepOrange),
            onSelect: SettingsManager().setDetailLayout,
          ),
        );
      },
    );
  }

  /// 「模块管理」入口 tile（打开独立页面做拖动 + 显隐）
  Widget _buildModuleManagementTile() {
    return _buildMenuTile(
      icon: Icons.tune_rounded,
      color: Colors.deepPurple,
      title: '详情页模块管理',
      subtitle: '隐藏 / 重排 番剧详情页内的模块',
      trailing: const Icon(Icons.chevron_right, color: Colors.grey),
      onTap: () {
        Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const DetailModuleManagementPage()),
        );
      },
    );
  }

  Widget _buildSectionTitle(String title) {
    final colorScheme = Theme.of(context).colorScheme;
    return Padding(
      padding: const EdgeInsets.only(left: 8, bottom: 12),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 14,
          fontWeight: FontWeight.bold,
          color: colorScheme.onSurfaceVariant,
          letterSpacing: 1.2,
        ),
      ),
    );
  }

  Widget _buildSettingsCard(List<Widget> children) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(24),
        boxShadow: AppElevation.card(context),
        border: Border.all(
          color: colorScheme.outlineVariant.withValues(alpha: 0.35),
        ),
      ),
      child: Column(children: children),
    );
  }

  Widget _buildDivider() {
    final colorScheme = Theme.of(context).colorScheme;
    return Divider(
      height: 1,
      indent: 60,
      endIndent: 20,
      color: colorScheme.outlineVariant.withValues(alpha: 0.35),
    );
  }

  Widget _buildMenuTile({
    required IconData icon,
    required Color color,
    required String title,
    String? subtitle,
    required Widget trailing,
    VoidCallback? onTap,
  }) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(14),
              ),
              child: Icon(icon, color: color, size: 22),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (subtitle != null)
                    Text(
                      subtitle,
                      style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                    ),
                ],
              ),
            ),
            trailing,
          ],
        ),
      ),
    );
  }

  Widget _buildSliderItem({
    required IconData icon,
    required Color color,
    required String title,
    required String value,
    required Widget slider,
  }) {
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: color.withValues(alpha: 0.12),
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Icon(icon, color: color, size: 22),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Text(
                  title,
                  style: const TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                value,
                style: TextStyle(
                  color: color,
                  fontWeight: FontWeight.bold,
                  fontSize: 13,
                ),
              ),
            ],
          ),
          Padding(padding: const EdgeInsets.only(left: 44), child: slider),
        ],
      ),
    );
  }

  Widget _buildDisplayItemToggle(
    String title,
    ValueNotifier<bool> notifier,
    String key, {
    String? subtitle,
  }) {
    return ValueListenableBuilder<bool>(
      valueListenable: notifier,
      builder: (context, value, _) {
        return _buildMenuTile(
          icon: _getIconForKey(key),
          color: _getColorForKey(key),
          title: title,
          subtitle: subtitle,
          trailing: Switch(
            value: value,
            onChanged: (v) => SettingsManager().setShowItem(key, v),
          ),
          onTap: () {
            SettingsManager().setShowItem(key, !value);
            setState(() {});
          },
        );
      },
    );
  }

  IconData _getIconForKey(String key) {
    switch (key) {
      case 'title':
        return Icons.title_outlined;
      case 'rating':
        return Icons.pets_outlined;
      case 'progress':
        return Icons.playlist_add_check_outlined;
      case 'status':
        return Icons.label_outline;
      case 'calendar':
        return Icons.calendar_today_outlined;
      case 'statistics':
        return Icons.bar_chart_outlined;
      case 'discovery':
        return Icons.explore_outlined;
      case 'subject_type':
        return Icons.category_outlined;
      case 'cover_status':
        return Icons.label_outline;
      case 'cover_rating':
        return Icons.star_outline_rounded;
      case 'cover_progress':
        return Icons.timelapse_rounded;
      case 'cover_type':
        return Icons.category_outlined;
      case 'cover_series_count':
        return Icons.layers_outlined;
      default:
        return Icons.settings_outlined;
    }
  }

  Color _getColorForKey(String key) {
    switch (key) {
      case 'title':
        return Colors.orange;
      case 'rating':
        return Colors.amber;
      case 'progress':
        return Colors.green;
      case 'status':
        return Colors.blue;
      case 'calendar':
        return Colors.indigo;
      case 'statistics':
        return Colors.deepPurple;
      case 'discovery':
        return Colors.purple;
      case 'subject_type':
        return Colors.teal;
      case 'cover_status':
        return Colors.blue;
      case 'cover_rating':
        return Colors.amber;
      case 'cover_progress':
        return Colors.green;
      case 'cover_type':
        return Colors.teal;
      case 'cover_series_count':
        return Colors.deepPurple;
      default:
        return Colors.grey;
    }
  }

  // ============== 启动封面相关 ==============

  /// 启动封面整体 section（开关 + 当前图预览 + 选择/清除按钮）
  Widget _buildSplashSection() {
    return ValueListenableBuilder<bool>(
      valueListenable: SettingsManager().enableCustomSplashNotifier,
      builder: (context, enabled, _) {
        return Column(
          children: [
            // 主开关
            _buildMenuTile(
              icon: Icons.image_outlined,
              color: Colors.pinkAccent,
              title: "启用自定义启动封面",
              subtitle: "启动 APP 时短暂展示你设置的图片",
              trailing: Switch(
                value: enabled,
                onChanged: (v) {
                  SettingsManager().setShowItem('enable_custom_splash', v);
                },
              ),
              onTap: () {
                SettingsManager().setShowItem('enable_custom_splash', !enabled);
              },
            ),

            // 仅在开启时展示图片选择器 + 展示时长
            if (enabled) ...[
              _buildDivider(),
              ValueListenableBuilder<String>(
                valueListenable: SettingsManager().splashImagePathNotifier,
                builder: (context, path, _) {
                  return _buildSplashPickerTile(path);
                },
              ),
              _buildDivider(),
              ValueListenableBuilder<int>(
                valueListenable: SettingsManager().splashDurationNotifier,
                builder: (context, ms, _) {
                  return _buildSliderItem(
                    icon: Icons.timer_outlined,
                    color: Colors.amber,
                    title: '展示时长',
                    value: '${(ms / 1000).toStringAsFixed(1)} 秒',
                    slider: Slider(
                      value: ms.toDouble(),
                      min: 500,
                      max: 5000,
                      divisions: 9,
                      label: '${(ms / 1000).toStringAsFixed(1)}s',
                      onChanged: (v) =>
                          SettingsManager().setSplashDuration(v.round()),
                    ),
                  );
                },
              ),
            ],
          ],
        );
      },
    );
  }

  /// 当前封面预览 / 选择按钮
  Widget _buildSplashPickerTile(String currentPath) {
    final hasImage = currentPath.isNotEmpty && File(currentPath).existsSync();

    return InkWell(
      onTap: _pickSplashImage,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            // 缩略图预览 / 占位
            Container(
              width: 56,
              height: 56,
              decoration: BoxDecoration(
                color: Colors.grey[100],
                borderRadius: BorderRadius.circular(12),
                border: Border.all(color: Colors.grey[200]!, width: 1),
              ),
              clipBehavior: Clip.antiAlias,
              child: hasImage
                  ? Image.file(
                      File(currentPath),
                      fit: BoxFit.cover,
                      // key 强制刷新，防止换图后仍命中老缓存
                      key: ValueKey(currentPath),
                      errorBuilder: (_, _, _) => Icon(
                        Icons.broken_image_outlined,
                        color: Colors.grey[400],
                      ),
                    )
                  : Icon(
                      Icons.add_photo_alternate_outlined,
                      color: Colors.grey[400],
                      size: 28,
                    ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    hasImage ? "当前封面" : "选择封面图片",
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  Text(
                    hasImage ? "点击更换图片" : "从相册选择并裁剪",
                    style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                  ),
                ],
              ),
            ),
            if (hasImage)
              IconButton(
                icon: const Icon(Icons.delete_outline, color: Colors.redAccent),
                tooltip: "清除封面",
                onPressed: _clearSplashImage,
              ),
          ],
        ),
      ),
    );
  }

  /// 选择 + 裁剪 + 持久化
  Future<void> _pickSplashImage() async {
    try {
      final picker = ImagePicker();
      final XFile? picked = await picker.pickImage(
        source: ImageSource.gallery,
        imageQuality: 90,
      );
      if (picked == null) return;

      final String? cropped = await _cropImage(picked.path);
      if (cropped == null) return;

      // 复制到永久目录 splash/cover_<timestamp>.<ext>
      final Directory docDir = await getApplicationDocumentsDirectory();
      final Directory splashDir = Directory(p.join(docDir.path, 'splash'));
      if (!await splashDir.exists()) {
        await splashDir.create(recursive: true);
      }

      // 清理旧文件
      final String oldPath = SettingsManager().splashImagePathNotifier.value;
      if (oldPath.isNotEmpty) {
        try {
          final old = File(oldPath);
          if (await old.exists()) await old.delete();
        } catch (_) {
          /* ignore */
        }
      }

      final String ext = p.extension(cropped).isNotEmpty
          ? p.extension(cropped)
          : '.jpg';
      final String newPath = p.join(
        splashDir.path,
        'cover_${DateTime.now().millisecondsSinceEpoch}$ext',
      );
      await File(cropped).copy(newPath);

      await SettingsManager().setSplashImagePath(newPath);

      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(const SnackBar(content: Text('启动封面已更新喵 ~')));
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('选择失败：$e')));
      }
    }
  }

  /// 清除当前封面（仅清空设置 + 删除文件，不关闭主开关）
  Future<void> _clearSplashImage() async {
    final String oldPath = SettingsManager().splashImagePathNotifier.value;
    if (oldPath.isNotEmpty) {
      try {
        final f = File(oldPath);
        if (await f.exists()) await f.delete();
      } catch (_) {
        /* ignore */
      }
    }
    await SettingsManager().setSplashImagePath('');
    if (mounted) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('已清除自定义启动封面')));
    }
  }

  /// 跨平台图片裁剪 —— 推进 [ImageCropPage]，返回裁剪后 PNG 的临时路径；取消则 null
  Future<String?> _cropImage(String sourcePath) async {
    if (!mounted) return null;
    return await Navigator.of(context).push<String>(
      MaterialPageRoute(
        builder: (_) => ImageCropPage(imagePath: sourcePath, title: '裁剪启动封面'),
        fullscreenDialog: true,
      ),
    );
  }
}

/// 角标样式的迷你预览（一个 36x52 的模拟封面，按所选样式画出角标）
class _BadgeStylePreview extends StatelessWidget {
  final BadgeStyle style;
  const _BadgeStylePreview({required this.style});

  static const Color _coverColor = Color(0xFFB0BEC5);
  static const Color _statusColor = Color(0xFF42A5F5);

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 38,
      height: 56,
      decoration: BoxDecoration(
        color: _coverColor,
        borderRadius: BorderRadius.circular(6),
      ),
      clipBehavior: Clip.antiAlias,
      child: Stack(fit: StackFit.expand, children: _buildLayers()),
    );
  }

  List<Widget> _buildLayers() {
    switch (style) {
      case BadgeStyle.overlay:
        return [
          Positioned(
            left: 3,
            right: 3,
            bottom: 3,
            child: Container(
              height: 16,
              padding: const EdgeInsets.symmetric(horizontal: 3),
              decoration: BoxDecoration(
                color: Colors.black.withValues(alpha: 0.72),
                borderRadius: BorderRadius.circular(3),
              ),
              child: Row(
                children: [
                  Container(
                    width: 5,
                    height: 3,
                    decoration: BoxDecoration(
                      color: _statusColor,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const Spacer(),
                  Container(
                    width: 10,
                    height: 4,
                    decoration: BoxDecoration(
                      color: Colors.amber.withValues(alpha: 0.9),
                      borderRadius: BorderRadius.circular(1.5),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ];
      case BadgeStyle.corners:
        return [
          Positioned(top: 0, left: 0, child: _MiniFlush(color: _statusColor)),
          const Positioned(
            top: 0,
            right: 0,
            child: _MiniFlush(color: Colors.black54, isRating: true),
          ),
        ];
      case BadgeStyle.bottomBar:
        return [
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: Container(
              height: 12,
              padding: const EdgeInsets.symmetric(horizontal: 3),
              color: Colors.black.withValues(alpha: 0.6),
              child: Row(
                children: [
                  Container(width: 2, height: 6, color: _statusColor),
                  const Spacer(),
                  Container(
                    width: 8,
                    height: 4,
                    decoration: BoxDecoration(
                      color: Colors.amber.withValues(alpha: 0.9),
                      borderRadius: BorderRadius.circular(1),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ];
      case BadgeStyle.minimal:
        return [
          Positioned(
            top: 4,
            left: 4,
            child: Container(
              width: 6,
              height: 6,
              decoration: BoxDecoration(
                color: _statusColor,
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white, width: 0.8),
              ),
            ),
          ),
          const Positioned(
            top: 4,
            right: 4,
            child: Text(
              '8.5',
              style: TextStyle(
                color: Colors.white,
                fontSize: 8,
                fontWeight: FontWeight.w800,
                shadows: [Shadow(blurRadius: 2, color: Colors.black87)],
              ),
            ),
          ),
        ];
    }
  }
}

class _MiniFlush extends StatelessWidget {
  final Color color;
  final bool isRating;
  const _MiniFlush({required this.color, this.isRating = false});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: isRating ? 13 : 11,
      height: 6,
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.92),
        borderRadius: BorderRadius.only(
          bottomLeft: isRating ? const Radius.circular(2.5) : Radius.zero,
          bottomRight: !isRating ? const Radius.circular(2.5) : Radius.zero,
        ),
      ),
    );
  }
}

/// 底部弹层中的单项 tile
class _OptionSheetTile extends StatelessWidget {
  final Widget leading;
  final String title;
  final String description;
  final bool isSelected;
  final VoidCallback onTap;
  final Color accent;

  const _OptionSheetTile({
    required this.leading,
    required this.title,
    required this.description,
    required this.isSelected,
    required this.onTap,
    required this.accent,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return InkWell(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.lg,
          vertical: AppSpacing.md,
        ),
        decoration: BoxDecoration(
          color: isSelected
              ? accent.withValues(alpha: 0.06)
              : Colors.transparent,
        ),
        child: Row(
          children: [
            SizedBox(width: 50, height: 60, child: Center(child: leading)),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                      color: isSelected ? accent : cs.onSurface,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    description,
                    style: TextStyle(fontSize: 12, color: cs.onSurfaceVariant),
                  ),
                ],
              ),
            ),
            if (isSelected)
              Icon(Icons.check_circle_rounded, color: accent, size: 22)
            else
              Icon(
                Icons.radio_button_unchecked,
                color: cs.outlineVariant,
                size: 22,
              ),
          ],
        ),
      ),
    );
  }
}

/// 弹层中带颜色背景的 leading 图标
class _SheetLeadingIcon extends StatelessWidget {
  final IconData icon;
  final Color color;
  const _SheetLeadingIcon({required this.icon, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 36,
      height: 36,
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.14),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      child: Icon(icon, size: 20, color: color),
    );
  }
}

/// 评分图标弹层里的 leading：直接展示对应 emoji；none 展示空圆圈
class _RatingIconLeading extends StatelessWidget {
  final RatingIcon icon;
  const _RatingIconLeading({required this.icon});

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    if (icon == RatingIcon.none) {
      return Container(
        width: 36,
        height: 36,
        decoration: BoxDecoration(
          color: cs.surfaceContainerHigh,
          borderRadius: BorderRadius.circular(AppRadius.xs),
        ),
        child: Icon(
          Icons.do_not_disturb_alt_outlined,
          size: 18,
          color: cs.onSurfaceVariant,
        ),
      );
    }
    return Container(
      width: 36,
      height: 36,
      decoration: BoxDecoration(
        color: Colors.amber.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      alignment: Alignment.center,
      child: Text(
        icon.emoji,
        style: const TextStyle(fontSize: 20, height: 1.0),
      ),
    );
  }
}
