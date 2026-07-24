import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:path/path.dart' as path;
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

import '../../../models/fun_rating_tier_config.dart';
import '../../components/anime_cover_image.dart';
import '../../design_tokens.dart';
import 'fun_rating_style.dart';

class FunRatingSharePage extends StatefulWidget {
  final String boardTitle;
  final FunRatingTierConfig tierConfig;
  final Map<String, List<Map<String, dynamic>>> tierEntries;
  final Directory? appDocDir;

  const FunRatingSharePage({
    super.key,
    required this.boardTitle,
    required this.tierConfig,
    required this.tierEntries,
    required this.appDocDir,
  });

  @override
  State<FunRatingSharePage> createState() => _FunRatingSharePageState();
}

class _FunRatingSharePageState extends State<FunRatingSharePage> {
  final GlobalKey _captureKey = GlobalKey();
  late final DateTime _generatedAt = DateTime.now();
  bool _isSharing = false;

  Future<void> _shareImage() async {
    if (_isSharing) return;
    setState(() => _isSharing = true);

    try {
      await WidgetsBinding.instance.endOfFrame;
      final renderObject = _captureKey.currentContext?.findRenderObject();
      if (renderObject is! RenderRepaintBoundary) {
        throw StateError('分享画布尚未准备完成');
      }

      final image = await renderObject.toImage(pixelRatio: 1.0);
      try {
        final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
        if (byteData == null) throw StateError('图片编码失败');

        final tempDir = await getTemporaryDirectory();
        final fileName =
            'AniMeow_fun_rating_${_generatedAt.millisecondsSinceEpoch}.png';
        final output = File(path.join(tempDir.path, fileName));
        await output.writeAsBytes(byteData.buffer.asUint8List(), flush: true);

        if (!mounted) return;
        final box = context.findRenderObject() as RenderBox?;
        await Share.shareXFiles(
          [XFile(output.path)],
          text: '${widget.boardTitle}｜来自追番喵',
          subject: widget.boardTitle,
          sharePositionOrigin: box == null
              ? null
              : box.localToGlobal(Offset.zero) & box.size,
        );
      } finally {
        image.dispose();
      }
    } catch (error) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('生成分享图失败：$error')));
      }
    } finally {
      if (mounted) setState(() => _isSharing = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('分享预览'),
        actions: [
          FilledButton.icon(
            onPressed: _isSharing ? null : _shareImage,
            icon: _isSharing
                ? const SizedBox.square(
                    dimension: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.ios_share_rounded),
            label: Text(_isSharing ? '生成中' : '分享图片'),
          ),
          const SizedBox(width: AppSpacing.md),
        ],
      ),
      body: Column(
        children: [
          Container(
            width: double.infinity,
            color: Theme.of(context).colorScheme.primaryContainer,
            padding: const EdgeInsets.symmetric(
              horizontal: AppSpacing.lg,
              vertical: AppSpacing.sm,
            ),
            child: Text(
              '下方是最终图片的完整画布；封面尚未加载时，可稍等片刻再分享。',
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Theme.of(context).colorScheme.onPrimaryContainer,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
          Expanded(
            child: ColoredBox(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(AppSpacing.xl),
                child: SingleChildScrollView(
                  scrollDirection: Axis.horizontal,
                  child: RepaintBoundary(
                    key: _captureKey,
                    child: FunRatingShareCanvas(
                      boardTitle: widget.boardTitle,
                      tierConfig: widget.tierConfig,
                      tierEntries: widget.tierEntries,
                      appDocDir: widget.appDocDir,
                      generatedAt: _generatedAt,
                    ),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class FunRatingShareCanvas extends StatelessWidget {
  static const double canvasWidth = 1080;

  final String boardTitle;
  final FunRatingTierConfig tierConfig;
  final Map<String, List<Map<String, dynamic>>> tierEntries;
  final Directory? appDocDir;
  final DateTime generatedAt;

  const FunRatingShareCanvas({
    super.key,
    required this.boardTitle,
    required this.tierConfig,
    required this.tierEntries,
    required this.appDocDir,
    required this.generatedAt,
  });

  @override
  Widget build(BuildContext context) {
    final total = tierEntries.values.fold<int>(
      0,
      (sum, entries) => sum + entries.length,
    );
    return Container(
      width: canvasWidth,
      padding: const EdgeInsets.all(48),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF171526), Color(0xFF25213B), Color(0xFF131927)],
        ),
      ),
      child: DefaultTextStyle(
        style: const TextStyle(color: Colors.white),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    color: const Color(0xFFFFB4D0),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  alignment: Alignment.center,
                  child: const Text('喵', style: TextStyle(fontSize: 28)),
                ),
                const SizedBox(width: 20),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        boardTitle,
                        style: const TextStyle(
                          fontSize: 34,
                          height: 1.15,
                          fontWeight: FontWeight.w900,
                          letterSpacing: -0.5,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        '$total 部作品 · 一张只代表此刻口味的趣味榜单',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.66),
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 36),
            ClipRRect(
              borderRadius: BorderRadius.circular(28),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (final code in FunRatingTierConfig.codes)
                    _TierShareRow(
                      code: code,
                      label: tierConfig.labelFor(code),
                      color: funRatingTierColor(code),
                      entries: tierEntries[code] ?? const [],
                      appDocDir: appDocDir,
                    ),
                ],
              ),
            ),
            const SizedBox(height: 28),
            Row(
              children: [
                Text(
                  'AniMeow · 追番喵',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.74),
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                  ),
                ),
                const Spacer(),
                Text(
                  '${generatedAt.year}.${generatedAt.month.toString().padLeft(2, '0')}.${generatedAt.day.toString().padLeft(2, '0')}',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.5),
                    fontSize: 14,
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _TierShareRow extends StatelessWidget {
  final String code;
  final String label;
  final Color color;
  final List<Map<String, dynamic>> entries;
  final Directory? appDocDir;

  const _TierShareRow({
    required this.code,
    required this.label,
    required this.color,
    required this.entries,
    required this.appDocDir,
  });

  @override
  Widget build(BuildContext context) {
    return IntrinsicHeight(
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Container(
            width: 150,
            color: color,
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 24),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(
                  code,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 42,
                    height: 1,
                    fontWeight: FontWeight.w900,
                  ),
                ),
                if (label != code) ...[
                  const SizedBox(height: 10),
                  Text(
                    label,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 16,
                      height: 1.2,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ],
            ),
          ),
          Expanded(
            child: Container(
              constraints: const BoxConstraints(minHeight: 170),
              color: const Color(0xFF302C43),
              padding: const EdgeInsets.all(18),
              child: entries.isEmpty
                  ? Center(
                      child: Text(
                        '暂时空缺',
                        style: TextStyle(
                          color: Colors.white.withValues(alpha: 0.34),
                          fontSize: 16,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    )
                  : Wrap(
                      spacing: 12,
                      runSpacing: 16,
                      children: entries
                          .map(
                            (anime) => _ShareAnimeCard(
                              anime: anime,
                              appDocDir: appDocDir,
                            ),
                          )
                          .toList(growable: false),
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ShareAnimeCard extends StatelessWidget {
  final Map<String, dynamic> anime;
  final Directory? appDocDir;

  const _ShareAnimeCard({required this.anime, required this.appDocDir});

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 82,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: SizedBox(
              width: 82,
              height: 116,
              child: AnimeCoverImage(
                url: anime['cover_url']?.toString(),
                appDocDir: appDocDir,
              ),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            (anime['title'] ?? '未命名').toString(),
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            textAlign: TextAlign.center,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 10,
              height: 1.15,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}
