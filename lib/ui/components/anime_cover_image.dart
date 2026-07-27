import 'dart:io';
import 'package:flutter/material.dart';
import 'package:cached_network_image/cached_network_image.dart';
import 'package:path/path.dart' as path;
import '../../utils/bangumi_image_proxy.dart';

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
    final imageWidth = width;
    final imageHeight = height;
    final documentDir = appDocDir;
    final imageUrl = proxyBangumiImageUrl(url);
    final pixelRatio = MediaQuery.of(context).devicePixelRatio;

    if (imageUrl == null || imageUrl.isEmpty) {
      return Container(
        width: imageWidth,
        height: imageHeight,
        color: Colors.grey[200],
        child: const Icon(Icons.movie_outlined, color: Colors.grey),
      );
    }

    if (imageUrl.startsWith('http')) {
      final cacheWidth = imageWidth != null
          ? (imageWidth * pixelRatio).toInt()
          : (300 * pixelRatio).toInt();

      return CachedNetworkImage(
        imageUrl: imageUrl,
        width: imageWidth,
        height: imageHeight,
        memCacheWidth: cacheWidth,
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
    if (path.isAbsolute(imageUrl)) {
      imageFile = File(imageUrl);
    } else {
      if (documentDir == null) {
        return Container(
          width: imageWidth,
          height: imageHeight,
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
      imageFile = File(path.join(documentDir.path, imageUrl));
    }

    final cacheWidth = imageWidth != null
        ? (imageWidth * pixelRatio).toInt()
        : (300 * pixelRatio).toInt();

    return Image.file(
      imageFile,
      width: imageWidth,
      height: imageHeight,
      cacheWidth: cacheWidth,
      fit: fit,
      errorBuilder: (context, error, stackTrace) => Container(
        color: Colors.grey[200],
        child: const Icon(Icons.broken_image, color: Colors.grey),
      ),
    );
  }
}
