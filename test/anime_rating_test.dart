import 'package:anime_tracker/utils/anime_rating.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AnimeRatingValue', () {
    test('uses a letter grade when present', () {
      final rating = animeRatingOf({'rating': 9.5, 'rating_grade': 'b'});

      expect(rating.hasValue, isTrue);
      expect(rating.grade, 'B');
      expect(rating.label, 'B');
      expect(rating.detailLabel, 'B 级');
      expect(rating.sortValue, 8);
    });

    test('falls back to a numeric score', () {
      final rating = animeRatingOf({'rating': 8.6});

      expect(rating.hasValue, isTrue);
      expect(rating.grade, isNull);
      expect(rating.label, '8.6');
      expect(rating.detailLabel, '8.6 / 10');
      expect(rating.sortValue, 8.6);
    });

    test('treats invalid and zero values as unrated', () {
      final zeroRating = animeRatingOf({'rating': 0});
      final invalidRating = animeRatingOf({'rating': 0, 'rating_grade': 'S'});
      final missingRating = animeRatingOf({});

      for (final rating in [zeroRating, invalidRating, missingRating]) {
        expect(rating.hasValue, isFalse);
        expect(rating.label, isEmpty);
        expect(rating.detailLabel, isEmpty);
        expect(rating.sortValue, 0);
      }
      expect(animeRatingLabel({}), isNull);
    });
  });
}
