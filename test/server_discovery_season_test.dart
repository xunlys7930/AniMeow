import 'package:anime_tracker/settings/server_discovery_season.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('server discovery season ranges', () {
    test('assigns late December through late March to January season', () {
      expect(
        isAirDateInServerDiscoverySeason(
          '2025-12-25',
          seasonMonth: 1,
          seasonYear: 2026,
        ),
        isTrue,
      );
      expect(
        isAirDateInServerDiscoverySeason(
          '2026-03-24',
          seasonMonth: 1,
          seasonYear: 2026,
        ),
        isTrue,
      );
      expect(
        isAirDateInServerDiscoverySeason(
          '2026-03-25',
          seasonMonth: 1,
          seasonYear: 2026,
        ),
        isFalse,
      );
    });

    test('assigns late March through late June to April season', () {
      expect(
        isAirDateInServerDiscoverySeason(
          '2026-03-25',
          seasonMonth: 4,
          seasonYear: 2026,
        ),
        isTrue,
      );
      expect(
        isAirDateInServerDiscoverySeason(
          '2026-06-24',
          seasonMonth: 4,
          seasonYear: 2026,
        ),
        isTrue,
      );
      expect(
        isAirDateInServerDiscoverySeason(
          '2026-06-25',
          seasonMonth: 4,
          seasonYear: 2026,
        ),
        isFalse,
      );
    });

    test('filters by season month across years when no year is selected', () {
      expect(
        isAirDateInServerDiscoverySeason('2025-12-25', seasonMonth: 1),
        isTrue,
      );
      expect(
        isAirDateInServerDiscoverySeason('2026-03-25', seasonMonth: 4),
        isTrue,
      );
      expect(
        isAirDateInServerDiscoverySeason('2026-03-25', seasonMonth: 1),
        isFalse,
      );
    });

    test('rejects dates that cannot identify an exact day', () {
      expect(
        isAirDateInServerDiscoverySeason('2026-03', seasonMonth: 1),
        isFalse,
      );
      expect(isAirDateInServerDiscoverySeason('2026', seasonMonth: 1), isFalse);
      expect(isAirDateInServerDiscoverySeason(null, seasonMonth: 1), isFalse);
    });
  });
}
