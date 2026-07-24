/// A user's local rating can be either a numeric score (0-10) or a letter
/// grade.  Keeping the conversion in one place prevents the different home
/// layouts from disagreeing about whether an item is rated.
class AnimeRatingValue {
  final double? score;
  final String? grade;

  const AnimeRatingValue._({this.score, this.grade});

  factory AnimeRatingValue.fromMap(Map<String, dynamic> map) {
    final grade = normalizeAnimeRatingGrade(map['rating_grade']);
    if (grade != null) return AnimeRatingValue._(grade: grade);

    final raw = map['rating'] ?? map['score'];
    final score = _asDouble(raw);
    if (score != null && score > 0) {
      return AnimeRatingValue._(score: score);
    }
    return const AnimeRatingValue._();
  }

  bool get hasValue => grade != null || score != null;

  /// Compact label suitable for a card or chip.
  String get label {
    final gradeValue = grade;
    if (gradeValue != null) return gradeValue;

    final scoreValue = score;
    return scoreValue == null ? '' : scoreValue.toStringAsFixed(1);
  }

  /// A consistent value used for sorting and aggregate statistics.
  double get sortValue {
    switch (grade) {
      case 'A':
        return 10;
      case 'B':
        return 8;
      case 'C':
        return 6;
      case 'D':
        return 4;
    }
    return score ?? 0;
  }

  String get detailLabel {
    if (!hasValue) return '';
    return grade == null ? '$label / 10' : '$label 级';
  }

  static double? _asDouble(dynamic value) {
    if (value == null) return null;
    if (value is num) return value.toDouble();
    return double.tryParse(value.toString());
  }
}

String? normalizeAnimeRatingGrade(dynamic value) {
  if (value == null) return null;
  final normalized = value.toString().trim().toUpperCase();
  return const {'A', 'B', 'C', 'D'}.contains(normalized) ? normalized : null;
}

AnimeRatingValue animeRatingOf(Map<String, dynamic> map) =>
    AnimeRatingValue.fromMap(map);

bool hasAnimeRating(Map<String, dynamic> map) => animeRatingOf(map).hasValue;

String? animeRatingLabel(Map<String, dynamic> map) {
  final rating = animeRatingOf(map);
  return rating.hasValue ? rating.label : null;
}

double animeRatingSortValue(Map<String, dynamic> map) =>
    animeRatingOf(map).sortValue;
