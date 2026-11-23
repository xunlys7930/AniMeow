package com.animeow.app.data.local

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.animeow.app.data.CharacterRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CharacterRelationshipStorageTest {
    @Test
    fun migrationKeepsLegacyRelationsAndImages() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(RuntimeEnvironment.getApplication())
                .callback(object : SupportSQLiteOpenHelper.Callback(13) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE characters(id INTEGER PRIMARY KEY, name TEXT, imageUrl TEXT)")
                        db.execSQL("CREATE TABLE character_relations(id INTEGER PRIMARY KEY, relationType TEXT)")
                        db.execSQL("INSERT INTO characters VALUES(1, '原角色', 'image')")
                        db.execSQL("INSERT INTO character_relations VALUES(1, '朋友')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            AniMeowDatabase.MIGRATION_13_14.migrate(db)
            db.query("SELECT imageUrl, imageDisplayMode FROM characters").use {
                assertTrue(it.moveToFirst())
                assertEquals("image", it.getString(0))
                assertTrue(it.isNull(1))
            }
            db.query("SELECT relationType, sourceRole, targetRole FROM character_relations").use {
                assertTrue(it.moveToFirst())
                assertEquals("朋友", it.getString(0))
                assertTrue(it.isNull(1))
                assertTrue(it.isNull(2))
            }
        } finally { helper.close() }
    }

    @Test
    fun reversedIdOrderAndOppositeViewKeepCorrectRoles() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            dao.insertCharacter(CharacterEntity(id = 9, name = "老师", imageDisplayMode = "fit"))
            dao.insertCharacter(CharacterEntity(id = 2, name = "学生"))
            val repository = CharacterRepository(db)
            repository.upsertRelation(9, 2, "师生", null, 3, "老师", "学生")
            val teacherView = dao.observeRelationsForCharacter(9).first().single()
            val studentView = dao.observeRelationsForCharacter(2).first().single()
            assertEquals("学生", teacherView.relation.roleOf(teacherView.relatedCharacterId))
            assertEquals("老师", studentView.relation.roleOf(studentView.relatedCharacterId))
            assertEquals("fit", studentView.relatedImageDisplayMode)
            // Saving from the opposite end updates the same relationship, preserving intentional blank wording.
            repository.upsertRelation(2, 9, "学生", "备注", 4, "学生", "")
            assertEquals("", dao.observeRelationsForCharacter(2).first().single().relation.roleOf(9))
            assertEquals("学生", dao.observeRelationsForCharacter(9).first().single().relation.roleOf(2))
            assertEquals(1, dao.observeRelationsForCharacter(9).first().size)
            val legacy = CharacterRelationEntity(sourceCharacterId = 2, targetCharacterId = 9, relationType = "朋友")
            assertEquals("朋友", legacy.roleOf(2))
            assertEquals("朋友", legacy.roleOf(9))
        } finally { db.close() }
    }

    @Test
    fun mergingCharactersPreservesRolesAcrossIdReorderingAndBlocksConflicts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AniMeowDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.libraryDao()
            dao.insertCharacters(listOf(CharacterEntity(id = 1, name = "保留"),
                CharacterEntity(id = 8, name = "来源", imageDisplayMode = "fit"),
                CharacterEntity(id = 5, name = "学生"), CharacterEntity(id = 10, name = "冲突来源")))
            val repository = CharacterRepository(db)
            repository.upsertRelation(8, 5, "师生", null, 3, "老师", "学生")
            repository.mergeDuplicates(1, listOf(8))
            assertEquals("fit", dao.getCharacter(1)?.imageDisplayMode)
            assertEquals("老师", dao.getCharacterRelation(1, 5)?.roleOf(1))
            assertEquals("学生", dao.getCharacterRelation(1, 5)?.roleOf(5))
            repository.upsertRelation(10, 5, "朋友", null, 3, "朋友", "朋友")
            try {
                repository.mergeDuplicates(1, listOf(10))
                fail("Conflicting roles must not be overwritten")
            } catch (_: IllegalArgumentException) { }
            assertNotNull(dao.getCharacter(10))
            assertEquals("老师", dao.getCharacterRelation(1, 5)?.roleOf(1))
        } finally { db.close() }
    }
}
