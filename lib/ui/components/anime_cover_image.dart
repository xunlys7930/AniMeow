import 'dart:io';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:path/path.dart' as path;

class AnimeCoverImage extends StatelessWidget {
  final String? url;
  final double? width;
  final double? height;
  final BoxFit fit;
  final Directory? appDocDir;

  const AnimeCoverImage({
    super.key,
    required this.url,
    this.width,
    this.height,
    this.fit = BoxFit.cover,
    this.appDocDir,
  });

  @override
  Widget build(BuildContext context) {
    if (url == null || url!.isEmpty) {
      return Container(
        width: width,
        height: height,
        color: Colors.grey[200],
        child: const Icon(Icons.movie_outlined, color: Colors.grey),
      );
    }

    if (url!.startsWith('http')) {
      return CachedNetworkImage(
        imageUrl: url!,
        width: width,
        height: height,
        memCacheWidth: 300,
        fit: fit,
        placeholder: (context, url) => Container(
          color: Colors.grey[200],
          child: const Center(
            child: SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
        ),
        errorWidget: (context, url, error) => Container(
          color: Colors.grey[200],
          child: const Icon(Icons.broken_image, color: Colors.grey),
        ),
        fadeInDuration: const Duration(milliseconds: 300),
      );
    }

    // 本地图片
    File imageFile;
    if (path.isAbsolute(url!)) {
      imageFile = File(url!);
    } else {
      if (appDocDir == null) {
        return Container(
          width: width,
          height: height,
          color: Colors.grey[200],
          child: const Center(
            child: SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(strokeWidth: 2),
            ),
          ),
        );
      }
      imageFile = File(path.join(appDocDir!.path, url!));
    }

    return Image.file(
      imageFile,
      width: width,
      height: height,
      cacheWidth: 300,
      fit: fit,
      errorBuilder: (context, error, stackTrace) => Container(
        color: Colors.grey[200],
        child: const Icon(Icons.broken_image, color: Colors.grey),
      ),
    );
  }
}
