import '../../customization/page_display_config.dart';

enum CalendarModule {
  month,
  schedule,
  history;

  String get persistKey => name;

  String get label {
    switch (this) {
      case CalendarModule.month:
        return '月历';
      case CalendarModule.schedule:
        return '选中日期日程';
      case CalendarModule.history:
        return '那年今日';
    }
  }

  String get description {
    switch (this) {
      case CalendarModule.month:
        return '按月份浏览作品事件与观看记录';
      case CalendarModule.schedule:
        return '展示当前选中日期的全部事件';
      case CalendarModule.history:
        return '回顾其他年份同一天的记录';
    }
  }

  static CalendarModule? fromPersistKey(String key) {
    for (final module in values) {
      if (module.persistKey == key) return module;
    }
    return null;
  }
}

enum CalendarPreset {
  monthFocus,
  balanced,
  journal;

  String get persistKey => name;

  String get label {
    switch (this) {
      case CalendarPreset.monthFocus:
        return '清爽月历';
      case CalendarPreset.balanced:
        return '均衡';
      case CalendarPreset.journal:
        return '追番手账';
    }
  }

  String get description {
    switch (this) {
      case CalendarPreset.monthFocus:
        return '紧凑月历优先，减少封面与回忆信息';
      case CalendarPreset.balanced:
        return '月历、日程与历史记录保持平衡';
      case CalendarPreset.journal:
        return '日程优先并放大记录，更适合回顾';
    }
  }

  static CalendarPreset? fromPersistKey(String? key) {
    for (final preset in values) {
      if (preset.persistKey == key) return preset;
    }
    return null;
  }
}

enum CalendarMarkerStyle {
  tint,
  dots,
  count;

  String get persistKey => name;

  String get label {
    switch (this) {
      case CalendarMarkerStyle.tint:
        return '色彩底纹';
      case CalendarMarkerStyle.dots:
        return '事件圆点';
      case CalendarMarkerStyle.count:
        return '数量角标';
    }
  }

  static CalendarMarkerStyle fromPersistKey(String? key) {
    return values.firstWhere(
      (style) => style.persistKey == key,
      orElse: () => CalendarMarkerStyle.tint,
    );
  }
}

const calendarModuleKeys = <String>['month', 'schedule', 'history'];
const calendarDefaultOrder = <String>['month', 'schedule', 'history'];

PageDisplayConfig calendarDefaults() {
  return const PageDisplayConfig(
    preset: 'balanced',
    density: DisplayDensity.comfortable,
    moduleOrder: calendarDefaultOrder,
    options: {
      'marker_style': 'tint',
      'show_covers': 'true',
      'show_legend': 'true',
    },
  );
}

PageDisplayConfig calendarPresetConfig(CalendarPreset preset) {
  switch (preset) {
    case CalendarPreset.monthFocus:
      return const PageDisplayConfig(
        preset: 'monthFocus',
        density: DisplayDensity.compact,
        moduleOrder: calendarDefaultOrder,
        hiddenModules: {'history'},
        options: {
          'marker_style': 'dots',
          'show_covers': 'false',
          'show_legend': 'true',
        },
      );
    case CalendarPreset.balanced:
      return calendarDefaults();
    case CalendarPreset.journal:
      return const PageDisplayConfig(
        preset: 'journal',
        density: DisplayDensity.relaxed,
        moduleOrder: ['schedule', 'history', 'month'],
        options: {
          'marker_style': 'count',
          'show_covers': 'true',
          'show_legend': 'false',
        },
      );
  }
}

CalendarPreset? selectedCalendarPreset(PageDisplayConfig config) {
  final preset = CalendarPreset.fromPersistKey(config.preset);
  if (preset == null) return null;
  final expected = calendarPresetConfig(preset);
  if (expected.density != config.density ||
      !_sameOrder(expected.moduleOrder, config.moduleOrder) ||
      expected.hiddenModules.length != config.hiddenModules.length ||
      !expected.hiddenModules.containsAll(config.hiddenModules) ||
      expected.options['marker_style'] != config.options['marker_style'] ||
      expected.options['show_covers'] != config.options['show_covers'] ||
      expected.options['show_legend'] != config.options['show_legend']) {
    return null;
  }
  return preset;
}

CalendarMarkerStyle calendarMarkerStyle(PageDisplayConfig config) {
  return CalendarMarkerStyle.fromPersistKey(config.options['marker_style']);
}

bool calendarShowCovers(PageDisplayConfig config) {
  return config.options['show_covers'] != 'false';
}

bool calendarShowLegend(PageDisplayConfig config) {
  return config.options['show_legend'] != 'false';
}

PageDisplayConfig markCalendarCustom(PageDisplayConfig config) {
  return config.copyWith(preset: 'custom');
}

bool _sameOrder(List<String> left, List<String> right) {
  if (left.length != right.length) return false;
  for (var index = 0; index < left.length; index++) {
    if (left[index] != right[index]) return false;
  }
  return true;
}
