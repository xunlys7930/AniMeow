import 'package:flutter/material.dart';

/// 番剧详情查看页的预设布局（4 选 1）
///
/// 用于 [AnimeDetailPage] 选择布局模板，对应 [DetailLayoutPicker] 在设置页里
/// 给用户切换。每种 layout 都依据 [DetailModule.defaultOrder] 和用户的
/// 隐藏集渲染相同的一组模块，仅在容器/位置上不同。
enum DetailLayout {
  /// 经典卡片式：垂直堆叠 NeumorphicContainer 卡片，承接现有视觉
  classic,

  /// 杂志 Hero：SliverAppBar 大封面，下方模块平铺无卡片
  magazine,

  /// 数据看板：宽屏两栏（左封面统计 / 右模块），窄屏退化为单栏
  dashboard,

  /// 极简：仅封面 + 标题 + 状态 + 评分，模块需点「展开」才显示
  minimal;

  String get label {
    switch (this) {
      case DetailLayout.classic:
        return '经典卡片式';
      case DetailLayout.magazine:
        return '杂志 Hero';
      case DetailLayout.dashboard:
        return '数据看板';
      case DetailLayout.minimal:
        return '极简模式';
    }
  }

  String get description {
    switch (this) {
      case DetailLayout.classic:
        return '卡片垂直堆叠，信息全面';
      case DetailLayout.magazine:
        return '大封面 + 模块平铺，阅读感强';
      case DetailLayout.dashboard:
        return '左封面统计 + 右模块，宽屏自适应';
      case DetailLayout.minimal:
        return '只显示核心信息，点击展开更多';
    }
  }

  IconData get icon {
    switch (this) {
      case DetailLayout.classic:
        return Icons.view_agenda_rounded;
      case DetailLayout.magazine:
        return Icons.article_rounded;
      case DetailLayout.dashboard:
        return Icons.dashboard_customize_rounded;
      case DetailLayout.minimal:
        return Icons.center_focus_strong_rounded;
    }
  }

  String get persistKey {
    switch (this) {
      case DetailLayout.classic:
        return 'classic';
      case DetailLayout.magazine:
        return 'magazine';
      case DetailLayout.dashboard:
        return 'dashboard';
      case DetailLayout.minimal:
        return 'minimal';
    }
  }

  static DetailLayout fromPersistKey(String? key) {
    if (key == null) return DetailLayout.classic;
    return values.firstWhere(
      (e) => e.persistKey == key,
      orElse: () => DetailLayout.classic,
    );
  }
}

/// 详情页的可配置模块（用户在设置里可显隐 + 拖拽重排）
///
/// `header` 不在此枚举中——封面 + 标题 + 状态/评分 永远在页面顶部，
/// 由 layout 自身渲染，不允许用户隐藏。
enum DetailModule {
  /// 状态徽章 + 已观看/总集数或章节 + 进度条
  statusProgress,

  /// 标签 chip 列表
  tags,

  /// 评价 / 备注
  review,

  /// 放送/出版日期、制作/发行、开始/结束观看或阅读时间
  detailMeta,

  /// 提醒星期 + 时间
  reminder,

  /// 同系列其他作品横滑列表
  siblings,

  /// 关联角色列表
  characters;

  String get label {
    switch (this) {
      case DetailModule.statusProgress:
        return '进度与状态';
      case DetailModule.tags:
        return '标签';
      case DetailModule.review:
        return '评价与备注';
      case DetailModule.detailMeta:
        return '详细信息';
      case DetailModule.reminder:
        return '追番提醒';
      case DetailModule.siblings:
        return '同系列作品';
      case DetailModule.characters:
        return '角色';
    }
  }

  String get description {
    switch (this) {
      case DetailModule.statusProgress:
        return '状态徽章 + 集数/章节进度';
      case DetailModule.tags:
        return '所有已打标签';
      case DetailModule.review:
        return '你的评价 / 笔记';
      case DetailModule.detailMeta:
        return '放送/出版、制作/发行、观看/阅读时间';
      case DetailModule.reminder:
        return '追番提醒星期与时间';
      case DetailModule.siblings:
        return '同一系列下的其他作品';
      case DetailModule.characters:
        return '录入并管理作品角色';
    }
  }

  IconData get icon {
    switch (this) {
      case DetailModule.statusProgress:
        return Icons.timeline_rounded;
      case DetailModule.tags:
        return Icons.label_outline;
      case DetailModule.review:
        return Icons.edit_note_outlined;
      case DetailModule.detailMeta:
        return Icons.info_outline;
      case DetailModule.reminder:
        return Icons.notifications_active_outlined;
      case DetailModule.siblings:
        return Icons.auto_awesome_motion_outlined;
      case DetailModule.characters:
        return Icons.groups_2_outlined;
    }
  }

  String get persistKey {
    switch (this) {
      case DetailModule.statusProgress:
        return 'statusProgress';
      case DetailModule.tags:
        return 'tags';
      case DetailModule.review:
        return 'review';
      case DetailModule.detailMeta:
        return 'detailMeta';
      case DetailModule.reminder:
        return 'reminder';
      case DetailModule.siblings:
        return 'siblings';
      case DetailModule.characters:
        return 'characters';
    }
  }

  static DetailModule? fromPersistKey(String key) {
    for (final m in values) {
      if (m.persistKey == key) return m;
    }
    return null;
  }

  /// 默认顺序（用户首次进入或重置时）
  static List<DetailModule> get defaultOrder => const [
    DetailModule.statusProgress,
    DetailModule.tags,
    DetailModule.review,
    DetailModule.detailMeta,
    DetailModule.reminder,
    DetailModule.siblings,
    DetailModule.characters,
  ];

  /// 反序列化 setStringList 存储；缺失的模块追加到末尾，未知项忽略
  static List<DetailModule> deserializeOrder(List<String>? raw) {
    if (raw == null || raw.isEmpty) return defaultOrder;
    final seen = <DetailModule>{};
    final parsed = <DetailModule>[];
    for (final key in raw) {
      final m = fromPersistKey(key);
      if (m != null && seen.add(m)) parsed.add(m);
    }
    // 补齐新版本新增的模块（向后兼容）
    for (final m in defaultOrder) {
      if (seen.add(m)) parsed.add(m);
    }
    return parsed;
  }

  static Set<DetailModule> deserializeHidden(List<String>? raw) {
    if (raw == null || raw.isEmpty) return <DetailModule>{};
    final hidden = <DetailModule>{};
    for (final key in raw) {
      final m = fromPersistKey(key);
      if (m != null) hidden.add(m);
    }
    return hidden;
  }
}
