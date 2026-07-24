import 'dart:io';

import 'package:anime_tracker/models/fun_rating_tier_config.dart';
import 'package:anime_tracker/services/fun_rating_service.dart';
import 'package:anime_tracker/ui/pages/fun_rating/fun_rating_share_page.dart';
import 'package:anime_tracker/ui/pages/fun_rating_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  group('FunRatingTierConfig', () {
    test('uses an independent S to D tier system', () {
      expect(FunRatingTierConfig.codes, ['S', 'A', 'B', 'C', 'D']);
      expect(FunRatingTierConfig.letters.labels, ['S', 'A', 'B', 'C', 'D']);
      expect(normalizeFunRatingTier('s'), 'S');
      expect(normalizeFunRatingTier('A'), 'A');
      expect(normalizeFunRatingTier('E'), isNull);
    });

    test('accepts Chinese and English custom labels', () {
      expect(FunRatingTierConfig.chinese.labelFor('S'), '神作');
      expect(FunRatingTierConfig.english.labelFor('S'), 'Masterpiece');

      final custom = FunRatingTierConfig.fromLabels(const [
        '本命',
        '超喜欢',
        '喜欢',
        '还可以',
        '不推荐',
      ]);
      expect(custom.editorLabelFor('A'), 'A · 超喜欢');
    });

    test('rejects empty and duplicate labels', () {
      expect(
        FunRatingTierConfig.validationError(const ['S', 'A', '', 'C', 'D']),
        isNotNull,
      );
      expect(
        FunRatingTierConfig.validationError(const ['S', 'A', 'A', 'C', 'D']),
        isNotNull,
      );
    });
  });

  test('fun rating preferences persist independently', () async {
    final service = FunRatingService();
    await service.saveTierConfig(FunRatingTierConfig.chinese);
    await service.saveBoardTitle('我的夏日番剧榜');

    expect(await service.loadTierConfig(), FunRatingTierConfig.chinese);
    expect(await service.loadBoardTitle(), '我的夏日番剧榜');
  });

  testWidgets('tier editor supports presets on a compact screen', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(360, 800);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(
          body: FunRatingBoardSettingsSheet(
            initialTitle: FunRatingService.defaultBoardTitle,
            initialConfig: FunRatingTierConfig.letters,
          ),
        ),
      ),
    );

    await tester.tap(find.text('English'));
    await tester.pump();

    expect(find.text('Masterpiece'), findsOneWidget);
    expect(find.text('保存榜单设置'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets('share canvas renders every configured tier', (tester) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(360, 800);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);

    final entries = {
      for (final code in FunRatingTierConfig.codes)
        code: <Map<String, dynamic>>[],
    };
    entries['S'] = [
      {'id': 1, 'title': '测试番剧', 'cover_url': null},
    ];

    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SingleChildScrollView(
            child: SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: FunRatingShareCanvas(
                boardTitle: '我的榜单',
                tierConfig: FunRatingTierConfig.chinese,
                tierEntries: entries,
                appDocDir: null,
                generatedAt: DateTime(2026, 7, 23),
              ),
            ),
          ),
        ),
      ),
    );

    expect(find.text('我的榜单'), findsOneWidget);
    expect(find.text('神作'), findsOneWidget);
    expect(find.text('较差'), findsOneWidget);
    expect(find.text('测试番剧'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  testWidgets(
    'mobile board shows a lazy unrated carousel before compact tiers',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(390, 844);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      final entries = List.generate(
        30,
        (index) => <String, dynamic>{
          'id': index + 1,
          'title': '作品 $index',
          'cover_url': null,
          'subject_type': 'anime',
          'fun_rating_tier': null,
        },
      );

      await tester.pumpWidget(
        MaterialApp(
          home: FunRatingPage(
            service: _FakeFunRatingService(entries),
            documentsDirectoryLoader: () async => Directory.systemTemp,
          ),
        ),
      );
      await tester.pumpAndSettle();

      final pool = find.byKey(const ValueKey('fun-rating-unrated-pool'));
      final firstTier = find.byKey(const ValueKey('fun-rating-tier-S'));
      expect(pool, findsOneWidget);
      expect(firstTier, findsOneWidget);
      expect(
        tester.getTopLeft(pool).dy,
        lessThan(tester.getTopLeft(firstTier).dy),
      );
      expect(tester.getBottomRight(find.text('作品 0')).dy, lessThan(844));

      for (final code in FunRatingTierConfig.codes) {
        expect(
          tester.getSize(find.byKey(ValueKey('fun-rating-tier-$code'))).height,
          lessThan(70),
        );
      }

      final builtCards = find.byWidgetPredicate((widget) {
        final key = widget.key;
        return key is ValueKey<String> &&
            key.value.startsWith('fun-rating-anime-');
      });
      expect(builtCards.evaluate().length, greaterThan(0));
      expect(builtCards.evaluate().length, lessThan(entries.length));
      expect(tester.takeException(), isNull);
    },
  );

  testWidgets(
    'desktop workspace keeps tiers and unrated pool together and assigns by tap',
    (tester) async {
      tester.view.devicePixelRatio = 1;
      tester.view.physicalSize = const Size(1300, 1000);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.view.resetPhysicalSize);

      final entries = List.generate(
        80,
        (index) => <String, dynamic>{
          'id': index + 1,
          'title': '桌面作品 $index',
          'cover_url': null,
          'subject_type': 'anime',
          'fun_rating_tier': null,
        },
      );
      final service = _FakeFunRatingService(entries);

      await tester.pumpWidget(
        MaterialApp(
          home: FunRatingPage(
            service: service,
            documentsDirectoryLoader: () async => Directory.systemTemp,
          ),
        ),
      );
      await tester.pumpAndSettle();

      final tierPanel = find.byKey(const ValueKey('fun-rating-tier-panel'));
      final pool = find.byKey(const ValueKey('fun-rating-unrated-pool'));
      expect(tierPanel, findsOneWidget);
      expect(pool, findsOneWidget);
      expect(
        (tester.getTopLeft(tierPanel).dy - tester.getTopLeft(pool).dy).abs(),
        lessThan(1),
      );
      expect(
        tester.getRect(tierPanel).right,
        lessThan(tester.getRect(pool).left),
      );
      expect(
        find.byWidgetPredicate((widget) => widget is LongPressDraggable),
        findsNothing,
      );

      await tester.tap(find.byKey(const ValueKey('fun-rating-anime-1')));
      await tester.pumpAndSettle();
      expect(
        find.byKey(const ValueKey('fun-rating-tier-choice-S')),
        findsOneWidget,
      );

      await tester.tap(find.byKey(const ValueKey('fun-rating-tier-choice-S')));
      await tester.pumpAndSettle();
      expect(service.lastAnimeId, 1);
      expect(service.lastTier, 'S');
      expect(tester.takeException(), isNull);
    },
  );
}

class _FakeFunRatingService extends FunRatingService {
  final List<Map<String, dynamic>> entries;
  int? lastAnimeId;
  String? lastTier;

  _FakeFunRatingService(this.entries);

  @override
  Future<List<Map<String, dynamic>>> loadAnimeEntries() async => entries;

  @override
  Future<FunRatingTierConfig> loadTierConfig() async =>
      FunRatingTierConfig.letters;

  @override
  Future<String> loadBoardTitle() async => FunRatingService.defaultBoardTitle;

  @override
  Future<void> setTier(int animeId, String? tier) async {
    lastAnimeId = animeId;
    lastTier = tier;
  }
}
