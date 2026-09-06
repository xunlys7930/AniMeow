package com.animeow.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AnimeEntity::class,
        TagEntity::class,
        AnimeTagEntity::class,
        SeriesEntity::class,
        WatchStatusEntity::class,
        WatchRecordEntity::class,
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
    ],
    version = 8,
    exportSchema = false,
)
abstract class AniMeowDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao

    companion object {
        @Volatile
        private var instance: AniMeowDatabase? = null

        fun getInstance(context: Context): AniMeowDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AniMeowDatabase::class.java,
                    "animeow.db",
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                ).addCallback(
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
                                    "VALUES (3, '未看', 4294936576, 2)",
                            )
                            db.execSQL(
                                "INSERT INTO watch_statuses (id, name, color, sortOrder) " +
                                    "VALUES (4, '弃坑', 4294198070, 3)",
                            )
                        }
                    },
                ).build().also { instance = it }
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
    }
}
