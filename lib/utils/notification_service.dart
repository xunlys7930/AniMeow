import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:timezone/data/latest_all.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

enum ReminderAvailability { ready, permissionDenied, unsupported, error }

@immutable
class ReminderPermissionResult {
  final bool granted;
  final String message;

  const ReminderPermissionResult({
    required this.granted,
    required this.message,
  });
}

@immutable
class ReminderServiceStatus {
  final ReminderAvailability availability;
  final String message;
  final String timeZoneName;
  final int pendingTaskCount;

  const ReminderServiceStatus({
    required this.availability,
    required this.message,
    required this.timeZoneName,
    required this.pendingTaskCount,
  });

  bool get isReady => availability == ReminderAvailability.ready;
}

@immutable
class ReminderScheduleResult {
  final DateTime nextOccurrence;
  final int systemTaskCount;

  const ReminderScheduleResult({
    required this.nextOccurrence,
    required this.systemTaskCount,
  });
}

@immutable
class ReminderSyncResult {
  final int scheduledReminderCount;
  final int systemTaskCount;
  final List<String> failures;

  const ReminderSyncResult({
    required this.scheduledReminderCount,
    required this.systemTaskCount,
    required this.failures,
  });

  bool get isSuccess => failures.isEmpty;
}

class ReminderSchedulingException implements Exception {
  final String message;

  const ReminderSchedulingException(this.message);

  @override
  String toString() => message;
}

class NotificationService {
  static const int _testNotificationId = 900000001;
  static const int _windowsNotificationIdBase = 100000000;
  static const int _windowsOccurrenceSlots = 16;
  static const int _defaultWindowsOccurrenceCount = 8;
  static const int _maxWindowsPendingTasks = 1024;

  static final NotificationService _instance = NotificationService._internal();

  factory NotificationService() => _instance;

  NotificationService._internal();

  final FlutterLocalNotificationsPlugin _notificationsPlugin =
      FlutterLocalNotificationsPlugin();

  Future<void>? _initializationFuture;
  bool _initialized = false;
  Object? _initializationError;
  String _timeZoneName = 'UTC';

  bool get isSupported {
    if (kIsWeb) return false;
    return Platform.isAndroid ||
        Platform.isIOS ||
        Platform.isMacOS ||
        Platform.isWindows;
  }

  String get timeZoneName => _timeZoneName;

  Future<void> init() async {
    if (_initialized) return;
    final inFlight = _initializationFuture;
    if (inFlight != null) return inFlight;

    final future = _initialize();
    _initializationFuture = future;
    try {
      await future;
      _initialized = true;
      _initializationError = null;
    } catch (error) {
      _initializationError = error;
      rethrow;
    } finally {
      _initializationFuture = null;
    }
  }

  Future<void> _initialize() async {
    tz.initializeTimeZones();
    await _configureLocalTimeZone();
    if (!isSupported) return;

    const darwinSettings = DarwinInitializationSettings(
      requestAlertPermission: false,
      requestBadgePermission: false,
      requestSoundPermission: false,
    );
    const initializationSettings = InitializationSettings(
      android: AndroidInitializationSettings('@mipmap/ic_launcher'),
      iOS: darwinSettings,
      macOS: darwinSettings,
      windows: WindowsInitializationSettings(
        appName: '追番喵',
        appUserModelId: 'com.tanpeng.anime_tracker.app',
        guid: '79f64e0a-cd62-4f01-90a4-d1d86d5e75c6',
      ),
    );

    final initialized = await _notificationsPlugin.initialize(
      settings: initializationSettings,
      onDidReceiveNotificationResponse: (response) {
        debugPrint('通知点击: ${response.payload}');
      },
    );
    if (initialized == false) {
      throw const ReminderSchedulingException('系统通知服务初始化失败');
    }
  }

  Future<void> _configureLocalTimeZone() async {
    try {
      final timeZone = await FlutterTimezone.getLocalTimezone();
      final location = tz.getLocation(timeZone.identifier);
      tz.setLocalLocation(location);
      _timeZoneName = location.name;
      return;
    } catch (error) {
      debugPrint('读取系统时区失败，使用偏移量兜底: $error');
    }

    final fallbackName = _fallbackTimeZoneName(DateTime.now().timeZoneOffset);
    final fallback = tz.getLocation(fallbackName);
    tz.setLocalLocation(fallback);
    _timeZoneName = fallback.name;
  }

  String _fallbackTimeZoneName(Duration offset) {
    return switch (offset.inHours) {
      8 => 'Asia/Shanghai',
      9 => 'Asia/Tokyo',
      10 => 'Australia/Brisbane',
      -8 => 'America/Los_Angeles',
      -7 => 'America/Denver',
      -6 => 'America/Chicago',
      -5 => 'America/New_York',
      0 => 'UTC',
      _ => 'UTC',
    };
  }

  Future<ReminderPermissionResult> requestPermissions() async {
    if (!isSupported) {
      return const ReminderPermissionResult(
        granted: false,
        message: '当前平台暂不支持系统定时通知',
      );
    }
    try {
      await init();
      if (Platform.isAndroid) {
        final android = _notificationsPlugin
            .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin
            >();
        await android?.requestNotificationsPermission();
        final enabled = await android?.areNotificationsEnabled() ?? true;
        return ReminderPermissionResult(
          granted: enabled,
          message: enabled ? '通知权限已开启' : '通知权限未开启，请在系统设置中允许通知',
        );
      }
      if (Platform.isIOS) {
        final granted =
            await _notificationsPlugin
                .resolvePlatformSpecificImplementation<
                  IOSFlutterLocalNotificationsPlugin
                >()
                ?.requestPermissions(alert: true, badge: true, sound: true) ??
            false;
        return ReminderPermissionResult(
          granted: granted,
          message: granted ? '通知权限已开启' : '通知权限未开启，请在系统设置中允许通知',
        );
      }
      if (Platform.isMacOS) {
        final granted =
            await _notificationsPlugin
                .resolvePlatformSpecificImplementation<
                  MacOSFlutterLocalNotificationsPlugin
                >()
                ?.requestPermissions(alert: true, badge: true, sound: true) ??
            false;
        return ReminderPermissionResult(
          granted: granted,
          message: granted ? '通知权限已开启' : '通知权限未开启，请在系统设置中允许通知',
        );
      }
      return const ReminderPermissionResult(
        granted: true,
        message: 'Windows 通知服务已就绪',
      );
    } catch (error) {
      return ReminderPermissionResult(
        granted: false,
        message: describeError(error),
      );
    }
  }

  Future<ReminderPermissionResult> currentPermission() async {
    if (!isSupported) {
      return const ReminderPermissionResult(
        granted: false,
        message: '当前平台暂不支持系统定时通知',
      );
    }
    try {
      await init();
      if (Platform.isAndroid) {
        final enabled =
            await _notificationsPlugin
                .resolvePlatformSpecificImplementation<
                  AndroidFlutterLocalNotificationsPlugin
                >()
                ?.areNotificationsEnabled() ??
            true;
        return ReminderPermissionResult(
          granted: enabled,
          message: enabled ? '通知权限已开启' : '通知权限未开启',
        );
      }
      if (Platform.isIOS) {
        final options = await _notificationsPlugin
            .resolvePlatformSpecificImplementation<
              IOSFlutterLocalNotificationsPlugin
            >()
            ?.checkPermissions();
        final enabled = options?.isEnabled ?? false;
        return ReminderPermissionResult(
          granted: enabled,
          message: enabled ? '通知权限已开启' : '通知权限未开启',
        );
      }
      if (Platform.isMacOS) {
        final options = await _notificationsPlugin
            .resolvePlatformSpecificImplementation<
              MacOSFlutterLocalNotificationsPlugin
            >()
            ?.checkPermissions();
        final enabled = options?.isEnabled ?? false;
        return ReminderPermissionResult(
          granted: enabled,
          message: enabled ? '通知权限已开启' : '通知权限未开启',
        );
      }
      return const ReminderPermissionResult(
        granted: true,
        message: 'Windows 通知服务已就绪',
      );
    } catch (error) {
      return ReminderPermissionResult(
        granted: false,
        message: describeError(error),
      );
    }
  }

  Future<ReminderServiceStatus> getStatus() async {
    if (!isSupported) {
      return ReminderServiceStatus(
        availability: ReminderAvailability.unsupported,
        message: '当前平台暂不支持系统定时通知',
        timeZoneName: _timeZoneName,
        pendingTaskCount: 0,
      );
    }
    try {
      await init();
      final permission = await currentPermission();
      var pendingCount = 0;
      try {
        pendingCount =
            (await _notificationsPlugin.pendingNotificationRequests()).length;
      } catch (error) {
        debugPrint('读取待执行通知失败: $error');
      }
      if (!permission.granted) {
        return ReminderServiceStatus(
          availability: ReminderAvailability.permissionDenied,
          message: permission.message,
          timeZoneName: _timeZoneName,
          pendingTaskCount: pendingCount,
        );
      }
      return ReminderServiceStatus(
        availability: ReminderAvailability.ready,
        message: Platform.isWindows
            ? 'Windows 会预排未来数周，并在每次启动时自动续期'
            : '系统通知与每周排程均已就绪',
        timeZoneName: _timeZoneName,
        pendingTaskCount: pendingCount,
      );
    } catch (error) {
      return ReminderServiceStatus(
        availability: ReminderAvailability.error,
        message: describeError(_initializationError ?? error),
        timeZoneName: _timeZoneName,
        pendingTaskCount: 0,
      );
    }
  }

  Future<ReminderScheduleResult> scheduleWeeklyNotification({
    required int id,
    required String title,
    required String body,
    required int day,
    required TimeOfDay time,
    int windowsOccurrenceCount = _defaultWindowsOccurrenceCount,
  }) async {
    await init();
    final permission = await currentPermission();
    if (!permission.granted) {
      throw ReminderSchedulingException(permission.message);
    }
    return _scheduleWeeklyNotification(
      id: id,
      title: title,
      body: body,
      day: day,
      time: time,
      windowsOccurrenceCount: windowsOccurrenceCount,
    );
  }

  Future<ReminderScheduleResult> _scheduleWeeklyNotification({
    required int id,
    required String title,
    required String body,
    required int day,
    required TimeOfDay time,
    required int windowsOccurrenceCount,
  }) async {
    if (id < 0) {
      throw const ReminderSchedulingException('提醒记录 ID 无效');
    }
    final nextOccurrence = nextInstanceOfDayAndTime(day: day, time: time);
    await cancelNotification(id);

    if (Platform.isWindows) {
      final count = windowsOccurrenceCount.clamp(1, _windowsOccurrenceSlots);
      try {
        for (var index = 0; index < count; index++) {
          await _notificationsPlugin.zonedSchedule(
            id: windowsOccurrenceNotificationId(id, index),
            title: title,
            body: body,
            payload: 'anime:$id',
            scheduledDate: nextOccurrence.add(Duration(days: index * 7)),
            notificationDetails: _notificationDetails,
            androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
          );
        }
      } catch (error) {
        await _cancelWindowsOccurrences(id);
        throw ReminderSchedulingException(describeError(error));
      }
      return ReminderScheduleResult(
        nextOccurrence: nextOccurrence,
        systemTaskCount: count,
      );
    }

    try {
      await _notificationsPlugin.zonedSchedule(
        id: id,
        title: title,
        body: body,
        payload: 'anime:$id',
        scheduledDate: nextOccurrence,
        notificationDetails: _notificationDetails,
        androidScheduleMode: AndroidScheduleMode.inexactAllowWhileIdle,
        matchDateTimeComponents: DateTimeComponents.dayOfWeekAndTime,
      );
    } catch (error) {
      throw ReminderSchedulingException(describeError(error));
    }
    return ReminderScheduleResult(
      nextOccurrence: nextOccurrence,
      systemTaskCount: 1,
    );
  }

  Future<void> cancelNotification(int id) async {
    if (!isSupported) return;
    await init();
    await _notificationsPlugin.cancel(id: id);
    if (Platform.isWindows) await _cancelWindowsOccurrences(id);
  }

  Future<void> _cancelWindowsOccurrences(int animeId) async {
    for (var index = 0; index < _windowsOccurrenceSlots; index++) {
      await _notificationsPlugin.cancel(
        id: windowsOccurrenceNotificationId(animeId, index),
      );
    }
  }

  Future<void> showTestNotification() async {
    await init();
    final permission = await currentPermission();
    if (!permission.granted) {
      throw ReminderSchedulingException(permission.message);
    }
    await _notificationsPlugin.show(
      id: _testNotificationId,
      title: '追番提醒测试',
      body: '如果你看到这条通知，说明系统通知链路已经正常工作。',
      payload: 'reminder:test',
      notificationDetails: _notificationDetails,
    );
  }

  Future<ReminderSyncResult> synchronizeStoredReminders(
    Iterable<Map<String, dynamic>> records,
  ) async {
    if (!isSupported) {
      return const ReminderSyncResult(
        scheduledReminderCount: 0,
        systemTaskCount: 0,
        failures: ['当前平台暂不支持系统定时通知'],
      );
    }
    await init();
    final permission = await currentPermission();
    if (!permission.granted) {
      return ReminderSyncResult(
        scheduledReminderCount: 0,
        systemTaskCount: 0,
        failures: [permission.message],
      );
    }

    final validRecords = records.where(_isValidReminderRecord).toList();
    final windowsOccurrenceCount = Platform.isWindows && validRecords.isNotEmpty
        ? (_maxWindowsPendingTasks ~/ validRecords.length).clamp(
            1,
            _defaultWindowsOccurrenceCount,
          )
        : 1;
    final failures = <String>[];
    var reminderCount = 0;
    var taskCount = 0;

    await _clearPendingSchedules();
    for (final record in validRecords) {
      final id = record['id'] as int;
      final title = (record['title'] ?? '未命名作品').toString();
      final subjectType = (record['subject_type'] ?? 'anime').toString();
      final time = parseReminderTime(record['reminder_time']?.toString());
      try {
        final result = await _scheduleWeeklyNotification(
          id: id,
          title: subjectType == 'anime' ? '追番提醒' : '阅读提醒',
          body: subjectType == 'anime'
              ? '您追的番剧《$title》今天更新啦，快去看看吧！'
              : '《$title》到了计划阅读的时间，来记录一下进度吧！',
          day: record['reminder_day'] as int,
          time: time!,
          windowsOccurrenceCount: windowsOccurrenceCount,
        );
        reminderCount++;
        taskCount += result.systemTaskCount;
      } catch (error) {
        failures.add('《$title》：${describeError(error)}');
      }
    }
    return ReminderSyncResult(
      scheduledReminderCount: reminderCount,
      systemTaskCount: taskCount,
      failures: failures,
    );
  }

  Future<void> _clearPendingSchedules() async {
    try {
      final pending = await _notificationsPlugin.pendingNotificationRequests();
      for (final request in pending) {
        if (request.id == _testNotificationId) continue;
        await _notificationsPlugin.cancel(id: request.id);
      }
    } catch (error) {
      debugPrint('清理旧提醒排程失败，将继续覆盖已有任务: $error');
    }
  }

  bool _isValidReminderRecord(Map<String, dynamic> record) {
    final id = record['id'];
    final day = record['reminder_day'];
    final time = parseReminderTime(record['reminder_time']?.toString());
    return id is int &&
        id >= 0 &&
        day is int &&
        day >= 1 &&
        day <= 7 &&
        time != null;
  }

  tz.TZDateTime nextInstanceOfDayAndTime({
    required int day,
    required TimeOfDay time,
    tz.TZDateTime? now,
  }) {
    if (day < DateTime.monday || day > DateTime.sunday) {
      throw const ReminderSchedulingException('提醒日期必须是周一到周日');
    }
    final current = now ?? tz.TZDateTime.now(tz.local);
    var scheduledDate = tz.TZDateTime(
      current.location,
      current.year,
      current.month,
      current.day,
      time.hour,
      time.minute,
    );
    while (scheduledDate.weekday != day) {
      scheduledDate = scheduledDate.add(const Duration(days: 1));
    }
    if (!scheduledDate.isAfter(current)) {
      scheduledDate = scheduledDate.add(const Duration(days: 7));
    }
    return scheduledDate;
  }

  static TimeOfDay? parseReminderTime(String? value) {
    if (value == null || value.trim().isEmpty) return null;
    final parts = value.trim().split(':');
    if (parts.length != 2) return null;
    final hour = int.tryParse(parts[0]);
    final minute = int.tryParse(parts[1]);
    if (hour == null ||
        minute == null ||
        hour < 0 ||
        hour > 23 ||
        minute < 0 ||
        minute > 59) {
      return null;
    }
    return TimeOfDay(hour: hour, minute: minute);
  }

  @visibleForTesting
  static int windowsOccurrenceNotificationId(int animeId, int occurrence) {
    if (occurrence < 0 || occurrence >= _windowsOccurrenceSlots) {
      throw RangeError.range(
        occurrence,
        0,
        _windowsOccurrenceSlots - 1,
        'occurrence',
      );
    }
    final normalizedAnimeId = animeId.abs() % 1000000;
    return _windowsNotificationIdBase +
        normalizedAnimeId * _windowsOccurrenceSlots +
        occurrence;
  }

  String describeError(Object error) {
    final text = error.toString().replaceFirst('Exception: ', '');
    if (text.contains('exact_alarms_not_permitted') ||
        text.contains('exact alarms')) {
      return '系统不允许精确闹钟；请重新同步提醒，应用会改用兼容排程';
    }
    if (text.contains('notifications are disabled') ||
        text.contains('permission')) {
      return '系统通知权限未开启';
    }
    if (text.contains('not been initialized')) {
      return '系统通知服务尚未初始化';
    }
    return text;
  }

  static const NotificationDetails _notificationDetails = NotificationDetails(
    android: AndroidNotificationDetails(
      'anime_update_channel_v2',
      '追番与阅读提醒',
      channelDescription: '在你设定的每周时间提醒观看或阅读作品',
      importance: Importance.high,
      priority: Priority.high,
      category: AndroidNotificationCategory.reminder,
      enableVibration: true,
      playSound: true,
    ),
    iOS: DarwinNotificationDetails(
      presentAlert: true,
      presentBadge: true,
      presentSound: true,
    ),
    macOS: DarwinNotificationDetails(
      presentAlert: true,
      presentBadge: true,
      presentSound: true,
    ),
    windows: WindowsNotificationDetails(),
  );
}
