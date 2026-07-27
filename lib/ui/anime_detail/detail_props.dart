import 'dart:io';
import 'package:flutter/material.dart';

import 'detail_layout.dart';
import '../../utils/anime_rating.dart';

/// 详情页按作品类型切换文案和进度语义。
///
/// 数据库目前仍使用统一的 watched/total 字段；先在展示层区分“集”和“章”，
/// 后续增加页数或卷数时只需扩展这里，不必改动每一种详情布局。
enum DetailSubjectKind { anime, book }

class DetailSubjectProfile {
  final DetailSubjectKind kind;

  const DetailSubjectProfile(this.kind);

  bool get isAnime => kind == DetailSubjectKind.anime;
  String get typeLabel => isAnime ? '番剧' : '漫画 / 小说';
  String get progressTitle => isAnime ? '观看进度' : '阅读进度';
  String get progressUnit => isAnime ? '集' : '章';
  String get progressCompletionLabel => isAnime ? '完成' : '读完';
  String get startLabel => isAnime ? '开始观看' : '开始阅读';
  String get finishLabel => isAnime ? '看完时间' : '读完时间';
  String get spanLabel => isAnime ? '观看跨度' : '阅读跨度';
  String get dateLabel => isAnime ? '放送日期' : '出版日期';
  String get providerLabel => isAnime ? '制作公司' : '出版 / 发行';
}

/// 详情查看页共享给所有 layout 和 module 的数据包
///
/// 不可变；上层 [AnimeDetailPage] 在每次 setState 时重新构造一个新的。
class DetailViewProps {
  /// 最新从 DB 拉到的番剧记录（可能比 push 时传进来的 existingAnime 更新）
  final Map<String, dynamic> anime;

  /// 该番剧的标签列表（来自 `anime_tags` 关联表，已查 join）
  final List<Map<String, dynamic>> tags;

  /// 同一 series 下的其他番剧（用于 SiblingsModule）。
  /// 若该番剧不属于任何系列，则为空。
  final List<Map<String, dynamic>> siblings;

  /// 当前作品关联的角色列表。
  final List<Map<String, dynamic>> characters;

  /// 系列名（若有 series_id）
  final String? seriesName;

  /// 用户自定义状态 → 颜色映射
  final Map<String, Color> statusColors;

  /// 本地图片基目录（封面相对路径要拼上去）
  final Directory? appDocDir;

  /// 封面圆角（逻辑像素）
  final double coverBorderRadius;

  /// 当前选择的布局
  final DetailLayout layout;

  /// 按用户排序后的可见模块列表（已过滤掉 hidden）
  final List<DetailModule> orderedVisibleModules;

  /// 点编辑按钮要走的回调（layout 各自决定按钮放哪里）
  final VoidCallback onEditTap;

  /// 点系列芯片跳转到 SeriesDetailPage
  final VoidCallback? onSeriesTap;

  /// 点 sibling 卡片跳转到对应番剧的详情页
  final void Function(Map<String, dynamic> sibling) onSiblingTap;

  /// 点返回按钮（Magazine 的浮动按钮用）
  final VoidCallback onBackTap;

  /// 角色列表发生变更后刷新详情页。
  final VoidCallback onCharactersChanged;

  const DetailViewProps({
    required this.anime,
    required this.tags,
    required this.siblings,
    required this.characters,
    required this.seriesName,
    required this.statusColors,
    required this.appDocDir,
    required this.coverBorderRadius,
    required this.layout,
    required this.orderedVisibleModules,
    required this.onEditTap,
    required this.onSeriesTap,
    required this.onSiblingTap,
    required this.onBackTap,
    required this.onCharactersChanged,
  });

  // ---- 读取小工具 ----

  String get title =>
      (anime['name_cn'] ?? anime['title'] ?? anime['name'] ?? '').toString();

  String get status => (anime['status'] ?? '').toString();

  Color get statusColor => statusColors[status] ?? Colors.grey;

  String? get coverUrl => (anime['cover_url'] ?? anime['image']) as String?;

  double get rating {
    return animeRatingOf(anime).score ?? 0.0;
  }

  String? get ratingGrade => animeRatingOf(anime).grade;

  bool get hasRating => animeRatingOf(anime).hasValue;

  String get ratingLabel => animeRatingOf(anime).label;

  String get ratingDetailLabel => animeRatingOf(anime).detailLabel;

  int get watchedEpisodes => (anime['watched_episodes'] as int?) ?? 0;

  int get totalEpisodes =>
      (anime['total_episodes'] as int?) ?? (anime['total_eps'] as int?) ?? 0;

  String get airDate => (anime['air_date'] ?? '').toString();

  String get studio => (anime['studio'] ?? '').toString();

  String get review => (anime['review'] ?? anime['summary'] ?? '').toString();

  String get watchStartDate => (anime['watch_start_date'] ?? '').toString();

  String get watchFinishDate => (anime['watch_finish_date'] ?? '').toString();

  bool get isAnime => (anime['subject_type'] ?? 'anime') == 'anime';

  DetailSubjectProfile get subjectProfile => DetailSubjectProfile(
    isAnime ? DetailSubjectKind.anime : DetailSubjectKind.book,
  );

  int? get seriesId => anime['series_id'] as int?;

  int? get reminderDay => anime['reminder_day'] as int?;

  String? get reminderTime => anime['reminder_time'] as String?;

  bool get hasReminder =>
      reminderDay != null && reminderTime != null && reminderTime!.isNotEmpty;
}
