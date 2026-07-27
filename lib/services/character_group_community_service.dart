import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/character_group_package.dart';
import '../utils/api_config.dart';
import 'cloud_account_service.dart';

class CharacterGroupCommunityService {
  const CharacterGroupCommunityService();

  Future<List<CommunityCharacterGroupInfo>> listGroups({
    String? query,
    int limit = 40,
    int offset = 0,
  }) async {
    final json = await _requestJson(
      'GET',
      '/api/community/character-groups',
      queryParameters: {
        if (query != null && query.trim().isNotEmpty) 'keyword': query.trim(),
        'limit': limit.toString(),
        'offset': offset.toString(),
      },
    );
    final data = json['data'];
    final list = data is Map ? data['items'] : data;
    if (list is! List) return const [];
    return list
        .whereType<Map>()
        .map(
          (item) => CommunityCharacterGroupInfo.fromJson(
            Map<String, dynamic>.from(item),
          ),
        )
        .toList(growable: false);
  }

  Future<CharacterGroupPackage> fetchGroup(String id) async {
    final json = await _requestJson(
      'GET',
      '/api/community/character-groups/${Uri.encodeComponent(id)}',
    );
    final data = json['data'];
    if (data is Map) {
      return CharacterGroupPackage.fromJson(Map<String, dynamic>.from(data));
    }
    throw const CloudApiException('社区角色群组数据格式不正确');
  }

  Future<CharacterGroupPackage> fetchByShareCode(String code) async {
    final cleanCode = code.trim();
    if (cleanCode.isEmpty) throw const CloudApiException('请输入分享码');
    final json = await _requestJson(
      'GET',
      '/api/community/character-groups/share/${Uri.encodeComponent(cleanCode)}',
    );
    final data = json['data'];
    if (data is Map) {
      return CharacterGroupPackage.fromJson(Map<String, dynamic>.from(data));
    }
    throw const CloudApiException('分享码返回的数据格式不正确');
  }

  Future<CommunityCharacterGroupInfo> uploadGroup({
    required CloudSession session,
    required CharacterGroupPackage package,
    bool isPublic = true,
  }) async {
    final json = await _requestJson(
      'POST',
      '/api/community/character-groups',
      session: session,
      body: {'is_public': isPublic, 'payload': package.toJson()},
    );
    final data = json['data'];
    if (data is Map) {
      return CommunityCharacterGroupInfo.fromJson(
        Map<String, dynamic>.from(data),
      );
    }
    throw const CloudApiException('上传结果格式不正确');
  }

  Future<CommunityCharacterGroupInfo> updateGroup({
    required CloudSession session,
    required String communityId,
    required CharacterGroupPackage package,
    bool? isPublic,
  }) async {
    final body = <String, dynamic>{'payload': package.toJson()};
    if (isPublic != null) body['is_public'] = isPublic;

    final json = await _requestJson(
      'PUT',
      '/api/community/character-groups/${Uri.encodeComponent(communityId)}',
      session: session,
      body: body,
    );
    final data = json['data'];
    if (data is Map) {
      return CommunityCharacterGroupInfo.fromJson(
        Map<String, dynamic>.from(data),
      );
    }
    throw const CloudApiException('更新结果格式不正确');
  }

  Future<void> deleteGroup({
    required CloudSession session,
    required String communityId,
  }) async {
    await _requestJson(
      'DELETE',
      '/api/community/character-groups/${Uri.encodeComponent(communityId)}',
      session: session,
    );
  }

  Future<Map<String, dynamic>> _requestJson(
    String method,
    String path, {
    Map<String, String>? queryParameters,
    CloudSession? session,
    Map<String, dynamic>? body,
  }) async {
    final uri = ApiConfig.cloudUri(path, queryParameters);
    if (uri == null) {
      throw const CloudApiException('尚未配置云端 API 地址');
    }

    final headers = <String, String>{
      'Accept': 'application/json',
      if (body != null) 'Content-Type': 'application/json',
      if (session != null) 'Authorization': 'Bearer ${session.token}',
    };
    final encodedBody = body == null ? null : jsonEncode(body);

    http.Response response;
    switch (method.toUpperCase()) {
      case 'POST':
        response = await http.post(uri, headers: headers, body: encodedBody);
        break;
      case 'PUT':
        response = await http.put(uri, headers: headers, body: encodedBody);
        break;
      case 'DELETE':
        response = await http.delete(uri, headers: headers, body: encodedBody);
        break;
      case 'GET':
      default:
        response = await http.get(uri, headers: headers);
    }
    final decodedBody = utf8.decode(response.bodyBytes);
    final decoded = decodedBody.isEmpty ? const {} : jsonDecode(decodedBody);
    final root = decoded is Map<String, dynamic>
        ? decoded
        : Map<String, dynamic>.from(decoded as Map);

    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw CloudApiException(
        (root['message'] ?? root['error'] ?? '社区请求失败').toString(),
      );
    }
    if (root['status'] != 'success') {
      throw CloudApiException((root['message'] ?? '社区请求失败').toString());
    }
    return root;
  }
}
