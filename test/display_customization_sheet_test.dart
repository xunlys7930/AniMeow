import 'package:anime_tracker/ui/customization/page_display_config.dart';
import 'package:anime_tracker/ui/pages/calendar/calendar_display_config.dart';
import 'package:anime_tracker/ui/pages/calendar/calendar_display_sheet.dart';
import 'package:anime_tracker/ui/pages/character/character_display_config.dart';
import 'package:anime_tracker/ui/pages/character/character_display_sheet.dart';
import 'package:anime_tracker/ui/pages/community/community_display_config.dart';
import 'package:anime_tracker/ui/pages/community/community_display_sheet.dart';
import 'package:anime_tracker/ui/pages/editor/anime_editor_display_config.dart';
import 'package:anime_tracker/ui/pages/editor/anime_editor_display_sheet.dart';
import 'package:anime_tracker/ui/pages/statistics/statistics_display_config.dart';
import 'package:anime_tracker/ui/pages/statistics/statistics_display_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('statistics customization fits a compact screen', (tester) async {
    await _setSurface(tester, const Size(360, 800));
    final controller = PageDisplayController(
      pageId: 'statistics-test',
      defaults: statisticsDefaults(),
      knownModules: statisticsModuleKeys,
      fallbackOrder: statisticsDefaultOrder,
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(body: StatisticsDisplaySheet(controller: controller)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定制统计页面'), findsOneWidget);
    expect(tester.takeException(), isNull);
    controller.dispose();
  });

  testWidgets('calendar customization exposes modular display controls', (
    tester,
  ) async {
    await _setSurface(tester, const Size(360, 800));
    final controller = PageDisplayController(
      pageId: 'calendar-test',
      defaults: calendarDefaults(),
      knownModules: calendarModuleKeys,
      fallbackOrder: calendarDefaultOrder,
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(body: CalendarDisplaySheet(controller: controller)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定制追随日历'), findsOneWidget);
    expect(find.text('日期事件标记'), findsOneWidget);
    expect(find.text('显示作品封面'), findsOneWidget);
    expect(tester.takeException(), isNull);
    controller.dispose();
  });

  testWidgets('anime editor customization fits a compact screen', (
    tester,
  ) async {
    await _setSurface(tester, const Size(360, 800));
    final controller = PageDisplayController(
      pageId: 'anime-editor-test',
      defaults: animeEditorDefaults(),
      knownModules: animeEditorModuleKeys,
      fallbackOrder: animeEditorDefaultOrder,
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(body: AnimeEditorDisplaySheet(controller: controller)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定制编辑页面'), findsOneWidget);
    expect(find.text('简洁编辑'), findsOneWidget);
    expect(find.text('显示放送时间与系列快捷项'), findsOneWidget);
    expect(tester.takeException(), isNull);
    controller.dispose();
  });

  testWidgets('character customization fits compact and wide screens', (
    tester,
  ) async {
    final controller = PageDisplayController(
      pageId: 'characters-test',
      defaults: characterDefaults(),
      knownModules: characterModuleKeys,
      fallbackOrder: characterDefaultOrder,
    );

    for (final size in const [Size(360, 800), Size(1000, 800)]) {
      await _setSurface(tester, size);
      await tester.pumpWidget(
        MaterialApp(
          theme: ThemeData(useMaterial3: true),
          home: Scaffold(body: CharacterDisplaySheet(controller: controller)),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('定制角色管理'), findsOneWidget);
      expect(tester.takeException(), isNull);
    }

    controller.dispose();
  });

  testWidgets('community customization exposes layout and card controls', (
    tester,
  ) async {
    await _setSurface(tester, const Size(360, 800));
    final controller = PageDisplayController(
      pageId: 'community-test',
      defaults: communityDefaults(),
      knownModules: communityModuleKeys,
      fallbackOrder: communityDefaultOrder,
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: Scaffold(body: CommunityDisplaySheet(controller: controller)),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('定制群组社区'), findsOneWidget);
    expect(find.text('群组排列'), findsOneWidget);
    expect(find.text('显示群组简介'), findsOneWidget);
    expect(tester.takeException(), isNull);
    controller.dispose();
  });
}

Future<void> _setSurface(WidgetTester tester, Size size) async {
  tester.view.devicePixelRatio = 1;
  tester.view.physicalSize = size;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
}
