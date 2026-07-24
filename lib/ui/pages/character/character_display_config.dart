import '../../customization/page_display_config.dart';

const characterModuleKeys = <String>['name', 'metadata', 'rating'];
const characterDefaultOrder = <String>['name', 'metadata', 'rating'];

PageDisplayConfig characterDefaults() {
  return const PageDisplayConfig(
    preset: 'balanced',
    density: DisplayDensity.comfortable,
    moduleOrder: characterDefaultOrder,
    options: {'view': 'list', 'default_tab': 'profile'},
  );
}

enum CharacterListView {
  list,
  grid;

  String get persistKey => name;

  String get label {
    switch (this) {
      case CharacterListView.list:
        return '列表';
      case CharacterListView.grid:
        return '网格';
    }
  }

  static CharacterListView fromPersistKey(String? key) {
    return values.firstWhere(
      (view) => view.persistKey == key,
      orElse: () => CharacterListView.list,
    );
  }
}

CharacterListView characterListView(PageDisplayConfig config) {
  return CharacterListView.fromPersistKey(config.options['view']);
}

bool characterShowMetadata(PageDisplayConfig config) {
  return !config.hiddenModules.contains('metadata');
}

bool characterShowRating(PageDisplayConfig config) {
  return !config.hiddenModules.contains('rating');
}

PageDisplayConfig markCharacterCustom(PageDisplayConfig config) {
  return config.copyWith(preset: 'custom');
}
