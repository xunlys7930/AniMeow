import '../../customization/page_display_config.dart';

enum StatisticsModule {
  overview,
  status,
  tags;

  String get persistKey => name;

  String get label {
    switch (this) {
      case StatisticsModule.overview:
        return '总览';
      case StatisticsModule.status:
        return '状态分布';
      case StatisticsModule.tags:
        return '标签分布';
    }
  }

  String get description {
    switch (this) {
      case StatisticsModule.overview:
        return '总收录、番剧与小说数量';
      case StatisticsModule.status:
        return '按自定义观看状态查看作品构成';
      case StatisticsModule.tags:
        return '最常使用的标签及作品数量';
    }
  }

  static StatisticsModule? fromPersistKey(String key) {
    for (final module in values) {
      if (module.persistKey == key) return module;
    }
    return null;
  }
}

enum StatisticsPreset {
  concise,
  balanced,
  analysis;

  String get label {
    switch (this) {
      case StatisticsPreset.concise:
        return '精简';
      case StatisticsPreset.balanced:
        return '均衡';
      case StatisticsPreset.analysis:
        return '分析';
    }
  }

  String get description {
    switch (this) {
      case StatisticsPreset.concise:
        return '只保留最核心的概览与状态';
      case StatisticsPreset.balanced:
        return '概览、状态和标签全部显示';
      case StatisticsPreset.analysis:
        return '宽松间距，更适合桌面分析';
    }
  }

  String get persistKey => name;

  static StatisticsPreset? fromPersistKey(String? key) {
    for (final preset in values) {
      if (preset.persistKey == key) return preset;
    }
    return null;
  }
}

enum StatisticsChartStyle {
  donut,
  ranked;

  String get label {
    switch (this) {
      case StatisticsChartStyle.donut:
        return '环形图';
      case StatisticsChartStyle.ranked:
        return '排行条';
    }
  }

  String get persistKey => name;

  static StatisticsChartStyle fromPersistKey(String? key) {
    return values.firstWhere(
      (style) => style.persistKey == key,
      orElse: () => StatisticsChartStyle.donut,
    );
  }
}

const statisticsModuleKeys = <String>['overview', 'status', 'tags'];

const statisticsDefaultOrder = <String>['overview', 'status', 'tags'];

PageDisplayConfig statisticsDefaults() {
  return const PageDisplayConfig(
    preset: 'balanced',
    density: DisplayDensity.comfortable,
    moduleOrder: statisticsDefaultOrder,
    options: {'status_chart': 'donut', 'tag_chart': 'donut'},
  );
}

PageDisplayConfig statisticsPresetConfig(StatisticsPreset preset) {
  switch (preset) {
    case StatisticsPreset.concise:
      return const PageDisplayConfig(
        preset: 'concise',
        density: DisplayDensity.compact,
        moduleOrder: statisticsDefaultOrder,
        hiddenModules: {'tags'},
        options: {'status_chart': 'ranked', 'tag_chart': 'ranked'},
      );
    case StatisticsPreset.balanced:
      return statisticsDefaults();
    case StatisticsPreset.analysis:
      return const PageDisplayConfig(
        preset: 'analysis',
        density: DisplayDensity.relaxed,
        moduleOrder: statisticsDefaultOrder,
        options: {'status_chart': 'donut', 'tag_chart': 'ranked'},
      );
  }
}

StatisticsPreset? selectedStatisticsPreset(PageDisplayConfig config) {
  final preset = StatisticsPreset.fromPersistKey(config.preset);
  if (preset == null) return null;
  final expected = statisticsPresetConfig(preset);
  if (expected.density != config.density ||
      !_sameOrder(expected.moduleOrder, config.moduleOrder) ||
      expected.hiddenModules.length != config.hiddenModules.length ||
      !expected.hiddenModules.containsAll(config.hiddenModules) ||
      expected.options['status_chart'] != config.options['status_chart'] ||
      expected.options['tag_chart'] != config.options['tag_chart']) {
    return null;
  }
  return preset;
}

bool _sameOrder(List<String> left, List<String> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}

StatisticsChartStyle statisticsStatusChart(PageDisplayConfig config) {
  return StatisticsChartStyle.fromPersistKey(config.options['status_chart']);
}

StatisticsChartStyle statisticsTagChart(PageDisplayConfig config) {
  return StatisticsChartStyle.fromPersistKey(config.options['tag_chart']);
}

PageDisplayConfig markStatisticsCustom(PageDisplayConfig config) {
  return config.copyWith(preset: 'custom');
}
