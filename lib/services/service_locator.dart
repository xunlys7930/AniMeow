import 'package:get_it/get_it.dart';

import '../db/database_helper.dart';
import '../repositories/anime_repository.dart';
import '../repositories/character_repository.dart';
import '../repositories/series_repository.dart';
import '../repositories/tag_repository.dart';
import '../repositories/watch_repository.dart';

final getIt = GetIt.instance;

void setupServiceLocator() {
  if (!getIt.isRegistered<DatabaseHelper>()) {
    getIt.registerLazySingleton<DatabaseHelper>(() => DatabaseHelper());
  }
  if (!getIt.isRegistered<AnimeRepository>()) {
    getIt.registerLazySingleton<AnimeRepository>(
      () => AnimeRepository(dbHelper: getIt<DatabaseHelper>()),
    );
  }
  if (!getIt.isRegistered<TagRepository>()) {
    getIt.registerLazySingleton<TagRepository>(
      () => TagRepository(dbHelper: getIt<DatabaseHelper>()),
    );
  }
  if (!getIt.isRegistered<SeriesRepository>()) {
    getIt.registerLazySingleton<SeriesRepository>(
      () => SeriesRepository(dbHelper: getIt<DatabaseHelper>()),
    );
  }
  if (!getIt.isRegistered<WatchRepository>()) {
    getIt.registerLazySingleton<WatchRepository>(
      () => WatchRepository(dbHelper: getIt<DatabaseHelper>()),
    );
  }
  if (!getIt.isRegistered<CharacterRepository>()) {
    getIt.registerLazySingleton<CharacterRepository>(
      () => CharacterRepository(dbHelper: getIt<DatabaseHelper>()),
    );
  }
}
