import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_colorpicker/flutter_colorpicker.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'theme_manager.dart';
import 'settings_manager.dart';
import 'statistics_page.dart';
import 'server_search_page.dart';
import 'settings/settings_general_page.dart';
import 'settings/settings_display_page.dart';
import 'settings/settings_data_page.dart';
import 'settings/settings_about_page.dart';
import 'settings/testing_features_page.dart';
import 'settings/reminder_management_page.dart';
import 'ui/design_tokens.dart';

/// 「我的」页面：4-Tab 重构后聚合统计、资源库、设置、主题、关于等次级入口。
///
/// 设计上分为 4 个分组：
/// 1. **数据中心**：统计 / 资源库 / 追番提醒
/// 2. **个性化**：主题 / 展示定制
/// 3. **应用设置**：通用 / 数据管理
/// 4. **关于**：更新日志 / 测试功能 / QQ 群
class MyPage extends StatefulWidget {
  final VoidCallback? onDatabaseRefresh;
  final VoidCallback? onAnimeAdded;

  const MyPage({super.key, this.onDatabaseRefresh, this.onAnimeAdded});

  @override
  State<MyPage> createState() => MyPageState();
}

class MyPageState extends State<MyPage> {
  String _version = '';

  @override
  void initState() {
    super.initState();
    _loadVersion();
  }

  Future<void> _loadVersion() async {
    try {
      final info = await PackageInfo.fromPlatform();
      if (mounted) setState(() => _version = info.version);
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: colorScheme.surface,
      body: CustomScrollView(
        slivers: [
          SliverAppBar.large(
            title: const Text('我的'),
            pinned: true,
            stretch: true,
            backgroundColor: colorScheme.surface,
            surfaceTintColor: Colors.transparent,
          ),
          SliverToBoxAdapter(child: _buildHeader(colorScheme)),
          SliverPadding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.lg,
              AppSpacing.md,
              AppSpacing.lg,
              AppSpacing.xxl,
            ),
            sliver: SliverList(
              delegate: SliverChildListDelegate([
                _buildSectionTitle('数据中心'),
                _buildSettingsCard([
                  _buildTile(
                    icon: Icons.analytics_outlined,
                    color: Colors.deepPurple,
                    title: '统计',
                    subtitle: '观看进度、状态分布、标签使用',
                    onTap: () => _push(const StatisticsPage()),
                  ),
                  ValueListenableBuilder<bool>(
                    valueListenable:
                        SettingsManager().showServerSearchNotifier,
                    builder: (context, show, _) {
                      if (!show) return const SizedBox.shrink();
                      return Column(
                        children: [
                          _buildDivider(),
                          _buildTile(
                            icon: Icons.cloud_download_outlined,
                            color: Colors.teal,
                            title: '资源库',
                            subtitle: '从公共资源库搜索并添加',
                            onTap: () => _push(
                              ServerSearchPage(
                                onAnimeAdded: widget.onAnimeAdded,
                              ),
                            ),
                          ),
                        ],
                      );
                    },
                  ),
                  _buildDivider(),
                  _buildTile(
                    icon: Icons.notifications_active_outlined,
                    color: Colors.orange,
                    title: '追番提醒',
                    subtitle: '管理已设定的提醒',
                    onTap: () => _push(const ReminderManagementPage()),
                  ),
                ]),
                const SizedBox(height: AppSpacing.xl),

                _buildSectionTitle('个性化'),
                _buildSettingsCard([
                  _buildTile(
                    icon: Icons.palette_outlined,
                    color: Colors.purple,
                    title: '主题色',
                    subtitle: '8 款二次元配色',
                    trailing: ValueListenableBuilder<Color>(
                      valueListenable: ThemeManager().colorNotifier,
                      builder: (context, color, _) {
                        return Container(
                          width: 24,
                          height: 24,
                          decoration: BoxDecoration(
                            color: color,
                            shape: BoxShape.circle,
                            boxShadow: [
                              BoxShadow(
                                color: color.withValues(alpha: 0.3),
                                blurRadius: 8,
                                offset: const Offset(0, 2),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
                    onTap: _showThemePicker,
                  ),
                  _buildDivider(),
                  _buildTile(
                    icon: Icons.dashboard_customize_outlined,
                    color: Colors.blue,
                    title: '展示定制',
                    subtitle: '布局模式、字号、数据显隐',
                    onTap: () => _push(const SettingsDisplayPage()),
                  ),
                ]),
                const SizedBox(height: AppSpacing.xl),

                _buildSectionTitle('应用设置'),
                _buildSettingsCard([
                  _buildTile(
                    icon: Icons.tune_rounded,
                    color: Colors.indigo,
                    title: '通用',
                    subtitle: '默认状态、自动归纳、详情页行为',
                    onTap: () => _push(const SettingsGeneralPage()),
                  ),
                  _buildDivider(),
                  _buildTile(
                    icon: Icons.storage_outlined,
                    color: Colors.blue,
                    title: '数据管理',
                    subtitle: '备份、导出、状态与标签',
                    onTap: () => _push(
                      SettingsDataPage(
                        onDatabaseRefresh: widget.onDatabaseRefresh,
                      ),
                    ),
                  ),
                ]),
                const SizedBox(height: AppSpacing.xl),

                _buildSectionTitle('关于'),
                _buildSettingsCard([
                  _buildTile(
                    icon: Icons.info_outline,
                    color: Colors.indigo,
                    title: '关于与更新',
                    subtitle: '更新日志、反馈、版权',
                    onTap: () => _push(const SettingsAboutPage()),
                  ),
                  _buildDivider(),
                  _buildTile(
                    icon: Icons.group_add_outlined,
                    color: Colors.blueAccent,
                    title: 'QQ 交流群',
                    subtitle: '群号 1073623448',
                    onTap: () => _showQQDialog('1073623448'),
                  ),
                  _buildDivider(),
                  _buildTile(
                    icon: Icons.science_outlined,
                    color: Colors.deepOrange,
                    title: '测试功能',
                    subtitle: '错误日志与实验性功能',
                    onTap: () => _push(const TestingFeaturesPage()),
                  ),
                ]),

                const SizedBox(height: AppSpacing.xxl),
                Center(
                  child: Text(
                    '陪你记录每一份热爱',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                          letterSpacing: 1.5,
                        ),
                  ),
                ),
              ]),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildHeader(ColorScheme colorScheme) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.lg,
        AppSpacing.sm,
        AppSpacing.lg,
        AppSpacing.lg,
      ),
      child: Row(
        children: [
          Container(
            width: 64,
            height: 64,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(AppRadius.md),
              boxShadow: AppElevation.card(context),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(AppRadius.md),
              child: Image.asset(
                'assets/icon.png',
                fit: BoxFit.cover,
                errorBuilder: (_, __, ___) => Container(
                  color: colorScheme.primaryContainer,
                  child: Icon(
                    Icons.pets,
                    color: colorScheme.onPrimaryContainer,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: AppSpacing.lg),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                RichText(
                  text: TextSpan(
                    style: Theme.of(context).textTheme.headlineMedium?.copyWith(
                          color: colorScheme.onSurface,
                        ),
                    children: [
                      const TextSpan(
                        text: '追番喵',
                        style: TextStyle(fontWeight: FontWeight.w900),
                      ),
                      TextSpan(
                        text: '  AniMeow',
                        style: TextStyle(
                          fontSize: 14,
                          color: colorScheme.onSurfaceVariant,
                          fontStyle: FontStyle.italic,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 6),
                if (_version.isNotEmpty)
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: colorScheme.secondaryContainer,
                      borderRadius: BorderRadius.circular(AppRadius.xs),
                    ),
                    child: Text(
                      'v$_version',
                      style: TextStyle(
                        color: colorScheme.onSecondaryContainer,
                        fontSize: 11,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.sm,
        AppSpacing.md,
        AppSpacing.sm,
        AppSpacing.sm,
      ),
      child: Text(
        title,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              letterSpacing: 1.2,
            ),
      ),
    );
  }

  Widget _buildSettingsCard(List<Widget> children) {
    return Container(
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerLow,
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
      child: Column(children: children),
    );
  }

  Widget _buildDivider() => Divider(
        height: 1,
        indent: 64,
        endIndent: AppSpacing.md,
        color: Theme.of(context).colorScheme.outlineVariant.withValues(
              alpha: 0.4,
            ),
      );

  Widget _buildTile({
    required IconData icon,
    required Color color,
    required String title,
    String? subtitle,
    Widget? trailing,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadius.md),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.md,
          vertical: AppSpacing.md,
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: color.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(AppRadius.xs),
              ),
              child: Icon(icon, color: color, size: 22),
            ),
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  if (subtitle != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                            color: Theme.of(context).colorScheme.onSurfaceVariant,
                          ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: AppSpacing.sm),
            trailing ??
                Icon(
                  Icons.chevron_right_rounded,
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                  size: 22,
                ),
          ],
        ),
      ),
    );
  }

  void _push(Widget page) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => page),
    );
  }

  void _showThemePicker() {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      builder: (sheetCtx) => Padding(
        padding: const EdgeInsets.all(AppSpacing.xl),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              '选择本命色',
              style: Theme.of(context).textTheme.headlineMedium,
            ),
            Text(
              '点击预设色或最后一格自定义 RGB',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color:
                        Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: AppSpacing.lg),
            ValueListenableBuilder<Color>(
              valueListenable: ThemeManager().colorNotifier,
              builder: (context, currentColor, _) {
                final presets = ThemeManager().themeColors;
                final isPreset = presets.any(
                  (p) =>
                      (p['color'] as Color).toARGB32() ==
                      currentColor.toARGB32(),
                );
                return GridView.builder(
                  shrinkWrap: true,
                  physics: const NeverScrollableScrollPhysics(),
                  gridDelegate:
                      const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 4,
                    mainAxisSpacing: AppSpacing.lg,
                    crossAxisSpacing: AppSpacing.lg,
                    childAspectRatio: 0.85,
                  ),
                  // 末位为「自定义」入口
                  itemCount: presets.length + 1,
                  itemBuilder: (context, index) {
                    if (index == presets.length) {
                      return _CustomColorTile(
                        active: !isPreset,
                        currentColor: currentColor,
                        onPick: () {
                          Navigator.pop(sheetCtx);
                          _showCustomColorDialog(currentColor);
                        },
                      );
                    }
                    final item = presets[index];
                    final color = item['color'] as Color;
                    final isSelected =
                        currentColor.toARGB32() == color.toARGB32();
                    return InkWell(
                      onTap: () {
                        ThemeManager().changeTheme(color);
                        Navigator.pop(sheetCtx);
                      },
                      borderRadius: BorderRadius.circular(AppRadius.xs),
                      child: Column(
                        children: [
                          Container(
                            width: 48,
                            height: 48,
                            decoration: BoxDecoration(
                              color: color,
                              shape: BoxShape.circle,
                              border: isSelected
                                  ? Border.all(
                                      color: Theme.of(context)
                                          .colorScheme
                                          .onSurface,
                                      width: 2.5,
                                    )
                                  : null,
                              boxShadow: [
                                BoxShadow(
                                  color: color.withValues(alpha: 0.3),
                                  blurRadius: 10,
                                  offset: const Offset(0, 4),
                                ),
                              ],
                            ),
                            child: isSelected
                                ? const Icon(
                                    Icons.check,
                                    color: Colors.white,
                                    size: 22,
                                  )
                                : null,
                          ),
                          const SizedBox(height: AppSpacing.sm),
                          Text(
                            item['name'],
                            style:
                                Theme.of(context).textTheme.labelMedium,
                          ),
                        ],
                      ),
                    );
                  },
                );
              },
            ),
            const SizedBox(height: AppSpacing.md),
          ],
        ),
      ),
    );
  }

  /// 自定义 RGB 颜色选择对话框
  void _showCustomColorDialog(Color initial) {
    Color picked = initial;
    showDialog<void>(
      context: context,
      builder: (ctx) {
        return AlertDialog(
          title: const Text('自定义颜色'),
          contentPadding: const EdgeInsets.fromLTRB(8, 16, 8, 0),
          content: SingleChildScrollView(
            child: ColorPicker(
              pickerColor: initial,
              onColorChanged: (c) => picked = c,
              enableAlpha: false,
              hexInputBar: true,
              displayThumbColor: true,
              labelTypes: const [
                ColorLabelType.rgb,
                ColorLabelType.hex,
                ColorLabelType.hsv,
              ],
              paletteType: PaletteType.hsvWithHue,
              pickerAreaBorderRadius:
                  BorderRadius.circular(AppRadius.sm),
            ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx),
              child: const Text('取消'),
            ),
            FilledButton(
              onPressed: () {
                ThemeManager().changeTheme(picked);
                Navigator.pop(ctx);
              },
              child: const Text('应用'),
            ),
          ],
        );
      },
    );
  }

  void _showQQDialog(String groupNumber) {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('加入 QQ 交流群'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('交流群号：'),
            const SizedBox(height: AppSpacing.sm),
            Text(
              groupNumber,
              style: TextStyle(
                fontSize: 24,
                fontWeight: FontWeight.w900,
                color: Theme.of(context).colorScheme.primary,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          FilledButton.icon(
            icon: const Icon(Icons.copy_rounded, size: 18),
            label: const Text('复制群号'),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: groupNumber));
              if (context.mounted) {
                Navigator.pop(context);
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('群号已复制')),
                );
              }
            },
          ),
        ],
      ),
    );
  }
}

/// 主题色网格末尾的"自定义"瓦片
class _CustomColorTile extends StatelessWidget {
  final bool active;
  final Color currentColor;
  final VoidCallback onPick;

  const _CustomColorTile({
    required this.active,
    required this.currentColor,
    required this.onPick,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onPick,
      borderRadius: BorderRadius.circular(AppRadius.xs),
      child: Column(
        children: [
          Container(
            width: 48,
            height: 48,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              gradient: const SweepGradient(
                colors: [
                  Color(0xFFFF5252),
                  Color(0xFFFFEB3B),
                  Color(0xFF69F0AE),
                  Color(0xFF40C4FF),
                  Color(0xFFE040FB),
                  Color(0xFFFF5252),
                ],
              ),
              border: active
                  ? Border.all(
                      color: Theme.of(context).colorScheme.onSurface,
                      width: 2.5,
                    )
                  : null,
              boxShadow: [
                BoxShadow(
                  color: currentColor.withValues(alpha: 0.25),
                  blurRadius: 10,
                  offset: const Offset(0, 4),
                ),
              ],
            ),
            child: active
                ? Center(
                    child: Container(
                      width: 24,
                      height: 24,
                      decoration: BoxDecoration(
                        color: currentColor,
                        shape: BoxShape.circle,
                        border: Border.all(color: Colors.white, width: 2),
                      ),
                    ),
                  )
                : const Center(
                    child: Icon(
                      Icons.colorize_rounded,
                      color: Colors.white,
                      size: 22,
                    ),
                  ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            '自定义',
            style: Theme.of(context).textTheme.labelMedium,
          ),
        ],
      ),
    );
  }
}
