import '../../customization/page_display_config.dart';

const communityModuleKeys = <String>['description', 'downloads'];
const communityDefaultOrder = <String>['description', 'downloads'];

PageDisplayConfig communityDefaults() {
  return const PageDisplayConfig(
    preset: 'balanced',
    density: DisplayDensity.comfortable,
    moduleOrder: communityDefaultOrder,
    options: {'view': 'grid'},
  );
}

enum CommunityGroupView {
  grid,
  list;

  String get persistKey => name;

  String get label {
    switch (this) {
      case CommunityGroupView.grid:
        return '网格';
      case CommunityGroupView.list:
        return '列表';
    }
  }

  static CommunityGroupView fromPersistKey(String? key) {
    return values.firstWhere(
      (view) => view.persistKey == key,
      orElse: () => CommunityGroupView.grid,
    );
  }
}

CommunityGroupView communityGroupView(PageDisplayConfig config) {
  return CommunityGroupView.fromPersistKey(config.options['view']);
}

bool communityShowDescription(PageDisplayConfig config) {
  return !config.hiddenModules.contains('description');
}

bool communityShowDownloads(PageDisplayConfig config) {
  return !config.hiddenModules.contains('downloads');
}

PageDisplayConfig markCommunityCustom(PageDisplayConfig config) {
  return config.copyWith(preset: 'custom');
}
