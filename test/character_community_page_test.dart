import 'package:anime_tracker/db/database_helper.dart';
import 'package:anime_tracker/models/character_group_package.dart';
import 'package:anime_tracker/repositories/character_repository.dart';
import 'package:anime_tracker/services/character_group_community_service.dart';
import 'package:anime_tracker/ui/pages/community/character_community_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('community center adapts and switches between remote and local', (
    tester,
  ) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(360, 800);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(useMaterial3: true),
        home: CharacterCommunityPage(
          communityService: const _FakeCommunityService(),
          repository: _FakeCharacterRepository(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('社区测试群组'), findsOneWidget);
    expect(find.text('12 角色'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('社区测试群组'));
    await tester.pumpAndSettle();

    expect(find.text('社区角色甲'), findsOneWidget);
    expect(find.text('社区测试作品'), findsOneWidget);
    await tester.tap(find.text('导入群组'));
    await tester.pumpAndSettle();
    expect(find.text('角色优先按 Bangumi ID 复用，再按中日文名称匹配。'), findsOneWidget);

    await tester.tap(find.text('确认导入'));
    await tester.pumpAndSettle();
    expect(find.text('导入完成'), findsOneWidget);
    expect(find.textContaining('角色：新增 1、复用 1'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.byTooltip('Back'));
    await tester.pumpAndSettle();

    await tester.tap(find.text('本地群组'));
    await tester.pumpAndSettle();

    expect(find.text('本地测试群组'), findsOneWidget);
    expect(find.text('社区同步'), findsOneWidget);
    expect(tester.takeException(), isNull);

    await tester.tap(find.text('本地测试群组'));
    await tester.pumpAndSettle();

    expect(find.text('角色成员'), findsOneWidget);
    expect(find.text('测试角色'), findsOneWidget);
    expect(find.text('测试作品'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}

class _FakeCommunityService extends CharacterGroupCommunityService {
  const _FakeCommunityService();

  @override
  Future<List<CommunityCharacterGroupInfo>> listGroups({
    String? query,
    int limit = 40,
    int offset = 0,
  }) async {
    return const [
      CommunityCharacterGroupInfo(
        id: 'community-1',
        name: '社区测试群组',
        description: '用于验证社区页面响应式布局',
        characterCount: 12,
        workCount: 4,
        downloadCount: 30,
      ),
    ];
  }

  @override
  Future<CharacterGroupPackage> fetchGroup(String id) async {
    return const CharacterGroupPackage(
      name: '社区测试群组',
      description: '导入前先查看完整内容',
      communityId: 'community-1',
      characters: [
        {'bgm_id': 1, 'name_cn': '社区角色甲'},
        {'bgm_id': 2, 'name_cn': '社区角色乙'},
      ],
      works: [
        {'title': '社区测试作品', 'subject_type': 'anime'},
      ],
    );
  }
}

class _FakeCharacterRepository extends CharacterRepository {
  _FakeCharacterRepository() : super(dbHelper: DatabaseHelper());

  @override
  Future<List<Map<String, dynamic>>> getCharacterGroups({String? query}) async {
    return const [
      {
        'id': 1,
        'name': '本地测试群组',
        'description': '已经同步到本地的群组',
        'character_count': 8,
        'work_count': 3,
        'source': 'community',
        'community_id': 'community-1',
      },
    ];
  }

  @override
  Future<List<Map<String, dynamic>>> getCharacterGroupCharacters(
    int groupId,
  ) async {
    return const [
      {'id': 11, 'name_cn': '测试角色', 'role_name': '主角'},
    ];
  }

  @override
  Future<List<Map<String, dynamic>>> getCharacterGroupWorks(int groupId) async {
    return const [
      {'id': 21, 'title': '测试作品', 'subject_type': 'anime'},
    ];
  }

  @override
  Future<CharacterGroupImportResult> importCharacterGroupPackage(
    CharacterGroupPackage package, {
    String source = 'community',
  }) async {
    return const CharacterGroupImportResult(
      groupId: 1,
      characterCount: 2,
      workCount: 1,
      createdCharacterCount: 1,
      createdWorkCount: 0,
    );
  }
}
