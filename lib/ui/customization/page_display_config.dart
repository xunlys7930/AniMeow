import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum DisplayDensity {
  compact,
  comfortable,
  relaxed;

  String get persistKey => name;

  String get label {
    switch (this) {
      case DisplayDensity.compact:
        return '紧凑';
      case DisplayDensity.comfortable:
        return '舒适';
      case DisplayDensity.relaxed:
        return '宽松';
    }
  }

  static DisplayDensity fromPersistKey(String? key) {
    return values.firstWhere(
      (density) => density.persistKey == key,
      orElse: () => DisplayDensity.comfortable,
    );
  }
}

/// 通用页面显示配置。
///
/// 页面只需要定义自己的模块 key、预设名和 options；排序、显隐、密度、
/// 持久化和新模块迁移由这一层统一处理。
@immutable
class PageDisplayConfig {
  static const int currentSchemaVersion = 1;

  final int schemaVersion;
  final String preset;
  final DisplayDensity density;
  final List<String> moduleOrder;
  final Set<String> hiddenModules;
  final Map<String, String> options;

  const PageDisplayConfig({
    this.schemaVersion = currentSchemaVersion,
    required this.preset,
    required this.density,
    required this.moduleOrder,
    this.hiddenModules = const <String>{},
    this.options = const <String, String>{},
  });

  PageDisplayConfig copyWith({
    int? schemaVersion,
    String? preset,
    DisplayDensity? density,
    List<String>? moduleOrder,
    Set<String>? hiddenModules,
    Map<String, String>? options,
  }) {
    return PageDisplayConfig(
      schemaVersion: schemaVersion ?? this.schemaVersion,
      preset: preset ?? this.preset,
      density: density ?? this.density,
      moduleOrder: moduleOrder ?? this.moduleOrder,
      hiddenModules: hiddenModules ?? this.hiddenModules,
      options: options ?? this.options,
    );
  }

  PageDisplayConfig normalized({
    required List<String> knownModules,
    required List<String> fallbackOrder,
  }) {
    final known = knownModules.toSet();
    final seen = <String>{};
    final normalizedOrder = <String>[];

    void append(String key) {
      if (known.contains(key) && seen.add(key)) normalizedOrder.add(key);
    }

    for (final key in moduleOrder) {
      append(key);
    }
    for (final key in fallbackOrder) {
      append(key);
    }
    for (final key in knownModules) {
      append(key);
    }

    return copyWith(
      schemaVersion: currentSchemaVersion,
      moduleOrder: List.unmodifiable(normalizedOrder),
      hiddenModules: Set.unmodifiable(hiddenModules.where(known.contains)),
      options: Map.unmodifiable(options),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'schemaVersion': schemaVersion,
      'preset': preset,
      'density': density.persistKey,
      'moduleOrder': moduleOrder,
      'hiddenModules': hiddenModules.toList(growable: false),
      'options': options,
    };
  }

  String encode() => jsonEncode(toJson());

  static PageDisplayConfig decode(
    String? raw, {
    required PageDisplayConfig fallback,
    required List<String> knownModules,
    required List<String> fallbackOrder,
  }) {
    if (raw == null || raw.trim().isEmpty) {
      return fallback.normalized(
        knownModules: knownModules,
        fallbackOrder: fallbackOrder,
      );
    }

    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) throw const FormatException('not an object');
      final json = Map<String, dynamic>.from(decoded);
      final moduleOrder = _stringList(json['moduleOrder']);
      final hiddenModules = _stringList(json['hiddenModules']).toSet();
      final rawOptions = json['options'];
      final options = <String, String>{};
      if (rawOptions is Map) {
        for (final entry in rawOptions.entries) {
          options[entry.key.toString()] = entry.value.toString();
        }
      }

      return PageDisplayConfig(
        schemaVersion: _asInt(json['schemaVersion']) ?? currentSchemaVersion,
        preset: json['preset']?.toString() ?? fallback.preset,
        density: DisplayDensity.fromPersistKey(json['density']?.toString()),
        moduleOrder: moduleOrder.isEmpty ? fallback.moduleOrder : moduleOrder,
        hiddenModules: hiddenModules,
        options: options.isEmpty ? fallback.options : options,
      ).normalized(knownModules: knownModules, fallbackOrder: fallbackOrder);
    } catch (_) {
      return fallback.normalized(
        knownModules: knownModules,
        fallbackOrder: fallbackOrder,
      );
    }
  }
}

/// 单个页面的显示配置控制器。
class PageDisplayController extends ChangeNotifier {
  static const String _storagePrefix = 'page_display_config_v1.';

  final String pageId;
  final PageDisplayConfig defaults;
  final List<String> knownModules;
  final List<String> fallbackOrder;

  PageDisplayConfig _value;
  bool _isLoaded = false;
  bool _isDisposed = false;

  PageDisplayController({
    required this.pageId,
    required this.defaults,
    required this.knownModules,
    required this.fallbackOrder,
  }) : _value = defaults.normalized(
         knownModules: knownModules,
         fallbackOrder: fallbackOrder,
       );

  PageDisplayConfig get value => _value;
  bool get isLoaded => _isLoaded;
  String get _storageKey => '$_storagePrefix$pageId';

  Future<void> load() async {
    final preferences = await SharedPreferences.getInstance();
    final loaded = PageDisplayConfig.decode(
      preferences.getString(_storageKey),
      fallback: defaults,
      knownModules: knownModules,
      fallbackOrder: fallbackOrder,
    );
    if (_isDisposed) return;
    _value = loaded;
    _isLoaded = true;
    notifyListeners();
  }

  Future<void> setValue(PageDisplayConfig config) async {
    _value = config.normalized(
      knownModules: knownModules,
      fallbackOrder: fallbackOrder,
    );
    if (!_isDisposed) notifyListeners();

    final preferences = await SharedPreferences.getInstance();
    await preferences.setString(_storageKey, _value.encode());
  }

  Future<void> reset() async {
    _value = defaults.normalized(
      knownModules: knownModules,
      fallbackOrder: fallbackOrder,
    );
    if (!_isDisposed) notifyListeners();

    final preferences = await SharedPreferences.getInstance();
    await preferences.remove(_storageKey);
  }

  @override
  void dispose() {
    _isDisposed = true;
    super.dispose();
  }
}

List<String> _stringList(dynamic value) {
  if (value is! List) return const [];
  return value.map((item) => item.toString()).toList(growable: false);
}

int? _asInt(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  return int.tryParse(value?.toString() ?? '');
}
