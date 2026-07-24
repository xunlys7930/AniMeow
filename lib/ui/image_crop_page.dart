import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// 跨平台图片裁剪页（纯 Flutter，Android/iOS/Windows/桌面都能用）
///
/// 实现思路：
/// - 把原图放进 [InteractiveViewer] 内，用户用双指或鼠标滚轮缩放、拖动来取景
/// - 外层用 [AspectRatio] + [ClipRect] 限定取景框比例
/// - 用 [RepaintBoundary.toImage] 把当前可见区域栅格化为 PNG，保存到临时文件
///
/// 输出：临时 PNG 路径（用户的回调里通常会把它复制到永久目录）；
/// 取消则返回 null。
class ImageCropPage extends StatefulWidget {
  /// 待裁剪的源图绝对路径
  final String imagePath;

  /// 裁剪框宽高比，null 表示使用当前屏幕宽高比
  final double? aspectRatio;

  /// AppBar 标题
  final String title;

  const ImageCropPage({
    super.key,
    required this.imagePath,
    this.aspectRatio,
    this.title = '裁剪图片',
  });

  @override
  State<ImageCropPage> createState() => _ImageCropPageState();
}

class _ImageCropPageState extends State<ImageCropPage> {
  final GlobalKey _boundaryKey = GlobalKey();
  final TransformationController _ctrl = TransformationController();
  bool _isProcessing = false;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    if (_isProcessing) return;
    setState(() => _isProcessing = true);

    try {
      final ctx = _boundaryKey.currentContext;
      if (ctx == null) {
        setState(() => _isProcessing = false);
        return;
      }
      final boundary = ctx.findRenderObject() as RenderRepaintBoundary;
      // 按设备像素比渲染，保证清晰度
      final pixelRatio = MediaQuery.of(context).devicePixelRatio;
      final image = await boundary.toImage(pixelRatio: pixelRatio);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) {
        if (mounted) Navigator.of(context).pop();
        return;
      }

      final tmpDir = await getTemporaryDirectory();
      final outPath = p.join(
        tmpDir.path,
        'crop_${DateTime.now().millisecondsSinceEpoch}.png',
      );
      final file = File(outPath);
      await file.writeAsBytes(byteData.buffer.asUint8List());

      if (mounted) Navigator.of(context).pop(outPath);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('裁剪失败：$e')));
        setState(() => _isProcessing = false);
      }
    }
  }

  void _resetTransform() {
    _ctrl.value = Matrix4.identity();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.of(context).size;
    final ratio = widget.aspectRatio ?? (size.width / size.height);

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(widget.title),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: '取消',
          onPressed: () => Navigator.of(context).pop(),
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.restart_alt_rounded),
            tooltip: '重置',
            onPressed: _isProcessing ? null : _resetTransform,
          ),
          if (_isProcessing)
            const Padding(
              padding: EdgeInsets.all(14),
              child: SizedBox(
                width: 18,
                height: 18,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  color: Colors.white,
                ),
              ),
            )
          else
            TextButton(
              onPressed: _confirm,
              child: const Text(
                '完成',
                style: TextStyle(
                  color: Colors.white,
                  fontWeight: FontWeight.bold,
                  fontSize: 15,
                ),
              ),
            ),
        ],
      ),
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: Center(
                child: AspectRatio(
                  aspectRatio: ratio,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      ClipRect(
                        child: RepaintBoundary(
                          key: _boundaryKey,
                          child: ColoredBox(
                            color: Colors.black,
                            child: InteractiveViewer(
                              transformationController: _ctrl,
                              minScale: 0.8,
                              maxScale: 6.0,
                              boundaryMargin: const EdgeInsets.all(
                                double.infinity,
                              ),
                              clipBehavior: Clip.none,
                              child: Image.file(
                                File(widget.imagePath),
                                // contain: 保证图片完整显示
                                fit: BoxFit.contain,
                                errorBuilder: (context, error, stack) {
                                  return Container(
                                    color: Colors.grey[900],
                                    alignment: Alignment.center,
                                    child: const Text(
                                      '图片加载失败',
                                      style: TextStyle(color: Colors.white70),
                                    ),
                                  );
                                },
                              ),
                            ),
                          ),
                        ),
                      ),
                      // 裁剪框边框，用 IgnorePointer 避免阻挡手势
                      const IgnorePointer(child: _CropOverlay()),
                    ],
                  ),
                ),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
              child: Text(
                '双指缩放、单指拖动以选取保留区域；裁剪框外的部分会被舍弃',
                textAlign: TextAlign.center,
                style: TextStyle(
                  color: Colors.white.withValues(alpha: 0.7),
                  fontSize: 12,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// 取景框的白色边框 + 网格线
class _CropOverlay extends StatelessWidget {
  const _CropOverlay();

  @override
  Widget build(BuildContext context) {
    return CustomPaint(painter: _CropOverlayPainter(), size: Size.infinite);
  }
}

class _CropOverlayPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final border = Paint()
      ..color = Colors.white.withValues(alpha: 0.8)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5;
    canvas.drawRect(Offset.zero & size, border);

    final grid = Paint()
      ..color = Colors.white.withValues(alpha: 0.25)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 0.5;
    // 三分线
    final dx = size.width / 3;
    final dy = size.height / 3;
    canvas.drawLine(Offset(dx, 0), Offset(dx, size.height), grid);
    canvas.drawLine(Offset(dx * 2, 0), Offset(dx * 2, size.height), grid);
    canvas.drawLine(Offset(0, dy), Offset(size.width, dy), grid);
    canvas.drawLine(Offset(0, dy * 2), Offset(size.width, dy * 2), grid);
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
