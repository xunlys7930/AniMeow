/// “趣味评级”使用的独立五档配置。
///
/// 它与作品详情里的数字 / ABCD 评分完全分离。数据库只保存稳定档位代码
/// S / A / B / C / D，用户可以随时修改这里的显示名称，不会影响已经排好的
/// 番剧。
class FunRatingTierConfig {
  static const List<String> codes = ['S', 'A', 'B', 'C', 'D'];
  static const int maxLabelLength = 16;

  static const FunRatingTierConfig letters = FunRatingTierConfig(
    s: 'S',
    a: 'A',
    b: 'B',
    c: 'C',
    d: 'D',
  );

  static const FunRatingTierConfig chinese = FunRatingTierConfig(
    s: '神作',
    a: '优秀',
    b: '良好',
    c: '普通',
    d: '较差',
  );

  static const FunRatingTierConfig english = FunRatingTierConfig(
    s: 'Masterpiece',
    a: 'Great',
    b: 'Good',
    c: 'Fair',
    d: 'Poor',
  );

  final String s;
  final String a;
  final String b;
  final String c;
  final String d;

  const FunRatingTierConfig({
    required this.s,
    required this.a,
    required this.b,
    required this.c,
    required this.d,
  });

  factory FunRatingTierConfig.fromLabels(List<String> labels) {
    final error = validationError(labels);
    if (error != null) {
      throw ArgumentError.value(labels, 'labels', error);
    }
    final normalized = labels.map((label) => label.trim()).toList();
    return FunRatingTierConfig(
      s: normalized[0],
      a: normalized[1],
      b: normalized[2],
      c: normalized[3],
      d: normalized[4],
    );
  }

  static FunRatingTierConfig? fromPersisted(List<String>? labels) {
    if (labels == null || validationError(labels) != null) return null;
    return FunRatingTierConfig.fromLabels(labels);
  }

  static String? validationError(List<String> labels) {
    if (labels.length != codes.length) return '需要填写五个档位名称';

    final normalized = labels.map((label) => label.trim()).toList();
    if (normalized.any((label) => label.isEmpty)) return '档位名称不能为空';
    if (normalized.any(
      (label) => label.contains('\n') || label.contains('\r'),
    )) {
      return '档位名称不能换行';
    }
    if (normalized.any((label) => label.length > maxLabelLength)) {
      return '每个档位名称最多 $maxLabelLength 个字符';
    }

    final unique = normalized.map((label) => label.toLowerCase()).toSet();
    if (unique.length != normalized.length) return '五个档位名称不能重复';
    return null;
  }

  List<String> get labels => [s, a, b, c, d];

  String labelFor(String code) {
    switch (code.toUpperCase()) {
      case 'S':
        return s;
      case 'A':
        return a;
      case 'B':
        return b;
      case 'C':
        return c;
      case 'D':
        return d;
      default:
        return code;
    }
  }

  String editorLabelFor(String code) {
    final label = labelFor(code);
    return label == code ? code : '$code · $label';
  }

  List<String> toPersisted() => labels;

  @override
  bool operator ==(Object other) {
    return other is FunRatingTierConfig &&
        other.s == s &&
        other.a == a &&
        other.b == b &&
        other.c == c &&
        other.d == d;
  }

  @override
  int get hashCode => Object.hash(s, a, b, c, d);
}

String? normalizeFunRatingTier(dynamic value) {
  if (value == null) return null;
  final normalized = value.toString().trim().toUpperCase();
  return FunRatingTierConfig.codes.contains(normalized) ? normalized : null;
}
