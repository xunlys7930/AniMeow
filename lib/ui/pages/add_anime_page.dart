import 'dart:io';
import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';
import 'package:path/path.dart' as path;
import 'package:cached_network_image/cached_network_image.dart';
import 'package:anime_tracker/db/database_helper.dart';
import 'package:anime_tracker/api/bangumi_service.dart';
import 'package:anime_tracker/api/anilist_service.dart';
import 'package:anime_tracker/settings_manager.dart';

import 'package:anime_tracker/ui/neumorphic_style.dart';
import 'package:anime_tracker/ui/anime_detail/anime_detail_page.dart';
import 'package:anime_tracker/ui/image_crop_page.dart';
import 'package:anime_tracker/ui/pages/image_anime_search_page.dart';
import 'package:anime_tracker/ui/series_dialogs.dart'; // 新增
import 'package:anime_tracker/ui/image_viewer_page.dart';
import 'package:anime_tracker/ui/components/adaptive_content_frame.dart';
import 'package:anime_tracker/ui/components/empty_state.dart';
import 'package:anime_tracker/ui/customization/page_display_config.dart';
import 'package:anime_tracker/ui/design_tokens.dart';
import 'package:anime_tracker/ui/pages/editor/anime_editor_display_config.dart';
import 'package:anime_tracker/ui/pages/editor/anime_editor_display_sheet.dart';
import 'package:anime_tracker/ui/pages/editor/anime_editor_validation.dart';
import 'package:anime_tracker/utils/notification_service.dart';
import 'package:anime_tracker/utils/anime_rating.dart';
import 'package:anime_tracker/utils/error_logger.dart';
import 'package:anime_tracker/utils/operation_log_service.dart';

part 'add_anime_page_ui.dart';

/// 添加/编辑动漫页面
class AddAnimePage extends StatefulWidget {
  final Map<String, dynamic>? existingAnime;
  final String? initialSearchQuery;
  final ImageAnimeSearchSelection? initialImageSearchSelection;
  final bool isDiscoveryMode;
  const AddAnimePage({
    super.key,
    this.existingAnime,
    this.initialSearchQuery,
    this.initialImageSearchSelection,
    this.isDiscoveryMode = false,
  });

  @override
  State<AddAnimePage> createState() => _AddAnimePageState();
}

class _AddAnimePageState extends State<AddAnimePage> {
  final PageDisplayController _displayController = PageDisplayController(
    pageId: 'anime_editor',
    defaults: animeEditorDefaults(),
    knownModules: animeEditorModuleKeys,
    fallbackOrder: animeEditorDefaultOrder,
  );

  // 表单控制器
  final _titleController = TextEditingController();
  final _reviewController = TextEditingController();
  String _studioInput = '';
  final _airDateController = TextEditingController();
  double _rating = 0.0; // 新增：评分状态
  String _ratingMode = 'score';
  String _ratingGrade = 'A';
  List<Map<String, dynamic>> _seriesSiblings = []; // 新增：同系列其他作品

  // --- 进度控制器 ---
  final _watchedController = TextEditingController(text: '0');
  final _totalController = TextEditingController(text: '0');
  final _tvEpsController = TextEditingController(text: '0');
  final _spEpsController = TextEditingController(text: '0');
  late TextEditingController _ratingController; // 新增评分控制器

  // 观看日期
  DateTime? _watchStartDate;
  DateTime? _watchFinishDate;

  // 状态选择
  String _status = '在看';
  List<String> _statusOptions = ['未看', '在看', '看完', '弃坑'];

  // 放送时间
  int? _broadcastDay;
  TimeOfDay? _broadcastTime;

  // 系列
  int? _seriesId;
  String? _seriesName;

  // 提醒设置
  int? _reminderDay;
  TimeOfDay? _reminderTime;
  bool _isReminderEnabled = false;

  // 标签管理
  List<Map<String, dynamic>> _allTags = [];
  final Set<int> _selectedTagIds = {};
  final Set<int> _initialTagIds = {}; // 新增：用于记录初始标签状态
  List<String> _knownStudios = [];

  // 封面图片
  String? _coverUrl; // 这里存储相对路径 (covers/xxx.jpg) 或 网络URL (http...)
  bool _isSearching = false;
  bool _isImageSearching = false;
  bool _isTagEditMode = false; // 新增：是否处于标签编辑模式

  // 类型选择：'anime' (番剧) 或 'book' (漫画/书籍)
  String _subjectType = 'anime';

  // 【修复】保存状态锁，防止重复提交
  bool _isSaving = false;
  bool _isInitializing = true;
  String? _initializationError;
  String? _formErrorMessage;

  // 集数详情 (如 "TV 12集, SP 2集")
  // String? _epsBreakdown; // 不再使用 breakdown 字符串，改用具体数值显示

  // --- 初始数据快照（用于检测真实修改） ---
  Map<String, dynamic> _initialSnapshot = {};

  final ImagePicker _picker = ImagePicker();

  /// 判断当前是否为编辑模式（即已经在本地数据库中存在的作品）
  bool get _isEditMode {
    if (widget.existingAnime == null) return false;
    // 如果是从预览/搜索模式进来的，且 existingAnime 中没有本地数据库的 id 字段，则不算作编辑模式
    if (widget.isDiscoveryMode && widget.existingAnime!['id'] == null) {
      return false;
    }
    return true;
  }

  @override
  void initState() {
    super.initState();

    // 【修复】立即同步初始化 _ratingController，防止 late 变量红屏
    _ratingController = TextEditingController(text: _rating.toStringAsFixed(1));

    _displayController.load();
    _registerDirtyListeners();
    _loadEditorData();

    // 监听 _airDateController 变化
    _airDateController.addListener(_onAirDateTextChanged);

    // 添加监听器以自动计算总集数
    _tvEpsController.addListener(_updateTotalEpisodes);
    _spEpsController.addListener(_updateTotalEpisodes);
  }

  void _registerDirtyListeners() {
    for (final controller in [
      _titleController,
      _reviewController,
      _watchedController,
      _totalController,
      _tvEpsController,
      _spEpsController,
    ]) {
      controller.addListener(_notifyFormChanged);
    }
  }

  void _notifyFormChanged() {
    if (!mounted || _isInitializing || _initialSnapshot.isEmpty) return;
    setState(() => _formErrorMessage = null);
  }

  void _updateEditorState(VoidCallback update) {
    setState(() {
      update();
      _formErrorMessage = null;
    });
  }

  Future<void> _loadEditorData() async {
    if (mounted) {
      setState(() {
        _isInitializing = true;
        _initializationError = null;
      });
    }
    try {
      await _initializeData();
    } catch (error) {
      if (!mounted) return;
      setState(() => _initializationError = '编辑数据加载失败：$error');
    } finally {
      if (mounted) setState(() => _isInitializing = false);
    }
  }

  /// 检查提醒设置与系统通知权限。
  /// 返回 true 表示可以继续保存（包括用户选择关闭提醒后保存）。
  Future<bool> _validateReminderSettings() async {
    if (_isReminderEnabled && (_reminderDay == null || _reminderTime == null)) {
      final bool? action = await showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('提醒设置不完整'),
          content: const Text('您开启了追番提醒，但尚未设置具体的提醒时间或日期。此时保存将无法生效。'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false), // 停留在页面
              child: const Text('去设置'),
            ),
            TextButton(
              onPressed: () {
                setState(() => _isReminderEnabled = false);
                Navigator.pop(ctx, true); // 决定不保存提醒并继续
              },
              child: const Text('关闭提醒并保存'),
            ),
          ],
        ),
      );
      if (action != true || _isReminderEnabled) return false;
    }

    if (!_isReminderEnabled) return true;

    final permission = await NotificationService().requestPermissions();
    if (!mounted) return false;
    if (permission.granted) return true;

    final bool? continueWithoutReminder = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        icon: const Icon(Icons.notifications_off_outlined),
        title: const Text('通知权限未开启'),
        content: Text(
          '${permission.message}\n\n系统没有通知权限时，追番提醒不会生效。你可以返回检查系统设置，或关闭本条提醒后继续保存。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('返回检查'),
          ),
          FilledButton.tonal(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('关闭提醒并保存'),
          ),
        ],
      ),
    );
    if (continueWithoutReminder == true) {
      setState(() => _isReminderEnabled = false);
      return true;
    }
    return false;
  }

  Future<void> _initializeData() async {
    // 确保异步基础数据先加载
    await _loadStatuses();
    await _loadTags();
    await _loadKnownStudios();

    // 如果是编辑模式或预览模式且已有数据，填充现有数据
    if (_isEditMode ||
        (widget.isDiscoveryMode && widget.existingAnime != null)) {
      Map<String, dynamic> anime = widget.existingAnime!;

      // 【关键修复】如果是本地数据库记录，尝试重新获取最新数据以同步其它页面的修改
      if (anime['id'] != null) {
        final latest = await DatabaseHelper().getAnimeById(anime['id']);
        if (latest != null) {
          anime = latest;
        }
      }

      _titleController.text =
          anime['name_cn'] ?? anime['title'] ?? anime['name'] ?? '';
      _status = anime['status'] ?? '在看';
      _reviewController.text = anime['review'] ?? anime['summary'] ?? '';
      _coverUrl = anime['cover_url'] ?? anime['image'];
      _studioInput = anime['studio'] ?? '';

      // 【新增】只要是来自网络（如发现页、服务器搜索结果）的封面，立即尝试静默推送至服务器
      if (_coverUrl != null && _coverUrl!.startsWith('http')) {
        String title =
            anime['name_cn'] ?? anime['title'] ?? anime['name'] ?? '';
        BangumiService.updateServerCover(title, _coverUrl!);
      }
      _airDateController.text = anime['air_date'] ?? '';
      final storedGrade = normalizeAnimeRatingGrade(anime['rating_grade']);
      if (storedGrade != null) {
        _ratingMode = 'grade';
        _ratingGrade = storedGrade;
      } else {
        _ratingMode = 'score';
        final rawRating = anime['rating'] ?? anime['score'] ?? 0;
        if (rawRating is String) {
          _rating = double.tryParse(rawRating) ?? 0.0;
        } else {
          _rating = (rawRating as num).toDouble();
        }
      }
      _seriesId = anime['series_id']; // 回填系列ID
      _subjectType = anime['subject_type'] ?? 'anime'; // 回填作品类型
      if (_seriesId != null) {
        _loadSeriesName(_seriesId!);
        _loadSeriesSiblings();
      }

      // 回填观看进度/集数信息
      _watchedController.text = (anime['watched_episodes'] ?? 0).toString();
      _totalController.text =
          (anime['total_episodes'] ?? anime['total_eps'] ?? 0).toString();
      _tvEpsController.text = (anime['tv_episodes'] ?? anime['tv_eps'] ?? 0)
          .toString();
      _spEpsController.text = (anime['sp_episodes'] ?? anime['sp_eps'] ?? 0)
          .toString();

      // 回填观看日期
      if (anime['watch_start_date'] != null &&
          anime['watch_start_date'].isNotEmpty) {
        _watchStartDate = DateTime.parse(anime['watch_start_date']);
      }
      if (anime['watch_finish_date'] != null &&
          anime['watch_finish_date'].isNotEmpty) {
        _watchFinishDate = DateTime.parse(anime['watch_finish_date']);
      }

      // 回填提醒设置
      _reminderDay = anime['reminder_day'];
      final String? timeStr = anime['reminder_time'];
      if (timeStr != null && timeStr.isNotEmpty) {
        final timeParts = timeStr.split(':');
        if (timeParts.length >= 2) {
          _reminderTime = TimeOfDay(
            hour: int.tryParse(timeParts[0]) ?? 0,
            minute: int.tryParse(timeParts[1]) ?? 0,
          );
          _isReminderEnabled = true;
          debugPrint("已回显提醒设置: day=$_reminderDay, time=$timeStr");
        }
      } else {
        _isReminderEnabled = false;
        _reminderDay = null; // 确保清空
        _reminderTime = null;
      }

      // 加载现有标签
      await _loadExistingTags();

      // 评分控制器更新（异步获取到 ID 后更新其值）
      _ratingController.text = _rating.toStringAsFixed(1);

      // 尝试解析并设置放送信息
      if (anime['air_date'] != null) {
        _parseAirDateForBroadcastInfo(anime['air_date']);
      }

      if (mounted) setState(() {});
    } else if (widget.initialImageSearchSelection != null) {
      _subjectType = 'anime';
      final selection = widget.initialImageSearchSelection!;
      await _fillFromSearchResult(selection.result, selection.isBangumi, const {
        'cover': true,
        'title': true,
        'rating': true,
        'episodes': true,
        'studio': true,
        'summary': true,
        'airDate': true,
      });
    } else if (widget.initialSearchQuery != null) {
      // 没有任何现有数据，且有初始搜索关键词，才自动开始搜索
      _titleController.text = widget.initialSearchQuery!;
      // 延迟一帧执行搜索，确保Context可用
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _performSearch();
      });
    }

    // 初始化评分控制器（必须在 _rating 回填之后）
    _ratingController.text = _rating.toStringAsFixed(1);

    // 尝试从 existingAnime 的 air_date 设置 _broadcastDay 和 _broadcastTime
    if (widget.existingAnime != null &&
        widget.existingAnime!['air_date'] != null) {
      _parseAirDateForBroadcastInfo(widget.existingAnime!['air_date']);
    }

    // 注：_airDateController 的 listener 已在 initState (L119) 注册，
    // 这里不再重复添加，避免同一 change 触发两次回调。

    // 【重要】在初始化最后捕获快照
    _captureSnapshot();
  }

  /// 捕获当前页面状态快照，作为后续比对基准
  void _captureSnapshot() {
    // 在快照之前先同步把 broadcastDay/Time 拼回 air_date 控制器，
    // 防止之后 PostFrameCallback 才拼上"周X"导致快照过时、脏检测误报。
    _syncBroadcastTimeToAirDate();

    _initialSnapshot = {
      'title': _titleController.text.trim(),
      'status': _status.trim(),
      'review': _reviewController.text.trim(),
      'cover_url': (_coverUrl ?? '').trim(),
      'studio': _studioInput.trim(),
      'air_date': _airDateController.text.trim(),
      'subject_type': _subjectType,
      'watched': int.tryParse(_watchedController.text) ?? 0,
      'total': int.tryParse(_totalController.text) ?? 0,
      'tv': int.tryParse(_tvEpsController.text) ?? 0,
      'sp': int.tryParse(_spEpsController.text) ?? 0,
      'rating': _rating,
      'rating_mode': _ratingMode,
      'rating_grade': _ratingGrade,
      'series_id': _seriesId,
      'watch_start': _watchStartDate?.toIso8601String(),
      'watch_finish': _watchFinishDate?.toIso8601String(),
      'reminder_day': _reminderDay,
      'reminder_time': _isReminderEnabled && _reminderTime != null
          ? '${_reminderTime!.hour.toString().padLeft(2, '0')}:${_reminderTime!.minute.toString().padLeft(2, '0')}'
          : null,
      'tags': Set<int>.from(_selectedTagIds),
    };
    debugPrint("[AddAnimePage] 初始快照已捕获: ${_initialSnapshot['title']}");
  }

  void _parseAirDateForBroadcastInfo(String airDate) {
    if (airDate.isEmpty) {
      _broadcastDay = null;
      _broadcastTime = null;
      return;
    }
    try {
      final parts = airDate.split(' ');
      // 提取日期部分（如果有）
      String? datePart;
      int startIndex = 0;
      if (parts.first.contains('-')) {
        datePart = parts.first;
        startIndex = 1;
      }

      // 尝试读取明确写出的周几和时间
      if (parts.length > startIndex && parts[startIndex].startsWith('周')) {
        final dayStr = parts[startIndex];
        const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
        _broadcastDay = days.indexOf(dayStr) + 1;
        if (_broadcastDay == 0) _broadcastDay = null;

        if (parts.length > startIndex + 1) {
          final timeParts = parts[startIndex + 1].split(':');
          if (timeParts.length == 2) {
            _broadcastTime = TimeOfDay(
              hour: int.tryParse(timeParts[0]) ?? 0,
              minute: int.tryParse(timeParts[1]) ?? 0,
            );
          }
        } else {
          _broadcastTime = null;
        }
      } else if (datePart != null) {
        // 没有明确写出周几，但有日期，自动计算
        final date = DateTime.tryParse(datePart);
        if (date != null) {
          _broadcastDay = date.weekday;
        } else {
          _broadcastDay = null;
        }
        _broadcastTime = null;
      } else {
        _broadcastDay = null;
        _broadcastTime = null;
      }
    } catch (e) {
      debugPrint("Failed to parse air_date for broadcast info: $e");
    }
  }

  void _onAirDateTextChanged() {
    // 避免循环触发：只在确实是从外部修改时才解析
    final oldDay = _broadcastDay;
    final oldTime = _broadcastTime;

    _parseAirDateForBroadcastInfo(_airDateController.text);

    if (oldDay != _broadcastDay || oldTime != _broadcastTime) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) {
          setState(() {});
          _syncBroadcastTimeToAirDate();
        }
      });
    }
  }

  void _syncBroadcastTimeToAirDate() {
    try {
      String newAirDate = '';
      if (_airDateController.text.isNotEmpty) {
        final parts = _airDateController.text.split(' ');
        if (parts.first.contains('-')) {
          newAirDate = parts.first; // 保留原有的 YYYY-MM-DD
        }
      }

      if (_broadcastDay != null) {
        const days = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
        if (newAirDate.isNotEmpty) newAirDate += ' ';
        newAirDate += days[_broadcastDay! - 1];

        if (_broadcastTime != null) {
          final hr = _broadcastTime!.hour.toString().padLeft(2, '0');
          final min = _broadcastTime!.minute.toString().padLeft(2, '0');
          newAirDate += ' $hr:$min';
        }
      }

      if (_airDateController.text != newAirDate) {
        // 利用当前的 Controller 选择器避免触发重新解析的问题
        _airDateController.removeListener(_onAirDateTextChanged);
        if (mounted) {
          setState(() {
            _airDateController.text = newAirDate;
          });
        } else {
          _airDateController.text = newAirDate;
        }
        _airDateController.addListener(_onAirDateTextChanged);
      }
    } catch (e) {
      debugPrint("Error syncing broadcast time to air_date: $e");
    }
  }

  @override
  void dispose() {
    _displayController.dispose();
    _tvEpsController.dispose();
    _spEpsController.dispose();
    _watchedController.dispose();
    _totalController.dispose();
    _titleController.dispose();
    _reviewController.dispose();
    _airDateController.dispose();
    _ratingController.dispose(); // 释放控制器
    super.dispose();
  }

  /// 监听 TV/SP 输入变化，自动计算总集数
  void _updateTotalEpisodes() {
    int tv = int.tryParse(_tvEpsController.text) ?? 0;
    int sp = int.tryParse(_spEpsController.text) ?? 0;
    _totalController.text = (tv + sp).toString();
  }

  /// 检测是否有未保存的更改
  bool _hasUnsavedChanges({bool logChanges = true}) {
    if (_initialSnapshot.isEmpty) return false;

    final title = _titleController.text.trim();
    final status = _status.trim();
    final review = _reviewController.text.trim();
    final cover = (_coverUrl ?? '').trim();
    final studio = _studioInput.trim();
    final airDate = _airDateController.text.trim();
    final watched = int.tryParse(_watchedController.text) ?? 0;
    final total = int.tryParse(_totalController.text) ?? 0;
    final tv = int.tryParse(_tvEpsController.text) ?? 0;
    final sp = int.tryParse(_spEpsController.text) ?? 0;
    final reminderTime = _isReminderEnabled && _reminderTime != null
        ? '${_reminderTime!.hour.toString().padLeft(2, '0')}:${_reminderTime!.minute.toString().padLeft(2, '0')}'
        : null;

    Map<String, bool> diffs = {
      'title': title != _initialSnapshot['title'],
      'status': status != _initialSnapshot['status'],
      'review': review != _initialSnapshot['review'],
      'cover': cover != _initialSnapshot['cover_url'],
      'studio': studio != _initialSnapshot['studio'],
      'airDate': airDate != _initialSnapshot['air_date'],
      'subjectType': _subjectType != _initialSnapshot['subject_type'],
      'watched': watched != _initialSnapshot['watched'],
      'total': total != _initialSnapshot['total'],
      'tv': tv != _initialSnapshot['tv'],
      'sp': sp != _initialSnapshot['sp'],
      'rating':
          _rating != _initialSnapshot['rating'] ||
          _ratingMode != _initialSnapshot['rating_mode'] ||
          _ratingGrade != _initialSnapshot['rating_grade'],
      'seriesId': _seriesId != _initialSnapshot['series_id'],
      'watchStartDate':
          _watchStartDate?.toIso8601String() != _initialSnapshot['watch_start'],
      'watchFinishDate':
          _watchFinishDate?.toIso8601String() !=
          _initialSnapshot['watch_finish'],
      'reminderDay': _reminderDay != _initialSnapshot['reminder_day'],
      'reminderTime': reminderTime != _initialSnapshot['reminder_time'],
      'tags': !_sameIntSet(
        _selectedTagIds,
        _initialSnapshot['tags'] as Set<int>,
      ),
    };

    bool changed = diffs.values.any((d) => d);

    if (changed && logChanges) {
      debugPrint("--- [AddAnimePage] 检测到未保存更改 ---");
      diffs.forEach((key, isChanged) {
        if (isChanged) {
          dynamic original =
              _initialSnapshot[key == 'airDate' ? 'air_date' : key]; // 简化的映射检查
          if (key == 'watched') original = _initialSnapshot['watched'];
          if (key == 'cover') original = _initialSnapshot['cover_url'];

          // 获取更准确的原始值（应对 snapshot 中的映射名）
          final snapKey = {
            'title': 'title',
            'status': 'status',
            'review': 'review',
            'cover': 'cover_url',
            'studio': 'studio',
            'airDate': 'air_date',
            'subjectType': 'subject_type',
            'watched': 'watched',
            'total': 'total',
            'tv': 'tv',
            'sp': 'sp',
            'rating': 'rating',
            'seriesId': 'series_id',
            'watchStartDate': 'watch_start',
            'watchFinishDate': 'watch_finish',
            'reminderDay': 'reminder_day',
            'reminderTime': 'reminder_time',
            'tags': 'tags',
          }[key];

          original = _initialSnapshot[snapKey];

          Object? current;
          switch (key) {
            case 'title':
              current = title;
              break;
            case 'status':
              current = status;
              break;
            case 'review':
              current = review;
              break;
            case 'cover':
              current = cover;
              break;
            case 'studio':
              current = studio;
              break;
            case 'airDate':
              current = airDate;
              break;
            case 'subjectType':
              current = _subjectType;
              break;
            case 'watched':
              current = watched;
              break;
            case 'total':
              current = total;
              break;
            case 'tv':
              current = tv;
              break;
            case 'sp':
              current = sp;
              break;
            case 'rating':
              current = _ratingMode == 'grade' ? _ratingGrade : _rating;
              break;
            case 'seriesId':
              current = _seriesId;
              break;
            case 'watchStartDate':
              current = _watchStartDate?.toIso8601String();
              break;
            case 'watchFinishDate':
              current = _watchFinishDate?.toIso8601String();
              break;
            case 'reminderDay':
              current = _reminderDay;
              break;
            case 'reminderTime':
              current = reminderTime;
              break;
            case 'tags':
              current = _selectedTagIds;
              break;
          }

          debugPrint("  • $key 发生变化: [$original] -> [$current]");
        }
      });
      debugPrint("---------------------------------------");
    }

    return changed;
  }

  bool _sameIntSet(Set<int> left, Set<int> right) {
    return left.length == right.length && left.containsAll(right);
  }

  Future<void> _loadKnownStudios() async {
    final studios = await DatabaseHelper().getAllStudios();
    if (mounted) setState(() => _knownStudios = studios);
  }

  Future<void> _loadExistingTags() async {
    if (!_isEditMode) return;
    final existingTags = await DatabaseHelper().getTagsByAnimeId(
      widget.existingAnime!['id'],
    );
    if (mounted) {
      setState(() {
        for (var tag in existingTags) {
          final id = tag['id'] as int;
          _selectedTagIds.add(id);
          _initialTagIds.add(id); // 记录初始状态
        }
      });
    }
  }

  Future<void> _loadStatuses() async {
    final list = await DatabaseHelper().getAllStatuses();
    if (mounted && list.isNotEmpty) {
      setState(() {
        // Sort by sort_order (already sorted by DB query)
        _statusOptions = list.map((e) => e['name'] as String).toList();
        // Ensure current status is in options
        if (!_statusOptions.contains(_status)) {
          _statusOptions.add(_status);
        }
      });
    }
  }

  Future<void> _loadTags() async {
    final tags = await DatabaseHelper().getAllTags();
    if (mounted) {
      setState(() {
        _allTags = tags;
      });
    }
  }

  /// 显示创建标签对话框
  void _showCreateTagDialog() {
    final textController = TextEditingController();

    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('新建标签'),
        content: TextField(
          controller: textController,
          decoration: const InputDecoration(hintText: "例如：#热血"),
          autofocus: true,
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              final tagName = textController.text.trim();
              if (tagName.isEmpty) return;

              // 查找是否已存在
              final existingTag = _allTags.firstWhere(
                (t) =>
                    t['name'].toString().toLowerCase() == tagName.toLowerCase(),
                orElse: () => {},
              );

              if (existingTag.isNotEmpty) {
                // 已存在，弹出询问
                if (!context.mounted) return;
                Navigator.pop(context); // 先关闭输入框

                showDialog(
                  context: this.context,
                  builder: (ctx) => AlertDialog(
                    title: const Text('标签已存在'),
                    content: Text('标签 "$tagName" 已存在，是否直接为本番剧添加该标签？'),
                    actions: [
                      TextButton(
                        onPressed: () => Navigator.pop(ctx),
                        child: const Text('取消'),
                      ),
                      TextButton(
                        onPressed: () {
                          setState(() {
                            _selectedTagIds.add(existingTag['id'] as int);
                          });
                          Navigator.pop(ctx);
                        },
                        child: const Text('直接添加'),
                      ),
                    ],
                  ),
                );
              } else {
                // 不存在，创建并选择
                int result = await DatabaseHelper().insertTag(tagName);
                if (!context.mounted) return;

                if (result != -1) {
                  setState(() {
                    _selectedTagIds.add(result);
                  });
                  _loadTags(); // 刷新标签列表
                  Navigator.pop(context);
                } else {
                  ScaffoldMessenger.of(
                    context,
                  ).showSnackBar(const SnackBar(content: Text('创建标签失败')));
                }
              }
            },
            child: const Text('创建'),
          ),
        ],
      ),
    );
  }

  /// 执行联网搜索动漫
  void _performSearch() async {
    final query = _titleController.text.trim();
    if (query.isEmpty) return;

    // 收起键盘
    FocusScope.of(context).unfocus();

    setState(() => _isSearching = true);

    try {
      final List<Future<List<BangumiSearchResult>>> searchRequests = [];

      if (_subjectType == 'anime') {
        searchRequests.add(BangumiService.searchAnime(query));
        searchRequests.add(AnilistService.searchAnime(query));
      } else {
        // 漫画模式目前仅支持 Bangumi
        searchRequests.add(BangumiService.searchBook(query));
      }

      final resultsList = await Future.wait(searchRequests);

      if (!mounted) return;
      setState(() => _isSearching = false);

      final List<BangumiSearchResult> bangumiResults = resultsList[0];
      final List<BangumiSearchResult> anilistResults =
          (_subjectType == 'anime' && resultsList.length > 1)
          ? resultsList[1]
          : [];
      final allResults = [...bangumiResults, ...anilistResults];

      showDialog(
        context: context,
        builder: (context) => AlertDialog(
          title: Text('搜索结果: "$query"'),
          content: SizedBox(
            width: double.maxFinite,
            height: 400,
            child: allResults.isEmpty
                ? const Center(child: Text('未找到相关动画'))
                : ListView.builder(
                    itemCount: allResults.length,
                    itemBuilder: (context, index) {
                      final anime = allResults[index];
                      final bool isBangumi = index < bangumiResults.length;
                      final String sourceName = isBangumi
                          ? "Bangumi"
                          : "Anilist";
                      final Color sourceColor = isBangumi
                          ? Colors.pinkAccent
                          : Colors.blueAccent;

                      return ListTile(
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 0,
                          vertical: 4,
                        ),
                        leading: SizedBox(
                          width: 50,
                          height: 70,
                          child: anime.coverUrl != null
                              ? ClipRRect(
                                  borderRadius: BorderRadius.circular(4),
                                  child: Image.network(
                                    anime.coverUrl!,
                                    fit: BoxFit.cover,
                                  ),
                                )
                              : const Icon(Icons.movie),
                        ),
                        title: Text(
                          anime.nameCn,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        subtitle: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            const SizedBox(height: 2),
                            Container(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 4,
                                vertical: 1,
                              ),
                              decoration: BoxDecoration(
                                color: sourceColor.withValues(alpha: 0.1),
                                border: Border.all(
                                  color: sourceColor,
                                  width: 0.5,
                                ),
                                borderRadius: BorderRadius.circular(4),
                              ),
                              child: Text(
                                sourceName,
                                style: TextStyle(
                                  fontSize: 10,
                                  color: sourceColor,
                                ),
                              ),
                            ),
                            const SizedBox(height: 2),
                            Builder(
                              builder: (context) {
                                String epsText = "${anime.eps ?? '?'}集";
                                if (isBangumi &&
                                    anime.totalCount != null &&
                                    anime.totalCount! > 0) {
                                  String breakdown = "";
                                  if (anime.tvCount != null &&
                                      anime.tvCount! > 0) {
                                    breakdown += "TV ${anime.tvCount} ";
                                  }
                                  if (anime.spCount != null &&
                                      anime.spCount! > 0) {
                                    breakdown += "SP ${anime.spCount}";
                                  }
                                  epsText =
                                      "${anime.totalCount}集 (${breakdown.trim()})";
                                }

                                // 新增：显示评分
                                String scoreText = "";
                                if (anime.score != null && anime.score! > 0) {
                                  scoreText =
                                      " • ⭐ ${anime.score!.toStringAsFixed(1)}";
                                }

                                return Text(
                                  "${anime.airDate ?? '日期未知'} • $epsText$scoreText",
                                  style: const TextStyle(fontSize: 12),
                                );
                              },
                            ),
                          ],
                        ),
                        onTap: () async {
                          // 显示同步选项对话框
                          final Map<String, bool>?
                          selections = await showDialog<Map<String, bool>>(
                            context: context,
                            builder: (ctx) {
                              Map<String, bool> localSelections = {
                                'cover': true,
                                'title': true,
                                'rating': true,
                                'episodes': true,
                                'studio': true,
                                'summary': true,
                                'airDate': true,
                              };
                              return StatefulBuilder(
                                builder: (ctx, setDialogState) {
                                  return AlertDialog(
                                    title: const Text('同步选项'),
                                    content: SingleChildScrollView(
                                      child: Column(
                                        mainAxisSize: MainAxisSize.min,
                                        crossAxisAlignment:
                                            CrossAxisAlignment.start,
                                        children: [
                                          // 新增：番剧信息预览头部
                                          Container(
                                            padding: const EdgeInsets.all(12),
                                            decoration: BoxDecoration(
                                              color: Colors.grey[100],
                                              borderRadius:
                                                  BorderRadius.circular(12),
                                            ),
                                            child: Row(
                                              crossAxisAlignment:
                                                  CrossAxisAlignment.start,
                                              children: [
                                                if (anime.coverUrl != null)
                                                  ClipRRect(
                                                    borderRadius:
                                                        BorderRadius.circular(
                                                          8,
                                                        ),
                                                    child: Image.network(
                                                      anime.coverUrl!,
                                                      width: 60,
                                                      height: 84,
                                                      fit: BoxFit.cover,
                                                    ),
                                                  ),
                                                const SizedBox(width: 12),
                                                Expanded(
                                                  child: Column(
                                                    crossAxisAlignment:
                                                        CrossAxisAlignment
                                                            .start,
                                                    children: [
                                                      Text(
                                                        anime.nameCn,
                                                        style: const TextStyle(
                                                          fontWeight:
                                                              FontWeight.bold,
                                                          fontSize: 15,
                                                        ),
                                                        maxLines: 2,
                                                        overflow: TextOverflow
                                                            .ellipsis,
                                                      ),
                                                      const SizedBox(height: 6),
                                                      Text(
                                                        "${anime.airDate ?? '日期未知'} • ${anime.eps ?? '?'}集",
                                                        style: TextStyle(
                                                          fontSize: 12,
                                                          color:
                                                              Colors.grey[600],
                                                        ),
                                                      ),
                                                      if (anime.score != null &&
                                                          anime.score! > 0)
                                                        Padding(
                                                          padding:
                                                              const EdgeInsets.only(
                                                                top: 4,
                                                              ),
                                                          child: Text(
                                                            "⭐ ${anime.score!.toStringAsFixed(1)}",
                                                            style:
                                                                const TextStyle(
                                                                  fontSize: 12,
                                                                  color: Colors
                                                                      .orange,
                                                                  fontWeight:
                                                                      FontWeight
                                                                          .bold,
                                                                ),
                                                          ),
                                                        ),
                                                    ],
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                          const SizedBox(height: 16),
                                          const Text(
                                            "选择要同步的内容：",
                                            style: TextStyle(
                                              fontSize: 13,
                                              fontWeight: FontWeight.w600,
                                              color: Colors.grey,
                                            ),
                                          ),
                                          const SizedBox(height: 8),
                                          CheckboxListTile(
                                            title: const Text('封面'),
                                            value: localSelections['cover'],
                                            onChanged: (v) => setDialogState(
                                              () =>
                                                  localSelections['cover'] = v!,
                                            ),
                                          ),
                                          CheckboxListTile(
                                            title: const Text('标题'),
                                            value: localSelections['title'],
                                            onChanged: (v) => setDialogState(
                                              () =>
                                                  localSelections['title'] = v!,
                                            ),
                                          ),
                                          CheckboxListTile(
                                            title: const Text('评分'),
                                            value: localSelections['rating'],
                                            onChanged: (v) => setDialogState(
                                              () => localSelections['rating'] =
                                                  v!,
                                            ),
                                          ),
                                          CheckboxListTile(
                                            title: const Text('集数配置'),
                                            value: localSelections['episodes'],
                                            onChanged: (v) => setDialogState(
                                              () =>
                                                  localSelections['episodes'] =
                                                      v!,
                                            ),
                                          ),
                                          CheckboxListTile(
                                            title: const Text('制作公司'),
                                            value: localSelections['studio'],
                                            onChanged: (v) => setDialogState(
                                              () => localSelections['studio'] =
                                                  v!,
                                            ),
                                          ),
                                          CheckboxListTile(
                                            title: const Text('简介'),
                                            value: localSelections['summary'],
                                            onChanged: (v) => setDialogState(
                                              () => localSelections['summary'] =
                                                  v!,
                                            ),
                                          ),
                                          CheckboxListTile(
                                            title: const Text('放送日期'),
                                            value: localSelections['airDate'],
                                            onChanged: (v) => setDialogState(
                                              () => localSelections['airDate'] =
                                                  v!,
                                            ),
                                          ),
                                        ],
                                      ),
                                    ),
                                    actions: [
                                      TextButton(
                                        onPressed: () => Navigator.pop(ctx),
                                        child: const Text('取消'),
                                      ),
                                      TextButton(
                                        onPressed: () =>
                                            Navigator.pop(ctx, localSelections),
                                        child: const Text('同步选中项'),
                                      ),
                                      FilledButton(
                                        onPressed: () {
                                          Navigator.pop(ctx, {
                                            'cover': true,
                                            'title': true,
                                            'rating': true,
                                            'episodes': true,
                                            'studio': true,
                                            'summary': true,
                                            'airDate': true,
                                          });
                                        },
                                        child: const Text('全部同步'),
                                      ),
                                    ],
                                  );
                                },
                              );
                            },
                          );

                          if (selections != null &&
                              mounted &&
                              context.mounted) {
                            Navigator.pop(context); // 关闭搜索列表弹窗
                            _fillFromSearchResult(anime, isBangumi, selections);
                          }
                        },
                      );
                    },
                  ),
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('关闭'),
            ),
          ],
        ),
      );
    } catch (e) {
      if (mounted) {
        setState(() => _isSearching = false);
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text("搜索出错: $e")));
      }
    }
  }

  /// 使用 trace.moe 按截图搜索番剧，再复用标题搜索补全资料。
  Future<void> _performImageSearch() async {
    if (_subjectType != 'anime') {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('以图搜番仅支持番剧截图')));
      return;
    }

    FocusScope.of(context).unfocus();
    setState(() => _isImageSearching = true);
    final selection = await Navigator.push<ImageAnimeSearchSelection>(
      context,
      MaterialPageRoute(builder: (_) => const ImageAnimeSearchPage()),
    );

    if (!mounted) return;
    setState(() => _isImageSearching = false);
    if (selection == null) return;

    await _fillFromSearchResult(selection.result, selection.isBangumi, const {
      'cover': true,
      'title': true,
      'rating': true,
      'episodes': true,
      'studio': true,
      'summary': true,
      'airDate': true,
    });
  }

  /// 从搜索结果填充数据
  Future<void> _fillFromSearchResult(
    dynamic anime,
    bool isBangumi,
    Map<String, bool> enabledFields,
  ) async {
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('正在获取详细信息...'),
        duration: Duration(milliseconds: 500),
      ),
    );

    String finalStudio = anime.studio ?? '';
    String finalSummary = anime.summary ?? '';
    String? finalDate = anime.airDate;

    int? finalEps = anime.eps;
    double? finalScore = anime.score; // 获取评分

    int tvCount = 0;
    int spCount = 0;

    if (isBangumi) {
      try {
        final detail = await BangumiService.getAnimeDetail(anime.id);
        if (detail['studio'] != null) finalStudio = detail['studio'];
        if (detail['summary'] != null) finalSummary = detail['summary'];
        if (detail['air_date'] != null) finalDate = detail['air_date'];
        if (detail['eps'] != null && detail['eps'] != 0) {
          finalEps = detail['eps'];
        }
        if (detail['score'] != null) finalScore = detail['score']; // 优先使用详情页评分

        if (detail['tv_count'] != null) tvCount = detail['tv_count'];
        if (detail['sp_count'] != null) spCount = detail['sp_count'];

        // 如果没有 breakdown 数据，尝试用 eps 当作 TV 集数 (如果 SP 为 0)
        if (tvCount == 0 && spCount == 0 && finalEps != null) {
          tvCount = finalEps;
        }
      } catch (e) {
        debugPrint("详情获取失败: $e");
      }
    }

    if (!mounted) return;

    setState(() {
      if (enabledFields['title'] == true) {
        _titleController.text = anime.nameCn;
      }
      if (enabledFields['cover'] == true) {
        _coverUrl = anime.coverUrl; // 网络图片直接存URL
      }
      if (enabledFields['studio'] == true) {
        _studioInput = finalStudio;
      }
      if (enabledFields['airDate'] == true && finalDate != null) {
        _airDateController.text = finalDate;
      }

      if (enabledFields['episodes'] == true) {
        if (finalEps != null) _totalController.text = finalEps.toString();
        _tvEpsController.text = tvCount.toString();
        _spEpsController.text = spCount.toString();
        // 如果总集数和 TV+SP 不一致，优先信任 API 返回的 total，或者自动计算
        if (tvCount + spCount > 0) {
          _totalController.text = (tvCount + spCount).toString();
        }
      }

      if (enabledFields['rating'] == true && finalScore != null) {
        _ratingMode = 'score';
        _rating = finalScore; // 填充评分
        _ratingController.text = _rating.toStringAsFixed(1);
      }

      if (enabledFields['summary'] == true && finalSummary.isNotEmpty) {
        if (_reviewController.text.isEmpty || enabledFields['title'] == true) {
          _reviewController.text = finalSummary;
        }
      }
    });

    // --- 新增：封面 URL 自动回传服务器逻辑 ---
    // 只有在同步了封面，且当前不是编辑已有的本地番剧（即它是新搜出来的预览/入库作品）时，才尝试回传
    if (enabledFields['cover'] == true &&
        _coverUrl != null &&
        _coverUrl!.startsWith('http')) {
      // 异步执行，不阻塞 UI
      BangumiService.updateServerCover(
        _titleController.text.trim(),
        _coverUrl!,
      );
    }
  }

  Future<void> _selectWatchDate(BuildContext context, bool isStart) async {
    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate:
          (isStart ? _watchStartDate : _watchFinishDate) ?? DateTime.now(),
      firstDate: DateTime(1980),
      lastDate: DateTime(2100),
    );

    if (picked != null) {
      _updateEditorState(() {
        if (isStart) {
          _watchStartDate = picked;
        } else {
          _watchFinishDate = picked;
        }
      });
    }
  }

  Future<void> _selectAirDate(BuildContext context) async {
    final DateTime? picked = await showDatePicker(
      context: context,
      initialDate: DateTime.now(),
      firstDate: DateTime(1960),
      lastDate: DateTime(2100),
    );
    if (picked != null) {
      _updateEditorState(() {
        _airDateController.text =
            "${picked.year}-${picked.month.toString().padLeft(2, '0')}-${picked.day.toString().padLeft(2, '0')}";
        _broadcastDay = picked.weekday; // DateTime.weekday 返回 1-7 (周一至周日)
        _syncBroadcastTimeToAirDate(); // 将提取出的周几附加到文本框，并带上可能原有的时间
      });
    }
  }

  Future<String?> _cropImage(String sourcePath) async {
    if (!mounted) return null;
    return await Navigator.of(context).push<String>(
      MaterialPageRoute(
        builder: (_) => ImageCropPage(
          imagePath: sourcePath,
          aspectRatio: 2 / 3, // 番剧封面标准比例
          title: '裁剪封面',
        ),
        fullscreenDialog: true,
      ),
    );
  }

  /// 【修复】选择图片：存储相对路径，并清理旧图片
  Future<void> _pickLocalImage() async {
    try {
      final XFile? pickedFile = await _picker.pickImage(
        source: ImageSource.gallery,
        imageQuality: 90,
      );

      if (pickedFile == null) return;

      final String? finalImagePath = await _cropImage(pickedFile.path);
      if (finalImagePath == null) return;

      final Directory appDocDir = await getApplicationDocumentsDirectory();
      final String coverDirName = 'covers';
      final Directory coverDir = Directory(
        path.join(appDocDir.path, coverDirName),
      );

      // 确保目录存在
      if (!await coverDir.exists()) {
        await coverDir.create(recursive: true);
      }

      // 【清理旧图片】如果当前是本地图片，先删除旧文件
      if (_coverUrl != null && !_coverUrl!.startsWith('http')) {
        try {
          File oldFile;
          if (path.isAbsolute(_coverUrl!)) {
            oldFile = File(_coverUrl!); // 兼容旧数据的绝对路径
          } else {
            oldFile = File(path.join(appDocDir.path, _coverUrl!)); // 相对路径拼接
          }
          if (await oldFile.exists()) {
            await oldFile.delete();
          }
        } catch (e) {
          debugPrint("清理旧图片失败 (非致命): $e");
        }
      }

      // 生成新文件名和路径
      final String fileName =
          '${DateTime.now().millisecondsSinceEpoch}${path.extension(finalImagePath)}';
      final String localSavedPath = path.join(coverDir.path, fileName);

      // 复制文件
      await File(finalImagePath).copy(localSavedPath);

      if (!mounted) return;
      setState(() {
        // 【关键】数据库只存相对路径: "covers/123.jpg"
        _coverUrl = path.join(coverDirName, fileName);
      });
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('处理图片失败: $e')));
    }
  }

  /// 【修复】构建封面：兼容网络图片、绝对路径(旧数据)和相对路径(新数据)

  /// 【修复】保存逻辑：增加防抖和 mounted 检查
  Future<void> _saveAnime() async {
    if (_isSaving) return; // 防止重复点击

    final title = _titleController.text.trim();
    final validationError = validateAnimeEditorFields(
      title: title,
      watchedText: _watchedController.text,
      totalText: _totalController.text,
      primaryEpisodeText: _tvEpsController.text,
      extraEpisodeText: _spEpsController.text,
      watchStartDate: _watchStartDate,
      watchFinishDate: _watchFinishDate,
    );
    if (validationError != null) {
      setState(() => _formErrorMessage = validationError);
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text(validationError)));
      return;
    }

    // 保存前验证提醒设置
    if (!await _validateReminderSettings()) return;

    if (!mounted) return;
    FocusScope.of(context).unfocus();
    setState(() {
      _isSaving = true;
      _formErrorMessage = null;
    });
    OperationLogService.instance.record(
      _isEditMode ? '开始保存作品修改' : '开始添加作品',
      screen: '作品编辑',
      details: {
        'mode': _isEditMode ? 'edit' : 'create',
        'subjectType': _subjectType,
        'tagCount': _selectedTagIds.length,
        'reminderEnabled': _isReminderEnabled,
      },
    );

    try {
      // 查重逻辑
      if (!_isEditMode ||
          (widget.existingAnime != null &&
              widget.existingAnime!['title'] != title)) {
        final existing = await DatabaseHelper().getAnimeByTitle(title);

        if (!mounted) return;

        if (existing != null && !widget.isDiscoveryMode) {
          final existingType = existing['subject_type'] ?? 'anime';
          final existingSeriesId = existing['series_id'];
          final isTypeConflict = existingType != _subjectType;

          if (isTypeConflict) {
            if (existingSeriesId == null) {
              // 场景 A: 都没有系列，建议创建新系列
              final bool? merge = await showDialog<bool>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('发现同名作品'),
                  content: Text(
                    '已存在名为 "$title" 的${existingType == 'anime' ? '番剧' : '漫画'}。\n\n是否将它们关联为同一个系列？关联后它们将在首页显示在一起。',
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(ctx, null), // 取消
                      child: const Text('取消'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.pop(ctx, false), // 仅继续添加
                      child: const Text('仅继续添加'),
                    ),
                    FilledButton(
                      onPressed: () => Navigator.pop(ctx, true), // 合并
                      child: const Text('创建并合并系列'),
                    ),
                  ],
                ),
              );

              if (merge == null) {
                if (mounted) setState(() => _isSaving = false);
                return;
              }

              if (merge == true) {
                // 用户同意合并：创建新系列
                final newSeriesId = await DatabaseHelper().createSeries(title);
                _seriesId = newSeriesId;
                // 同时把旧作品也拉入这个系列
                await DatabaseHelper().updateAnime({
                  'id': existing['id'],
                  'series_id': newSeriesId,
                });
              }
            } else {
              // 场景 B: 已有作品存在系列，建议加入
              final series = await DatabaseHelper().getSeriesById(
                existingSeriesId,
              );
              if (!mounted) return;
              final seriesName = series?['name'] ?? '现有系列';

              final bool? join = await showDialog<bool>(
                context: context,
                builder: (ctx) => AlertDialog(
                  title: const Text('加入已有系列'),
                  content: Text('已存在同名作品属于系列「$seriesName」。\n\n是否也将当前作品加入该系列？'),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(ctx, null),
                      child: const Text('取消'),
                    ),
                    TextButton(
                      onPressed: () => Navigator.pop(ctx, false),
                      child: const Text('仅继续添加'),
                    ),
                    FilledButton(
                      onPressed: () => Navigator.pop(ctx, true),
                      child: const Text('加入系列'),
                    ),
                  ],
                ),
              );

              if (join == null) {
                if (mounted) setState(() => _isSaving = false);
                return;
              }

              if (join == true) {
                _seriesId = existingSeriesId;
              }
            }
          } else {
            // 类型相同，走原有的简单查重提示
            final bool? continueAdd = await showDialog<bool>(
              context: context,
              builder: (ctx) => AlertDialog(
                title: const Text('发现重名作品'),
                content: Text(
                  '数据库中已存在名为 "$title" 的${_subjectType == 'anime' ? '番剧' : '漫画'}。\n\n是否继续添加？',
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(ctx, false),
                    child: const Text('取消'),
                  ),
                  FilledButton(
                    onPressed: () => Navigator.pop(ctx, true),
                    child: const Text('继续'),
                  ),
                ],
              ),
            );

            if (continueAdd != true) {
              if (mounted) setState(() => _isSaving = false);
              return;
            }
          }
        }
      }

      // 准备数据
      Map<String, dynamic> animeData = {
        'title': title,
        'status': _status,
        'review': _reviewController.text,
        'cover_url': _coverUrl,
        'studio': _studioInput,
        'air_date': _airDateController.text,
        'watch_start_date': _watchStartDate?.toIso8601String(),
        'watch_finish_date': _watchFinishDate?.toIso8601String(),
        'watched_episodes': int.tryParse(_watchedController.text) ?? 0,
        'total_episodes': int.tryParse(_totalController.text) ?? 0,
        'tv_episodes': int.tryParse(_tvEpsController.text) ?? 0,
        'sp_episodes': int.tryParse(_spEpsController.text) ?? 0,
        'series_id': _seriesId, // 保存系列ID
        'subject_type': _subjectType, // 保存类型
        'reminder_day': _isReminderEnabled ? _reminderDay : null,
        'reminder_time': _isReminderEnabled && _reminderTime != null
            ? '${_reminderTime!.hour.toString().padLeft(2, '0')}:${_reminderTime!.minute.toString().padLeft(2, '0')}'
            : null,
        'rating': _ratingMode == 'score' ? _rating : 0,
        'rating_grade': _ratingMode == 'grade' ? _ratingGrade : null,
      };

      if (_isEditMode) {
        int id = widget.existingAnime!['id'];
        animeData['id'] = id;
        await DatabaseHelper().updateAnime(animeData);
        await DatabaseHelper().updateAnimeTags(id, _selectedTagIds);
      } else {
        animeData['created_at'] = DateTime.now().toString();
        int newId = await DatabaseHelper().insertAnime(animeData);
        for (int tagId in _selectedTagIds) {
          await DatabaseHelper().addTagToAnime(newId, tagId);
        }
        animeData['id'] = newId;
      }

      // 只有系统排程成功时，数据库中的提醒才算真正启用。
      final int finalId = animeData['id'] as int;
      String? reminderWarning;
      if (_isReminderEnabled && _reminderDay != null && _reminderTime != null) {
        try {
          await NotificationService().scheduleWeeklyNotification(
            id: finalId,
            title: _subjectType == 'anime' ? '追番提醒' : '阅读提醒',
            body: _subjectType == 'anime'
                ? '您追的番剧《$title》今天更新啦，快去看看吧！'
                : '《$title》到了计划阅读的时间，来记录一下进度吧！',
            day: _reminderDay!,
            time: _reminderTime!,
          );
        } catch (error) {
          // 作品主体已经保存，回滚提醒字段，避免界面显示“已开启”但系统无任务。
          await DatabaseHelper().updateAnime({
            'id': finalId,
            'reminder_day': null,
            'reminder_time': null,
          });
          try {
            await NotificationService().cancelNotification(finalId);
          } catch (_) {
            // 原排程清理失败会在提醒管理页的“重新同步”中再次处理。
          }
          animeData['reminder_day'] = null;
          animeData['reminder_time'] = null;
          reminderWarning = NotificationService().describeError(error);
          if (mounted) setState(() => _isReminderEnabled = false);
        }
      } else {
        // 如果关闭了提醒，确保取消该番剧的旧通知
        try {
          await NotificationService().cancelNotification(finalId);
        } catch (error) {
          reminderWarning =
              '作品中的提醒设置已关闭，但旧系统任务未能立即清理：${NotificationService().describeError(error)}。请稍后在“追番提醒管理”中重新同步。';
        }
      }

      // 【新增】保存时作为最后一道防线再次尝试同步封面 URL
      if (_coverUrl != null && _coverUrl!.startsWith('http')) {
        BangumiService.updateServerCover(
          _titleController.text.trim(),
          _coverUrl!,
        );
      }

      if (reminderWarning != null && mounted) {
        await showDialog<void>(
          context: context,
          barrierDismissible: false,
          builder: (ctx) => AlertDialog(
            icon: const Icon(Icons.notification_important_outlined),
            title: const Text('作品已保存，提醒未生效'),
            content: Text(reminderWarning!),
            actions: [
              FilledButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('知道了'),
              ),
            ],
          ),
        );
      }

      OperationLogService.instance.record(
        _isEditMode ? '作品修改保存成功' : '作品添加成功',
        screen: '作品编辑',
        details: {'id': finalId},
      );
      if (mounted) Navigator.pop(context, true);
    } catch (e, stackTrace) {
      ErrorLogger.instance.addError(e, stackTrace);
      OperationLogService.instance.record(
        '作品保存失败',
        screen: '作品编辑',
        details: {'mode': _isEditMode ? 'edit' : 'create'},
      );
      if (mounted) {
        final message = "保存失败: $e";
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(message)));
        setState(() {
          _isSaving = false;
          _formErrorMessage = message;
        });
      }
    }
    // 成功跳转会自动销毁页面，无需在此处重置 _isSaving
  }

  String get _subjectLabel => _subjectType == 'anime' ? '番剧' : '小说/漫画';

  Future<void> _openEditorDisplaySettings() async {
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      showDragHandle: true,
      constraints: MediaQuery.sizeOf(context).width >= AppBreakpoints.medium
          ? const BoxConstraints(maxWidth: 760)
          : null,
      builder: (_) => AnimeEditorDisplaySheet(controller: _displayController),
    );
  }

  Widget _buildEditorBody(PageDisplayConfig config, Color primaryColor) {
    if (_isInitializing) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_initializationError != null) {
      return EmptyStateWidget(
        icon: Icons.edit_note_rounded,
        message: '编辑页面加载失败',
        description: _initializationError,
        buttonText: '重新加载',
        onButtonPressed: _loadEditorData,
      );
    }

    final modules = config.moduleOrder
        .where(
          (key) =>
              key == AnimeEditorModule.basic.persistKey ||
              !config.hiddenModules.contains(key),
        )
        .map(AnimeEditorModule.fromPersistKey)
        .whereType<AnimeEditorModule>()
        .where(
          (module) =>
              module != AnimeEditorModule.related || _seriesSiblings.isNotEmpty,
        )
        .toList(growable: false);
    final gap = switch (config.density) {
      DisplayDensity.compact => AppSpacing.md,
      DisplayDensity.comfortable => AppSpacing.lg,
      DisplayDensity.relaxed => AppSpacing.xl,
    };

    return GestureDetector(
      onTap: () => FocusScope.of(context).unfocus(),
      child: SingleChildScrollView(
        keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag,
        child: AdaptiveContentFrame(
          maxContentWidth: 1180,
          top: AppSpacing.lg,
          bottom: AppSpacing.xxl,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              _buildEditorModeBanner(config),
              SizedBox(height: gap),
              LayoutBuilder(
                builder: (context, constraints) {
                  final wide = constraints.maxWidth >= 900;
                  final halfWidth =
                      (constraints.maxWidth - gap) / (wide ? 2 : 1);
                  return Wrap(
                    spacing: gap,
                    runSpacing: gap,
                    children: modules.map((module) {
                      final fullWidth =
                          !wide ||
                          module == AnimeEditorModule.basic ||
                          module == AnimeEditorModule.related;
                      return SizedBox(
                        width: fullWidth ? constraints.maxWidth : halfWidth,
                        child: _buildEditorModule(module, config, primaryColor),
                      );
                    }).toList(),
                  );
                },
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildEditorModeBanner(PageDisplayConfig config) {
    final colorScheme = Theme.of(context).colorScheme;
    final preset = selectedAnimeEditorPreset(config);
    return Material(
      color: colorScheme.primaryContainer,
      borderRadius: BorderRadius.circular(AppRadius.sm),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.sm),
        onTap: _openEditorDisplaySettings,
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg,
            vertical: AppSpacing.md,
          ),
          child: Row(
            children: [
              Icon(
                preset == AnimeEditorPreset.simple
                    ? Icons.bolt_rounded
                    : Icons.tune_rounded,
                color: colorScheme.onPrimaryContainer,
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      preset?.label ?? '自定义编辑视图',
                      style: TextStyle(
                        color: colorScheme.onPrimaryContainer,
                        fontWeight: FontWeight.w900,
                      ),
                    ),
                    Text(
                      preset?.description ?? '已按你的偏好调整模块和信息密度',
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: colorScheme.onPrimaryContainer.withValues(
                          alpha: 0.78,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Icon(
                Icons.chevron_right_rounded,
                color: colorScheme.onPrimaryContainer,
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildEditorModule(
    AnimeEditorModule module,
    PageDisplayConfig config,
    Color primaryColor,
  ) {
    final padding = switch (config.density) {
      DisplayDensity.compact => AppSpacing.md,
      DisplayDensity.comfortable => 20.0,
      DisplayDensity.relaxed => AppSpacing.xl,
    };
    switch (module) {
      case AnimeEditorModule.basic:
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _buildSubjectTypeSelector(),
            const SizedBox(height: AppSpacing.md),
            _buildHeaderCard(
              primaryColor,
              showAdvanced: animeEditorShowAdvancedHeader(config),
            ),
          ],
        );
      case AnimeEditorModule.progress:
        return _buildProgressCard(primaryColor);
      case AnimeEditorModule.tags:
        return NeumorphicContainer(
          padding: EdgeInsets.all(padding),
          child: _buildTagSection(),
        );
      case AnimeEditorModule.details:
        return _buildDetailSection(primaryColor);
      case AnimeEditorModule.reminder:
        return _buildReminderCard(primaryColor);
      case AnimeEditorModule.related:
        return _buildSeriesNavigationSection(primaryColor);
    }
  }

  Widget _buildSubjectTypeSelector() {
    final colorScheme = Theme.of(context).colorScheme;
    return Card(
      margin: EdgeInsets.zero,
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Row(
          children: [
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    '作品类型',
                    style: Theme.of(context).textTheme.titleSmall?.copyWith(
                      fontWeight: FontWeight.w900,
                    ),
                  ),
                  Text(
                    _isEditMode ? '已收录作品不能直接更改类型' : '字段名称会根据类型自动调整',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: colorScheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: AppSpacing.md),
            AbsorbPointer(
              absorbing: _isEditMode,
              child: Opacity(
                opacity: _isEditMode ? 0.62 : 1,
                child: SegmentedButton<String>(
                  segments: const [
                    ButtonSegment(
                      value: 'anime',
                      label: Text('番剧'),
                      icon: Icon(Icons.movie_outlined),
                    ),
                    ButtonSegment(
                      value: 'book',
                      label: Text('小说/漫画'),
                      icon: Icon(Icons.menu_book_outlined),
                    ),
                  ],
                  selected: {_subjectType},
                  showSelectedIcon: false,
                  onSelectionChanged: (selection) {
                    _updateEditorState(() => _subjectType = selection.first);
                  },
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSaveBar(bool hasChanges) {
    final colorScheme = Theme.of(context).colorScheme;
    final isNewRecord = !_isEditMode;
    final canSave =
        !_isSaving && !_isInitializing && (isNewRecord || hasChanges);
    final message =
        _formErrorMessage ??
        (_isSaving
            ? '正在保存，请稍候…'
            : hasChanges
            ? '有尚未保存的更改'
            : isNewRecord
            ? '填写必要信息后即可加入资料库'
            : '当前内容已保存');
    final messageColor = _formErrorMessage != null
        ? colorScheme.error
        : colorScheme.onSurfaceVariant;
    final statusIcon = _formErrorMessage != null
        ? Icons.error_outline_rounded
        : _isSaving
        ? Icons.sync_rounded
        : hasChanges
        ? Icons.edit_note_rounded
        : Icons.check_circle_outline_rounded;

    return Material(
      key: const ValueKey('anime-editor-save-bar'),
      color: colorScheme.surfaceContainer,
      elevation: 8,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg,
            vertical: AppSpacing.sm,
          ),
          child: Center(
            // Scaffold 会用“最大可到整页高度”的宽松约束测量
            // bottomNavigationBar。显式收缩高度，避免保存栏铺满并遮住页面。
            heightFactor: 1,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 1180),
              child: Row(
                children: [
                  Icon(statusIcon, size: 20, color: messageColor),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(
                    child: Text(
                      message,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: messageColor,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  const SizedBox(width: AppSpacing.md),
                  FilledButton.icon(
                    onPressed: canSave ? _saveAnime : null,
                    icon: _isSaving
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.save_outlined),
                    label: Text(_isEditMode ? '保存更改' : '加入资料库'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false, // 拦截直接退出
      onPopInvokedWithResult: (didPop, result) async {
        if (didPop) return;

        // 【最高优先级】如果是“发现”或“资料库搜索”进来的预览模式，且尚未入库，则不论是否有更改都直接退出
        if (widget.isDiscoveryMode && !_isEditMode) {
          Navigator.of(context).pop();
          return;
        }

        // 如果正在保存，或者没有变化，则允许退出
        if (_isSaving || !_hasUnsavedChanges()) {
          Navigator.of(context).pop();
          return;
        }

        // 检查设置：是否开启了自动保存或直接放弃
        final settings = SettingsManager();
        if (settings.autoSaveDetailNotifier.value) {
          // 自动保存前验证
          if (await _validateReminderSettings()) {
            _saveAnime();
          }
          return;
        }
        if (settings.discardDetailChangesNotifier.value) {
          Navigator.of(context).pop(); // 直接放弃并退出
          return;
        }

        // 显示带“记住选择”的确认对话框
        bool rememberChoice = false;
        final String? action = await showDialog<String>(
          context: context,
          builder: (ctx) => StatefulBuilder(
            builder: (ctx, setDialogState) {
              return AlertDialog(
                title: const Text('确认退出？'),
                content: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Text('内容已修改，您可以选择保存后再退出。'),
                    const SizedBox(height: 16),
                    CheckboxListTile(
                      contentPadding: EdgeInsets.zero,
                      title: const Text(
                        '以后不再提示，按本次操作执行',
                        style: TextStyle(fontSize: 14),
                      ),
                      value: rememberChoice,
                      onChanged: (v) =>
                          setDialogState(() => rememberChoice = v!),
                      controlAffinity: ListTileControlAffinity.leading,
                    ),
                    const SizedBox(height: 8),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: Text(
                        '💡 此设置可在 设置 -> 通用管理 中修改',
                        style: TextStyle(fontSize: 11, color: Colors.grey[500]),
                      ),
                    ),
                  ],
                ),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.pop(ctx, 'cancel'),
                    child: const Text('取消'),
                  ),
                  TextButton(
                    onPressed: () => Navigator.pop(ctx, 'discard'),
                    style: TextButton.styleFrom(foregroundColor: Colors.red),
                    child: const Text('放弃更改'),
                  ),
                  FilledButton(
                    onPressed: () => Navigator.pop(ctx, 'save'),
                    child: const Text('保存并退出'),
                  ),
                ],
              );
            },
          ),
        );

        if (action == 'discard' && context.mounted) {
          if (rememberChoice) {
            settings.setShowItem('detail_discard_changes', true);
            settings.setShowItem('detail_auto_save', false);
          }
          Navigator.of(context).pop();
        } else if (action == 'save' && context.mounted) {
          if (rememberChoice) {
            settings.setShowItem('detail_auto_save', true);
            settings.setShowItem('detail_discard_changes', false);
          }
          // 确认保存前验证
          if (await _validateReminderSettings()) {
            _saveAnime();
          }
        }
      },
      child: AnimatedBuilder(
        animation: _displayController,
        builder: (context, _) {
          final config = _displayController.value;
          final primaryColor = Theme.of(context).colorScheme.primary;
          final hasChanges =
              !_isInitializing && _hasUnsavedChanges(logChanges: false);
          final preset = selectedAnimeEditorPreset(config);
          return Scaffold(
            appBar: AppBar(
              title: Row(
                children: [
                  Icon(
                    _subjectType == 'anime'
                        ? Icons.movie_filter_outlined
                        : Icons.menu_book_rounded,
                    size: 22,
                    color: primaryColor,
                  ),
                  const SizedBox(width: AppSpacing.sm),
                  Expanded(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '${_isEditMode ? '编辑' : '添加'}$_subjectLabel',
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        Text(
                          '${preset?.label ?? '自定义视图'} · ${hasChanges
                              ? '未保存'
                              : _isEditMode
                              ? '已同步'
                              : '待加入'}',
                          style: Theme.of(context).textTheme.labelSmall
                              ?.copyWith(
                                color: Theme.of(
                                  context,
                                ).colorScheme.onSurfaceVariant,
                              ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
              actions: [
                IconButton(
                  tooltip: '定制编辑页面',
                  onPressed: _openEditorDisplaySettings,
                  icon: const Icon(Icons.tune_rounded),
                ),
                const SizedBox(width: AppSpacing.sm),
              ],
            ),
            body: _buildEditorBody(config, primaryColor),
            bottomNavigationBar: _buildSaveBar(hasChanges),
          );
        },
      ),
    );
  }

  // --- 系列相关方法 ---

  void _loadSeriesName(int id) async {
    final db = await DatabaseHelper().database;
    final List<Map<String, dynamic>> maps = await db.query(
      'series',
      where: 'id = ?',
      whereArgs: [id],
    );
    if (maps.isNotEmpty && mounted) {
      setState(() {
        _seriesName = maps.first['name'];
      });
    }
  }

  void _selectSeries() async {
    final int? result = await showDialog<int?>(
      context: context,
      builder: (context) => SeriesSelectionDialog(initialSeriesId: _seriesId),
    );

    // result 可以是 null (代表用户选择了"无")，或者是具体的 ID
    // 只有当用户点击了确定(返回了值)才更新，如果用户点击取消(返回null但不是"无"的含义，通常 Dialog dismiss 是 null)，
    // 但这里 SeriesSelectionDialog 如果点击取消应该返回 null? 区分一下?
    // 我们的 SeriesSelectionDialog 取消是 pop(context), 返回 null.
    // 确定是 pop(context, _selectedSeriesId). _selectedSeriesId 可以是 null (无).
    // 所以我们需要区分 "取消" 和 "选择了无".
    // 可以在 Dialog 中取消返回 null, 确定返回 (int?)value.
    // 但 showDialog 本身 dismiss 也是 null.
    // 让 SeriesSelectionDialog 在取消时返回一个特殊值? 或者 just check if state changed?
    // 简单点: SeriesSelectionDialog 返回值:
    // null -> Cancelled (不做改变)
    // -1 -> Selected "None"
    // >0 -> Selected Series ID
    // 我需要修改 SeriesSelectionDialog 吗？
    // 之前的代码: Navigator.pop(context, _selectedSeriesId);
    // 如果 _selectedSeriesId 是 null, 返回 null. 与取消混淆.
    // 我应该修改 SeriesSelectionDialog 让 "无" 返回 -1.

    // 既然还没改 Dialog，由于我不能同时改两个文件 (multi_replace restricts to one file),
    // 我先假设 Dialog 返回值逻辑:
    // 实际上 SeriesSelectionDialog 代码里:
    // onPress Cancel -> pop(context) -> result is null
    // onPress Confirm -> pop(context, id) -> id can be null.
    // 这确实有歧义.
    // 我在 AddAnimePage 里重写 _selectSeries 的时候，暂时先假设:
    // 如果 result == null, 暂时认为是取消? 不，那样无法取消系列.
    // 我应该先去修正 SeriesSelectionDialog.

    // 但我可以稍微 hack 一下，让 SeriesSelectionDialog 总是返回一个对象? 比如 Wrapper.
    // 或者我现在就相信 result.

    // 修正 plan:
    // 1. 修改 SeriesSelectionDialog 返回 -1 代表 None. (需要另一次 tool call, 或者我先 update AddAnimePage 逻辑兼容它)

    // 让我们先用 result, 如果是 null 且 _selectedSeriesId 也是 null (initial), 没变化.
    // 无论如何，为了正确实现，我应该先修改 SeriesSelectionDialog.
    // 这里先写上 UI 代码，稍后(下一个步骤)去修 Dialog.

    if (result != null) {
      setState(() {
        if (result == -1) {
          _seriesId = null;
          _seriesName = null;
          _seriesSiblings = [];
        } else {
          _seriesId = result;
          _loadSeriesName(result);
          _loadSeriesSiblings();
        }
      });
    }
  }

  void _loadSeriesSiblings() async {
    if (_seriesId == null) return;
    final db = await DatabaseHelper().database;
    final List<Map<String, dynamic>> results = await db.query(
      'animes',
      where: 'series_id = ? AND id != ?',
      whereArgs: [_seriesId, widget.existingAnime?['id'] ?? -1],
      orderBy: 'air_date ASC',
    );
    if (mounted) {
      setState(() {
        _seriesSiblings = results;
      });
    }
  }

  // ============== 子组件构建方法 ==============

  /// 确认并彻底删除标签
  void _confirmDeleteTag(Map<String, dynamic> tag) {
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('彻底删除标签？'),
        content: Text('确认要彻底删除标签 "${tag['name']}" 吗？\n该操作会影响所有已贴过此标签的番剧。'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(ctx);
              final tagId = tag['id'] as int;
              await DatabaseHelper().deleteTag(tagId);
              if (!mounted) return;
              setState(() {
                _selectedTagIds.remove(tagId);
                // 显式创建新列表副本以确保 UI 刷新
                _allTags = List.from(_allTags)
                  ..removeWhere((t) => t['id'] == tagId);
              });
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(content: Text('标签 "${tag['name']}" 已删除')),
              );
            },
            style: TextButton.styleFrom(foregroundColor: Colors.red),
            child: const Text('确认删除'),
          ),
        ],
      ),
    );
  }

  InputDecoration _buildInputDecoration(
    String label,
    IconData icon,
    Color color,
  ) {
    final colorScheme = Theme.of(context).colorScheme;
    return InputDecoration(
      labelText: label,
      prefixIcon: Icon(icon, size: 20, color: color.withValues(alpha: 0.6)),
      filled: true,
      fillColor: colorScheme.surfaceContainerHighest,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(12),
        borderSide: BorderSide.none,
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      labelStyle: const TextStyle(fontSize: 13),
    );
  }

  void _previewCover() {
    if (_coverUrl == null) return;
    Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => ImageViewerPage(
          imageUrl: _coverUrl!,
          title: _titleController.text.isEmpty ? '预览封面' : _titleController.text,
          onModify: _pickLocalImage,
        ),
      ),
    );
  }

  /// 处理完成逻辑
  void _handleCompletion() {
    // 自动记录完成到日历
    if (widget.existingAnime != null) {
      DatabaseHelper().insertWatchRecord(
        animeId: widget.existingAnime!['id'],
        episode: int.tryParse(_totalController.text) ?? 0,
        status: 'completed',
      );
    }

    final settings = SettingsManager();
    if (settings.autoStatusTransitionNotifier.value) {
      final targetStatus = settings.completionStatusNotifier.value;
      if (_status != targetStatus) {
        setState(() {
          _status = targetStatus;
          // 如果目标是“看完”，且结束日期未设置，则自动设为今天
          if (targetStatus == '看完' && _watchFinishDate == null) {
            _watchFinishDate = DateTime.now();
          }
        });

        ScaffoldMessenger.of(context).hideCurrentSnackBar();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('已归纳至 "$targetStatus"'),
            duration: const Duration(seconds: 2),
          ),
        );
      }
    } else {
      ScaffoldMessenger.of(context).hideCurrentSnackBar();
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('已达到最大集数')));
    }
  }

  // --- 新增：放送时间选择器 ---
}
