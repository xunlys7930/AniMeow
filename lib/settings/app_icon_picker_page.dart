import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../settings_manager.dart';
import '../ui/design_tokens.dart';

/// 应用图标选择页（仅 Android）。
///
/// 通过原生 MethodChannel 调用 PackageManager.setComponentEnabledSetting
/// 切换 activity-alias，启动器图标会在几秒内更新。
class AppIconPickerPage extends StatefulWidget {
  const AppIconPickerPage({super.key});

  @override
  State<AppIconPickerPage> createState() => _AppIconPickerPageState();
}

class _AppIconPickerPageState extends State<AppIconPickerPage> {
  static const _channel = MethodChannel('anime_tracker/app_icon');

  static const List<_IconOption> _options = [
    _IconOption(alias: null, asset: 'assets/icon.png', label: '默认'),
    _IconOption(
      alias: 'icon_02',
      asset: 'assets/raw/icon_02.png',
      label: '图标 02',
    ),
    _IconOption(
      alias: 'icon_04',
      asset: 'assets/raw/icon_04.png',
      label: '图标 04',
    ),
    _IconOption(
      alias: 'icon_06',
      asset: 'assets/raw/icon_06.png',
      label: '图标 06',
    ),
    _IconOption(
      alias: 'icon_07',
      asset: 'assets/raw/icon_07.png',
      label: '图标 07',
    ),
    _IconOption(
      alias: 'icon_14',
      asset: 'assets/raw/icon_14.png',
      label: '图标 14',
    ),
    _IconOption(
      alias: 'icon_15',
      asset: 'assets/raw/icon_15.png',
      label: '图标 15',
    ),
    _IconOption(
      alias: 'icon_16',
      asset: 'assets/raw/icon_16.png',
      label: '图标 16',
    ),
    _IconOption(
      alias: 'icon_18',
      asset: 'assets/raw/icon_18.png',
      label: '图标 18',
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Scaffold(
      backgroundColor: cs.surface,
      appBar: AppBar(
        title: const Text('应用图标'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back_ios_new, size: 20),
          onPressed: () => Navigator.pop(context),
        ),
      ),
      body: Platform.isAndroid ? _buildGrid(cs) : _buildUnsupported(cs),
    );
  }

  Future<void> _selectIcon(_IconOption option) async {
    if (option.alias == SettingsManager().appIconAliasNotifier.value) return;
    final confirmed = await _confirmSwitch(option);
    if (!confirmed || !mounted) return;

    String? error;
    try {
      await _channel.invokeMethod<bool>('setIcon', {'alias': option.alias});
    } on PlatformException catch (e) {
      error = e.message ?? e.code;
    } on MissingPluginException {
      error = '原生通道未就绪，请重启应用后再试';
    }

    if (error != null) {
      if (!mounted) return;
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('切换桌面图标失败：$error')));
      return;
    }

    // 桌面切换成功后，同步更新应用内图标并持久化。
    await SettingsManager().setAppIcon(
      alias: option.alias,
      asset: option.asset,
    );

    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(
          option.alias == null
              ? '已切换为默认图标，桌面会在几秒内更新'
              : '已切换为「${option.label}」，桌面会在几秒内更新',
        ),
        duration: const Duration(seconds: 3),
      ),
    );
  }

  Future<bool> _confirmSwitch(_IconOption option) async {
    final result = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('切换桌面图标'),
        content: Text(
          option.alias == null
              ? '将恢复为默认图标，桌面图标会在几秒内更新。'
              : '将切换为「${option.label}」，桌面图标会在几秒内更新。\n部分启动器可能需要回到桌面停留几秒后才会刷新。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, true),
            child: const Text('切换'),
          ),
        ],
      ),
    );
    return result ?? false;
  }

  Widget _buildUnsupported(ColorScheme cs) {
    return Padding(
      padding: const EdgeInsets.all(AppSpacing.xl),
      child: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.phone_android, size: 48, color: cs.outline),
            const SizedBox(height: AppSpacing.md),
            const Text(
              '该功能仅在 Android 上可用',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 14),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildGrid(ColorScheme cs) {
    return ValueListenableBuilder<String?>(
      valueListenable: SettingsManager().appIconAliasNotifier,
      builder: (context, currentAlias, _) {
        return ListView(
          padding: const EdgeInsets.symmetric(
            horizontal: AppSpacing.lg,
            vertical: AppSpacing.sm,
          ),
          children: [
            Container(
              margin: const EdgeInsets.only(bottom: AppSpacing.md),
              padding: const EdgeInsets.all(AppSpacing.lg),
              decoration: BoxDecoration(
                color: cs.primaryContainer.withValues(alpha: 0.4),
                borderRadius: BorderRadius.circular(AppRadius.sm),
              ),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.info_outline, size: 20, color: cs.primary),
                  const SizedBox(width: AppSpacing.md),
                  Expanded(
                    child: Text(
                      '点击图标即可切换。应用内图标会立即更新，桌面启动器图标会在几秒内刷新。',
                      style: TextStyle(fontSize: 13, color: cs.onSurface),
                    ),
                  ),
                ],
              ),
            ),
            GridView.builder(
              shrinkWrap: true,
              physics: const NeverScrollableScrollPhysics(),
              itemCount: _options.length,
              gridDelegate: const SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: 3,
                mainAxisSpacing: AppSpacing.md,
                crossAxisSpacing: AppSpacing.md,
                childAspectRatio: 0.82,
              ),
              itemBuilder: (context, index) {
                final option = _options[index];
                final selected = option.alias == currentAlias;
                return _IconTile(
                  option: option,
                  selected: selected,
                  onTap: () => _selectIcon(option),
                );
              },
            ),
            const SizedBox(height: AppSpacing.xxl),
          ],
        );
      },
    );
  }
}

class _IconOption {
  final String? alias;
  final String asset;
  final String label;

  const _IconOption({
    required this.alias,
    required this.asset,
    required this.label,
  });
}

class _IconTile extends StatelessWidget {
  final _IconOption option;
  final bool selected;
  final VoidCallback onTap;

  const _IconTile({
    required this.option,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final border = selected
        ? Border.all(color: cs.primary, width: 2.5)
        : Border.all(color: cs.outlineVariant.withValues(alpha: 0.5), width: 1);
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(AppRadius.sm),
      child: Container(
        padding: const EdgeInsets.all(AppSpacing.sm),
        decoration: BoxDecoration(
          color: cs.surfaceContainerHighest.withValues(alpha: 0.3),
          borderRadius: BorderRadius.circular(AppRadius.sm),
          border: border,
        ),
        child: Column(
          children: [
            Expanded(
              child: Stack(
                children: [
                  Center(
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(AppRadius.xs),
                      child: AspectRatio(
                        aspectRatio: 1,
                        child: Image.asset(
                          option.asset,
                          fit: BoxFit.cover,
                          errorBuilder: (_, e, s) => Container(
                            color: cs.surfaceContainerHighest,
                            child: Icon(Icons.broken_image, color: cs.outline),
                          ),
                        ),
                      ),
                    ),
                  ),
                  if (selected)
                    Positioned(
                      top: 0,
                      right: 0,
                      child: Container(
                        padding: const EdgeInsets.all(2),
                        decoration: BoxDecoration(
                          color: cs.primary,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(Icons.check, size: 14, color: cs.onPrimary),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              option.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(
                fontSize: 12,
                fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
                color: selected ? cs.primary : cs.onSurface,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
