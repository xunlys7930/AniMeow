import 'dart:io';

import 'package:flutter/foundation.dart';

import '../../../api/trace_moe_service.dart';

enum ImageAnimeSearchPhase {
  idle,
  cropping,
  searchingTrace,
  resolvingMetadata,
  ready,
  failed;

  bool get isBusy =>
      this == ImageAnimeSearchPhase.cropping ||
      this == ImageAnimeSearchPhase.searchingTrace ||
      this == ImageAnimeSearchPhase.resolvingMetadata;

  int get activeStep {
    switch (this) {
      case ImageAnimeSearchPhase.idle:
        return 0;
      case ImageAnimeSearchPhase.cropping:
        return 1;
      case ImageAnimeSearchPhase.searchingTrace:
      case ImageAnimeSearchPhase.failed:
        return 2;
      case ImageAnimeSearchPhase.resolvingMetadata:
      case ImageAnimeSearchPhase.ready:
        return 3;
    }
  }

  String get statusLabel {
    switch (this) {
      case ImageAnimeSearchPhase.idle:
        return '等待选择截图';
      case ImageAnimeSearchPhase.cropping:
        return '正在裁剪截图';
      case ImageAnimeSearchPhase.searchingTrace:
        return '正在识别画面';
      case ImageAnimeSearchPhase.resolvingMetadata:
        return '正在补全条目资料';
      case ImageAnimeSearchPhase.ready:
        return '请选择并确认候选';
      case ImageAnimeSearchPhase.failed:
        return '识别未完成';
    }
  }
}

@immutable
class ImageAnimeSearchViewState {
  static const Object _unchanged = Object();

  final ImageAnimeSearchPhase phase;
  final File? queryImage;
  final TraceMoeSearchResponse? response;
  final int selectedIndex;
  final String? errorText;

  const ImageAnimeSearchViewState({
    this.phase = ImageAnimeSearchPhase.idle,
    this.queryImage,
    this.response,
    this.selectedIndex = 0,
    this.errorText,
  });

  bool get hasResults => response?.results.isNotEmpty == true;

  TraceMoeSearchResult? get selected {
    final results = response?.results;
    if (results == null || results.isEmpty) return null;
    return results[selectedIndex.clamp(0, results.length - 1)];
  }

  ImageAnimeSearchViewState copyWith({
    ImageAnimeSearchPhase? phase,
    Object? queryImage = _unchanged,
    Object? response = _unchanged,
    int? selectedIndex,
    Object? errorText = _unchanged,
  }) {
    return ImageAnimeSearchViewState(
      phase: phase ?? this.phase,
      queryImage: identical(queryImage, _unchanged)
          ? this.queryImage
          : queryImage as File?,
      response: identical(response, _unchanged)
          ? this.response
          : response as TraceMoeSearchResponse?,
      selectedIndex: selectedIndex ?? this.selectedIndex,
      errorText: identical(errorText, _unchanged)
          ? this.errorText
          : errorText as String?,
    );
  }
}
