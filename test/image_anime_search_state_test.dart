import 'dart:io';

import 'package:anime_tracker/api/trace_moe_service.dart';
import 'package:anime_tracker/ui/pages/image_search/image_anime_search_state.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ImageAnimeSearchPhase', () {
    test('maps each phase to one stable workflow step', () {
      expect(ImageAnimeSearchPhase.idle.activeStep, 0);
      expect(ImageAnimeSearchPhase.cropping.activeStep, 1);
      expect(ImageAnimeSearchPhase.searchingTrace.activeStep, 2);
      expect(ImageAnimeSearchPhase.failed.activeStep, 2);
      expect(ImageAnimeSearchPhase.ready.activeStep, 3);
      expect(ImageAnimeSearchPhase.resolvingMetadata.activeStep, 3);
    });

    test('only active operations are marked busy', () {
      expect(ImageAnimeSearchPhase.cropping.isBusy, isTrue);
      expect(ImageAnimeSearchPhase.searchingTrace.isBusy, isTrue);
      expect(ImageAnimeSearchPhase.resolvingMetadata.isBusy, isTrue);
      expect(ImageAnimeSearchPhase.ready.isBusy, isFalse);
      expect(ImageAnimeSearchPhase.failed.isBusy, isFalse);
    });
  });

  group('ImageAnimeSearchViewState', () {
    const first = TraceMoeSearchResult(
      anilistId: 1,
      titleNative: '候选一',
      titleRomaji: null,
      titleEnglish: null,
      filename: '',
      episode: 1,
      from: 10,
      to: 12,
      similarity: 0.95,
      previewImageUrl: null,
      previewVideoUrl: null,
    );
    const second = TraceMoeSearchResult(
      anilistId: 2,
      titleNative: '候选二',
      titleRomaji: null,
      titleEnglish: null,
      filename: '',
      episode: 2,
      from: 20,
      to: 22,
      similarity: 0.80,
      previewImageUrl: null,
      previewVideoUrl: null,
    );
    const response = TraceMoeSearchResponse(
      frameCount: 120,
      elapsedSeconds: 0.4,
      results: [first, second],
    );

    test('selected result is safely clamped to the available candidates', () {
      const state = ImageAnimeSearchViewState(
        phase: ImageAnimeSearchPhase.ready,
        response: response,
        selectedIndex: 99,
      );

      expect(state.hasResults, isTrue);
      expect(state.selected, same(second));
    });

    test('copyWith can explicitly clear stale response and errors', () {
      final state = ImageAnimeSearchViewState(
        phase: ImageAnimeSearchPhase.ready,
        queryImage: File('query.png'),
        response: response,
        errorText: '旧错误',
      );

      final searching = state.copyWith(
        phase: ImageAnimeSearchPhase.searchingTrace,
        response: null,
        selectedIndex: 0,
        errorText: null,
      );

      expect(searching.queryImage?.path, 'query.png');
      expect(searching.response, isNull);
      expect(searching.errorText, isNull);
      expect(searching.phase, ImageAnimeSearchPhase.searchingTrace);
    });
  });
}
