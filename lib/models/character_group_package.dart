class CharacterGroupPackage {
  static const schemaName = 'anime_tracker.character_group.v1';

  final String schema;
  final String name;
  final String? description;
  final String? coverUrl;
  final String? communityId;
  final String? shareCode;
  final String? source;
  final List<Map<String, dynamic>> characters;
  final List<Map<String, dynamic>> works;
  final Map<String, dynamic> extra;

  const CharacterGroupPackage({
    this.schema = schemaName,
    required this.name,
    this.description,
    this.coverUrl,
    this.communityId,
    this.shareCode,
    this.source,
    required this.characters,
    required this.works,
    this.extra = const {},
  });

  factory CharacterGroupPackage.fromJson(Map<String, dynamic> json) {
    final payload = _asMap(json['payload']) ?? json;
    final group = _asMap(payload['group']) ?? payload;
    final characters = _asMapList(
      payload['characters'] ?? payload['character_snapshots'],
    );
    final works = _asMapList(
      payload['works'] ?? payload['work_snapshots'] ?? payload['subjects'],
    );

    final name =
        _asText(group['name']) ??
        _asText(group['title']) ??
        _asText(payload['name']) ??
        '未命名角色群组';

    return CharacterGroupPackage(
      schema: _asText(payload['schema']) ?? schemaName,
      name: name,
      description:
          _asText(group['description']) ?? _asText(payload['description']),
      coverUrl: _asText(group['cover_url']) ?? _asText(group['coverUrl']),
      communityId:
          _asText(group['community_id']) ??
          _asText(group['communityId']) ??
          _asText(payload['community_id']) ??
          _asText(payload['communityId']) ??
          _asText(json['community_id']) ??
          _asText(json['communityId']),
      shareCode:
          _asText(group['share_code']) ??
          _asText(group['shareCode']) ??
          _asText(payload['share_code']) ??
          _asText(payload['shareCode']) ??
          _asText(json['share_code']) ??
          _asText(json['shareCode']),
      source: _asText(group['source']) ?? _asText(payload['source']),
      characters: characters,
      works: works,
      extra: Map<String, dynamic>.from(_asMap(payload['extra']) ?? const {}),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'schema': schema,
      'group': {
        'name': name,
        if (description != null) 'description': description,
        if (coverUrl != null) 'cover_url': coverUrl,
        if (communityId != null) 'community_id': communityId,
        if (shareCode != null) 'share_code': shareCode,
        if (source != null) 'source': source,
      },
      'characters': characters,
      'works': works,
      if (extra.isNotEmpty) 'extra': extra,
    };
  }
}

class CharacterGroupImportResult {
  final int groupId;
  final int characterCount;
  final int workCount;
  final int createdCharacterCount;
  final int createdWorkCount;

  const CharacterGroupImportResult({
    required this.groupId,
    required this.characterCount,
    required this.workCount,
    required this.createdCharacterCount,
    required this.createdWorkCount,
  });
}

class CommunityCharacterGroupInfo {
  final String id;
  final String name;
  final String? description;
  final String? coverUrl;
  final String? shareCode;
  final int characterCount;
  final int workCount;
  final int downloadCount;
  final String? createdAt;
  final String? updatedAt;

  const CommunityCharacterGroupInfo({
    required this.id,
    required this.name,
    this.description,
    this.coverUrl,
    this.shareCode,
    this.characterCount = 0,
    this.workCount = 0,
    this.downloadCount = 0,
    this.createdAt,
    this.updatedAt,
  });

  factory CommunityCharacterGroupInfo.fromJson(Map<String, dynamic> json) {
    final id =
        _asText(json['community_id']) ??
        _asText(json['communityId']) ??
        _asText(json['id']) ??
        '';
    return CommunityCharacterGroupInfo(
      id: id,
      name: _asText(json['name']) ?? '未命名角色群组',
      description: _asText(json['description']),
      coverUrl: _asText(json['cover_url']) ?? _asText(json['coverUrl']),
      shareCode: _asText(json['share_code']) ?? _asText(json['shareCode']),
      characterCount: _asInt(json['character_count']) ?? 0,
      workCount: _asInt(json['work_count']) ?? 0,
      downloadCount: _asInt(json['download_count']) ?? 0,
      createdAt: _asText(json['created_at']) ?? _asText(json['createdAt']),
      updatedAt: _asText(json['updated_at']) ?? _asText(json['updatedAt']),
    );
  }
}

Map<String, dynamic>? _asMap(dynamic value) {
  if (value is Map<String, dynamic>) return value;
  if (value is Map) return Map<String, dynamic>.from(value);
  return null;
}

List<Map<String, dynamic>> _asMapList(dynamic value) {
  if (value is! List) return const [];
  return value
      .whereType<Map>()
      .map((item) => Map<String, dynamic>.from(item))
      .toList(growable: false);
}

String? _asText(dynamic value) {
  final text = value?.toString().trim();
  return text == null || text.isEmpty ? null : text;
}

int? _asInt(dynamic value) {
  if (value == null) return null;
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value.toString());
}
