import 'dart:io';

import 'package:anime_tracker/providers/data_refresh_provider.dart';
import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:provider/provider.dart';
import 'package:table_calendar/table_calendar.dart';
import 'package:cached_network_image/cached_network_image.dart';

import 'package:anime_tracker/db/database_helper.dart';
import 'package:anime_tracker/ui/anime_detail/anime_detail_page.dart';

import '../components/adaptive_content_frame.dart';
import '../components/empty_state.dart';
import '../customization/page_display_config.dart';
import '../design_tokens.dart';
import 'add_anime_page.dart';
import 'calendar/calendar_display_config.dart';
import 'calendar/calendar_display_sheet.dart';

/// 日历事件类型枚举
enum EventType { air, startWatch, finishWatch, watch }

enum _CalendarAction { missingDates, refresh }

/// 日历事件类，表示与动漫相关的时间点事件
class AnimeEvent {
  final String animeTitle; // 动漫标题
  final EventType type; // 事件类型
  final Map<String, dynamic> anime; // 完整的动漫数据

  AnimeEvent(this.animeTitle, this.type, this.anime);
}

/// 日历页面，展示动漫相关的时间线事件
class CalendarPage extends StatefulWidget {
  const CalendarPage({super.key});

  @override
  State<CalendarPage> createState() => CalendarPageState();
}

class CalendarPageState extends State<CalendarPage> {
  final PageDisplayController _displayController = PageDisplayController(
    pageId: 'calendar',
    defaults: calendarDefaults(),
    knownModules: calendarModuleKeys,
    fallbackOrder: calendarDefaultOrder,
  );

  // 日历相关状态
  // 使用 UTC 时间以保持与 TableCalendar 的 firstDay/lastDay 一致 (避免时区导致的断言错误)
  DateTime _focusedDay = DateTime.utc(
    DateTime.now().year,
    DateTime.now().month,
    DateTime.now().day,
  );
  DateTime? _selectedDay;

  // 事件存储：Key为日期，Value为该日期的事件列表
  Map<DateTime, List<AnimeEvent>> _events = {};

  // 存储日期信息不完整的动漫
  List<Map<String, dynamic>> _unknownDateAnimes = [];

  // 是否显示"未知日期"列表模式
  bool _showUnknownMode = false;
  bool _isLoading = true;
  String? _loadError;
  int _loadGeneration = 0;

  DataRefreshProvider? _refreshProvider;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _refreshProvider = Provider.of<DataRefreshProvider>(context, listen: false);
  }

  @override
  void initState() {
    super.initState();
    _displayController.load();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refreshProvider?.addListener(_onGlobalRefresh);
    });
    _selectedDay = _focusedDay;
    refreshData();
  }

  void _onGlobalRefresh() {
    if (mounted) refreshData();
  }

  @override
  void dispose() {
    _loadGeneration++;
    _refreshProvider?.removeListener(_onGlobalRefresh);
    _displayController.dispose();
    super.dispose();
  }

  /// 从数据库加载动漫数据并分类为日历事件
  Future<void> refreshData() async {
    final generation = ++_loadGeneration;
    if (mounted) {
      setState(() {
        _isLoading = true;
        _loadError = null;
      });
    }

    try {
      final animes = await DatabaseHelper().queryAllAnimes();
      final tempEvents = <DateTime, List<AnimeEvent>>{};
      final tempUnknown = <Map<String, dynamic>>[];

      for (final anime in animes) {
        final type = anime['subject_type'] ?? 'anime';
        final typeLabel = type == 'book' ? '小说' : '番剧';
        // 确保标题不为空
        final title = anime['title'] as String? ?? '未命名$typeLabel';

        // 1. 处理首播日期 (air_date)
        if (anime['air_date'] != null && anime['air_date'].isNotEmpty) {
          try {
            DateTime date = DateTime.parse(anime['air_date']);
            _addEvent(
              tempEvents,
              date,
              AnimeEvent(title, EventType.air, anime),
            );
          } catch (e) {
            // 日期格式解析失败，跳过此项
          }
        }

        // 2. 处理开始观看日期
        if (anime['watch_start_date'] != null &&
            anime['watch_start_date'].isNotEmpty) {
          try {
            DateTime date = DateTime.parse(anime['watch_start_date']);
            _addEvent(
              tempEvents,
              date,
              AnimeEvent(title, EventType.startWatch, anime),
            );
          } catch (e) {
            // 日期格式解析失败，跳过此项
          }
        }

        // 3. 处理看完日期
        if (anime['watch_finish_date'] != null &&
            anime['watch_finish_date'].isNotEmpty) {
          try {
            DateTime date = DateTime.parse(anime['watch_finish_date']);
            _addEvent(
              tempEvents,
              date,
              AnimeEvent(title, EventType.finishWatch, anime),
            );
          } catch (e) {
            // 日期格式解析失败，跳过此项
          }
        }

        // 筛查日期信息缺失的动漫
        if (anime['air_date'] == null ||
            anime['air_date'] == '' ||
            (anime['status'] == '在看' &&
                (anime['watch_start_date'] == null ||
                    anime['watch_start_date'] == '')) ||
            (anime['status'] == '看完' &&
                (anime['watch_finish_date'] == null ||
                    anime['watch_finish_date'] == ''))) {
          tempUnknown.add(anime);
        }
      }

      // 4. 处理具体的观看记录 (watch_records)
      final watchRecords = await DatabaseHelper().getAllWatchRecords();
      for (final record in watchRecords) {
        if (record['record_date'] != null && record['record_date'].isNotEmpty) {
          try {
            final date = DateTime.parse(record['record_date']);
            final isCompleted = record['status'] == 'completed';
            final type = record['subject_type'] ?? 'anime';
            final typeLabel = type == 'book' ? '小说' : '番剧';
            final title = record['title'] as String? ?? '未命名$typeLabel';

            _addEvent(
              tempEvents,
              date,
              AnimeEvent(
                title,
                isCompleted ? EventType.finishWatch : EventType.watch,
                {
                  ...record,
                  'record_id': record['id'], // 保存观看记录本身的 ID
                  'id': record['anime_id'], // 为了兼容点击跳转，这里保留为 anime_id
                  'event_episode': record['episode'], // 记录该次事件的集数
                  'watch_count': record['watch_count'], // 记录是第次数
                  'subject_type': type, // 确保传递类型
                },
              ),
            );
          } catch (_) {
            // 单条日期异常不应阻断整个日历。
          }
        }
      }

      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _events = tempEvents;
        _unknownDateAnimes = tempUnknown;
        _isLoading = false;
        _loadError = null;
      });
    } catch (error) {
      if (!mounted || generation != _loadGeneration) return;
      setState(() {
        _isLoading = false;
        _loadError = '日历数据加载失败：$error';
      });
    }
  }

  /// 添加事件到事件列表中，确保日期被标准化
  void _addEvent(
    Map<DateTime, List<AnimeEvent>> events,
    DateTime date,
    AnimeEvent event,
  ) {
    DateTime normalizedDate = DateTime.utc(date.year, date.month, date.day);

    if (events[normalizedDate] == null) {
      events[normalizedDate] = [];
    }
    events[normalizedDate]!.add(event);
  }

  /// 获取指定日期的事件列表
  List<AnimeEvent> _getEventsForDay(DateTime day) {
    DateTime normalizedDate = DateTime.utc(day.year, day.month, day.day);
    return _events[normalizedDate] ?? [];
  }

  /// 获取事件类型的对应颜色
  Color _getEventColor(EventType type) {
    switch (type) {
      case EventType.air:
        return const Color(0xFF9C27B0); // 紫色
      case EventType.startWatch:
        return const Color(0xFF2196F3); // 蓝色
      case EventType.finishWatch:
        return const Color(0xFF4CAF50); // 绿色
      case EventType.watch:
        return const Color(0xFFFF9800); // 橙色
    }
  }

  /// 获取事件类型的中文描述
  String _getEventText(EventType type, {String subjectType = 'anime'}) {
    final isBook = subjectType == 'book';
    switch (type) {
      case EventType.air:
        return isBook ? "出版/上架" : "开播";
      case EventType.startWatch:
        return isBook ? "开始读" : "开始看";
      case EventType.finishWatch:
        return isBook ? "读完" : "看完";
      case EventType.watch:
        return isBook ? "阅读" : "观看";
    }
  }

  /// 获取日期的主要事件类型（用于确定日期单元格的背景色）
  EventType? _getPrimaryEventType(DateTime day) {
    final events = _getEventsForDay(day);
    if (events.isEmpty) return null;

    // 优先级：开播 > 看完 > 开始看
    if (events.any((e) => e.type == EventType.air)) return EventType.air;
    if (events.any((e) => e.type == EventType.finishWatch)) {
      return EventType.finishWatch;
    }
    if (events.any((e) => e.type == EventType.watch)) return EventType.watch;
    if (events.any((e) => e.type == EventType.startWatch)) {
      return EventType.startWatch;
    }
    return null;
  }

  /// 快速跳转到指定日期
  Future<void> _jumpToDate() async {
    // 确保传递给 DatePicker 的是本地时间，否则可能因时区差异导致显示的初始日期偏差
    final localInitialDate = DateTime(
      _focusedDay.year,
      _focusedDay.month,
      _focusedDay.day,
    );

    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate: localInitialDate,
      firstDate: DateTime(1980),
      lastDate: DateTime(2050),
      helpText: "跳转到指定日期",
      locale: const Locale('zh', 'CN'), // 设置为中文
    );

    if (picked != null) {
      setState(() {
        // 将选择的本地日期转换为 UTC 日期
        final utcPicked = DateTime.utc(picked.year, picked.month, picked.day);
        _focusedDay = utcPicked;
        _selectedDay = utcPicked;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_showUnknownMode) {
      return _buildUnknownDateList();
    }

    return AnimatedBuilder(
      animation: _displayController,
      builder: (context, _) {
        final config = _displayController.value;
        final preset = selectedCalendarPreset(config);
        return Scaffold(
          appBar: AppBar(
            title: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('追随日历'),
                Text(
                  preset?.label ?? '自定义视图',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
            actions: [
              IconButton(
                icon: const Icon(Icons.dashboard_customize_outlined),
                tooltip: '定制日历显示',
                onPressed: _openDisplaySettings,
              ),
              IconButton(
                icon: const Icon(Icons.calendar_month_outlined),
                tooltip: '跳转到日期',
                onPressed: _jumpToDate,
              ),
              PopupMenuButton<_CalendarAction>(
                tooltip: '更多日历操作',
                onSelected: _handleCalendarAction,
                itemBuilder: (context) => [
                  PopupMenuItem(
                    value: _CalendarAction.missingDates,
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: const Icon(Icons.event_busy_outlined),
                      title: const Text('待补充日期'),
                      trailing: Text('${_unknownDateAnimes.length}'),
                    ),
                  ),
                  const PopupMenuItem(
                    value: _CalendarAction.refresh,
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.refresh_rounded),
                      title: Text('刷新日历'),
                    ),
                  ),
                ],
              ),
              const SizedBox(width: AppSpacing.sm),
            ],
          ),
          body: _buildCalendarView(config),
        );
      },
    );
  }

  Future<void> _openDisplaySettings() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      constraints: MediaQuery.sizeOf(context).width >= AppBreakpoints.medium
          ? const BoxConstraints(maxWidth: 760)
          : null,
      builder: (_) => CalendarDisplaySheet(controller: _displayController),
    );
  }

  void _handleCalendarAction(_CalendarAction action) {
    switch (action) {
      case _CalendarAction.missingDates:
        setState(() => _showUnknownMode = true);
        return;
      case _CalendarAction.refresh:
        _refreshWithFeedback();
        return;
    }
  }

  Future<void> _refreshWithFeedback() async {
    await refreshData();
    if (!mounted) return;
    final error = _loadError;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text(error ?? '日历数据已刷新')));
  }

  void _goToToday() {
    final now = DateTime.now();
    final today = DateTime.utc(now.year, now.month, now.day);
    setState(() {
      _focusedDay = today;
      _selectedDay = today;
    });
  }

  /// 构建日历视图
  Widget _buildCalendarView(PageDisplayConfig config) {
    if (_isLoading && _events.isEmpty) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_loadError != null && _events.isEmpty) {
      return EmptyStateWidget(
        icon: Icons.calendar_month_outlined,
        message: '日历加载失败',
        description: _loadError,
        buttonText: '重新加载',
        onButtonPressed: refreshData,
      );
    }

    final selectedEvents = _getEventsForDay(_selectedDay ?? _focusedDay);
    final selectedDate = _selectedDay ?? _focusedDay;
    final historyEvents = _historyEventsFor(selectedDate);
    final now = DateTime.now();
    final today = DateTime.utc(now.year, now.month, now.day);

    final modules = <CalendarModule, Widget>{
      CalendarModule.month: _buildCalendarCard(config, today),
      CalendarModule.schedule: _buildScheduleCard(selectedEvents, config),
      CalendarModule.history: _buildHistoryCard(historyEvents, config),
    };

    var visibleModules = config.moduleOrder
        .where((key) => !config.hiddenModules.contains(key))
        .map(CalendarModule.fromPersistKey)
        .whereType<CalendarModule>()
        .where(
          (module) =>
              module != CalendarModule.history || historyEvents.isNotEmpty,
        )
        .toList(growable: false);
    if (visibleModules.isEmpty) {
      visibleModules = const [CalendarModule.month];
    }

    return Stack(
      children: [
        RefreshIndicator(
          onRefresh: refreshData,
          child: ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            children: [
              AdaptiveContentFrame(
                maxContentWidth: 1180,
                top: AppSpacing.lg,
                bottom: AppSpacing.xxl,
                child: LayoutBuilder(
                  builder: (context, constraints) {
                    final wide = constraints.maxWidth >= 880;
                    final moduleWidth = wide
                        ? (constraints.maxWidth - AppSpacing.lg) / 2
                        : constraints.maxWidth;
                    return Wrap(
                      spacing: AppSpacing.lg,
                      runSpacing: AppSpacing.lg,
                      children: visibleModules
                          .map(
                            (module) => SizedBox(
                              width: moduleWidth,
                              child: modules[module]!,
                            ),
                          )
                          .toList(),
                    );
                  },
                ),
              ),
            ],
          ),
        ),
        if (_isLoading && _events.isNotEmpty)
          const Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: LinearProgressIndicator(minHeight: 2),
          ),
        if (!_isLoading && _loadError != null)
          Positioned(
            left: AppSpacing.lg,
            right: AppSpacing.lg,
            bottom: AppSpacing.lg,
            child: Material(
              color: Theme.of(context).colorScheme.errorContainer,
              borderRadius: BorderRadius.circular(AppRadius.sm),
              child: Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: AppSpacing.md,
                  vertical: AppSpacing.sm,
                ),
                child: Row(
                  children: [
                    Icon(
                      Icons.sync_problem_rounded,
                      color: Theme.of(context).colorScheme.onErrorContainer,
                    ),
                    const SizedBox(width: AppSpacing.sm),
                    Expanded(
                      child: Text(
                        _loadError!,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          color: Theme.of(context).colorScheme.onErrorContainer,
                        ),
                      ),
                    ),
                    TextButton(onPressed: refreshData, child: const Text('重试')),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }

  Widget _buildCalendarCard(PageDisplayConfig config, DateTime today) {
    final colorScheme = Theme.of(context).colorScheme;
    final rowHeight = switch (config.density) {
      DisplayDensity.compact => 42.0,
      DisplayDensity.comfortable => 48.0,
      DisplayDensity.relaxed => 54.0,
    };

    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      clipBehavior: Clip.antiAlias,
      color: colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.sm,
          AppSpacing.sm,
          AppSpacing.sm,
          AppSpacing.md,
        ),
        child: Column(
          children: [
            TableCalendar<AnimeEvent>(
              firstDay: DateTime.utc(1980, 1, 1),
              lastDay: DateTime.utc(2050, 12, 31),
              focusedDay: _focusedDay,
              rowHeight: rowHeight,
              daysOfWeekHeight: 30,
              selectedDayPredicate: (day) => isSameDay(_selectedDay, day),
              eventLoader: _getEventsForDay,
              startingDayOfWeek: StartingDayOfWeek.sunday,
              calendarStyle: const CalendarStyle(
                outsideDaysVisible: false,
                markersMaxCount: 0,
              ),
              calendarBuilders: CalendarBuilders<AnimeEvent>(
                defaultBuilder: (context, date, focusedDay) => _buildDayCell(
                  date,
                  _getEventsForDay(date),
                  isSelected: false,
                  isToday: isSameDay(date, today),
                  config: config,
                ),
                selectedBuilder: (context, date, focusedDay) => _buildDayCell(
                  date,
                  _getEventsForDay(date),
                  isSelected: true,
                  isToday: false,
                  config: config,
                ),
                todayBuilder: (context, date, focusedDay) => _buildDayCell(
                  date,
                  _getEventsForDay(date),
                  isSelected: false,
                  isToday: true,
                  config: config,
                ),
                dowBuilder: (context, dayOfWeek) {
                  const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
                  return Center(
                    child: Text(
                      weekdays[dayOfWeek.weekday % 7],
                      style: Theme.of(context).textTheme.labelMedium?.copyWith(
                        color: colorScheme.onSurfaceVariant,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  );
                },
              ),
              headerStyle: HeaderStyle(
                titleCentered: true,
                formatButtonVisible: false,
                headerPadding: const EdgeInsets.symmetric(
                  vertical: AppSpacing.sm,
                ),
                titleTextStyle: Theme.of(
                  context,
                ).textTheme.titleLarge!.copyWith(fontWeight: FontWeight.w900),
                leftChevronIcon: Icon(
                  Icons.chevron_left_rounded,
                  color: colorScheme.onSurface,
                ),
                rightChevronIcon: Icon(
                  Icons.chevron_right_rounded,
                  color: colorScheme.onSurface,
                ),
                titleTextFormatter: (date, locale) =>
                    DateFormat('yyyy年 M月', 'zh_CN').format(date),
              ),
              calendarFormat: CalendarFormat.month,
              availableCalendarFormats: const {CalendarFormat.month: '月'},
              onDaySelected: (selectedDay, focusedDay) {
                setState(() {
                  _selectedDay = selectedDay;
                  _focusedDay = focusedDay;
                });
              },
              onPageChanged: (focusedDay) {
                setState(() => _focusedDay = focusedDay);
              },
            ),
            if (calendarShowLegend(config)) ...[
              const SizedBox(height: AppSpacing.sm),
              _buildEventLegend(),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildEventLegend() {
    const entries = <(EventType, String)>[
      (EventType.air, '开播 / 出版'),
      (EventType.startWatch, '开始'),
      (EventType.finishWatch, '完成'),
      (EventType.watch, '观看记录'),
    ];
    return Wrap(
      alignment: WrapAlignment.center,
      spacing: AppSpacing.md,
      runSpacing: AppSpacing.sm,
      children: entries.map((entry) {
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 8,
              height: 8,
              decoration: BoxDecoration(
                color: _getEventColor(entry.$1),
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: AppSpacing.xs),
            Text(
              entry.$2,
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        );
      }).toList(),
    );
  }

  /// 构建日期单元格
  Widget _buildDayCell(
    DateTime date,
    List<AnimeEvent> events, {
    required bool isSelected,
    required bool isToday,
    required PageDisplayConfig config,
  }) {
    final colorScheme = Theme.of(context).colorScheme;
    final markerStyle = calendarMarkerStyle(config);

    final primaryType = _getPrimaryEventType(date);
    final isSameMonth = date.month == _focusedDay.month;
    final eventTypes = events.map((event) => event.type).toSet().take(3);

    Color? backgroundColor;
    if (isSelected) {
      backgroundColor = colorScheme.primary;
    } else if (isToday) {
      backgroundColor = colorScheme.secondaryContainer;
    } else if (primaryType != null && markerStyle == CalendarMarkerStyle.tint) {
      backgroundColor = _getEventColor(primaryType).withValues(alpha: 0.16);
    }

    final foregroundColor = !isSameMonth
        ? colorScheme.onSurface.withValues(alpha: 0.35)
        : isSelected
        ? colorScheme.onPrimary
        : isToday
        ? colorScheme.onSecondaryContainer
        : colorScheme.onSurface;
    final margin = config.density == DisplayDensity.compact ? 3.0 : 2.0;

    return Semantics(
      label: '${date.month}月${date.day}日，${events.length}项事件',
      selected: isSelected,
      button: true,
      child: Container(
        margin: EdgeInsets.all(margin),
        decoration: BoxDecoration(
          color: backgroundColor ?? Colors.transparent,
          borderRadius: BorderRadius.circular(AppRadius.xs),
          border: isToday && !isSelected
              ? Border.all(color: colorScheme.secondary, width: 1.5)
              : null,
        ),
        child: Stack(
          alignment: Alignment.center,
          children: [
            Text(
              '${date.day}',
              style: TextStyle(
                fontSize: config.density == DisplayDensity.compact ? 13 : 14,
                fontWeight: isSelected || isToday
                    ? FontWeight.w900
                    : FontWeight.w600,
                color: foregroundColor,
              ),
            ),
            if (events.isNotEmpty && markerStyle == CalendarMarkerStyle.dots)
              Positioned(
                bottom: 3,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: eventTypes
                      .map(
                        (type) => Container(
                          width: 4,
                          height: 4,
                          margin: const EdgeInsets.symmetric(horizontal: 1),
                          decoration: BoxDecoration(
                            color: isSelected
                                ? colorScheme.onPrimary
                                : _getEventColor(type),
                            shape: BoxShape.circle,
                          ),
                        ),
                      )
                      .toList(),
                ),
              ),
            if (events.isNotEmpty && markerStyle == CalendarMarkerStyle.count)
              Positioned(
                top: 2,
                right: 2,
                child: Container(
                  constraints: const BoxConstraints(
                    minWidth: 14,
                    minHeight: 14,
                  ),
                  padding: const EdgeInsets.symmetric(horizontal: 3),
                  decoration: BoxDecoration(
                    color: isSelected
                        ? colorScheme.onPrimary
                        : colorScheme.primary,
                    borderRadius: BorderRadius.circular(AppRadius.full),
                  ),
                  alignment: Alignment.center,
                  child: Text(
                    events.length > 9 ? '9+' : '${events.length}',
                    style: TextStyle(
                      color: isSelected
                          ? colorScheme.primary
                          : colorScheme.onPrimary,
                      fontSize: 8,
                      fontWeight: FontWeight.w900,
                      height: 1,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildScheduleCard(List<AnimeEvent> events, PageDisplayConfig config) {
    final selectedDate = _selectedDay ?? _focusedDay;
    final dateStr = DateFormat('M月d日 EEEE', 'zh_CN').format(selectedDate);
    final now = DateTime.now();
    final today = DateTime.utc(now.year, now.month, now.day);
    final isToday = isSameDay(selectedDate, today);
    final colorScheme = Theme.of(context).colorScheme;

    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.lg,
              AppSpacing.lg,
              AppSpacing.sm,
              AppSpacing.md,
            ),
            child: Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        dateStr,
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        events.isEmpty ? '当天暂无记录' : '${events.length} 项日程',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                if (!isToday)
                  TextButton.icon(
                    onPressed: _goToToday,
                    icon: const Icon(Icons.today_outlined, size: 18),
                    label: const Text('今天'),
                  ),
              ],
            ),
          ),
          const Divider(height: 1),
          if (events.isEmpty)
            Padding(
              padding: const EdgeInsets.all(AppSpacing.xxl),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    Icons.nights_stay_outlined,
                    color: colorScheme.onSurfaceVariant,
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  Text(
                    '这一天很安静',
                    style: TextStyle(color: colorScheme.onSurfaceVariant),
                  ),
                ],
              ),
            )
          else
            for (var index = 0; index < events.length; index++) ...[
              if (index > 0) const Divider(height: 1, indent: AppSpacing.lg),
              _buildScheduleItem(events[index], config),
            ],
        ],
      ),
    );
  }

  List<MapEntry<DateTime, AnimeEvent>> _historyEventsFor(DateTime date) {
    final result = <MapEntry<DateTime, AnimeEvent>>[];
    _events.forEach((eventDate, eventList) {
      if (eventDate.month == date.month &&
          eventDate.day == date.day &&
          eventDate.year != date.year) {
        for (final event in eventList) {
          result.add(MapEntry(eventDate, event));
        }
      }
    });
    result.sort((a, b) => b.key.year.compareTo(a.key.year));
    return result;
  }

  Widget _buildHistoryCard(
    List<MapEntry<DateTime, AnimeEvent>> entries,
    PageDisplayConfig config,
  ) {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.all(AppSpacing.lg),
            child: Row(
              children: [
                Icon(Icons.history_rounded, color: colorScheme.tertiary),
                const SizedBox(width: AppSpacing.sm),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        '那年今日',
                        style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w900,
                        ),
                      ),
                      Text(
                        '${entries.length} 条跨年份回忆',
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          for (var index = 0; index < entries.length; index++) ...[
            if (index > 0) const Divider(height: 1, indent: AppSpacing.lg),
            _buildHistoryScheduleItem(entries[index], config),
          ],
        ],
      ),
    );
  }

  Widget _buildHistoryScheduleItem(
    MapEntry<DateTime, AnimeEvent> entry,
    PageDisplayConfig config,
  ) {
    final event = entry.value;
    final eventColor = _getEventColor(event.type);
    final subjectType = (event.anime['subject_type'] ?? 'anime').toString();
    final colorScheme = Theme.of(context).colorScheme;
    final verticalPadding = config.density == DisplayDensity.compact
        ? AppSpacing.sm
        : AppSpacing.md;

    return InkWell(
      onTap: () => _openEvent(event),
      child: Padding(
        padding: EdgeInsets.symmetric(
          horizontal: AppSpacing.lg,
          vertical: verticalPadding,
        ),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.symmetric(
                horizontal: AppSpacing.sm,
                vertical: AppSpacing.xs,
              ),
              decoration: BoxDecoration(
                color: colorScheme.tertiaryContainer,
                borderRadius: BorderRadius.circular(AppRadius.xs),
              ),
              child: Text(
                '${entry.key.year}',
                style: TextStyle(
                  color: colorScheme.onTertiaryContainer,
                  fontWeight: FontWeight.w900,
                ),
              ),
            ),
            if (calendarShowCovers(config)) ...[
              const SizedBox(width: AppSpacing.md),
              ClipRRect(
                borderRadius: BorderRadius.circular(AppRadius.xs),
                child: _buildCoverImage(event.anime['cover_url'], 42),
              ),
            ],
            const SizedBox(width: AppSpacing.md),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    event.animeTitle,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    _getEventText(event.type, subjectType: subjectType),
                    style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: eventColor,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right_rounded,
              color: colorScheme.onSurfaceVariant,
            ),
          ],
        ),
      ),
    );
  }

  /// 构建日程项
  Widget _buildScheduleItem(AnimeEvent event, PageDisplayConfig config) {
    final coverUrl = event.anime['cover_url'];
    final eventColor = _getEventColor(event.type);
    final subjectType = (event.anime['subject_type'] ?? 'anime').toString();
    final colorScheme = Theme.of(context).colorScheme;
    final canDelete = _canDeleteEvent(event);
    final verticalPadding = switch (config.density) {
      DisplayDensity.compact => AppSpacing.sm,
      DisplayDensity.comfortable => AppSpacing.md,
      DisplayDensity.relaxed => AppSpacing.lg,
    };
    final coverSize = switch (config.density) {
      DisplayDensity.compact => 42.0,
      DisplayDensity.comfortable => 50.0,
      DisplayDensity.relaxed => 58.0,
    };

    return InkWell(
      onTap: () => _openEvent(event),
      onLongPress: () => _showDeleteRecordDialog(event),
      child: Padding(
        padding: EdgeInsets.symmetric(
          horizontal: AppSpacing.lg,
          vertical: verticalPadding,
        ),
        child: Row(
          children: [
            if (calendarShowCovers(config)) ...[
              ClipRRect(
                borderRadius: BorderRadius.circular(AppRadius.xs),
                child: _buildCoverImage(coverUrl, coverSize),
              ),
              const SizedBox(width: AppSpacing.md),
            ],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    event.animeTitle,
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      fontWeight: FontWeight.w800,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  Wrap(
                    crossAxisAlignment: WrapCrossAlignment.center,
                    spacing: AppSpacing.sm,
                    runSpacing: AppSpacing.xs,
                    children: [
                      Text(
                        _eventDetailText(event),
                        style: Theme.of(context).textTheme.labelMedium
                            ?.copyWith(
                              color: eventColor,
                              fontWeight: FontWeight.w700,
                            ),
                      ),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: AppSpacing.sm,
                          vertical: 3,
                        ),
                        decoration: BoxDecoration(
                          color: colorScheme.surfaceContainerHighest,
                          borderRadius: BorderRadius.circular(AppRadius.full),
                        ),
                        child: Text(
                          subjectType == 'book' ? '小说' : '番剧',
                          style: Theme.of(context).textTheme.labelSmall
                              ?.copyWith(
                                color: colorScheme.onSurfaceVariant,
                                fontWeight: FontWeight.w700,
                              ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            if (canDelete)
              PopupMenuButton<String>(
                tooltip: '记录操作',
                onSelected: (value) {
                  if (value == 'delete') _showDeleteRecordDialog(event);
                },
                itemBuilder: (context) => const [
                  PopupMenuItem(
                    value: 'delete',
                    child: ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.delete_outline_rounded),
                      title: Text('删除这条记录'),
                    ),
                  ),
                ],
              )
            else
              Icon(
                Icons.chevron_right_rounded,
                color: colorScheme.onSurfaceVariant,
              ),
          ],
        ),
      ),
    );
  }

  Future<void> _openEvent(AnimeEvent event) async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => AnimeDetailPage(existingAnime: event.anime),
      ),
    );
    if (mounted) await refreshData();
  }

  bool _canDeleteEvent(AnimeEvent event) {
    return (event.type == EventType.watch ||
            event.type == EventType.finishWatch) &&
        event.anime['record_id'] != null;
  }

  String _eventDetailText(AnimeEvent event) {
    final subjectType = (event.anime['subject_type'] ?? 'anime').toString();
    final isBook = subjectType == 'book';
    final episode = event.anime['event_episode'];
    if (event.type == EventType.watch && episode != null) {
      return '${isBook ? '阅读' : '看完'}第 $episode ${isBook ? '话/页' : '集'}';
    }
    final watchCount = _asInt(event.anime['watch_count']);
    if (event.type == EventType.finishWatch &&
        watchCount != null &&
        watchCount > 1) {
      return '${isBook ? '读完' : '看完'} (${_getWatchCountText(watchCount)})';
    }
    return _getEventText(event.type, subjectType: subjectType);
  }

  /// 显示删除确认对话框
  void _showDeleteRecordDialog(AnimeEvent event) {
    if (event.type != EventType.watch && event.type != EventType.finishWatch) {
      // 仅允许删除手动产生的观看/完成记录，开播等暂不允许在此删除
      return;
    }

    final recordId = event.anime['record_id'];
    if (recordId == null) return;

    showDialog(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text("删除记录"),
        content: Text("确定要删除这条“${_getEventText(event.type)}”记录吗？"),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text("取消"),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () async {
              Navigator.pop(dialogContext);
              await DatabaseHelper().deleteWatchRecord(recordId);
              if (!mounted) return;
              await refreshData();
              if (!mounted) return;
              ScaffoldMessenger.of(
                context,
              ).showSnackBar(const SnackBar(content: Text("记录已删除")));
            },
            child: const Text("删除"),
          ),
        ],
      ),
    );
  }

  /// 获取观看次数的中文描述 (二刷, 三刷等)
  String _getWatchCountText(int count) {
    if (count == 1) return "首刷";
    if (count == 2) return "二刷";
    if (count == 3) return "三刷";
    if (count == 4) return "四刷";
    if (count == 5) return "五刷";
    return "$count刷";
  }

  int? _asInt(dynamic value) {
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value?.toString() ?? '');
  }

  /// 构建封面图片
  Widget _buildCoverImage(String? url, double size) {
    if (url == null || url.isEmpty) {
      return Container(
        width: size,
        height: size,
        color: Colors.grey[200],
        child: const Icon(Icons.movie_creation, color: Colors.grey, size: 24),
      );
    }

    // 网络图片
    if (url.startsWith('http')) {
      return SizedBox(
        width: size,
        height: size,
        child: CachedNetworkImage(
          imageUrl: url,
          // 优化：指定缓存大小 (乘3以确保在高清屏上清晰)
          memCacheWidth: (size * 3).toInt(),
          fit: BoxFit.cover,
          placeholder: (context, url) => Container(
            width: size,
            height: size,
            color: Colors.grey[200],
            child: const Center(
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          ),
          errorWidget: (context, url, error) => Container(
            width: size,
            height: size,
            color: Colors.grey[200],
            child: const Icon(Icons.broken_image, color: Colors.grey),
          ),
        ),
      );
    }

    // 本地图片处理（兼容绝对路径和相对路径）
    return FutureBuilder<String>(
      future: _getLocalImagePath(url),
      builder: (context, snapshot) {
        if (!snapshot.hasData) {
          return Container(
            width: size,
            height: size,
            color: Colors.grey[200],
            child: const Center(
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
          );
        }
        return SizedBox(
          width: size,
          height: size,
          child: Image.file(
            File(snapshot.data!),
            // 优化：指定缓存大小
            cacheWidth: (size * 3).toInt(),
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => Container(
              width: size,
              height: size,
              color: Colors.grey[200],
              child: const Icon(Icons.broken_image, color: Colors.grey),
            ),
          ),
        );
      },
    );
  }

  /// 获取本地图片的完整路径
  Future<String> _getLocalImagePath(String url) async {
    // 如果是绝对路径，直接返回
    if (path.isAbsolute(url)) {
      return url;
    }
    // 如果是相对路径，拼接应用文档目录
    final appDocDir = await getApplicationDocumentsDirectory();
    return path.join(appDocDir.path, url);
  }

  /// 构建日期缺失动漫列表
  Widget _buildUnknownDateList() {
    return AnimatedBuilder(
      animation: _displayController,
      builder: (context, _) {
        final config = _displayController.value;
        final colorScheme = Theme.of(context).colorScheme;
        return Scaffold(
          appBar: AppBar(
            leading: BackButton(
              onPressed: () => setState(() => _showUnknownMode = false),
            ),
            title: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('待补充日期'),
                Text(
                  '${_unknownDateAnimes.length} 部作品需要整理',
                  style: Theme.of(context).textTheme.labelSmall?.copyWith(
                    color: colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
            actions: [
              IconButton(
                tooltip: '刷新',
                onPressed: _refreshWithFeedback,
                icon: const Icon(Icons.refresh_rounded),
              ),
              const SizedBox(width: AppSpacing.sm),
            ],
          ),
          body: Stack(
            children: [
              if (_unknownDateAnimes.isEmpty && !_isLoading)
                EmptyStateWidget(
                  icon: Icons.event_available_rounded,
                  message: '关键日期已经补全',
                  description: '所有作品都具备当前状态需要的日期信息。',
                  buttonText: '返回日历',
                  onButtonPressed: () =>
                      setState(() => _showUnknownMode = false),
                )
              else
                LayoutBuilder(
                  builder: (context, constraints) {
                    final horizontalPadding = AppBreakpoints.centeredPadding(
                      constraints.maxWidth,
                      maxContentWidth: 860,
                      minimum: AppBreakpoints.pagePadding(constraints.maxWidth),
                    );
                    return RefreshIndicator(
                      onRefresh: refreshData,
                      child: ListView.separated(
                        physics: const AlwaysScrollableScrollPhysics(),
                        padding: EdgeInsets.fromLTRB(
                          horizontalPadding,
                          AppSpacing.lg,
                          horizontalPadding,
                          AppSpacing.xxl,
                        ),
                        itemCount: _unknownDateAnimes.length,
                        separatorBuilder: (_, _) =>
                            const SizedBox(height: AppSpacing.sm),
                        itemBuilder: (context, index) {
                          final anime = _unknownDateAnimes[index];
                          final missing = _missingDateLabels(anime);
                          final title =
                              (anime['title'] ?? anime['name_cn'] ?? '未命名作品')
                                  .toString();
                          return Card(
                            margin: EdgeInsets.zero,
                            elevation: 0,
                            color: colorScheme.surfaceContainerLow,
                            child: InkWell(
                              borderRadius: BorderRadius.circular(AppRadius.sm),
                              onTap: () => _editMissingDate(anime),
                              child: Padding(
                                padding: const EdgeInsets.all(AppSpacing.md),
                                child: Row(
                                  children: [
                                    if (calendarShowCovers(config)) ...[
                                      ClipRRect(
                                        borderRadius: BorderRadius.circular(
                                          AppRadius.xs,
                                        ),
                                        child: _buildCoverImage(
                                          anime['cover_url'],
                                          52,
                                        ),
                                      ),
                                      const SizedBox(width: AppSpacing.md),
                                    ],
                                    Expanded(
                                      child: Column(
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          Text(
                                            title,
                                            maxLines: 1,
                                            overflow: TextOverflow.ellipsis,
                                            style: Theme.of(context)
                                                .textTheme
                                                .titleMedium
                                                ?.copyWith(
                                                  fontWeight: FontWeight.w800,
                                                ),
                                          ),
                                          const SizedBox(height: AppSpacing.sm),
                                          Wrap(
                                            spacing: AppSpacing.xs,
                                            runSpacing: AppSpacing.xs,
                                            children: missing
                                                .map(
                                                  (label) => Container(
                                                    padding:
                                                        const EdgeInsets.symmetric(
                                                          horizontal:
                                                              AppSpacing.sm,
                                                          vertical: 3,
                                                        ),
                                                    decoration: BoxDecoration(
                                                      color: colorScheme
                                                          .errorContainer,
                                                      borderRadius:
                                                          BorderRadius.circular(
                                                            AppRadius.full,
                                                          ),
                                                    ),
                                                    child: Text(
                                                      label,
                                                      style: Theme.of(context)
                                                          .textTheme
                                                          .labelSmall
                                                          ?.copyWith(
                                                            color: colorScheme
                                                                .onErrorContainer,
                                                            fontWeight:
                                                                FontWeight.w700,
                                                          ),
                                                    ),
                                                  ),
                                                )
                                                .toList(),
                                          ),
                                        ],
                                      ),
                                    ),
                                    IconButton(
                                      tooltip: '编辑并补充日期',
                                      onPressed: () => _editMissingDate(anime),
                                      icon: const Icon(Icons.edit_outlined),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                          );
                        },
                      ),
                    );
                  },
                ),
              if (_isLoading)
                const Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  child: LinearProgressIndicator(minHeight: 2),
                ),
            ],
          ),
        );
      },
    );
  }

  List<String> _missingDateLabels(Map<String, dynamic> anime) {
    final missing = <String>[];
    if ((anime['air_date'] ?? '').toString().trim().isEmpty) {
      missing.add('缺少放映日期');
    }
    if (anime['status'] == '在看' &&
        (anime['watch_start_date'] ?? '').toString().trim().isEmpty) {
      missing.add('缺少开始时间');
    }
    if (anime['status'] == '看完' &&
        (anime['watch_finish_date'] ?? '').toString().trim().isEmpty) {
      missing.add('缺少完成时间');
    }
    return missing;
  }

  Future<void> _editMissingDate(Map<String, dynamic> anime) async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => AddAnimePage(existingAnime: anime)),
    );
    if (mounted) await refreshData();
  }
}
