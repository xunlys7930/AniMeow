import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:package_info_plus/package_info_plus.dart';

import '../db/database_helper.dart';
import '../models/anime_analysis_record.dart';
import 'cloud_account_service.dart';
import '../utils/anime_rating.dart';

class AnimeAnalysisService {
  AnimeAnalysisService._();

  static final DatabaseHelper _db = DatabaseHelper();
  static const Duration _customModelTimeout = Duration(seconds: 60);

  static Future<Map<String, dynamic>> buildStatsPayload() async {
    final animes = await _db.queryAllAnimes();
    final tagCounts = await _db.getTagCounts();
    final watchRecords = await _db.getAllWatchRecords();
    final generatedAt = DateTime.now();

    final statusCounts = <String, int>{};
    final subjectTypeCounts = <String, int>{};
    final studioCounts = <String, int>{};
    final yearCounts = <String, int>{};
    final ratingDistribution = <String, int>{};

    var watchedEpisodes = 0;
    var totalEpisodes = 0;
    var completedEntries = 0;
    var ratedEntries = 0;
    var numericRatedEntries = 0;
    var ratingSum = 0.0;

    for (final anime in animes) {
      final status = _text(anime['status'], fallback: '未分类');
      statusCounts[status] = (statusCounts[status] ?? 0) + 1;
      if (status == '看完' || status == '鐪嬪畬') completedEntries++;

      final subjectType = _text(anime['subject_type'], fallback: 'anime');
      subjectTypeCounts[subjectType] =
          (subjectTypeCounts[subjectType] ?? 0) + 1;

      final studio = _text(anime['studio']);
      if (studio.isNotEmpty) {
        studioCounts[studio] = (studioCounts[studio] ?? 0) + 1;
      }

      final year = _airYear(anime['air_date']);
      if (year != null) yearCounts[year] = (yearCounts[year] ?? 0) + 1;

      final rating = animeRatingOf(anime);
      if (rating.hasValue) {
        ratedEntries++;
        final key = rating.label;
        ratingDistribution[key] = (ratingDistribution[key] ?? 0) + 1;
        if (rating.score != null) {
          numericRatedEntries++;
          ratingSum += rating.score!;
        }
      }

      watchedEpisodes += _asInt(anime['watched_episodes']) ?? 0;
      totalEpisodes += _asInt(anime['total_episodes']) ?? 0;
    }

    final watchRecordDates = watchRecords
        .map((record) => DateTime.tryParse(_text(record['record_date'])))
        .whereType<DateTime>()
        .toList();
    watchRecordDates.sort();

    final activeDays = watchRecordDates
        .map((date) => _dateKey(date))
        .toSet()
        .length;
    final recentMonthCounts = <String, int>{};
    for (final date in watchRecordDates) {
      final month = '${date.year}-${date.month.toString().padLeft(2, '0')}';
      recentMonthCounts[month] = (recentMonthCounts[month] ?? 0) + 1;
    }

    return {
      'schema_version': 1,
      'generated_at': generatedAt.toIso8601String(),
      'source': 'anime_tracker',
      'request': {
        'model': 'deepseek-v4-flash',
        'language': 'zh-CN',
        'tone': 'cute',
        'task': 'anime_habit_style_analysis',
      },
      'privacy': {
        'scope': 'summary_only',
        'contains_reviews': false,
        'contains_cover_urls': false,
        'contains_api_keys': false,
      },
      'summary': {
        'total_entries': animes.length,
        'completed_entries': completedEntries,
        'rated_entries': ratedEntries,
        'numeric_rated_entries': numericRatedEntries,
        'average_rating': numericRatedEntries == 0
            ? null
            : double.parse(
                (ratingSum / numericRatedEntries).toStringAsFixed(2),
              ),
        'watched_episodes': watchedEpisodes,
        'total_episodes': totalEpisodes,
        'episode_completion_rate': totalEpisodes == 0
            ? null
            : double.parse(
                (watchedEpisodes / totalEpisodes).toStringAsFixed(3),
              ),
      },
      'status_counts': _sortedEntries(statusCounts),
      'subject_type_counts': _sortedEntries(subjectTypeCounts),
      'top_tags': tagCounts
          .take(20)
          .map(
            (tag) => {
              'name': _text(tag['name']),
              'count': _asInt(tag['count']) ?? 0,
            },
          )
          .where((tag) => (tag['count'] as int) > 0)
          .toList(growable: false),
      'top_studios': _sortedEntries(studioCounts, limit: 15),
      'air_year_counts': _sortedEntries(yearCounts, limit: 20),
      'rating_distribution': _sortedEntries(ratingDistribution),
      'watch_activity': {
        'record_count': watchRecords.length,
        'active_days': activeDays,
        'first_record_date': watchRecordDates.isEmpty
            ? null
            : watchRecordDates.first.toIso8601String(),
        'last_record_date': watchRecordDates.isEmpty
            ? null
            : watchRecordDates.last.toIso8601String(),
        'recent_month_counts': _sortedEntries(recentMonthCounts, limit: 6),
      },
    };
  }

  static Future<AnimeAnalysisRecord> requestAnalysis(
    CloudSession session,
  ) async {
    final stats = await buildStatsPayload();
    String? clientVersion;
    try {
      final packageInfo = await PackageInfo.fromPlatform();
      clientVersion = packageInfo.version;
    } catch (_) {
      clientVersion = null;
    }
    final result = await CloudAccountService.analyzeAnimeStats(
      session: session,
      stats: stats,
      clientVersion: clientVersion,
    );
    final record = AnimeAnalysisRecord(
      serverRecordId: result.id,
      userId: session.userId,
      username: session.username,
      model: result.model,
      analysis: result.analysis,
      statsJson: jsonEncode(stats),
      createdAt: result.createdAt,
    );
    final id = await _db.insertAnimeAnalysisRecord(
      record.toMap(includeId: false),
    );
    return record.copyWith(id: id);
  }

  static String buildPrompt(Map<String, dynamic> stats) {
    const encoder = JsonEncoder.withIndent('  ');
    return '''
你是一位语气可爱、温柔、观察力很强的二次元看番搭子。请根据下面的追番统计摘要，分析我的看番习惯和风格。

要求：
1. 用中文输出，语气可爱但不要幼稚。
2. 重点分析偏好的题材/标签、观看完成度、评分倾向、追番活跃度和可能的口味画像。
3. 给 3 条轻量建议，比如下一步可以补什么类型、如何整理片单。
4. 不要做心理诊断，不要过度推断隐私。
5. 不要原样复述 JSON，只输出分析结果。

追番统计摘要：
${encoder.convert(stats)}
''';
  }

  static Future<AnimeAnalysisRecord> requestCustomModelAnalysis({
    required Map<String, dynamic> stats,
    required String endpoint,
    required String model,
    required String apiKey,
    required int userId,
    required String username,
  }) async {
    final uri = _chatCompletionsUri(endpoint);
    final prompt = buildPrompt(stats);
    final response = await http
        .post(
          uri,
          headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            if (apiKey.trim().isNotEmpty)
              'Authorization': 'Bearer ${apiKey.trim()}',
          },
          body: jsonEncode({
            'model': model.trim(),
            'messages': [
              {'role': 'system', 'content': '你是追番喵里的可爱看番风格分析助手。'},
              {'role': 'user', 'content': prompt},
            ],
            'temperature': 0.85,
          }),
        )
        .timeout(_customModelTimeout);

    final body = utf8.decode(response.bodyBytes);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw CloudApiException(_extractErrorMessage(body));
    }

    final decoded = jsonDecode(body);
    if (decoded is! Map<String, dynamic>) {
      throw const CloudApiException('自定义模型返回格式不正确');
    }

    final analysis = _extractAssistantText(decoded);
    if (analysis.trim().isEmpty) {
      throw const CloudApiException('自定义模型没有返回可用的分析内容');
    }

    final record = AnimeAnalysisRecord(
      userId: userId,
      username: username,
      model: model.trim(),
      analysis: analysis.trim(),
      statsJson: jsonEncode(stats),
      createdAt: DateTime.now().toIso8601String(),
    );
    final id = await _db.insertAnimeAnalysisRecord(
      record.toMap(includeId: false),
    );
    return record.copyWith(id: id);
  }

  static Future<List<AnimeAnalysisRecord>> loadLocalRecords({
    int? userId,
  }) async {
    final rows = await _db.getAnimeAnalysisRecords(userId: userId);
    return rows.map(AnimeAnalysisRecord.fromMap).toList(growable: false);
  }

  static Future<AnimeAnalysisRecord?> loadLatestLocalRecord({
    int? userId,
  }) async {
    final row = await _db.getLatestAnimeAnalysisRecord(userId: userId);
    if (row == null) return null;
    return AnimeAnalysisRecord.fromMap(row);
  }

  static Duration? todayCooldownRemaining(AnimeAnalysisRecord? record) {
    final createdDate = record?.createdDate;
    if (createdDate == null) return null;

    final now = DateTime.now();
    final localCreated = createdDate.toLocal();
    if (!_isSameLocalDay(now, localCreated)) return null;

    final tomorrow = DateTime(now.year, now.month, now.day + 1);
    return tomorrow.difference(now);
  }

  static String formatRemaining(Duration duration) {
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60);
    if (hours <= 0) return '$minutes 分钟';
    if (minutes == 0) return '$hours 小时';
    return '$hours 小时 $minutes 分钟';
  }

  static String _text(dynamic value, {String fallback = ''}) {
    final text = value?.toString().trim() ?? '';
    return text.isEmpty ? fallback : text;
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }

  static String? _airYear(dynamic value) {
    final text = _text(value);
    if (text.length < 4) return null;
    final year = text.substring(0, 4);
    return int.tryParse(year) == null ? null : year;
  }

  static String _dateKey(DateTime date) {
    final local = date.toLocal();
    return '${local.year}-${local.month.toString().padLeft(2, '0')}-'
        '${local.day.toString().padLeft(2, '0')}';
  }

  static List<Map<String, dynamic>> _sortedEntries(
    Map<String, int> counts, {
    int? limit,
  }) {
    final entries = counts.entries.toList()
      ..sort((a, b) {
        final countCompare = b.value.compareTo(a.value);
        if (countCompare != 0) return countCompare;
        return a.key.compareTo(b.key);
      });
    return entries
        .take(limit ?? entries.length)
        .map((entry) => {'name': entry.key, 'count': entry.value})
        .toList(growable: false);
  }

  static bool _isSameLocalDay(DateTime a, DateTime b) {
    return a.year == b.year && a.month == b.month && a.day == b.day;
  }

  static Uri _chatCompletionsUri(String endpoint) {
    final clean = endpoint.trim();
    if (clean.isEmpty) {
      throw const CloudApiException('请填写模型接口地址');
    }
    var normalized = clean;
    while (normalized.endsWith('/')) {
      normalized = normalized.substring(0, normalized.length - 1);
    }
    if (!normalized.endsWith('/chat/completions')) {
      normalized = '$normalized/chat/completions';
    }
    return Uri.parse(normalized);
  }

  static String _extractAssistantText(Map<String, dynamic> json) {
    final choices = json['choices'];
    if (choices is List && choices.isNotEmpty) {
      final first = choices.first;
      if (first is Map<String, dynamic>) {
        final message = first['message'];
        if (message is Map<String, dynamic>) {
          final content = message['content'];
          if (content is String) return content;
          if (content is List) {
            return content
                .map(_contentPartText)
                .where((e) => e.isNotEmpty)
                .join();
          }
        }
        final text = first['text'];
        if (text is String) return text;
      }
    }
    final outputText = json['output_text'];
    if (outputText is String) return outputText;
    return '';
  }

  static String _contentPartText(dynamic part) {
    if (part is String) return part;
    if (part is Map<String, dynamic>) {
      final text = part['text'];
      if (text is String) return text;
    }
    return '';
  }

  static String _extractErrorMessage(String body) {
    try {
      final decoded = jsonDecode(body);
      if (decoded is Map<String, dynamic>) {
        final error = decoded['error'];
        if (error is Map<String, dynamic>) {
          return (error['message'] ?? '自定义模型请求失败').toString();
        }
        final message = decoded['message'];
        if (message != null) return message.toString();
      }
    } catch (_) {
      // Fall through to a generic message.
    }
    return '自定义模型请求失败';
  }
}
