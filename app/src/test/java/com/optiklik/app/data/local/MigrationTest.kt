package com.optiklik.app.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.optiklik.app.data.local.entity.ExamResultEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies Room migrations 4->8, 5->8, 6->8, and 7->8.
 * Note: app/schemas contains 5.json, 6.json, 7.json, and 8.json; v4 schema was not
 * exported in earlier versions and is defined as a fixed constant in [createV4Schema].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    private fun createV4Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exam_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `title` TEXT NOT NULL,
                `sinavDonemi` TEXT NOT NULL,
                `sinifSubesi` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `student_directory` (
                `tcKimlikNo` TEXT NOT NULL,
                `okulNo` TEXT NOT NULL,
                `adSoyad` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`tcKimlikNo`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `exam_results` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `examSessionId` INTEGER NOT NULL,
                `tcKimlikNo` TEXT NOT NULL,
                `okulNo` TEXT NOT NULL,
                `adSoyad` TEXT NOT NULL,
                `sinifSubesi` TEXT NOT NULL,
                `score1` INTEGER NOT NULL,
                `score2` INTEGER NOT NULL,
                `score3` INTEGER NOT NULL,
                `score4` INTEGER NOT NULL,
                `score5` INTEGER NOT NULL,
                `score6` INTEGER NOT NULL,
                `score7` INTEGER NOT NULL,
                `score8` INTEGER NOT NULL,
                `score9` INTEGER NOT NULL,
                `score10` INTEGER NOT NULL,
                `totalScore` INTEGER NOT NULL,
                `codedTotalScore` INTEGER,
                `hasTotalMismatch` INTEGER NOT NULL,
                `isTcValid` INTEGER NOT NULL,
                `needsManualReview` INTEGER NOT NULL,
                `scannedAt` INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun openTestDb(
        dbName: String,
        startVersion: Int,
        seedBlock: (SupportSQLiteDatabase) -> Unit
    ): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV4Schema(db)
                        if (startVersion >= 5) OptiklikDatabase.MIGRATION_4_5.migrate(db)
                        if (startVersion >= 6) OptiklikDatabase.MIGRATION_5_6.migrate(db)
                        if (startVersion >= 7) OptiklikDatabase.MIGRATION_6_7.migrate(db)
                        seedBlock(db)
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        ).writableDatabase
    }

    @Test
    fun migrate4To8PreservesDataAndAppliesMatchingDefaults() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_4_to_8.db"
        val db = openTestDb(dbName, 4) { d ->
            d.execSQL("INSERT INTO exam_sessions (id, title, sinavDonemi, sinifSubesi, createdAt) VALUES (1, 'Matematik', '1. Dönem', '9-A', 1000)")
            d.execSQL("INSERT INTO student_directory (tcKimlikNo, okulNo, adSoyad, updatedAt) VALUES ('10000000146', '104', 'AYŞE YILMAZ', 1500)")
            d.execSQL(
                """
                INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi,
                    score1, score2, score3, score4, score5, score6, score7, score8, score9, score10,
                    totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, scannedAt)
                VALUES (1, 1, '10000000146', '104', 'AYŞE YILMAZ', '9-A',
                    10, 10, 10, 10, 10, 10, 10, 10, 10, 10, 100, 100, 0, 1, 0, 2000)
                """.trimIndent()
            )
        }

        OptiklikDatabase.MIGRATION_4_5.migrate(db)
        OptiklikDatabase.MIGRATION_5_6.migrate(db)
        OptiklikDatabase.MIGRATION_6_7.migrate(db)
        OptiklikDatabase.MIGRATION_7_8.migrate(db)
        OptiklikDatabase.MIGRATION_8_9.migrate(db)

        db.query("SELECT matchStatus, reviewReasons FROM exam_results WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("EXACT_TC", c.getString(0))
            assertEquals("[]", c.getString(1))
        }
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='student_roster'").use { c ->
            assertEquals(0, c.count)
        }
        db.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun migrate5To9And6To9PreserveData() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // 5 -> 9
        val db5 = openTestDb("migration_5_to_9.db", 5) { d ->
            d.execSQL("INSERT INTO exam_sessions (id, title, okulAdi, egitimOgretimYili, sinavDonemi, sinifSubesi, dersAdi, createdAt) VALUES (1, 'Coğrafya', 'Lise', '2026-2027', '1. Dönem', '9-A', 'Coğrafya', 1000)")
            d.execSQL("INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi, score1, score2, score3, score4, score5, score6, score7, score8, score9, score10, totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, scannedAt) VALUES (1, 1, '-', '-', 'Listede Yok', '9-A', 0,0,0,0,0,0,0,0,0,0, 0, NULL, 0, 0, 1, 2000)")
        }
        OptiklikDatabase.MIGRATION_5_6.migrate(db5)
        OptiklikDatabase.MIGRATION_6_7.migrate(db5)
        OptiklikDatabase.MIGRATION_7_8.migrate(db5)
        OptiklikDatabase.MIGRATION_8_9.migrate(db5)
        db5.query("SELECT matchStatus, reviewReasons FROM exam_results WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("NONE", c.getString(0))
            assertEquals("[]", c.getString(1))
        }
        db5.close()
        context.deleteDatabase("migration_5_to_9.db")

        // 6 -> 9
        val db6 = openTestDb("migration_6_to_9.db", 6) { d ->
            d.execSQL("INSERT INTO exam_sessions (id, title, okulAdi, egitimOgretimYili, sinavDonemi, sinifSubesi, dersAdi, soruSayisi, soruPuanlari, createdAt) VALUES (1, 'Fizik', 'Lise', '2026-2027', '1. Dönem', '9-A', 'Fizik', 5, '20,20,20,20,20', 1000)")
            d.execSQL("INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi, score1, score2, score3, score4, score5, score6, score7, score8, score9, score10, totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, scannedAt) VALUES (1, 1, '10000000146', '104', 'AYŞE YILMAZ', '9-A', 20,20,20,20,20,0,0,0,0,0, 100, 100, 0, 1, 0, 2000)")
        }
        OptiklikDatabase.MIGRATION_6_7.migrate(db6)
        OptiklikDatabase.MIGRATION_7_8.migrate(db6)
        OptiklikDatabase.MIGRATION_8_9.migrate(db6)
        db6.query("SELECT totalScore, matchStatus FROM exam_results WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(100, c.getInt(0))
            assertEquals("EXACT_TC", c.getString(1))
        }
        db6.close()
        context.deleteDatabase("migration_6_to_9.db")
    }

    @Test
    fun migrate7To9HandlesDuplicatesDropsRosterAndConvertsWarningsToJsonArray() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "migration_7_to_9_dup.db"
        val db = openTestDb(dbName, 7) { d ->
            d.execSQL("INSERT INTO exam_sessions (id, title, okulAdi, egitimOgretimYili, sinavDonemi, sinifSubesi, dersAdi, soruSayisi, soruPuanlari, createdAt) VALUES (1, 'Kimya', 'Lise', '2026-2027', '1. Dönem', '9-A', 'Kimya', 5, '20,20,20,20,20', 1000)")
            d.execSQL("INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi, score1, score2, score3, score4, score5, score6, score7, score8, score9, score10, totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, warningsText, scannedAt) VALUES (1, 1, '10000000146', '104', 'AYŞE YILMAZ', '9-A', 20,20,20,20,20,0,0,0,0,0, 100, 100, 0, 1, 0, 'Uyarı 1 | Uyarı 2', 2000)")
            // Duplicate row in same session
            d.execSQL("INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi, score1, score2, score3, score4, score5, score6, score7, score8, score9, score10, totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, warningsText, scannedAt) VALUES (2, 1, '10000000146', '104', 'AYŞE YILMAZ', '9-A', 15,20,20,20,20,0,0,0,0,0, 95, 95, 0, 1, 0, '', 2001)")
        }

        OptiklikDatabase.MIGRATION_7_8.migrate(db)
        OptiklikDatabase.MIGRATION_8_9.migrate(db)

        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='student_roster'").use { c ->
            assertEquals(0, c.count)
        }

        db.query("SELECT reviewReasons FROM exam_results WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            val reasons = ExamResultEntity.decodeReviewReasons(c.getString(0))
            assertEquals(listOf("Uyarı 1", "Uyarı 2"), reasons)
        }

        db.query("SELECT tcKimlikNo, okulNo, needsManualReview, reviewReasons FROM exam_results WHERE id = 2").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("10000000146_DUP_2", c.getString(0))
            assertEquals("104_DUP_2", c.getString(1))
            assertEquals(1, c.getInt(2))
            val reasons = ExamResultEntity.decodeReviewReasons(c.getString(3))
            assertTrue(reasons.contains(com.optiklik.app.data.local.entity.ReviewReason.DUPLICATE_ON_MIGRATION.name))
        }

        // Partial unique index allows multiple '-' rows in the same session
        db.execSQL("INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi, score1, score2, score3, score4, score5, score6, score7, score8, score9, score10, totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, matchStatus, reviewReasons, scannedAt) VALUES (3, 1, '-', '-', 'Listede Yok', '9-A', 0,0,0,0,0,0,0,0,0,0, 0, NULL, 0, 0, 1, 'NONE', '[]', 2002)")
        db.execSQL("INSERT INTO exam_results (id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi, score1, score2, score3, score4, score5, score6, score7, score8, score9, score10, totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview, matchStatus, reviewReasons, scannedAt) VALUES (4, 1, '-', '-', 'Listede Yok', '9-A', 0,0,0,0,0,0,0,0,0,0, 0, NULL, 0, 0, 1, 'NONE', '[]', 2003)")

        db.close()
        context.deleteDatabase(dbName)
    }
}
