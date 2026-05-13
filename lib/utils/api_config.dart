/// 构建期注入的运行时配置。
///
/// 通过 `--dart-define=KEY=value` 在 `flutter run/build` 时传入；
/// 没传则 fallback 到空串（请求会被服务端拒绝，能及早暴露配置缺失）。
///
/// 例：
/// ```
/// flutter run --dart-define=API_TOKEN=xxxxx
/// flutter build apk --release --dart-define=API_TOKEN=xxxxx
/// ```
///
/// 也可在 IDE 的 launch.json / VS Code launch 配置里把 `toolArgs` 设为
/// `["--dart-define=API_TOKEN=..."]`，避免每次手动输入。
class ApiConfig {
  ApiConfig._();

  /// 调用云端代理后端使用的 Bearer Token，对应后端 `process.env.API_TOKEN`。
  static const String apiToken = String.fromEnvironment(
    'API_TOKEN',
    defaultValue: '0ee3976d68f886648ac2bbaf6a6e605dfe843bd6ec0adf547b13f7ebadd221b0',
  );

  /// 是否在构建期注入了 token；false 时云端代理相关接口会失效。
  static bool get hasToken => apiToken.isNotEmpty;
}
