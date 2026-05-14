/// 构建期注入的运行时配置。
///
/// 通过 `--dart-define=KEY=value` 在 `flutter run/build` 时传入；
/// 没传则 fallback 到空串（云端能力不可用，本地追番仍正常）。
///
/// 例：
/// ```
/// flutter run \
///   --dart-define=CLOUD_API_BASE=https://your-api.example.com \
///   --dart-define=API_TOKEN=xxxxx
/// ```
///
/// 也可在 IDE 的 launch.json / VS Code launch 配置里把 `toolArgs` 设为
/// `["--dart-define=CLOUD_API_BASE=...", "--dart-define=API_TOKEN=..."]`。
class ApiConfig {
  ApiConfig._();

  /// 自建云端 API 根地址（不含末尾 `/`），与后端部署的对外 URL 一致。
  static const String cloudApiBase = String.fromEnvironment(
    'CLOUD_API_BASE',
    defaultValue: '',
  );

  /// 调用云端代理后端使用的 Bearer Token，对应后端 `process.env.API_TOKEN`。
  static const String apiToken = String.fromEnvironment(
    'API_TOKEN',
    defaultValue: '',
  );

  static bool get hasCloudBase => cloudApiBase.trim().isNotEmpty;

  /// 是否在构建期注入了 token；false 时受保护的云端接口会返回 401。
  static bool get hasToken => apiToken.isNotEmpty;

  /// 与 [cloudApiBase] 拼接路径；未配置 [cloudApiBase] 时返回 null。
  static Uri? cloudUri(String path, [Map<String, String>? queryParameters]) {
    if (!hasCloudBase) return null;
    var base = cloudApiBase.trim();
    while (base.endsWith('/')) {
      base = base.substring(0, base.length - 1);
    }
    final p = path.startsWith('/') ? path : '/$path';
    final parsed = Uri.parse('$base$p');
    if (queryParameters == null || queryParameters.isEmpty) {
      return parsed;
    }
    return parsed.replace(
      queryParameters: {
        ...parsed.queryParameters,
        ...queryParameters,
      },
    );
  }
}
