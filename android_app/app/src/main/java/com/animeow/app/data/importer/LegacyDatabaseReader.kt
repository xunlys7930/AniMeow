package com.animeow.app.data.importer

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File

internal class LegacyDatabaseReader {
    fun read(databaseFile: File): LegacyLibrarySnapshot {
        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        )
        return try {
            verifyIntegrity(database)
            val existingTables = readTableNames(database)
            require("animes" in existingTables) {
                "备份中没有找到原版 animes 数据表"
            }

            val rowsByTable = KNOWN_TABLES.associateWith { tableName ->
                if (tableName in existingTables) {
                    readRows(database, tableName)
                } else {
                    emptyList()
                }
            }
            LegacySnapshotMapper.map(
                sourceSchemaVersion = database.version,
                tables = rowsByTable,
            )
        } finally {
            database.close()
        }
    }

    private fun verifyIntegrity(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA quick_check(1)", null).use { cursor ->
            val result = if (cursor.moveToFirst()) cursor.getString(0) else null
            require(result.equals("ok", ignoreCase = true)) {
                "原版数据库完整性检查失败：${result ?: "未知错误"}"
            }
        }
    }

    private fun readTableNames(database: SQLiteDatabase): Set<String> =
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table'",
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    add(cursor.getString(0))
                }
            }
        }

    private fun readRows(
        database: SQLiteDatabase,
        tableName: String,
    ): List<Map<String, Any?>> =
        database.rawQuery("SELECT * FROM `$tableName`", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toRow())
                }
            }
        }

    private fun Cursor.toRow(): Map<String, Any?> = buildMap(columnCount) {
        for (index in 0 until columnCount) {
            val value = when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> getBlob(index)
                else -> getString(index)
            }
            put(getColumnName(index), value)
        }
    }

    private companion object {
        val KNOWN_TABLES = listOf(
            "animes",
            "tags",
            "anime_tags",
            "series",
            "watch_statuses",
            "watch_records",
            "anime_analysis_records",
            "characters",
            "anime_characters",
            "character_relations",
            "character_tags",
            "character_tag_links",
            "character_groups",
            "character_group_characters",
            "character_group_works",
        )
    }
}
