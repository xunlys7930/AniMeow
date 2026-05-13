import 'package:flutter/material.dart';
import 'design_tokens.dart';

/// 构造全 App ThemeData，基于种子色生成 Material You / M3 Expressive 配色。
///
/// 替换原本写在 `main.dart` 里的内联 ThemeData，集中管理：
/// - ColorScheme（基于 seedColor，brightness 自适应）
/// - Shape（统一圆角 token）
/// - Typography（粗大化标题字号）
/// - AppBar / Card / FAB / NavBar / Chip / Dialog / Bottom Sheet 风格
ThemeData buildAppTheme(Color seedColor, {Brightness brightness = Brightness.light}) {
  final base = ColorScheme.fromSeed(
    seedColor: seedColor,
    brightness: brightness,
    // fidelity：让 primary 高度还原种子色（不被 M3 算法洗灰），
    // 但 surface 仍保持中性浅色调，整体清爽不"压"。
    dynamicSchemeVariant: DynamicSchemeVariant.fidelity,
  );

  // M3 fromSeed 仍会把 primary 锁定到 tone 40（保 onPrimary=白字对比度），
  // 这会让亮黄/亮绿等高明度种子色被强制"压暗"。
  // → 强制 primary 等于种子色本身；onPrimary 按种子色亮度自动选黑/白。
  final bool seedIsBright =
      ThemeData.estimateBrightnessForColor(seedColor) == Brightness.light;
  final Color onPrimary = seedIsBright ? Colors.black87 : Colors.white;

  // primaryContainer 同样跟着种子色走（浅色化版本，亮黄→淡米黄）
  final Color primaryContainer = Color.alphaBlend(
    seedColor.withValues(alpha: 0.20),
    brightness == Brightness.light ? Colors.white : Colors.black,
  );
  final Color onPrimaryContainer =
      brightness == Brightness.light ? Colors.black87 : Colors.white;

  final colorScheme = base.copyWith(
    primary: seedColor,
    onPrimary: onPrimary,
    primaryContainer: primaryContainer,
    onPrimaryContainer: onPrimaryContainer,
  );

  final isLight = brightness == Brightness.light;

  return ThemeData(
    useMaterial3: true,
    colorScheme: colorScheme,
    scaffoldBackgroundColor: isLight
        ? colorScheme.surface
        : colorScheme.surface,
    splashFactory: InkRipple.splashFactory,
    visualDensity: VisualDensity.adaptivePlatformDensity,

    // ============ Typography（M3 Expressive 倾向：大标题、粗字重） ============
    textTheme: const TextTheme(
      // 显示级（首页 Hero 标题）
      displayLarge: TextStyle(
        fontSize: 36,
        fontWeight: FontWeight.w900,
        letterSpacing: -0.5,
        height: 1.1,
      ),
      displayMedium: TextStyle(
        fontSize: 28,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.3,
        height: 1.15,
      ),
      // 区块标题
      headlineLarge: TextStyle(
        fontSize: 24,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.2,
      ),
      headlineMedium: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w700,
      ),
      // 标题
      titleLarge: TextStyle(
        fontSize: 18,
        fontWeight: FontWeight.w700,
      ),
      titleMedium: TextStyle(
        fontSize: 16,
        fontWeight: FontWeight.w600,
      ),
      titleSmall: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
      ),
      // 正文
      bodyLarge: TextStyle(fontSize: 15, height: 1.4),
      bodyMedium: TextStyle(fontSize: 13, height: 1.4),
      bodySmall: TextStyle(fontSize: 11, height: 1.3),
      // 标签 / 按钮
      labelLarge: TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
      labelMedium: TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
      labelSmall: TextStyle(fontSize: 10, fontWeight: FontWeight.w500),
    ),

    // ============ AppBar：透明背景 + 大标题 ============
    appBarTheme: AppBarTheme(
      centerTitle: false,
      elevation: 0,
      scrolledUnderElevation: 0,
      backgroundColor: colorScheme.surface,
      surfaceTintColor: Colors.transparent,
      foregroundColor: colorScheme.onSurface,
      titleTextStyle: TextStyle(
        color: colorScheme.onSurface,
        fontSize: 22,
        fontWeight: FontWeight.w800,
        letterSpacing: -0.2,
      ),
      iconTheme: IconThemeData(color: colorScheme.onSurface, size: 24),
    ),

    // ============ Card：低 elevation + 圆角 + surfaceContainer 色 ============
    cardTheme: CardThemeData(
      elevation: 0,
      color: colorScheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.md),
      ),
      margin: EdgeInsets.zero,
    ),

    // ============ FAB：小型，圆角 ============
    floatingActionButtonTheme: FloatingActionButtonThemeData(
      elevation: 2,
      highlightElevation: 4,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.sm),
      ),
      backgroundColor: colorScheme.primaryContainer,
      foregroundColor: colorScheme.onPrimaryContainer,
    ),

    // ============ Bottom Navigation：M3 NavigationBar ============
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: colorScheme.surfaceContainer,
      surfaceTintColor: Colors.transparent,
      indicatorColor: colorScheme.secondaryContainer,
      indicatorShape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      labelTextStyle: WidgetStatePropertyAll(
        TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w600,
          color: colorScheme.onSurface,
        ),
      ),
      iconTheme: WidgetStateProperty.resolveWith((states) {
        final selected = states.contains(WidgetState.selected);
        return IconThemeData(
          size: 24,
          color: selected
              ? colorScheme.onSecondaryContainer
              : colorScheme.onSurfaceVariant,
        );
      }),
      height: 72,
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
    ),

    // ============ Chip：M3 风格 ============
    chipTheme: ChipThemeData(
      backgroundColor: colorScheme.surfaceContainerHigh,
      selectedColor: colorScheme.secondaryContainer,
      checkmarkColor: colorScheme.onSecondaryContainer,
      labelStyle: TextStyle(
        fontSize: 13,
        fontWeight: FontWeight.w600,
        color: colorScheme.onSurface,
      ),
      side: BorderSide.none,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.xs),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    ),

    // ============ Dialog：大圆角 ============
    dialogTheme: DialogThemeData(
      backgroundColor: colorScheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.lg),
      ),
      titleTextStyle: TextStyle(
        fontSize: 20,
        fontWeight: FontWeight.w800,
        color: colorScheme.onSurface,
      ),
      contentTextStyle: TextStyle(
        fontSize: 14,
        color: colorScheme.onSurfaceVariant,
        height: 1.5,
      ),
    ),

    // ============ Bottom Sheet：大圆角 ============
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: colorScheme.surfaceContainerHigh,
      surfaceTintColor: Colors.transparent,
      modalBackgroundColor: colorScheme.surfaceContainerHigh,
      modalElevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadius.lg)),
      ),
      showDragHandle: true,
      dragHandleColor: colorScheme.onSurfaceVariant.withValues(alpha: 0.4),
    ),

    // ============ Button：胶囊 + 中等圆角 ============
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.sm),
        ),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w700),
      ),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.sm),
        ),
        elevation: 0,
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
      ),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.sm),
        ),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(AppRadius.sm),
        ),
      ),
    ),

    // ============ ListTile：紧凑、圆角 ============
    listTileTheme: ListTileThemeData(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.sm),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
    ),

    // ============ Divider ============
    dividerTheme: DividerThemeData(
      color: colorScheme.outlineVariant.withValues(alpha: 0.5),
      thickness: 0.5,
      space: 1,
    ),

    // ============ 输入框 ============
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: colorScheme.surfaceContainerHigh,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppRadius.sm),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppRadius.sm),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppRadius.sm),
        borderSide: BorderSide(color: colorScheme.primary, width: 1.5),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),

    // ============ Progress Indicator ============
    progressIndicatorTheme: ProgressIndicatorThemeData(
      color: colorScheme.primary,
      circularTrackColor: colorScheme.surfaceContainerHighest,
      linearTrackColor: colorScheme.surfaceContainerHighest,
    ),

    // ============ SnackBar ============
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: colorScheme.inverseSurface,
      contentTextStyle: TextStyle(color: colorScheme.onInverseSurface),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppRadius.sm),
      ),
      elevation: 0,
    ),
  );
}
