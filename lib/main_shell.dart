import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';
import 'anime_list_page.dart';
import 'discovery_page.dart';
import 'calendar_page.dart';
import 'my_page.dart';
import 'settings_manager.dart';
import 'api/update_service.dart';
import 'ui/components/keep_alive_wrapper.dart';

/// 应用主壳层（替代旧的 MainScreen）
///
/// 设计 4 个底部 Tab：**追番 / 发现 / 日历 / 我的**。
/// 「我的」聚合了统计 / 资源库 / 设置 / 主题 / 关于。
///
/// 用户可以在「我的 → 展示定制」隐藏 发现 / 日历 这两个可选 Tab，
/// 追番 和 我的 始终存在。
class MainShell extends StatefulWidget {
  const MainShell({super.key});

  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _currentIndex = 0;
  final PageController _pageController = PageController();

  @override
  void initState() {
    super.initState();
    // 启动后自动检查更新（保留原 MainScreen 行为）
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) UpdateService.checkUpdate(context, isAutoCheck: true);
    });
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  void _handleGlobalRefresh() {
    context.read<DataRefreshProvider>().refreshAll();
  }

  void _goToTab(int index) {
    HapticFeedback.selectionClick();
    setState(() => _currentIndex = index);
    _pageController.animateToPage(
      index,
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutCubic,
    );
  }

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: SettingsManager().showDiscoveryNotifier,
      builder: (context, showDiscovery, _) {
        return ValueListenableBuilder<bool>(
          valueListenable: SettingsManager().showCalendarNotifier,
          builder: (context, showCalendar, _) {
            // 组装可见 tab 列表
            final pages = <Widget>[
              KeepAliveWrapper(
                child: AnimeListPage(onSettingsChanged: () {}),
              ),
            ];
            final destinations = <NavigationDestination>[
              const NavigationDestination(
                icon: Icon(Icons.movie_filter_outlined),
                selectedIcon: Icon(Icons.movie_filter),
                label: '追番',
              ),
            ];

            if (showDiscovery) {
              pages.add(
                KeepAliveWrapper(
                  child: DiscoveryPage(onAnimeAdded: _handleGlobalRefresh),
                ),
              );
              destinations.add(
                const NavigationDestination(
                  icon: Icon(Icons.explore_outlined),
                  selectedIcon: Icon(Icons.explore),
                  label: '发现',
                ),
              );
            }

            if (showCalendar) {
              pages.add(
                const KeepAliveWrapper(child: CalendarPage()),
              );
              destinations.add(
                const NavigationDestination(
                  icon: Icon(Icons.calendar_month_outlined),
                  selectedIcon: Icon(Icons.calendar_month),
                  label: '日历',
                ),
              );
            }

            pages.add(
              KeepAliveWrapper(
                child: MyPage(
                  onDatabaseRefresh: _handleGlobalRefresh,
                  onAnimeAdded: _handleGlobalRefresh,
                ),
              ),
            );
            destinations.add(
              const NavigationDestination(
                icon: Icon(Icons.person_outline_rounded),
                selectedIcon: Icon(Icons.person_rounded),
                label: '我的',
              ),
            );

            // tabs 数量变化时夹紧 index
            if (_currentIndex >= pages.length) {
              _currentIndex = pages.length - 1;
            }

            return Scaffold(
              body: PageView(
                controller: _pageController,
                physics: const BouncingScrollPhysics(),
                children: pages,
                onPageChanged: (i) => setState(() => _currentIndex = i),
              ),
              bottomNavigationBar: NavigationBar(
                selectedIndex: _currentIndex,
                onDestinationSelected: _goToTab,
                destinations: destinations,
              ),
            );
          },
        );
      },
    );
  }
}
