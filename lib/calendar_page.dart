import 'package:provider/provider.dart';
import 'providers/data_refresh_provider.dart';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:table_calendar/table_calendar.dart';
import 'package:intl/intl.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import 'package:cached_network_image/cached_network_image.dart';
import 'db/database_helper.dart';
import 'add_anime_page.dart';
import 'ui/anime_detail/anime_detail_page.dart';

/// 日历事件类型枚举
enum EventType { air, startWatch, finishWatch, watch }

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

  DataRefreshProvider? _refreshProvider;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _refreshProvider = Provider.of<DataRefreshProvider>(context, listen: false);
  }

  @override
  void initState() {
    super.initState();
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
    _refreshProvider?.removeListener(_onGlobalRefresh);
    super.dispose();
  }

  /// 从数据库加载动漫数据并分类为日历事件
  void refreshData() async {
    final animes = await DatabaseHelper().queryAllAnimes();
    Map<DateTime, List<AnimeEvent>> tempEvents = {};
    List<Map<String, dynamic>> tempUnknown = [];

    for (var anime in animes) {
      final type = anime['subject_type'] ?? 'anime';
      final typeLabel = type == 'book' ? '小说' : '番剧';
      // 确保标题不为空
      final title = anime['title'] as String? ?? '未命名$typeLabel';

      // 1. 处理首播日期 (air_date)
      if (anime['air_date'] != null && anime['air_date'].isNotEmpty) {
        try {
          DateTime date = DateTime.parse(anime['air_date']);
          _addEvent(tempEvents, date, AnimeEvent(title, EventType.air, anime));
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
    for (var record in watchRecords) {
      if (record['record_date'] != null && record['record_date'].isNotEmpty) {
        try {
          DateTime date = DateTime.parse(record['record_date']);
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
        } catch (e) {
          // 解析失败
        }
      }
    }

    setState(() {
      _events = tempEvents;
      _unknownDateAnimes = tempUnknown;
    });
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

    return Scaffold(
      appBar: AppBar(
        title: const Text('追随日历'),
        actions: [
          IconButton(
            icon: const Icon(Icons.calendar_month),
            tooltip: "跳转到日期",
            onPressed: _jumpToDate,
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: "刷新数据",
            onPressed: () {
              refreshData();
              ScaffoldMessenger.of(
                context,
              ).showSnackBar(const SnackBar(content: Text('日历数据已刷新')));
            },
          ),
          IconButton(
            icon: const Icon(Icons.event_busy), // 或者是 Icons.help_outline
            tooltip: "查看日期缺失的作品",
            onPressed: () {
              setState(() {
                _showUnknownMode = true;
              });
            },
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: _buildCalendarView(),
    );
  }

  /// 构建日历视图
  Widget _buildCalendarView() {
    final selectedEvents = _getEventsForDay(_selectedDay ?? _focusedDay);
    // 获取当前日期的 UTC 标准化形式 (用于与日历格子的 UTC 日期比较)
    final now = DateTime.now();
    final today = DateTime.utc(now.year, now.month, now.day);

    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [
            Color(0xFFE3F2FD), // 浅蓝色
            Color(0xFFE8F5E9), // 浅绿色
          ],
        ),
      ),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.only(bottom: 20), // 底部留白防止贴底
          children: [
            // 日历组件
            Container(
              margin: const EdgeInsets.all(16),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(16),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.1),
                    blurRadius: 10,
                    offset: const Offset(0, 4),
                  ),
                ],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(16),
                child: TableCalendar<AnimeEvent>(
                  firstDay: DateTime.utc(1980, 1, 1),
                  lastDay: DateTime.utc(2050, 12, 31),
                  focusedDay: _focusedDay,
                  shouldFillViewport: false, // 改为 false，允许页面滚动
                  selectedDayPredicate: (day) => isSameDay(_selectedDay, day),
                  eventLoader: _getEventsForDay,
                  startingDayOfWeek: StartingDayOfWeek.sunday,
                  calendarStyle: CalendarStyle(
                    outsideDaysVisible: false,
                    defaultTextStyle: const TextStyle(
                      fontSize: 14,
                      color: Colors.black87,
                    ),
                    weekendTextStyle: const TextStyle(
                      fontSize: 14,
                      color: Colors.black87,
                    ),
                    selectedTextStyle: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                    todayTextStyle: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                    todayDecoration: BoxDecoration(
                      color: Colors.orange.withValues(alpha: 0.7),
                      shape: BoxShape.circle,
                    ),
                    selectedDecoration: BoxDecoration(
                      color: Colors.blue.withValues(alpha: 0.7),
                      shape: BoxShape.circle,
                    ),
                    markerDecoration: const BoxDecoration(
                      color: Colors.transparent,
                      shape: BoxShape.circle,
                    ),
                    // 自定义日期单元格装饰
                    defaultDecoration: BoxDecoration(shape: BoxShape.circle),
                    weekendDecoration: BoxDecoration(shape: BoxShape.circle),
                  ),
                  // 自定义日期单元格
                  calendarBuilders: CalendarBuilders<AnimeEvent>(
                    defaultBuilder: (context, date, focusedDay) {
                      final dayEvents = _getEventsForDay(date);
                      return _buildDayCell(
                        date,
                        dayEvents,
                        false,
                        isSameDay(date, today),
                      );
                    },
                    selectedBuilder: (context, date, focusedDay) {
                      final dayEvents = _getEventsForDay(date);
                      return _buildDayCell(date, dayEvents, true, false);
                    },
                    todayBuilder: (context, date, focusedDay) {
                      final dayEvents = _getEventsForDay(date);
                      return _buildDayCell(date, dayEvents, false, true);
                    },
                    dowBuilder: (context, dayOfWeek) {
                      // 自定义周几标题为中文
                      final weekdays = ['日', '一', '二', '三', '四', '五', '六'];
                      return Center(
                        child: Text(
                          weekdays[dayOfWeek.weekday % 7],
                          style: const TextStyle(
                            fontSize: 12,
                            fontWeight: FontWeight.w600,
                            color: Colors.black54,
                          ),
                        ),
                      );
                    },
                  ),
                  // 自定义周几标题
                  daysOfWeekStyle: const DaysOfWeekStyle(
                    weekdayStyle: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: Colors.black54,
                    ),
                    weekendStyle: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w600,
                      color: Colors.black54,
                    ),
                  ),
                  // 自定义日历头部
                  headerStyle: HeaderStyle(
                    titleCentered: true,
                    formatButtonVisible: false,
                    titleTextStyle: const TextStyle(
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                      color: Colors.black87,
                    ),
                    leftChevronIcon: const Icon(
                      Icons.chevron_left,
                      color: Colors.black87,
                    ),
                    rightChevronIcon: const Icon(
                      Icons.chevron_right,
                      color: Colors.black87,
                    ),
                    titleTextFormatter: (date, locale) {
                      // 自定义月份标题格式为中文
                      return DateFormat('yyyy年MM月', 'zh_CN').format(date);
                    },
                  ),
                  // 使用自定义的日历格式
                  calendarFormat: CalendarFormat.month,
                  availableCalendarFormats: const {CalendarFormat.month: '月'},
                  onDaySelected: (selectedDay, focusedDay) {
                    setState(() {
                      _selectedDay = selectedDay;
                      _focusedDay = focusedDay;
                    });
                  },
                  onPageChanged: (focusedDay) {
                    setState(() {
                      _focusedDay = focusedDay;
                    });
                  },
                ),
              ),
            ),

            // 今日日程卡片
            _buildTodayScheduleCard(selectedEvents),
          ],
        ),
      ),
    );
  }

  /// 构建日期单元格
  Widget _buildDayCell(
    DateTime date,
    List<AnimeEvent> events,
    bool isSelected,
    bool isToday,
  ) {
    final primaryType = _getPrimaryEventType(date);
    final dayNumber = date.day;
    final isSameMonth = date.month == _focusedDay.month;

    Color? backgroundColor;
    if (isSelected) {
      backgroundColor = Colors.blue.withValues(alpha: 0.7);
    } else if (isToday) {
      backgroundColor = Colors.orange.withValues(alpha: 0.7);
    } else if (primaryType != null) {
      final color = _getEventColor(primaryType);
      backgroundColor = color.withValues(alpha: 0.3);
    }

    return Container(
      margin: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        color: backgroundColor ?? Colors.transparent,
        shape: BoxShape.circle,
        border: isSelected ? Border.all(color: Colors.blue, width: 2) : null,
      ),
      child: Stack(
        alignment: Alignment.center,
        children: [
          Center(
            child: Text(
              '$dayNumber',
              style: TextStyle(
                fontSize: 14,
                fontWeight: isSelected || isToday
                    ? FontWeight.bold
                    : FontWeight.normal,
                color: isSameMonth
                    ? (isSelected || isToday ? Colors.white : Colors.black87)
                    : Colors.grey.withValues(alpha: 0.5),
              ),
            ),
          ),
          // 底部事件标记点
          if (events.isNotEmpty && !isSelected && !isToday)
            Positioned(
              bottom: 4,
              child: Container(
                width: 4,
                height: 4,
                decoration: BoxDecoration(
                  color: _getEventColor(events.first.type),
                  shape: BoxShape.circle,
                ),
              ),
            ),
        ],
      ),
    );
  }

  /// 构建今日日程卡片
  Widget _buildTodayScheduleCard(List<AnimeEvent> events) {
    final selectedDate = _selectedDay ?? _focusedDay;
    final dateStr = DateFormat('yyyy年MM月dd日', 'zh_CN').format(selectedDate);

    return Container(
      margin: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(16),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.1),
            blurRadius: 10,
            offset: const Offset(0, -4),
          ),
        ],
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // 卡片标题
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  '$dateStr 的日程',
                  style: const TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: Colors.black87,
                  ),
                ),
              ],
            ),
          ),

          // 事件列表
          if (events.isEmpty)
            Padding(
              padding: const EdgeInsets.all(32),
              child: Center(
                child: Text(
                  "当天无记录",
                  style: TextStyle(fontSize: 14, color: Colors.grey[400]),
                ),
              ),
            )
          else
            ...events.map((event) => _buildScheduleItem(event)),

          // --- 那年今日 (On This Day) ---
          Builder(
            builder: (context) {
              // 筛选其他年份同一天的事件
              List<MapEntry<DateTime, AnimeEvent>> historyEvents = [];
              _events.forEach((date, eventList) {
                if (date.month == selectedDate.month &&
                    date.day == selectedDate.day &&
                    date.year != selectedDate.year) {
                  for (var event in eventList) {
                    historyEvents.add(MapEntry(date, event));
                  }
                }
              });

              // 按年份倒序排列 (最近的年份在前)
              historyEvents.sort((a, b) => b.key.year.compareTo(a.key.year));

              if (historyEvents.isEmpty) return const SizedBox.shrink();

              return Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 16),
                    child: Divider(height: 32),
                  ),
                  Padding(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 8,
                    ),
                    child: Row(
                      children: [
                        Icon(
                          Icons.history,
                          size: 20,
                          color: Colors.orange[800],
                        ),
                        const SizedBox(width: 8),
                        Text(
                          "那年今日",
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                            color: Colors.orange[900],
                          ),
                        ),
                        const SizedBox(width: 8),
                        Text(
                          "历史上的今天发生的事",
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.grey[600],
                          ),
                        ),
                      ],
                    ),
                  ),
                  ...historyEvents.map((entry) {
                    final date = entry.key;
                    final event = entry.value;
                    return _buildHistoryScheduleItem(event, date.year);
                  }),
                  const SizedBox(height: 16),
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  /// 构建历史日程项 (那年今日)
  Widget _buildHistoryScheduleItem(AnimeEvent event, int year) {
    final coverUrl = event.anime['cover_url'];
    final eventColor = _getEventColor(event.type);
    final subjectType = event.anime['subject_type'] ?? 'anime';
    final eventText = _getEventText(event.type, subjectType: subjectType);

    return InkWell(
      onTap: () async {
        await Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) =>
                AnimeDetailPage(existingAnime: event.anime),
          ),
        );
        refreshData();
      },
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            // 年份标签
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.orange.withValues(alpha: 0.1),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(color: Colors.orange.withValues(alpha: 0.3)),
              ),
              child: Text(
                "$year",
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.bold,
                  color: Colors.orange[800],
                ),
              ),
            ),
            const SizedBox(width: 12),
            // 动漫封面 (小一点)
            ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: _buildCoverImage(coverUrl, 40),
            ),
            const SizedBox(width: 12),
            // 动漫信息
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    event.animeTitle,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.bold,
                      color: Colors.black87,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 2),
                  Row(
                    children: [
                      Text(
                        eventText,
                        style: TextStyle(
                          fontSize: 11,
                          color: eventColor,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// 构建日程项
  Widget _buildScheduleItem(AnimeEvent event) {
    final coverUrl = event.anime['cover_url'];
    final eventColor = _getEventColor(event.type);
    final subjectType = event.anime['subject_type'] ?? 'anime';
    final eventText = _getEventText(event.type, subjectType: subjectType);

    return InkWell(
      onTap: () async {
        await Navigator.push(
          context,
          MaterialPageRoute(
            builder: (context) =>
                AnimeDetailPage(existingAnime: event.anime),
          ),
        );
        refreshData();
      },
      onLongPress: () => _showDeleteRecordDialog(event),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            // 动漫封面
            ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: _buildCoverImage(coverUrl, 50),
            ),
            const SizedBox(width: 12),
            // 动漫信息
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    event.animeTitle,
                    style: const TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.bold,
                      color: Colors.black87,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      Text(
                        event.type == EventType.watch &&
                                event.anime['event_episode'] != null
                            ? "${subjectType == 'book' ? '阅读' : '看完'}第 ${event.anime['event_episode']} ${subjectType == 'book' ? '话/页' : '集'}"
                            : (event.type == EventType.finishWatch &&
                                  event.anime['watch_count'] != null &&
                                  event.anime['watch_count'] > 1)
                            ? "${subjectType == 'book' ? '读完' : '看完'} (${_getWatchCountText(event.anime['watch_count'])})"
                            : eventText,
                        style: TextStyle(
                          fontSize: 12,
                          color: eventColor,
                          fontWeight: FontWeight.w500,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 6,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.grey[200],
                          borderRadius: BorderRadius.circular(4),
                        ),
                        child: Text(
                          subjectType == 'book' ? '小说' : '番剧',
                          style: TextStyle(
                            fontSize: 10,
                            color: Colors.grey[700],
                          ),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
            // 右侧图标
            Icon(Icons.chevron_right, color: Colors.grey[400], size: 20),
          ],
        ),
      ),
    );
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
      builder: (context) => AlertDialog(
        title: const Text("删除记录"),
        content: Text("确定要删除这条“${_getEventText(event.type)}”记录吗？"),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text("取消"),
          ),
          TextButton(
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            onPressed: () async {
              Navigator.pop(context);
              await DatabaseHelper().deleteWatchRecord(recordId);
              refreshData(); // 重新加载数据
              if (mounted) {
                ScaffoldMessenger.of(
                  context,
                ).showSnackBar(const SnackBar(content: Text("记录已删除")));
              }
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
            errorBuilder: (_, __, ___) => Container(
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
    return Scaffold(
      appBar: AppBar(
        title: const Text('待补充日期作品'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () {
            setState(() {
              _showUnknownMode = false;
            });
          },
        ),
      ),
      body: _unknownDateAnimes.isEmpty
          ? const Center(child: Text("太棒了！所有作品的关键日期都已补全。"))
          : ListView.builder(
              itemCount: _unknownDateAnimes.length,
              itemBuilder: (context, index) {
                final anime = _unknownDateAnimes[index];

                // 分析缺失的日期信息
                List<String> missing = [];
                if (anime['air_date'] == null || anime['air_date'] == '') {
                  missing.add("放映日期");
                }
                if (anime['status'] == '在看' &&
                    (anime['watch_start_date'] == null ||
                        anime['watch_start_date'] == '')) {
                  missing.add("开始看时间");
                }
                if (anime['status'] == '看完' &&
                    (anime['watch_finish_date'] == null ||
                        anime['watch_finish_date'] == '')) {
                  missing.add("看完时间");
                }

                return ListTile(
                  title: Text(anime['title']),
                  subtitle: Text(
                    "缺失: ${missing.join(', ')}",
                    style: const TextStyle(color: Colors.red),
                  ),
                  trailing: const Icon(Icons.edit),
                  onTap: () async {
                    // 跳转到编辑页面补充日期信息
                    await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) =>
                            AddAnimePage(existingAnime: anime),
                      ),
                    );
                    // 重新加载事件
                    refreshData();
                  },
                );
              },
            ),
    );
  }
}
