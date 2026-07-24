import 'package:anime_tracker/ui/pages/add_anime_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues(const {});
  });

  testWidgets('editor save bar keeps its intrinsic height', (tester) async {
    tester.view.physicalSize = const Size(1302, 1071);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);

    await tester.pumpWidget(
      const MaterialApp(home: AddAnimePage(existingAnime: {'title': '测试作品'})),
    );

    final saveBar = find.byKey(const ValueKey('anime-editor-save-bar'));
    expect(saveBar, findsOneWidget);

    final saveBarRect = tester.getRect(saveBar);
    expect(saveBarRect.height, lessThan(100));
    expect(saveBarRect.top, greaterThan(550));
    expect(find.text('编辑番剧'), findsOneWidget);
    expect(find.text('保存更改'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}
