package com.animeow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        com.animeow.app.data.MediaSeriesEntity::class,
        com.animeow.app.data.MediaSeriesMember::class,
        com.animeow.app.data.audio.AudioWork::class,
        AnimeEntity::class,
        com.animeow.app.data.games.GameEntity::class,
        com.animeow.app.data.games.GamePendingSync::class,
        TagEntity::class,
        AnimeTagEntity::class,
        SeriesEntity::class,
        WatchStatusEntity::class,
        WatchRecordEntity::class,
        EpisodeDetailEntity::class,
        AnimeAnalysisRecordEntity::class,
        CharacterEntity::class,
        AnimeCharacterEntity::class,
        CharacterRelationEntity::class,
        CharacterTagEntity::class,
        CharacterTagLinkEntity::class,
        CharacterGroupEntity::class,
        CharacterGroupCharacterEntity::class,
        CharacterGroupWorkEntity::class,
        DiagnosticLogEntity::class,
        BangumiPendingSyncEntity::class,
        com.animeow.app.data.reader.BookReadingStateEntity::class,
        com.animeow.app.data.reader.BookBookmarkEntity::class,
    ],
    version = 26,
    exportSchema = true,
)
abstract class AniMeowDatabase : RoomDatabase() {
    abstract fun mediaSeriesDao(): com.animeow.app.data.MediaSeriesDao
    abstract fun audioWorkDao(): com.animeow.app.data.audio.AudioWorkDao
    abstract fun libraryDao(): LibraryDao
    abstract fun gameDao(): com.animeow.app.data.games.GameDao
    abstract fun bookReadingDao(): com.animeow.app.data.reader.BookReadingDao

    companion object {
        internal val ALL_MIGRATIONS: Array<Migration>
            get() = arrayOf(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19,
                MIGRATION_19_20,
                MIGRATION_20_21,
                MIGRATION_21_22,
                MIGRATION_22_23,
                MIGRATION_23_24,
                MIGRATION_24_25,
                MIGRATION_25_26,
            )

        @Volatile
        private var instance: AniMeowDatabase? = null

        fun getInstance(context: Context): AniMeowDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AniMeowDatabase::class.java,
                    "animeow.db",
                ).addMigrations(*ALL_MIGRATIONS).addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL(
                                "INSERT INTO watch_statuses (id, name, color, sortOrder) " +
                                    "VALUES (1, '在看', 4280391411, 0)",
                            )
                            db.execSQL(
                                "INSERT INTO watch_statuses (id, name, color, sortOrder) " +
                                    "VALUES (2, '看完', 4283215696, 1)",
                            )
                            db.execSQL(
                                "INSERT INTO watch_statuses (id, name, color, sortOrder) " +
                                    "VALUES (3, '想看', 4294936576, 2)",
                            )
                            db.execSQL(
                                "INSERT INTO watch_statuses (id, name, color, sortOrder) " +
                                    "VALUES (4, '弃坑', 4294198070, 3)",
                            )
                        }
                    },
                ).build().also { instance = it }
            }

        internal val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN updatedAt INTEGER")
            }
        }

        internal val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_reading_states` (" +
                        "`animeId` INTEGER NOT NULL, " +
                        "`localFileUri` TEXT, " +
                        "`localFileName` TEXT, " +
                        "`fileFormat` TEXT, " +
                        "`fileFingerprint` TEXT, " +
                        "`charsetName` TEXT, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`chapterTitle` TEXT, " +
                        "`totalChapters` INTEGER NOT NULL, " +
                        "`blockIndex` INTEGER NOT NULL, " +
                        "`charOffset` INTEGER NOT NULL, " +
                        "`progressPermille` INTEGER NOT NULL, " +
                        "`lastReadAt` TEXT, " +
                        "`legadoBookUrl` TEXT, " +
                        "`legadoOriginName` TEXT, " +
                        "`legadoAuthor` TEXT, " +
                        "`legadoChapterIndex` INTEGER, " +
                        "`legadoChapterTitle` TEXT, " +
                        "`legadoTotalChapters` INTEGER, " +
                        "`legadoLastReadAt` TEXT, " +
                        "`syncProgressToMain` INTEGER NOT NULL, " +
                        "`updatedAt` TEXT NOT NULL, " +
                        "PRIMARY KEY(`animeId`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_reading_states_legadoBookUrl` ON `book_reading_states` (`legadoBookUrl`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_reading_states_lastReadAt` ON `book_reading_states` (`lastReadAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `book_bookmarks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`animeId` INTEGER NOT NULL, " +
                        "`chapterIndex` INTEGER NOT NULL, " +
                        "`chapterTitle` TEXT NOT NULL, " +
                        "`blockIndex` INTEGER NOT NULL, " +
                        "`charOffset` INTEGER NOT NULL, " +
                        "`snippet` TEXT NOT NULL, " +
                        "`note` TEXT, " +
                        "`createdAt` TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_book_bookmarks_animeId` ON `book_bookmarks` (`animeId`)")
            }
        }

        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN memo TEXT")
                db.execSQL("ALTER TABLE series ADD COLUMN linkedMediaJson TEXT")
                db.execSQL(
                    "DELETE FROM watch_statuses WHERE TRIM(name) = '未看' " +
                        "AND EXISTS (SELECT 1 FROM watch_statuses w2 WHERE TRIM(w2.name) = '想看' AND w2.id != watch_statuses.id)",
                )
                db.execSQL("UPDATE watch_statuses SET name = '想看' WHERE TRIM(name) = '未看'")
                db.execSQL("UPDATE animes SET status = '想看' WHERE TRIM(status) = '未看'")
            }
        }

        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN seriesRole TEXT")
            }
        }

        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN bookReadVolumes INTEGER")
                db.execSQL("ALTER TABLE animes ADD COLUMN bookReadChapters INTEGER")
            }
        }

        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN sourceRatingJson TEXT")
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS media_series (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, syncId TEXT NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_series_syncId ON media_series(syncId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS media_series_members (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, kind TEXT NOT NULL, workSyncId TEXT NOT NULL, seriesId INTEGER, sortOrder INTEGER NOT NULL, updatedAt TEXT NOT NULL, FOREIGN KEY(seriesId) REFERENCES media_series(id) ON UPDATE NO ACTION ON DELETE SET NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_media_series_members_kind_workSyncId ON media_series_members(kind, workSyncId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_series_members_seriesId ON media_series_members(seriesId)")
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_works ADD COLUMN originalTitle TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE games ADD COLUMN tags TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN vndbId TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_games_vndbId ON games (vndbId)")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS audio_works (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, syncId TEXT NOT NULL, title TEXT NOT NULL,
                    coverUrl TEXT NOT NULL, category TEXT NOT NULL, voiceActors TEXT NOT NULL, circle TEXT NOT NULL,
                    releaseDate TEXT NOT NULL, durationSeconds INTEGER NOT NULL, workCode TEXT NOT NULL,
                    sourceUrl TEXT NOT NULL, status TEXT NOT NULL, listenedSeconds INTEGER NOT NULL,
                    rating INTEGER NOT NULL, review TEXT NOT NULL, tags TEXT NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT
                )""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audio_works_syncId ON audio_works(syncId)")
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tags ADD COLUMN sortOrder INTEGER")
                db.execSQL("ALTER TABLE animes ADD COLUMN statusChangedAt INTEGER")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN deletedAt TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN externalSource TEXT")
                db.execSQL("ALTER TABLE animes ADD COLUMN externalId TEXT")
                db.execSQL("ALTER TABLE animes ADD COLUMN externalUrl TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_animes_externalSource_externalId " +
                        "ON animes (externalSource, externalId)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN originalTitle TEXT")
                db.execSQL("ALTER TABLE animes ADD COLUMN synopsis TEXT")
                db.execSQL("ALTER TABLE animes ADD COLUMN mediaFormat TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS app_logs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "level TEXT NOT NULL, message TEXT NOT NULL, stack_trace TEXT, timestamp TEXT NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_app_logs_timestamp ON app_logs(timestamp)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "UPDATE animes SET subjectType = CASE " +
                        "WHEN LOWER(TRIM(subjectType)) IN ('book', 'manga', 'novel') THEN 'book' " +
                        "ELSE 'anime' END",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tags ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN broadcastDay INTEGER")
                db.execSQL("ALTER TABLE animes ADD COLUMN broadcastTime TEXT")
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN pinnedAt INTEGER")
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN mergedSourcesJson TEXT")
                db.execSQL("ALTER TABLE characters ADD COLUMN anilistId INTEGER")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_characters_anilistId ON characters (anilistId)")
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bangumi_pending_sync (" +
                        "animeId INTEGER NOT NULL, " +
                        "subjectId INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, " +
                        "payloadJson TEXT NOT NULL, " +
                        "createdAt TEXT NOT NULL, " +
                        "attempts INTEGER NOT NULL, " +
                        "PRIMARY KEY(animeId, kind))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_bangumi_pending_sync_subjectId " +
                        "ON bangumi_pending_sync (subjectId)",
                )
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS games (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, syncId TEXT NOT NULL, bangumiId INTEGER, title TEXT NOT NULL, originalTitle TEXT NOT NULL, coverUrl TEXT NOT NULL, synopsis TEXT NOT NULL, releaseDate TEXT NOT NULL, platforms TEXT NOT NULL, developer TEXT NOT NULL, publisher TEXT NOT NULL, genre TEXT NOT NULL, score REAL NOT NULL, status TEXT NOT NULL, playMinutes INTEGER NOT NULL, selectedPlatform TEXT NOT NULL, startedOn TEXT NOT NULL, finishedOn TEXT NOT NULL, review TEXT NOT NULL, rating INTEGER NOT NULL, updatedAt TEXT NOT NULL, deletedAt TEXT)")
                db.execSQL("CREATE TABLE IF NOT EXISTS game_pending_sync (gameId INTEGER NOT NULL, gameSyncId TEXT NOT NULL, subjectId INTEGER NOT NULL, ownerUserId INTEGER NOT NULL, bangumiUserId INTEGER NOT NULL, collectionType INTEGER, rate INTEGER, updatedAt TEXT NOT NULL, PRIMARY KEY(gameId, ownerUserId, bangumiUserId))")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_games_syncId ON games (syncId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_games_bangumiId ON games (bangumiId)")
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE characters ADD COLUMN imageDisplayMode TEXT")
                db.execSQL("ALTER TABLE character_relations ADD COLUMN sourceRole TEXT")
                db.execSQL("ALTER TABLE character_relations ADD COLUMN targetRole TEXT")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE animes ADD COLUMN voiceActors TEXT")
                db.execSQL("ALTER TABLE animes ADD COLUMN coverOrientation TEXT")
                db.execSQL("ALTER TABLE anime_characters ADD COLUMN voiceActor TEXT")
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS episode_details (" +
                        "animeId INTEGER NOT NULL, " +
                        "episode INTEGER NOT NULL, " +
                        "title TEXT, " +
                        "durationMinutes INTEGER, " +
                        "synopsis TEXT, " +
                        "review TEXT, " +
                        "rating INTEGER, " +
                        "updatedAt TEXT, " +
                        "PRIMARY KEY(animeId, episode))",
                )
            }
        }
    }
}
