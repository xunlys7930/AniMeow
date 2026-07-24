import 'package:anime_tracker/ui/components/app_brand_icon.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('default app brand icon is bundled and decodes', (tester) async {
    final data = await rootBundle.load(AppBrandIcon.defaultAsset);
    expect(data.lengthInBytes, greaterThan(0));

    await tester.pumpWidget(
      const MaterialApp(
        home: Center(
          child: SizedBox.square(
            dimension: 64,
            child: AppBrandIcon(assetPath: AppBrandIcon.defaultAsset),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(RawImage), findsOneWidget);
    expect(find.byKey(const ValueKey('app-brand-fallback')), findsNothing);
  });

  testWidgets('missing selected icon falls back to the default brand image', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Center(
          child: SizedBox.square(
            dimension: 64,
            child: AppBrandIcon(assetPath: 'assets/missing-icon.png'),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.byType(RawImage), findsOneWidget);
    expect(
      find.byKey(
        const ValueKey('app-brand-asset:${AppBrandIcon.defaultAsset}'),
      ),
      findsOneWidget,
    );
    expect(find.byKey(const ValueKey('app-brand-fallback')), findsNothing);
  });
}
