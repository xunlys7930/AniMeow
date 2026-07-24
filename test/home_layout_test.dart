import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:anime_tracker/ui/views/_shared/anime_poster_card.dart';
import 'package:anime_tracker/ui/views/home_layout.dart';

void main() {
  test('BadgeStyle migrates legacy persisted keys to the clean presets', () {
    expect(BadgeStyle.fromPersistKey('floating'), BadgeStyle.overlay);
    expect(BadgeStyle.fromPersistKey('flush'), BadgeStyle.corners);
    expect(BadgeStyle.fromPersistKey('blueRibbon'), BadgeStyle.bottomBar);
    expect(BadgeStyle.fromPersistKey('scoreTitleBar'), BadgeStyle.bottomBar);
    expect(BadgeStyle.fromPersistKey('unknown'), BadgeStyle.overlay);
  });

  for (final style in BadgeStyle.values) {
    testWidgets('poster card renders without overflow: ${style.persistKey}', (
      tester,
    ) async {
      final props = HomeViewProps(
        items: const [],
        totalItemCount: 1,
        statusColors: const {'在看': Colors.indigo},
        appDocDir: null,
        isSelectionMode: false,
        selectedIds: const {},
        onItemTap: (_) {},
        onItemLongPress: (_) {},
        onRefresh: () async {},
        onLoadMore: null,
        hasMore: false,
        gridColumns: 3,
        titlePosition: 'on_cover',
        coverBorderRadius: 12,
        badgeScale: 1,
        badgeOpacity: 0.78,
        badgeRadius: 10,
        showTitle: true,
        showRating: true,
        showProgress: true,
        showStatus: true,
        showSubjectType: true,
        badgeStyle: style,
        showCoverStatus: true,
        showCoverRating: true,
        showCoverProgress: true,
        showCoverType: true,
        showCoverSeriesCount: true,
        selectedStatus: '全部',
        isInDefaultMode: true,
        isSortedByPinyin: false,
        progressTextOf: (_) => '8/12',
      );

      Widget buildCard(Map<String, dynamic> item) {
        return MaterialApp(
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 112,
                height: 210,
                child: AnimePosterCard(item: item, props: props),
              ),
            ),
          ),
        );
      }

      await tester.pumpWidget(
        buildCard(const {
          'id': 1,
          'type': 'anime',
          'title': '一个较长的测试标题用于窄卡片',
          'cover_url': null,
          'status': '在看',
          'score': 8.8,
          'watched_episodes': 8,
          'total_episodes': 12,
          'subject_type': 'anime',
        }),
      );

      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);

      // A status update rebuilds the card. Items without any rating fields
      // must remain safe for every badge preset during that rebuild.
      await tester.pumpWidget(
        buildCard(const {
          'id': 2,
          'type': 'anime',
          'title': '无评分作品',
          'cover_url': null,
          'status': '未看',
          'watched_episodes': 0,
          'total_episodes': 12,
          'subject_type': 'anime',
        }),
      );

      await tester.pumpAndSettle();
      expect(tester.takeException(), isNull);
    });
  }
}
