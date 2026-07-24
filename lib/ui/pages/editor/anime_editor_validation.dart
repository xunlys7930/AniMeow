String? validateAnimeEditorFields({
  required String title,
  required String watchedText,
  required String totalText,
  required String primaryEpisodeText,
  required String extraEpisodeText,
  DateTime? watchStartDate,
  DateTime? watchFinishDate,
}) {
  if (title.trim().isEmpty) return '请输入作品标题';

  final numericFields = <String, String>{
    '当前进度': watchedText,
    '目标进度': totalText,
    '正篇数量': primaryEpisodeText,
    '附加篇数量': extraEpisodeText,
  };
  final parsed = <String, int>{};
  for (final entry in numericFields.entries) {
    final value = int.tryParse(entry.value.trim());
    if (value == null || value < 0) {
      return '${entry.key}需要填写不小于 0 的整数';
    }
    parsed[entry.key] = value;
  }

  final watched = parsed['当前进度']!;
  final total = parsed['目标进度']!;
  if (total > 0 && watched > total) {
    return '当前进度不能大于目标进度';
  }

  if (watchStartDate != null &&
      watchFinishDate != null &&
      watchFinishDate.isBefore(watchStartDate)) {
    return '结束时间不能早于开始时间';
  }

  return null;
}
