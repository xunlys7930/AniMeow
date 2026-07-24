import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'ui/pages/anime_list/anime_list_page.dart';
import 'ui/pages/discovery_page.dart';
import 'ui/pages/calendar_page.dart';
import 'ui/pages/my_page.dart';
import 'settings_manager.dart';
import 'api/update_service.dart';
import 'ui/components/keep_alive_wrapper.dart';
import 'providers/data_refresh_provider.dart';
import 'ui/components/app_brand_icon.dart';
import 'ui/design_tokens.dart';

/// 应用主壳层（替代旧的 MainScreen）
/// 设计 4 个底部 Tab：**追番 / 发现 / 日历 / 我的**。
/// 「我的」聚合了统计 / 资料库 / 设置 / 主题 / 关于。
/// 用户可以在「我的 → 展示定制」隐藏 发现 / 日历 这两个可选 Tab，
/// 追番 和 我的 始终存在。
class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  _ShellDestination _currentDestination = _ShellDestination.tracker;
  final GlobalKey<_PageStackState> _pageStackKey = GlobalKey<_PageStackState>();

  @override
  void initState() {
    super.initState();
    // 启动后自动检查更新（保留原 MainScreen 行为）
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) UpdateService.checkUpdate(context, isAutoCheck: true);
    });
  }

  void _handleRepositoryRefresh() {
    context.read<DataRefreshProvider>().refreshAll();
  }

  void _goToDestination(_ShellDestination destination) {
    if (_currentDestination == destination) return;
    HapticFeedback.selectionClick();
    setState(() => _currentDestination = destination);
  }

  @override
  Widget build(BuildContext context) {
    final settings = SettingsManager();
    return ListenableBuilder(
      listenable: Listenable.merge([
        settings.showDiscoveryNotifier,
        settings.showCalendarNotifier,
      ]),
      builder: (context, _) {
        final tabs = _buildVisibleTabs(
          showDiscovery: settings.showDiscoveryNotifier.value,
          showCalendar: settings.showCalendarNotifier.value,
        );
        final visibleDestinations = tabs.map((tab) => tab.id).toSet();
        final effectiveDestination =
            visibleDestinations.contains(_currentDestination)
            ? _currentDestination
            : _ShellDestination.tracker;
        final selectedIndex = tabs.indexWhere(
          (tab) => tab.id == effectiveDestination,
        );

        if (effectiveDestination != _currentDestination) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            if (!mounted || visibleDestinations.contains(_currentDestination)) {
              return;
            }
            setState(() => _currentDestination = effectiveDestination);
          });
        }

        return LayoutBuilder(
          builder: (context, constraints) {
            final useNavigationRail = AppBreakpoints.isMedium(
              constraints.maxWidth,
            );
            final extendNavigationRail = AppBreakpoints.isExpanded(
              constraints.maxWidth,
            );
            final pageStack = _PageStack(
              key: _pageStackKey,
              tabs: tabs,
              selectedIndex: selectedIndex,
            );

            if (useNavigationRail) {
              return Scaffold(
                backgroundColor: Theme.of(
                  context,
                ).colorScheme.surfaceContainerLowest,
                body: SafeArea(
                  child: Row(
                    children: [
                      _DesktopNavigation(
                        tabs: tabs,
                        selectedIndex: selectedIndex,
                        extended: extendNavigationRail,
                        onSelected: (index) => _goToDestination(tabs[index].id),
                      ),
                      Expanded(
                        child: Padding(
                          padding: const EdgeInsets.fromLTRB(0, 12, 12, 12),
                          child: ClipRRect(
                            borderRadius: BorderRadius.circular(AppRadius.md),
                            child: pageStack,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            }

            return Scaffold(
              backgroundColor: Theme.of(
                context,
              ).colorScheme.surfaceContainerLowest,
              body: pageStack,
              bottomNavigationBar: _MobileNavigation(
                tabs: tabs,
                selectedIndex: selectedIndex,
                onSelected: (index) => _goToDestination(tabs[index].id),
              ),
            );
          },
        );
      },
    );
  }

  List<_ShellTab> _buildVisibleTabs({
    required bool showDiscovery,
    required bool showCalendar,
  }) {
    return [
      _ShellTab(
        id: _ShellDestination.tracker,
        label: '追番',
        icon: Icons.movie_filter_outlined,
        selectedIcon: Icons.movie_filter_rounded,
        child: KeepAliveWrapper(
          child: AnimeListPage(onSettingsChanged: _handleRepositoryRefresh),
        ),
      ),
      if (showDiscovery)
        _ShellTab(
          id: _ShellDestination.discovery,
          label: '发现',
          icon: Icons.explore_outlined,
          selectedIcon: Icons.explore_rounded,
          child: KeepAliveWrapper(
            child: DiscoveryPage(onAnimeAdded: _handleRepositoryRefresh),
          ),
        ),
      if (showCalendar)
        const _ShellTab(
          id: _ShellDestination.calendar,
          label: '日历',
          icon: Icons.calendar_month_outlined,
          selectedIcon: Icons.calendar_month_rounded,
          child: KeepAliveWrapper(child: CalendarPage()),
        ),
      _ShellTab(
        id: _ShellDestination.profile,
        label: '我的',
        icon: Icons.person_outline_rounded,
        selectedIcon: Icons.person_rounded,
        child: KeepAliveWrapper(
          child: MyPage(
            onDatabaseRefresh: _handleRepositoryRefresh,
            onAnimeAdded: _handleRepositoryRefresh,
          ),
        ),
      ),
    ];
  }
}

enum _ShellDestination { tracker, discovery, calendar, profile }

class _ShellTab {
  final _ShellDestination id;
  final String label;
  final IconData icon;
  final IconData selectedIcon;
  final Widget child;

  const _ShellTab({
    required this.id,
    required this.label,
    required this.icon,
    required this.selectedIcon,
    required this.child,
  });
}

class _PageStack extends StatefulWidget {
  final List<_ShellTab> tabs;
  final int selectedIndex;

  const _PageStack({
    super.key,
    required this.tabs,
    required this.selectedIndex,
  });

  @override
  State<_PageStack> createState() => _PageStackState();
}

class _PageStackState extends State<_PageStack> {
  final Set<_ShellDestination> _builtDestinations = {};

  @override
  void initState() {
    super.initState();
    _rememberSelectedDestination();
  }

  @override
  void didUpdateWidget(covariant _PageStack oldWidget) {
    super.didUpdateWidget(oldWidget);
    _rememberSelectedDestination();
    final visible = widget.tabs.map((tab) => tab.id).toSet();
    _builtDestinations.removeWhere((id) => !visible.contains(id));
  }

  void _rememberSelectedDestination() {
    if (widget.tabs.isEmpty) return;
    _builtDestinations.add(widget.tabs[widget.selectedIndex].id);
  }

  @override
  Widget build(BuildContext context) {
    return IndexedStack(
      index: widget.selectedIndex,
      children: widget.tabs.indexed
          .map(
            (entry) => KeyedSubtree(
              key: ValueKey(entry.$2.id),
              child: _builtDestinations.contains(entry.$2.id)
                  ? TickerMode(
                      enabled: entry.$1 == widget.selectedIndex,
                      child: entry.$2.child,
                    )
                  : const SizedBox.expand(),
            ),
          )
          .toList(),
    );
  }
}

class _MobileNavigation extends StatelessWidget {
  final List<_ShellTab> tabs;
  final int selectedIndex;
  final ValueChanged<int> onSelected;

  const _MobileNavigation({
    required this.tabs,
    required this.selectedIndex,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return SafeArea(
      top: false,
      minimum: const EdgeInsets.fromLTRB(12, 0, 12, 8),
      child: Material(
        color: colorScheme.surfaceContainerHigh,
        elevation: 0,
        shadowColor: colorScheme.shadow.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(AppRadius.md),
        clipBehavior: Clip.antiAlias,
        child: NavigationBar(
          height: AppSize.compactNavigationHeight,
          selectedIndex: selectedIndex,
          onDestinationSelected: onSelected,
          destinations: tabs
              .map(
                (tab) => NavigationDestination(
                  icon: Icon(tab.icon),
                  selectedIcon: Icon(tab.selectedIcon),
                  label: tab.label,
                ),
              )
              .toList(),
        ),
      ),
    );
  }
}

class _DesktopNavigation extends StatelessWidget {
  final List<_ShellTab> tabs;
  final int selectedIndex;
  final bool extended;
  final ValueChanged<int> onSelected;

  const _DesktopNavigation({
    required this.tabs,
    required this.selectedIndex,
    required this.extended,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    return Container(
      decoration: BoxDecoration(
        color: colorScheme.surfaceContainerLowest,
        border: Border(
          right: BorderSide(
            color: colorScheme.outlineVariant.withValues(alpha: 0.35),
          ),
        ),
      ),
      child: NavigationRail(
        extended: extended,
        minWidth: AppSize.navigationRailWidth,
        minExtendedWidth: AppSize.navigationRailExtendedWidth,
        selectedIndex: selectedIndex,
        onDestinationSelected: onSelected,
        labelType: extended
            ? NavigationRailLabelType.none
            : NavigationRailLabelType.all,
        groupAlignment: -0.75,
        leading: Padding(
          padding: const EdgeInsets.only(top: AppSpacing.md),
          child: _BrandMark(extended: extended),
        ),
        destinations: tabs
            .map(
              (tab) => NavigationRailDestination(
                icon: Icon(tab.icon),
                selectedIcon: Icon(tab.selectedIcon),
                label: Text(tab.label),
              ),
            )
            .toList(),
      ),
    );
  }
}

class _BrandMark extends StatelessWidget {
  final bool extended;

  const _BrandMark({required this.extended});

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final mark = Container(
      width: 44,
      height: 44,
      decoration: BoxDecoration(
        color: colorScheme.primaryContainer,
        borderRadius: BorderRadius.circular(AppRadius.xs),
        boxShadow: AppElevation.card(context),
      ),
      clipBehavior: Clip.antiAlias,
      child: const AppBrandIcon(cacheSize: 128),
    );

    if (!extended) {
      return Tooltip(message: '追番喵 AniMeow', child: mark);
    }

    return SizedBox(
      width: AppSize.navigationRailExtendedWidth - AppSpacing.xl,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          mark,
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '追番喵',
                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                    fontWeight: FontWeight.w900,
                  ),
                ),
                Text(
                  'AniMeow',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                    letterSpacing: 0.6,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
