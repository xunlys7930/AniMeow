import 'package:anime_tracker/utils/notification_service.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:timezone/data/latest_all.dart' as tz_data;
import 'package:timezone/timezone.dart' as tz;

void main() {
  late NotificationService service;
  late tz.Location shanghai;

  setUpAll(() {
    tz_data.initializeTimeZones();
    shanghai = tz.getLocation('Asia/Shanghai');
  });

  setUp(() {
    service = NotificationService();
  });

  group('每周提醒时间计算', () {
    test('同一天且提醒时间尚未到时使用当天', () {
      final now = tz.TZDateTime(shanghai, 2026, 7, 20, 18, 30);

      final result = service.nextInstanceOfDayAndTime(
        day: DateTime.monday,
        time: const TimeOfDay(hour: 20, minute: 0),
        now: now,
      );

      expect(result, tz.TZDateTime(shanghai, 2026, 7, 20, 20));
    });

    test('同一天且提醒时间已过时顺延到下周', () {
      final now = tz.TZDateTime(shanghai, 2026, 7, 20, 20, 1);

      final result = service.nextInstanceOfDayAndTime(
        day: DateTime.monday,
        time: const TimeOfDay(hour: 20, minute: 0),
        now: now,
      );

      expect(result, tz.TZDateTime(shanghai, 2026, 7, 27, 20));
    });

    test('时间恰好相等时不会立即重复触发', () {
      final now = tz.TZDateTime(shanghai, 2026, 7, 20, 20);

      final result = service.nextInstanceOfDayAndTime(
        day: DateTime.monday,
        time: const TimeOfDay(hour: 20, minute: 0),
        now: now,
      );

      expect(result, tz.TZDateTime(shanghai, 2026, 7, 27, 20));
    });

    test('可以计算本周其他星期的提醒', () {
      final now = tz.TZDateTime(shanghai, 2026, 7, 20, 9);

      final result = service.nextInstanceOfDayAndTime(
        day: DateTime.thursday,
        time: const TimeOfDay(hour: 8, minute: 15),
        now: now,
      );

      expect(result, tz.TZDateTime(shanghai, 2026, 7, 23, 8, 15));
    });

    test('拒绝无效星期', () {
      expect(
        () => service.nextInstanceOfDayAndTime(
          day: 0,
          time: const TimeOfDay(hour: 20, minute: 0),
          now: tz.TZDateTime(shanghai, 2026, 7, 20),
        ),
        throwsA(isA<ReminderSchedulingException>()),
      );
    });
  });

  group('提醒时间解析', () {
    test('支持标准与非补零的小时分钟', () {
      expect(
        NotificationService.parseReminderTime('08:05'),
        const TimeOfDay(hour: 8, minute: 5),
      );
      expect(
        NotificationService.parseReminderTime(' 8:5 '),
        const TimeOfDay(hour: 8, minute: 5),
      );
    });

    test('拒绝空值、越界值和错误格式', () {
      for (final value in <String?>[
        null,
        '',
        '24:00',
        '12:60',
        '-1:30',
        '12',
        '12:30:00',
        '午后八点',
      ]) {
        expect(
          NotificationService.parseReminderTime(value),
          isNull,
          reason: '应拒绝 $value',
        );
      }
    });
  });

  group('Windows 预排通知 ID', () {
    test('同一作品的各周 ID 唯一且保持在 32 位整数范围内', () {
      final ids = {
        for (var occurrence = 0; occurrence < 16; occurrence++)
          NotificationService.windowsOccurrenceNotificationId(
            12345,
            occurrence,
          ),
      };

      expect(ids, hasLength(16));
      expect(ids.every((id) => id >= 0 && id <= 0x7fffffff), isTrue);
    });

    test('不同作品使用不同 ID 区段', () {
      final first = NotificationService.windowsOccurrenceNotificationId(7, 0);
      final second = NotificationService.windowsOccurrenceNotificationId(8, 0);

      expect(first, isNot(second));
    });

    test('拒绝超出预排槽位的周序号', () {
      expect(
        () => NotificationService.windowsOccurrenceNotificationId(1, 16),
        throwsRangeError,
      );
    });
  });
}
