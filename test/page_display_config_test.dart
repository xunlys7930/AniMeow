import 'package:anime_tracker/ui/customization/page_display_config.dart';
import 'package:anime_tracker/ui/pages/calendar/calendar_display_config.dart';
import 'package:anime_tracker/ui/pages/character/character_display_config.dart';
import 'package:anime_tracker/ui/pages/community/community_display_config.dart';
import 'package:anime_tracker/ui/pages/editor/anime_editor_display_config.dart';
import 'package:anime_tracker/ui/pages/statistics/statistics_display_config.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('PageDisplayConfig', () {
    const fallback = PageDisplayConfig(
      preset: 'balanced',
      density: DisplayDensity.comfortable,
      moduleOrder: ['overview', 'status', 'tags'],
      options: {'chart': 'donut'},
    );

    test('normalizes unknown, duplicate and newly added modules', () {
      final decoded = PageDisplayConfig.decode(
        '''
        {
          "schemaVersion": 1,
          "preset": "custom",
          "density": "compact",
          "moduleOrder": ["tags", "unknown", "tags"],
          "hiddenModules": ["status", "unknown"],
          "options": {"chart": "ranked"}
        }
        ''',
        fallback: fallback,
        knownModules: const ['overview', 'status', 'tags', 'trend'],
        fallbackOrder: const ['overview', 'status', 'tags', 'trend'],
      );

      expect(decoded.moduleOrder, ['tags', 'overview', 'status', 'trend']);
      expect(decoded.hiddenModules, {'status'});
      expect(decoded.density, DisplayDensity.compact);
      expect(decoded.options['chart'], 'ranked');
    });

    test('falls back safely when persisted json is invalid', () {
      final decoded = PageDisplayConfig.decode(
        '{broken json',
        fallback: fallback,
        knownModules: const ['overview', 'status', 'tags'],
        fallbackOrder: const ['overview', 'status', 'tags'],
      );

      expect(decoded.preset, fallback.preset);
      expect(decoded.moduleOrder, fallback.moduleOrder);
      expect(decoded.density, fallback.density);
    });

    test('controller persists and reloads a page configuration', () async {
      SharedPreferences.setMockInitialValues({});
      final first = PageDisplayController(
        pageId: 'test-page',
        defaults: fallback,
        knownModules: const ['overview', 'status', 'tags'],
        fallbackOrder: const ['overview', 'status', 'tags'],
      );
      await first.load();
      await first.setValue(
        fallback.copyWith(
          preset: 'custom',
          density: DisplayDensity.relaxed,
          moduleOrder: const ['tags', 'overview', 'status'],
          hiddenModules: const {'status'},
        ),
      );

      final second = PageDisplayController(
        pageId: 'test-page',
        defaults: fallback,
        knownModules: const ['overview', 'status', 'tags'],
        fallbackOrder: const ['overview', 'status', 'tags'],
      );
      await second.load();

      expect(second.value.preset, 'custom');
      expect(second.value.density, DisplayDensity.relaxed);
      expect(second.value.moduleOrder, ['tags', 'overview', 'status']);
      expect(second.value.hiddenModules, {'status'});

      first.dispose();
      second.dispose();
    });
  });

  group('statistics display presets', () {
    test('balanced preset is recognized until a module is reordered', () {
      final balanced = statisticsPresetConfig(StatisticsPreset.balanced);
      expect(selectedStatisticsPreset(balanced), StatisticsPreset.balanced);

      final reordered = balanced.copyWith(
        moduleOrder: const ['overview', 'tags', 'status'],
      );
      expect(selectedStatisticsPreset(reordered), isNull);
    });

    test('concise preset hides tags and uses ranked charts', () {
      final concise = statisticsPresetConfig(StatisticsPreset.concise);

      expect(concise.hiddenModules, contains('tags'));
      expect(statisticsStatusChart(concise), StatisticsChartStyle.ranked);
      expect(statisticsTagChart(concise), StatisticsChartStyle.ranked);
    });
  });

  group('calendar display configuration', () {
    test('balanced preset keeps all modules and readable markers', () {
      final balanced = calendarPresetConfig(CalendarPreset.balanced);

      expect(selectedCalendarPreset(balanced), CalendarPreset.balanced);
      expect(balanced.hiddenModules, isEmpty);
      expect(calendarMarkerStyle(balanced), CalendarMarkerStyle.tint);
      expect(calendarShowCovers(balanced), isTrue);
      expect(calendarShowLegend(balanced), isTrue);
    });

    test('journal preset prioritizes entries and supports custom changes', () {
      final journal = calendarPresetConfig(CalendarPreset.journal);

      expect(journal.moduleOrder.first, 'schedule');
      expect(journal.density, DisplayDensity.relaxed);
      expect(calendarMarkerStyle(journal), CalendarMarkerStyle.count);
      expect(calendarShowLegend(journal), isFalse);
      expect(selectedCalendarPreset(journal), CalendarPreset.journal);

      final custom = markCalendarCustom(
        journal.copyWith(hiddenModules: const {'history'}),
      );
      expect(custom.preset, 'custom');
      expect(selectedCalendarPreset(custom), isNull);
    });
  });

  group('anime editor display configuration', () {
    test('complete mode exposes every editor module', () {
      final complete = animeEditorPresetConfig(AnimeEditorPreset.complete);

      expect(selectedAnimeEditorPreset(complete), AnimeEditorPreset.complete);
      expect(complete.hiddenModules, isEmpty);
      expect(animeEditorShowAdvancedHeader(complete), isTrue);
    });

    test('simple mode keeps the required editing path concise', () {
      final simple = animeEditorPresetConfig(AnimeEditorPreset.simple);

      expect(
        simple.hiddenModules,
        containsAll(['tags', 'details', 'reminder']),
      );
      expect(simple.hiddenModules, isNot(contains('basic')));
      expect(simple.hiddenModules, isNot(contains('progress')));
      expect(animeEditorShowAdvancedHeader(simple), isFalse);
      expect(selectedAnimeEditorPreset(simple), AnimeEditorPreset.simple);
    });
  });

  group('character display configuration', () {
    test('defaults to a readable list with metadata and rating', () {
      final config = characterDefaults();

      expect(characterListView(config), CharacterListView.list);
      expect(characterShowMetadata(config), isTrue);
      expect(characterShowRating(config), isTrue);
    });

    test('grid choice and hidden fields are represented by page config', () {
      final defaults = characterDefaults();
      final config = defaults.copyWith(
        options: {...defaults.options, 'view': 'grid'},
        hiddenModules: const {'metadata', 'rating'},
      );

      expect(characterListView(config), CharacterListView.grid);
      expect(characterShowMetadata(config), isFalse);
      expect(characterShowRating(config), isFalse);
    });
  });

  group('community display configuration', () {
    test('defaults to a comfortable grid with full card information', () {
      final config = communityDefaults();

      expect(communityGroupView(config), CommunityGroupView.grid);
      expect(config.density, DisplayDensity.comfortable);
      expect(communityShowDescription(config), isTrue);
      expect(communityShowDownloads(config), isTrue);
    });

    test('list choice and hidden metadata survive in the shared config', () {
      final defaults = communityDefaults();
      final config = markCommunityCustom(
        defaults.copyWith(
          options: {...defaults.options, 'view': 'list'},
          hiddenModules: const {'description', 'downloads'},
        ),
      );

      expect(config.preset, 'custom');
      expect(communityGroupView(config), CommunityGroupView.list);
      expect(communityShowDescription(config), isFalse);
      expect(communityShowDownloads(config), isFalse);
    });
  });
}
