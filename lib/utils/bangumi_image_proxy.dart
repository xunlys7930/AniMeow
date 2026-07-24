import '../settings_manager.dart';

String? proxyBangumiImageUrl(String? url) {
  if (url == null) return null;

  var value = url.trim();
  if (value.isEmpty) return null;

  if (value.startsWith('//')) {
    value = 'https:$value';
  }

  final settings = SettingsManager();
  if (!settings.useBangumiImageProxyNotifier.value) {
    return value;
  }

  final uri = Uri.tryParse(value);
  if (uri == null || uri.host != 'lain.bgm.tv') {
    return value;
  }

  var base = settings.bangumiImageProxyBaseNotifier.value.trim();
  if (base.isEmpty) {
    base = BangumiSettingsDefaults.imageProxyBase;
  }
  while (base.endsWith('/')) {
    base = base.substring(0, base.length - 1);
  }

  final proxyUri = Uri.tryParse('$base${uri.path}');
  if (proxyUri == null) return value;

  return proxyUri
      .replace(
        query: uri.query.isEmpty ? null : uri.query,
        fragment: uri.fragment.isEmpty ? null : uri.fragment,
      )
      .toString();
}
