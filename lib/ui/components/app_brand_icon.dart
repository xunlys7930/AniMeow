import 'dart:io';

import 'package:anime_tracker/settings_manager.dart';
import 'package:flutter/material.dart';
import 'package:path/path.dart' as path;

/// 统一展示当前应用图标。
///
/// 正常情况下从 Flutter asset bundle 读取；Windows 调试期间如果资源清单
/// 被重新构建、旧进程仍持有旧索引，则直接读取可执行文件旁的资源，避免品牌
/// 图标退化为通用占位图标。
class AppBrandIcon extends StatelessWidget {
  static const String defaultAsset = 'assets/icon.png';
  static const double defaultAssetScale = 1.54;
  static const Alignment defaultAssetAlignment = Alignment(0, -0.11);

  final String? assetPath;
  final BoxFit fit;
  final int cacheSize;
  final double? displayScale;
  final Alignment displayAlignment;
  final Widget? fallback;

  const AppBrandIcon({
    super.key,
    this.assetPath,
    this.fit = BoxFit.cover,
    this.cacheSize = 256,
    this.displayScale,
    this.displayAlignment = defaultAssetAlignment,
    this.fallback,
  });

  @override
  Widget build(BuildContext context) {
    final explicitAsset = assetPath;
    if (explicitAsset != null) {
      return _buildSemantics(
        _buildPreferredSource(
          context,
          explicitAsset,
          allowDefaultFallback: true,
        ),
      );
    }

    return ValueListenableBuilder<String>(
      valueListenable: SettingsManager().appIconAssetNotifier,
      builder: (context, selectedAsset, _) => _buildSemantics(
        _buildPreferredSource(
          context,
          selectedAsset,
          allowDefaultFallback: true,
        ),
      ),
    );
  }

  Widget _buildSemantics(Widget child) {
    return Semantics(label: '追番喵应用图标', image: true, child: child);
  }

  Widget _buildPreferredSource(
    BuildContext context,
    String currentAsset, {
    required bool allowDefaultFallback,
  }) {
    // Windows 调试进程在热重载后可能仍持有旧的 AssetBundle 索引。
    // 此时 AssetImage 不会报错，而是永久停在 unresolved，errorBuilder
    // 也不会触发。优先读取可执行文件旁的真实资源可以绕开该状态。
    final desktopFile = _windowsBundledAsset(currentAsset);
    if (desktopFile != null && FileSystemEntity.isFileSync(desktopFile.path)) {
      return _buildFile(
        context,
        desktopFile,
        currentAsset,
        allowDefaultFallback: allowDefaultFallback,
      );
    }

    return _buildAsset(
      context,
      currentAsset,
      allowDefaultFallback: allowDefaultFallback,
    );
  }

  Widget _buildAsset(
    BuildContext context,
    String currentAsset, {
    required bool allowDefaultFallback,
  }) {
    return _buildImageFrame(
      context,
      currentAsset,
      Image.asset(
        currentAsset,
        key: ValueKey('app-brand-asset:$currentAsset'),
        fit: fit,
        cacheWidth: cacheSize,
        cacheHeight: cacheSize,
        filterQuality: FilterQuality.high,
        gaplessPlayback: true,
        excludeFromSemantics: true,
        errorBuilder: (_, _, _) => _buildFinalFallback(
          context,
          currentAsset,
          allowDefaultFallback: allowDefaultFallback,
        ),
      ),
    );
  }

  Widget _buildFile(
    BuildContext context,
    File file,
    String currentAsset, {
    required bool allowDefaultFallback,
  }) {
    return _buildImageFrame(
      context,
      currentAsset,
      Image.file(
        file,
        key: ValueKey('app-brand-file:$currentAsset'),
        fit: fit,
        cacheWidth: cacheSize,
        cacheHeight: cacheSize,
        filterQuality: FilterQuality.high,
        gaplessPlayback: true,
        excludeFromSemantics: true,
        errorBuilder: (_, _, _) => _buildFinalFallback(
          context,
          currentAsset,
          allowDefaultFallback: allowDefaultFallback,
        ),
      ),
    );
  }

  Widget _buildImageFrame(
    BuildContext context,
    String currentAsset,
    Widget image,
  ) {
    final effectiveScale =
        displayScale ??
        (currentAsset == defaultAsset ? defaultAssetScale : 1.0);
    final scaledImage = effectiveScale == 1
        ? image
        : Transform.scale(
            scale: effectiveScale,
            alignment: displayAlignment,
            child: image,
          );

    // 兜底始终位于图片下方。即使 ImageStream 卡在 unresolved 而没有
    // 触发 errorBuilder，界面也不会再次出现完全空白的品牌区域。
    return ClipRect(
      child: Stack(
        fit: StackFit.expand,
        children: [_buildPlaceholder(context), scaledImage],
      ),
    );
  }

  Widget _buildFinalFallback(
    BuildContext context,
    String currentAsset, {
    required bool allowDefaultFallback,
  }) {
    if (allowDefaultFallback && currentAsset != defaultAsset) {
      return _buildPreferredSource(
        context,
        defaultAsset,
        allowDefaultFallback: false,
      );
    }

    return fallback ?? _buildPlaceholder(context, keyed: true);
  }

  Widget _buildPlaceholder(BuildContext context, {bool keyed = false}) {
    final colorScheme = Theme.of(context).colorScheme;
    return ColoredBox(
      key: keyed ? const ValueKey('app-brand-fallback') : null,
      color: colorScheme.primaryContainer,
      child: Center(
        child: Icon(
          Icons.live_tv_rounded,
          color: colorScheme.onPrimaryContainer,
        ),
      ),
    );
  }

  File? _windowsBundledAsset(String asset) {
    if (!Platform.isWindows) return null;
    final normalized = asset.replaceAll('\\', '/');
    if (normalized.startsWith('/') || normalized.contains('../')) return null;

    final executableDirectory = File(Platform.resolvedExecutable).parent.path;
    return File(
      path.joinAll([
        executableDirectory,
        'data',
        'flutter_assets',
        ...normalized.split('/'),
      ]),
    );
  }
}
