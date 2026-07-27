import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../utils/api_config.dart';

class CloudSession {
  final int userId;
  final String username;
  final String token;

  const CloudSession({
    required this.userId,
    required this.username,
    required this.token,
  });
}

class CloudBackupInfo {
  final int id;
  final String fileName;
  final int payloadSize;
  final String uploadDate;
  final String createdAt;

  const CloudBackupInfo({
    required this.id,
    required this.fileName,
    required this.payloadSize,
    required this.uploadDate,
    required this.createdAt,
  });

  factory CloudBackupInfo.fromJson(Map<String, dynamic> json) {
    return CloudBackupInfo(
      id: json['id'] as int,
      fileName: (json['file_name'] ?? '').toString(),
      payloadSize: (json['payload_size'] as num?)?.toInt() ?? 0,
      uploadDate: (json['upload_date'] ?? '').toString(),
      createdAt: (json['created_at'] ?? '').toString(),
    );
  }
}

class CloudFeedbackItem {
  final int id;
  final String username;
  final String content;
  final String status;
  final String createdAt;

  const CloudFeedbackItem({
    required this.id,
    required this.username,
    required this.content,
    required this.status,
    required this.createdAt,
  });

  factory CloudFeedbackItem.fromJson(Map<String, dynamic> json) {
    return CloudFeedbackItem(
      id: json['id'] as int,
      username: (json['username'] ?? '').toString(),
      content: (json['content'] ?? '').toString(),
      status: (json['status'] ?? 'open').toString(),
      createdAt: (json['created_at'] ?? '').toString(),
    );
  }
}

class CloudAnimeAnalysisResult {
  final int? id;
  final String analysis;
  final String model;
  final String createdAt;
  final int? remainingToday;

  const CloudAnimeAnalysisResult({
    this.id,
    required this.analysis,
    required this.model,
    required this.createdAt,
    this.remainingToday,
  });

  factory CloudAnimeAnalysisResult.fromJson(Map<String, dynamic> json) {
    return CloudAnimeAnalysisResult(
      id: _asInt(json['id'] ?? json['record_id'] ?? json['server_record_id']),
      analysis: (json['analysis'] ?? json['content'] ?? json['text'] ?? '')
          .toString(),
      model: (json['model'] ?? 'deepseek-v4-flash').toString(),
      createdAt: (json['created_at'] ?? DateTime.now().toIso8601String())
          .toString(),
      remainingToday: _asInt(json['remaining_today']),
    );
  }

  static int? _asInt(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is num) return value.toInt();
    return int.tryParse(value.toString());
  }
}

class CloudApiException implements Exception {
  final String message;

  const CloudApiException(this.message);

  @override
  String toString() => message;
}

class CloudAccountService {
  CloudAccountService._();

  static const maxBackupCount = 3;
  static const backupUploadCooldown = Duration(minutes: 5);

  static const _tokenKey = 'cloud_user_token';
  static const _usernameKey = 'cloud_username';
  static const _userIdKey = 'cloud_user_id';
  static const _lastBackupUploadAtKeyPrefix = 'cloud_last_backup_upload_at_';

  static Future<CloudSession?> loadSession() async {
    final prefs = await SharedPreferences.getInstance();
    final token = prefs.getString(_tokenKey);
    final username = prefs.getString(_usernameKey);
    final userId = prefs.getInt(_userIdKey);
    if (token == null || username == null || userId == null) return null;
    return CloudSession(userId: userId, username: username, token: token);
  }

  static Future<void> logout() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
    await prefs.remove(_usernameKey);
    await prefs.remove(_userIdKey);
  }

  static Future<CloudSession> register({
    required String username,
    required String password,
    required String inviteCode,
  }) {
    return _auth('/api/auth/register', {
      'username': username,
      'password': password,
      'invite_code': inviteCode,
    });
  }

  static Future<CloudSession> login({
    required String username,
    required String password,
  }) {
    return _auth('/api/auth/login', {
      'username': username,
      'password': password,
    });
  }

  static Future<void> resetPassword({
    required String username,
    required String password,
    required String inviteCode,
  }) async {
    await _requestJson(
      method: 'POST',
      path: '/api/auth/reset-password',
      body: {
        'username': username,
        'password': password,
        'invite_code': inviteCode,
      },
    );
  }

  static Future<List<CloudBackupInfo>> listBackups(CloudSession session) async {
    final json = await _requestJson(
      method: 'GET',
      path: '/api/user/backups',
      session: session,
    );
    final data = json['data'] as List<dynamic>? ?? const [];
    final backups = data
        .map((e) => CloudBackupInfo.fromJson(e as Map<String, dynamic>))
        .toList();
    backups.sort(_compareBackupsNewestFirst);
    return backups.take(maxBackupCount).toList(growable: false);
  }

  static Future<CloudBackupInfo> uploadBackupFile({
    required CloudSession session,
    required File zipFile,
  }) async {
    final remaining = await getBackupUploadCooldownRemaining(session);
    if (remaining != null) {
      throw CloudApiException(
        '距离上次云存储未满 5 分钟，请 ${formatCooldown(remaining)} 后再试',
      );
    }

    final bytes = await zipFile.readAsBytes();
    final json = await _requestJson(
      method: 'POST',
      path: '/api/user/backups',
      session: session,
      body: {
        'file_name': path.basename(zipFile.path),
        'backup_base64': base64Encode(bytes),
      },
    );
    await _markBackupUploaded(session);
    return CloudBackupInfo.fromJson(json['data'] as Map<String, dynamic>);
  }

  static Future<Duration?> getBackupUploadCooldownRemaining(
    CloudSession session,
  ) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_lastBackupUploadAtKey(session));
    if (raw == null || raw.isEmpty) return null;

    final lastUploadedAt = DateTime.tryParse(raw);
    if (lastUploadedAt == null) return null;

    final elapsedMs = DateTime.now().difference(lastUploadedAt).inMilliseconds;
    final remainingMs = backupUploadCooldown.inMilliseconds - elapsedMs;
    if (remainingMs <= 0) return null;

    final clampedRemainingMs = remainingMs > backupUploadCooldown.inMilliseconds
        ? backupUploadCooldown.inMilliseconds
        : remainingMs;
    return Duration(milliseconds: clampedRemainingMs);
  }

  static String formatCooldown(Duration duration) {
    final minutes = duration.inMinutes;
    final seconds = duration.inSeconds.remainder(60);
    if (minutes <= 0) return '$seconds 秒';
    if (seconds == 0) return '$minutes 分钟';
    return '$minutes 分 $seconds 秒';
  }

  static Future<File> downloadBackupFile({
    required CloudSession session,
    required CloudBackupInfo backup,
  }) async {
    final uri = ApiConfig.cloudUri('/api/user/backups/${backup.id}/download');
    if (uri == null) {
      throw const CloudApiException('尚未配置云端 API 地址');
    }

    final response = await http.get(
      uri,
      headers: {
        'Accept': 'application/zip, application/json',
        'Authorization': 'Bearer ${session.token}',
      },
    );
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final responseJson = _decodeJson(utf8.decode(response.bodyBytes));
      final message = (responseJson['message'] ?? '下载云端备份失败').toString();
      throw CloudApiException(message);
    }

    final tempDir = await getTemporaryDirectory();
    final fileName = backup.fileName.endsWith('.zip')
        ? backup.fileName
        : 'AniMeow_cloud_backup_${backup.id}.zip';
    final file = File(path.join(tempDir.path, fileName));
    await file.writeAsBytes(response.bodyBytes, flush: true);
    return file;
  }

  static Future<List<CloudFeedbackItem>> listFeedback(
    CloudSession session,
  ) async {
    final json = await _requestJson(
      method: 'GET',
      path: '/api/feedback',
      session: session,
    );
    final data = json['data'] as List<dynamic>? ?? const [];
    return data
        .map((e) => CloudFeedbackItem.fromJson(e as Map<String, dynamic>))
        .toList(growable: false);
  }

  static Future<CloudFeedbackItem> submitFeedback({
    required CloudSession session,
    required String content,
  }) async {
    final json = await _requestJson(
      method: 'POST',
      path: '/api/feedback',
      session: session,
      body: {'content': content},
    );
    return CloudFeedbackItem.fromJson(json['data'] as Map<String, dynamic>);
  }

  static Future<CloudAnimeAnalysisResult> analyzeAnimeStats({
    required CloudSession session,
    required Map<String, dynamic> stats,
    String? clientVersion,
  }) async {
    final json = await _requestJson(
      method: 'POST',
      path: '/api/user/anime-analysis',
      session: session,
      body: {
        'model': 'deepseek-v4-flash',
        if (clientVersion != null && clientVersion.isNotEmpty)
          'client_version': clientVersion,
        'stats': stats,
      },
    );
    final data = json['data'];
    if (data is Map<String, dynamic>) {
      return CloudAnimeAnalysisResult.fromJson(data);
    }
    return CloudAnimeAnalysisResult.fromJson(json);
  }

  static Future<CloudSession> _auth(
    String path,
    Map<String, dynamic> body,
  ) async {
    final json = await _requestJson(method: 'POST', path: path, body: body);
    final data = json['data'] as Map<String, dynamic>;
    final user = data['user'] as Map<String, dynamic>;
    final session = CloudSession(
      userId: (user['id'] as num).toInt(),
      username: (user['username'] ?? '').toString(),
      token: (data['token'] ?? '').toString(),
    );
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_tokenKey, session.token);
    await prefs.setString(_usernameKey, session.username);
    await prefs.setInt(_userIdKey, session.userId);
    return session;
  }

  static Future<Map<String, dynamic>> _requestJson({
    required String method,
    required String path,
    CloudSession? session,
    Map<String, dynamic>? body,
  }) async {
    final uri = ApiConfig.cloudUri(path);
    if (uri == null) {
      throw const CloudApiException('尚未配置云端 API 地址');
    }

    final headers = <String, String>{
      'Accept': 'application/json',
      if (body != null) 'Content-Type': 'application/json',
      if (session != null) 'Authorization': 'Bearer ${session.token}',
    };

    final encodedBody = body == null ? null : jsonEncode(body);
    final response = switch (method) {
      'GET' => await http.get(uri, headers: headers),
      'POST' => await http.post(uri, headers: headers, body: encodedBody),
      _ => throw CloudApiException('不支持的请求方法：$method'),
    };

    final responseJson = _decodeJson(response.body);
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final message = (responseJson['message'] ?? '云端请求失败').toString();
      throw CloudApiException(message);
    }
    if (responseJson['status'] != 'success') {
      final message = (responseJson['message'] ?? '云端请求失败').toString();
      throw CloudApiException(message);
    }
    return responseJson;
  }

  static Map<String, dynamic> _decodeJson(String body) {
    if (body.isEmpty) return const {};
    final decoded = jsonDecode(body);
    if (decoded is Map<String, dynamic>) return decoded;
    return const {};
  }

  static int _compareBackupsNewestFirst(CloudBackupInfo a, CloudBackupInfo b) {
    final aTime = _backupTime(a);
    final bTime = _backupTime(b);
    if (aTime != null && bTime != null) return bTime.compareTo(aTime);
    if (aTime != null) return -1;
    if (bTime != null) return 1;
    return b.id.compareTo(a.id);
  }

  static DateTime? _backupTime(CloudBackupInfo backup) {
    return DateTime.tryParse(backup.createdAt) ??
        DateTime.tryParse(backup.uploadDate);
  }

  static Future<void> _markBackupUploaded(CloudSession session) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _lastBackupUploadAtKey(session),
      DateTime.now().toIso8601String(),
    );
  }

  static String _lastBackupUploadAtKey(CloudSession session) {
    return '$_lastBackupUploadAtKeyPrefix${session.userId}';
  }
}
