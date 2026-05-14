import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:dio/dio.dart';
import 'package:saver_gallery/saver_gallery.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:file_picker/file_picker.dart';

class ImageViewerPage extends StatefulWidget {
  final String imageUrl;
  final String title;
  final VoidCallback? onModify;

  const ImageViewerPage({
    super.key,
    required this.imageUrl,
    required this.title,
    this.onModify,
  });

  @override
  State<ImageViewerPage> createState() => _ImageViewerPageState();
}

class _ImageViewerPageState extends State<ImageViewerPage> {
  bool _isSaving = false;

  Future<void> _saveImage() async {
    setState(() => _isSaving = true);
    try {
      if (Platform.isAndroid || Platform.isIOS) {
        // 请求相册权限
        PermissionStatus status;
        if (Platform.isAndroid) {
          // Android 13+ 使用 Photos 权限
          status = await Permission.photos.request();
          if (status.isPermanentlyDenied) {
            status = await Permission.storage.request();
          }
        } else {
          status = await Permission.photos.request();
        }

        if (!status.isGranted) {
          if (mounted) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('需要相册权限才能保存图片')));
          }
          return;
        }

        Uint8List? imageBytes;
        if (widget.imageUrl.startsWith('http')) {
          var response = await Dio().get(
            widget.imageUrl,
            options: Options(responseType: ResponseType.bytes),
          );
          imageBytes = Uint8List.fromList(response.data);
        } else {
          imageBytes = await File(widget.imageUrl).readAsBytes();
        }

        final result = await SaverGallery.saveImage(
          imageBytes,
          quality: 100,
          name: "cover_${DateTime.now().millisecondsSinceEpoch}.jpg",
          androidRelativePath: "Pictures/AniMeow",
          androidExistNotSave: false,
        );

        if (result.isSuccess) {
          if (mounted) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('已保存到相册')));
          }
        } else {
          throw Exception(result.errorMessage ?? '保存失败');
        }
      } else if (Platform.isWindows || Platform.isMacOS || Platform.isLinux) {
        // 桌面端使用文件选择器另存为
        String fileName = "cover_${DateTime.now().millisecondsSinceEpoch}.jpg";
        Uint8List? bytes;

        if (widget.imageUrl.startsWith('http')) {
          var response = await Dio().get(
            widget.imageUrl,
            options: Options(responseType: ResponseType.bytes),
          );
          bytes = Uint8List.fromList(response.data);
        } else {
          bytes = await File(widget.imageUrl).readAsBytes();
        }

        String? outputFile = await FilePicker.platform.saveFile(
          dialogTitle: '请选择保存位置',
          fileName: fileName,
        );

        if (outputFile != null) {
          final file = File(outputFile);
          await file.writeAsBytes(bytes);
          if (mounted) {
            ScaffoldMessenger.of(
              context,
            ).showSnackBar(const SnackBar(content: Text('已保存到本地')));
          }
        }
      } else {
        if (mounted) {
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(const SnackBar(content: Text('该平台暂不支持保存图片')));
        }
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text('保存失败: $e')));
      }
    } finally {
      if (mounted) setState(() => _isSaving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // 图片预览
          Center(
            child: InteractiveViewer(
              minScale: 0.5,
              maxScale: 4.0,
              child: Hero(
                tag: 'cover_${widget.imageUrl}',
                child: widget.imageUrl.startsWith('http')
                    ? CachedNetworkImage(
                        imageUrl: widget.imageUrl,
                        fit: BoxFit.contain,
                        placeholder: (context, url) => const Center(
                          child: CircularProgressIndicator(color: Colors.white),
                        ),
                      )
                    : Image.file(File(widget.imageUrl), fit: BoxFit.contain),
              ),
            ),
          ),
          // 顶部按钮栏
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16.0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  CircleAvatar(
                    backgroundColor: Colors.black26,
                    child: IconButton(
                      icon: const Icon(Icons.close, color: Colors.white),
                      onPressed: () => Navigator.pop(context),
                    ),
                  ),
                  Row(
                    children: [
                      if (widget.onModify != null)
                        Padding(
                          padding: const EdgeInsets.only(right: 12),
                          child: CircleAvatar(
                            backgroundColor: Colors.black26,
                            child: IconButton(
                              icon: const Icon(
                                Icons.edit_outlined,
                                color: Colors.white,
                              ),
                              onPressed: () {
                                Navigator.pop(context);
                                widget.onModify!();
                              },
                              tooltip: '修改封面',
                            ),
                          ),
                        ),
                      CircleAvatar(
                        backgroundColor: Colors.black26,
                        child: _isSaving
                            ? const SizedBox(
                                width: 20,
                                height: 20,
                                child: CircularProgressIndicator(
                                  strokeWidth: 2,
                                  color: Colors.white,
                                ),
                              )
                            : IconButton(
                                icon: const Icon(
                                  Icons.download_rounded,
                                  color: Colors.white,
                                ),
                                onPressed: _saveImage,
                                tooltip: '保存到本地',
                              ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
          // 底部标题
          Positioned(
            bottom: 40,
            left: 0,
            right: 0,
            child: Text(
              widget.title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Colors.white70,
                fontSize: 14,
                fontWeight: FontWeight.w500,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
