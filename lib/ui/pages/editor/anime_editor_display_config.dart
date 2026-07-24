import '../../customization/page_display_config.dart';

enum AnimeEditorModule {
  basic,
  progress,
  tags,
  details,
  reminder,
  related;

  String get persistKey => name;

  String get label {
    switch (this) {
      case AnimeEditorModule.basic:
        return '封面与基本资料';
      case AnimeEditorModule.progress:
        return '状态与进度';
      case AnimeEditorModule.tags:
        return '标签';
      case AnimeEditorModule.details:
        return '日期、制作信息与备注';
      case AnimeEditorModule.reminder:
        return '更新提醒';
      case AnimeEditorModule.related:
        return '同系列作品';
    }
  }

  String get description {
    switch (this) {
      case AnimeEditorModule.basic:
        return '标题、封面、搜索和评分等必要字段';
      case AnimeEditorModule.progress:
        return '收藏状态、当前进度与目标进度';
      case AnimeEditorModule.tags:
        return '用于检索和整理作品的自定义标签';
      case AnimeEditorModule.details:
        return '放映或出版信息、开始结束日期及评价';
      case AnimeEditorModule.reminder:
        return '每周更新通知的日期与时间';
      case AnimeEditorModule.related:
        return '快速查看同一系列中的其他作品';
    }
  }

  static AnimeEditorModule? fromPersistKey(String key) {
    for (final module in values) {
      if (module.persistKey == key) return module;
    }
    return null;
  }
}

enum AnimeEditorPreset {
  simple,
  complete;

  String get persistKey => name;

  String get label {
    switch (this) {
      case AnimeEditorPreset.simple:
        return '简洁编辑';
      case AnimeEditorPreset.complete:
        return '完整编辑';
    }
  }

  String get description {
    switch (this) {
      case AnimeEditorPreset.simple:
        return '保留标题、封面、评分和进度，适合快速收录';
      case AnimeEditorPreset.complete:
        return '显示标签、日期、提醒和系列等全部信息';
    }
  }

  static AnimeEditorPreset? fromPersistKey(String? key) {
    for (final preset in values) {
      if (preset.persistKey == key) return preset;
    }
    return null;
  }
}

const animeEditorModuleKeys = <String>[
  'basic',
  'progress',
  'tags',
  'details',
  'reminder',
  'related',
];

const animeEditorDefaultOrder = <String>[
  'basic',
  'progress',
  'tags',
  'details',
  'reminder',
  'related',
];

PageDisplayConfig animeEditorDefaults() {
  return const PageDisplayConfig(
    preset: 'complete',
    density: DisplayDensity.comfortable,
    moduleOrder: animeEditorDefaultOrder,
    options: {'advanced_header': 'true'},
  );
}

PageDisplayConfig animeEditorPresetConfig(AnimeEditorPreset preset) {
  switch (preset) {
    case AnimeEditorPreset.simple:
      return const PageDisplayConfig(
        preset: 'simple',
        density: DisplayDensity.compact,
        moduleOrder: animeEditorDefaultOrder,
        hiddenModules: {'tags', 'details', 'reminder', 'related'},
        options: {'advanced_header': 'false'},
      );
    case AnimeEditorPreset.complete:
      return animeEditorDefaults();
  }
}

AnimeEditorPreset? selectedAnimeEditorPreset(PageDisplayConfig config) {
  final preset = AnimeEditorPreset.fromPersistKey(config.preset);
  if (preset == null) return null;
  final expected = animeEditorPresetConfig(preset);
  if (expected.density != config.density ||
      !_sameOrder(expected.moduleOrder, config.moduleOrder) ||
      expected.hiddenModules.length != config.hiddenModules.length ||
      !expected.hiddenModules.containsAll(config.hiddenModules) ||
      expected.options['advanced_header'] !=
          config.options['advanced_header']) {
    return null;
  }
  return preset;
}

bool animeEditorShowAdvancedHeader(PageDisplayConfig config) {
  return config.options['advanced_header'] != 'false';
}

PageDisplayConfig markAnimeEditorCustom(PageDisplayConfig config) {
  return config.copyWith(preset: 'custom');
}

bool _sameOrder(List<String> left, List<String> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}
