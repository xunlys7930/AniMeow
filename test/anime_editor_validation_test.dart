import 'package:anime_tracker/ui/pages/editor/anime_editor_validation.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('accepts a valid editor payload', () {
    final result = validateAnimeEditorFields(
      title: '葬送的芙莉莲',
      watchedText: '12',
      totalText: '28',
      primaryEpisodeText: '28',
      extraEpisodeText: '0',
      watchStartDate: DateTime(2026, 1, 1),
      watchFinishDate: DateTime(2026, 2, 1),
    );

    expect(result, isNull);
  });

  test('requires a title and non-negative integer progress', () {
    expect(
      validateAnimeEditorFields(
        title: ' ',
        watchedText: '0',
        totalText: '0',
        primaryEpisodeText: '0',
        extraEpisodeText: '0',
      ),
      '请输入作品标题',
    );
    expect(
      validateAnimeEditorFields(
        title: '测试作品',
        watchedText: '-1',
        totalText: '12',
        primaryEpisodeText: '12',
        extraEpisodeText: '0',
      ),
      contains('当前进度'),
    );
  });

  test('rejects progress beyond the target', () {
    final result = validateAnimeEditorFields(
      title: '测试作品',
      watchedText: '13',
      totalText: '12',
      primaryEpisodeText: '12',
      extraEpisodeText: '0',
    );

    expect(result, '当前进度不能大于目标进度');
  });

  test('rejects a finish date earlier than the start date', () {
    final result = validateAnimeEditorFields(
      title: '测试作品',
      watchedText: '1',
      totalText: '12',
      primaryEpisodeText: '12',
      extraEpisodeText: '0',
      watchStartDate: DateTime(2026, 2, 1),
      watchFinishDate: DateTime(2026, 1, 1),
    );

    expect(result, '结束时间不能早于开始时间');
  });
}
