class Series {
  final int? id;
  final String name;
  final String? description;
  final String? customCoverUrl;
  final String? createdAt;
  final int? animeCount;
  final String? defaultCoverUrl;

  const Series({
    this.id,
    required this.name,
    this.description,
    this.customCoverUrl,
    this.createdAt,
    this.animeCount,
    this.defaultCoverUrl,
  });

  factory Series.fromMap(Map<String, dynamic> map) {
    return Series(
      id: _asInt(map['id']),
      name: map['name'] as String? ?? '',
      description: map['description'] as String?,
      customCoverUrl: map['custom_cover_url'] as String?,
      createdAt: map['created_at'] as String?,
      animeCount: _asInt(map['anime_count']),
      defaultCoverUrl: map['default_cover_url'] as String?,
    );
  }

  Map<String, dynamic> toMap({bool includeId = true}) {
    return {
      if (includeId && id != null) 'id': id,
      'name': name,
      'description': description,
      'custom_cover_url': customCoverUrl,
      'created_at': createdAt,
      if (animeCount != null) 'anime_count': animeCount,
      if (defaultCoverUrl != null) 'default_cover_url': defaultCoverUrl,
    };
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}
