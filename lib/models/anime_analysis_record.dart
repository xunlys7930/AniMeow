import 'dart:convert';

class AnimeAnalysisRecord {
  const AnimeAnalysisRecord({
    this.id,
    this.serverRecordId,
    required this.userId,
    required this.username,
    required this.model,
    required this.analysis,
    required this.statsJson,
    required this.createdAt,
  });

  final int? id;
  final int? serverRecordId;
  final int userId;
  final String username;
  final String model;
  final String analysis;
  final String statsJson;
  final String createdAt;

  factory AnimeAnalysisRecord.fromMap(Map<String, dynamic> map) {
    return AnimeAnalysisRecord(
      id: _asInt(map['id']),
      serverRecordId: _asInt(map['server_record_id']),
      userId: _asInt(map['user_id']) ?? 0,
      username: (map['username'] ?? '').toString(),
      model: (map['model'] ?? '').toString(),
      analysis: (map['analysis'] ?? '').toString(),
      statsJson: (map['stats_json'] ?? '{}').toString(),
      createdAt: (map['created_at'] ?? '').toString(),
    );
  }

  Map<String, dynamic> toMap({bool includeId = true}) {
    return {
      if (includeId && id != null) 'id': id,
      'server_record_id': serverRecordId,
      'user_id': userId,
      'username': username,
      'model': model,
      'analysis': analysis,
      'stats_json': statsJson,
      'created_at': createdAt,
    };
  }

  Map<String, dynamic> get stats {
    final decoded = jsonDecode(statsJson);
    if (decoded is Map<String, dynamic>) return decoded;
    return const {};
  }

  DateTime? get createdDate => DateTime.tryParse(createdAt);

  AnimeAnalysisRecord copyWith({int? id}) {
    return AnimeAnalysisRecord(
      id: id ?? this.id,
      serverRecordId: serverRecordId,
      userId: userId,
      username: username,
      model: model,
      analysis: analysis,
      statsJson: statsJson,
      createdAt: createdAt,
    );
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}
