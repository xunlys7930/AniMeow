class Anime {
  final int? id;
  final String? title;
  final String? coverUrl;
  final String? status;
  final int? rating;
  final String? ratingGrade;
  final String? review;
  final int? seriesId;
  final String? seriesName;
  final String? createdAt;
  final String? airDate;
  final String? studio;
  final String? watchStartDate;
  final String? watchFinishDate;
  final int watchedEpisodes;
  final int totalEpisodes;
  final int tvEpisodes;
  final int spEpisodes;
  final String subjectType;
  final int? reminderDay;
  final String? reminderTime;

  const Anime({
    this.id,
    this.title,
    this.coverUrl,
    this.status,
    this.rating,
    this.ratingGrade,
    this.review,
    this.seriesId,
    this.seriesName,
    this.createdAt,
    this.airDate,
    this.studio,
    this.watchStartDate,
    this.watchFinishDate,
    this.watchedEpisodes = 0,
    this.totalEpisodes = 0,
    this.tvEpisodes = 0,
    this.spEpisodes = 0,
    this.subjectType = 'anime',
    this.reminderDay,
    this.reminderTime,
  });

  factory Anime.fromMap(Map<String, dynamic> map) {
    return Anime(
      id: _asInt(map['id']),
      title: map['title'] as String?,
      coverUrl: map['cover_url'] as String?,
      status: map['status'] as String?,
      rating: _asInt(map['rating']),
      ratingGrade: map['rating_grade'] as String?,
      review: map['review'] as String?,
      seriesId: _asInt(map['series_id']),
      seriesName: map['series_name'] as String?,
      createdAt: map['created_at'] as String?,
      airDate: map['air_date'] as String?,
      studio: map['studio'] as String?,
      watchStartDate: map['watch_start_date'] as String?,
      watchFinishDate: map['watch_finish_date'] as String?,
      watchedEpisodes: _asInt(map['watched_episodes']) ?? 0,
      totalEpisodes: _asInt(map['total_episodes']) ?? 0,
      tvEpisodes: _asInt(map['tv_episodes']) ?? 0,
      spEpisodes: _asInt(map['sp_episodes']) ?? 0,
      subjectType: map['subject_type'] as String? ?? 'anime',
      reminderDay: _asInt(map['reminder_day']),
      reminderTime: map['reminder_time'] as String?,
    );
  }

  Map<String, dynamic> toMap({bool includeId = true}) {
    return {
      if (includeId && id != null) 'id': id,
      'title': title,
      'cover_url': coverUrl,
      'status': status,
      'rating': rating,
      'rating_grade': ratingGrade,
      'review': review,
      'series_id': seriesId,
      'created_at': createdAt,
      'air_date': airDate,
      'studio': studio,
      'watch_start_date': watchStartDate,
      'watch_finish_date': watchFinishDate,
      'watched_episodes': watchedEpisodes,
      'total_episodes': totalEpisodes,
      'tv_episodes': tvEpisodes,
      'sp_episodes': spEpisodes,
      'subject_type': subjectType,
      'reminder_day': reminderDay,
      'reminder_time': reminderTime,
    };
  }

  Anime copyWith({
    int? id,
    String? title,
    String? coverUrl,
    String? status,
    int? rating,
    String? ratingGrade,
    String? review,
    int? seriesId,
    String? seriesName,
    String? createdAt,
    String? airDate,
    String? studio,
    String? watchStartDate,
    String? watchFinishDate,
    int? watchedEpisodes,
    int? totalEpisodes,
    int? tvEpisodes,
    int? spEpisodes,
    String? subjectType,
    int? reminderDay,
    String? reminderTime,
  }) {
    return Anime(
      id: id ?? this.id,
      title: title ?? this.title,
      coverUrl: coverUrl ?? this.coverUrl,
      status: status ?? this.status,
      rating: rating ?? this.rating,
      ratingGrade: ratingGrade ?? this.ratingGrade,
      review: review ?? this.review,
      seriesId: seriesId ?? this.seriesId,
      seriesName: seriesName ?? this.seriesName,
      createdAt: createdAt ?? this.createdAt,
      airDate: airDate ?? this.airDate,
      studio: studio ?? this.studio,
      watchStartDate: watchStartDate ?? this.watchStartDate,
      watchFinishDate: watchFinishDate ?? this.watchFinishDate,
      watchedEpisodes: watchedEpisodes ?? this.watchedEpisodes,
      totalEpisodes: totalEpisodes ?? this.totalEpisodes,
      tvEpisodes: tvEpisodes ?? this.tvEpisodes,
      spEpisodes: spEpisodes ?? this.spEpisodes,
      subjectType: subjectType ?? this.subjectType,
      reminderDay: reminderDay ?? this.reminderDay,
      reminderTime: reminderTime ?? this.reminderTime,
    );
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}
