import 'package:anime_tracker/ui/anime_detail/detail_props.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('anime detail profile uses viewing terminology', () {
    const profile = DetailSubjectProfile(DetailSubjectKind.anime);

    expect(profile.typeLabel, '番剧');
    expect(profile.progressTitle, '观看进度');
    expect(profile.progressUnit, '集');
    expect(profile.startLabel, '开始观看');
    expect(profile.finishLabel, '看完时间');
  });

  test('book detail profile uses reading terminology', () {
    const profile = DetailSubjectProfile(DetailSubjectKind.book);

    expect(profile.typeLabel, '漫画 / 小说');
    expect(profile.progressTitle, '阅读进度');
    expect(profile.progressUnit, '章');
    expect(profile.startLabel, '开始阅读');
    expect(profile.finishLabel, '读完时间');
    expect(profile.spanLabel, '阅读跨度');
  });
}
