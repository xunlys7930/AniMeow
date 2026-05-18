import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:timezone/data/latest_all.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

class NotificationService {
  static final NotificationService _instance = NotificationService._internal();
  factory NotificationService() => _instance;
  NotificationService._internal();

  final FlutterLocalNotificationsPlugin _notificationsPlugin =
      FlutterLocalNotificationsPlugin();

  Future<void> init() async {
    tz.initializeTimeZones();

    const AndroidInitializationSettings initializationSettingsAndroid =
        AndroidInitializationSettings('@mipmap/ic_launcher');

    const DarwinInitializationSettings initializationSettingsIOS =
        DarwinInitializationSettings(
          requestAlertPermission: true,
          requestBadgePermission: true,
          requestSoundPermission: true,
        );

    // Windows 平台特定配置
    const WindowsInitializationSettings initializationSettingsWindows =
        WindowsInitializationSettings(
          appName: '追番喵',
          appUserModelId: 'com.tanpeng.anime_tracker.app',
          guid: '79f64e0a-cd62-4f01-90a4-d1d86d5e75c6',
        );

    const InitializationSettings initializationSettings =
        InitializationSettings(
          android: initializationSettingsAndroid,
          iOS: initializationSettingsIOS,
          windows: initializationSettingsWindows,
        );

    await _notificationsPlugin.initialize(
      settings: initializationSettings,
      onDidReceiveNotificationResponse: (NotificationResponse response) {
        debugPrint("通知点击: ${response.payload}");
      },
    );
  }

  /// 调度每周提醒
  Future<void> scheduleWeeklyNotification({
    required int id,
    required String title,
    required String body,
    required int day, // 1-7 (周一到周日)
    required TimeOfDay time,
  }) async {
    debugPrint("调度每周提醒: id=$id, day=$day, time=$time");
    try {
      await _notificationsPlugin.zonedSchedule(
        id: id,
        title: title,
        body: body,
        scheduledDate: _nextInstanceOfDayAndTime(day, time),
        notificationDetails: const NotificationDetails(
          android: AndroidNotificationDetails(
            'anime_update_channel',
            '追番提醒',
            channelDescription: '提醒你观看本周更新的番剧',
            importance: Importance.max,
            priority: Priority.high,
          ),
          iOS: DarwinNotificationDetails(),
          windows: WindowsNotificationDetails(),
        ),
        androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
        matchDateTimeComponents: DateTimeComponents.dayOfWeekAndTime,
      );
    } catch (e) {
      debugPrint("调度通知失败: $e");
    }
  }

  /// 取消特定通知
  Future<void> cancelNotification(int id) async {
    debugPrint("取消通知: id=$id");
    await _notificationsPlugin.cancel(id: id);
  }

  tz.TZDateTime _nextInstanceOfDayAndTime(int day, TimeOfDay time) {
    final tz.TZDateTime now = tz.TZDateTime.now(tz.local);

    tz.TZDateTime scheduledDate = tz.TZDateTime(
      tz.local,
      now.year,
      now.month,
      now.day,
      time.hour,
      time.minute,
    );

    while (scheduledDate.weekday != day) {
      scheduledDate = scheduledDate.add(const Duration(days: 1));
    }

    if (scheduledDate.isBefore(now)) {
      scheduledDate = scheduledDate.add(const Duration(days: 7));
    }

    return scheduledDate;
  }
}
