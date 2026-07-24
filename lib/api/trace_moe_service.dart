import 'dart:convert';
import 'dart:io';
import 'package:http/http.dart' as http;
import '../utils/logger.dart';

class TraceMoeSearchResult {
  final int? anilistId;
  final String? titleNative;
  final String? titleRomaji;
  final String? titleEnglish;
  final String filename;
  final dynamic episode;
  final double from;
  final double to;
  final double similarity;
  final String? previewImageUrl;
  final String? previewVideoUrl;

  const TraceMoeSearchResult({
    required this.anilistId,
    required this.titleNative,
    required this.titleRomaji,
    required this.titleEnglish,
    required this.filename,
    required this.episode,
    required this.from,
    required this.to,
    required this.similarity,
    required this.previewImageUrl,
    required this.previewVideoUrl,
  });

  factory TraceMoeSearchResult.fromJson(Map<String, dynamic> json) {
    final anilist = json['anilist'];
    final anilistMap = anilist is Map<String, dynamic>
        ? anilist
        : <String, dynamic>{};
    final titleMap = anilistMap['title'] is Map<String, dynamic>
        ? anilistMap['title'] as Map<String, dynamic>
        : <String, dynamic>{};

    return TraceMoeSearchResult(
      anilistId: _asInt(anilistMap['id'] ?? anilist),
      titleNative: _asNonEmptyString(titleMap['native']),
      titleRomaji: _asNonEmptyString(titleMap['romaji']),
      titleEnglish: _asNonEmptyString(titleMap['english']),
      filename: _asNonEmptyString(json['filename']) ?? '',
      episode: json['episode'],
      from: _asDouble(json['from']) ?? 0,
      to: _asDouble(json['to']) ?? 0,
      similarity: _asDouble(json['similarity']) ?? 0,
      previewImageUrl: _asNonEmptyString(json['image']),
      previewVideoUrl: _asNonEmptyString(json['video']),
    );
  }

  String get displayTitle {
    return titleNative ??
        titleRomaji ??
        titleEnglish ??
        _titleFromFilename() ??
        '未知番剧';
  }

  String get searchTitle =>
      titleNative ?? titleRomaji ?? titleEnglish ?? _titleFromFilename() ?? '';

  String get episodeText {
    final value = episode;
    if (value == null || value.toString().trim().isEmpty) return '';
    return '第 $value 集';
  }

  String get timeText => _formatTime(from);

  String get similarityText => '${(similarity * 100).toStringAsFixed(1)}%';

  String get confidenceLabel {
    if (similarity >= 0.92) return '高';
    if (similarity >= 0.85) return '较高';
    if (similarity >= 0.75) return '可能';
    return '偏低';
  }

  String? _titleFromFilename() {
    if (filename.trim().isEmpty) return null;
    final clean = filename
        .replaceAll(RegExp(r'\[[^\]]+\]'), '')
        .replaceAll(RegExp(r'\([^\)]+\)'), '')
        .replaceAll(
          RegExp(r'\.(mkv|mp4|avi|mov|webm)$', caseSensitive: false),
          '',
        )
        .trim();
    return clean.isEmpty ? null : clean;
  }

  static String _formatTime(double seconds) {
    final total = seconds.round().clamp(0, 24 * 60 * 60);
    final minutes = total ~/ 60;
    final secs = total % 60;
    return '$minutes:${secs.toString().padLeft(2, '0')}';
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }

  static double? _asDouble(dynamic value) {
    if (value == null) return null;
    if (value is num) return value.toDouble();
    return double.tryParse(value.toString());
  }

  static String? _asNonEmptyString(dynamic value) {
    final text = value?.toString().trim();
    if (text == null || text.isEmpty) return null;
    return text;
  }
}

class TraceMoeSearchResponse {
  final int? frameCount;
  final double elapsedSeconds;
  final List<TraceMoeSearchResult> results;

  const TraceMoeSearchResponse({
    required this.frameCount,
    required this.elapsedSeconds,
    required this.results,
  });

  String get statsText {
    final parts = <String>[];
    if (frameCount != null && frameCount! > 0) {
      parts.add('${_formatNumber(frameCount!)} 帧');
    }
    parts.add('${elapsedSeconds.toStringAsFixed(2)} 秒');
    return parts.join(' / ');
  }

  static String _formatNumber(int value) {
    final text = value.toString();
    final buffer = StringBuffer();
    for (var i = 0; i < text.length; i++) {
      final fromEnd = text.length - i;
      buffer.write(text[i]);
      if (fromEnd > 1 && fromEnd % 3 == 1) {
        buffer.write(',');
      }
    }
    return buffer.toString();
  }
}

class TraceMoeService {
  static final Uri _searchUri = Uri.parse(
    'https://api.trace.moe/search?anilistInfo&cutBorders',
  );
  static const Duration _requestTimeout = Duration(seconds: 25);

  static Future<List<TraceMoeSearchResult>> searchByImage(
    File imageFile, {
    int limit = 12,
  }) async {
    final response = await searchByImageWithStats(imageFile, limit: limit);
    return response.results;
  }

  static Future<TraceMoeSearchResponse> searchByImageWithStats(
    File imageFile, {
    int limit = 12,
  }) async {
    if (!await imageFile.exists()) {
      throw const TraceMoeException('图片文件不存在');
    }

    final stopwatch = Stopwatch()..start();
    try {
      final request = http.MultipartRequest('POST', _searchUri);
      request.files.add(
        await http.MultipartFile.fromPath('image', imageFile.path),
      );

      final streamed = await request.send().timeout(_requestTimeout);
      final response = await http.Response.fromStream(streamed);
      final body = utf8.decode(response.bodyBytes);

      if (response.statusCode != 200) {
        logger.w('trace.moe search failed: ${response.statusCode} $body');
        throw TraceMoeException('trace.moe 返回 ${response.statusCode}');
      }

      final decoded = jsonDecode(body);
      if (decoded is! Map<String, dynamic>) {
        throw const TraceMoeException('trace.moe 返回格式异常');
      }

      final error = decoded['error']?.toString().trim();
      if (error != null && error.isNotEmpty) {
        throw TraceMoeException(error);
      }

      final rawResults = decoded['result'];
      if (rawResults is! List) {
        return TraceMoeSearchResponse(
          frameCount: _asInt(decoded['frameCount']),
          elapsedSeconds: stopwatch.elapsedMilliseconds / 1000,
          results: const [],
        );
      }

      final parsedResults = rawResults
          .whereType<Map<String, dynamic>>()
          .map(TraceMoeSearchResult.fromJson)
          .where((item) => item.searchTitle.isNotEmpty)
          .toList();
      final results = _dedupeResults(parsedResults).take(limit).toList();

      return TraceMoeSearchResponse(
        frameCount: _asInt(decoded['frameCount']),
        elapsedSeconds: stopwatch.elapsedMilliseconds / 1000,
        results: results,
      );
    } catch (e) {
      if (e is TraceMoeException) rethrow;
      logger.e('trace.moe search error: $e');
      throw TraceMoeException('以图搜番失败：$e');
    }
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }

  static List<TraceMoeSearchResult> _dedupeResults(
    List<TraceMoeSearchResult> results,
  ) {
    final bestByAnime = <String, TraceMoeSearchResult>{};
    for (final result in results) {
      final key = _resultKey(result);
      final current = bestByAnime[key];
      if (current == null || result.similarity > current.similarity) {
        bestByAnime[key] = result;
      }
    }

    return bestByAnime.values.toList()
      ..sort((a, b) => b.similarity.compareTo(a.similarity));
  }

  static String _resultKey(TraceMoeSearchResult result) {
    final id = result.anilistId;
    if (id != null && id > 0) return 'anilist:$id';
    return 'title:${_normalizeKey(result.searchTitle)}';
  }

  static String _normalizeKey(String value) {
    return value.toLowerCase().replaceAll(
      RegExp(r'[\s\-_·・:：~～!！?？,，.。()\[\]（）【】]'),
      '',
    );
  }
}

class TraceMoeException implements Exception {
  final String message;

  const TraceMoeException(this.message);

  @override
  String toString() => message;
}
