package com.optiklik.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.optiklik.app.data.local.dao.ExamResultDao
import com.optiklik.app.data.local.dao.ExamSessionDao
import com.optiklik.app.data.local.dao.StudentDirectoryDao
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.local.entity.ExamSessionEntity
import com.optiklik.app.data.local.entity.ReviewReason
import com.optiklik.app.data.local.entity.StudentDirectoryEntity

@Database(
    entities = [
        ExamSessionEntity::class,
        ExamResultEntity::class,
        StudentDirectoryEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class OptiklikDatabase : RoomDatabase() {
    abstract fun examSessionDao(): ExamSessionDao
    abstract fun examResultDao(): ExamResultDao
    abstract fun studentDirectoryDao(): StudentDirectoryDao

    companion object {
        @Volatile
        private var INSTANCE: OptiklikDatabase? = null

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN okulAdi TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN egitimOgretimYili TEXT NOT NULL DEFAULT '2026-2027'")
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN dersAdi TEXT NOT NULL DEFAULT ''")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `student_directory_new` (
                        `tcKimlikNo` TEXT NOT NULL,
                        `okulAdi` TEXT NOT NULL DEFAULT '',
                        `sinifSubesi` TEXT NOT NULL DEFAULT '',
                        `okulNo` TEXT NOT NULL,
                        `adSoyad` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`tcKimlikNo`, `okulAdi`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO `student_directory_new` (`tcKimlikNo`, `okulAdi`, `sinifSubesi`, `okulNo`, `adSoyad`, `updatedAt`)
                    SELECT `tcKimlikNo`, '', '', `okulNo`, `adSoyad`, `updatedAt` FROM `student_directory`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `student_directory`")
                db.execSQL("ALTER TABLE `student_directory_new` RENAME TO `student_directory`")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN soruSayisi INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN soruPuanlari TEXT NOT NULL DEFAULT '10,10,10,10,10,10,10,10,10,10'")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam_results ADD COLUMN warningsText TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `exam_results_new` (
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
                        `matchStatus` TEXT NOT NULL DEFAULT 'NONE',
                        `reviewReasons` TEXT NOT NULL DEFAULT '[]',
                        `scannedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`examSessionId`) REFERENCES `exam_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )

                val seenTc = HashSet<Pair<Long, String>>()
                val seenOkulNo = HashSet<Pair<Long, String>>()

                db.query(
                    """
                    SELECT id, examSessionId, tcKimlikNo, okulNo, adSoyad, sinifSubesi,
                           score1, score2, score3, score4, score5, score6, score7, score8, score9, score10,
                           totalScore, codedTotalScore, hasTotalMismatch, isTcValid, needsManualReview,
                           warningsText, scannedAt
                    FROM exam_results
                    ORDER BY id ASC
                    """.trimIndent()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val sessionId = cursor.getLong(1)
                        var tc = cursor.getString(2).orEmpty()
                        var okulNo = cursor.getString(3).orEmpty()
                        val adSoyad = cursor.getString(4).orEmpty()
                        val sinif = cursor.getString(5).orEmpty()
                        val scores = IntArray(10) { i -> cursor.getInt(6 + i) }
                        val totalScore = cursor.getInt(16)
                        val codedTotal = if (cursor.isNull(17)) null else cursor.getInt(17)
                        val hasMismatch = cursor.getInt(18)
                        val isTcValid = cursor.getInt(19)
                        var needsReview = cursor.getInt(20)
                        val oldWarnings = cursor.getString(21).orEmpty()
                        val scannedAt = cursor.getLong(22)

                        val reasons = oldWarnings.split("|")
                            .map { it.trim() }
                            .filter { it.isNotBlank() }
                            .toMutableList()

                        var isDuplicate = false
                        val isValidTcForIndex = tc.isNotBlank() && tc != "-" && !tc.contains("?")
                        if (isValidTcForIndex && !seenTc.add(sessionId to tc)) {
                            tc = "${tc}_DUP_$id"
                            isDuplicate = true
                        }
                        val isValidNoForIndex = okulNo.isNotBlank() && okulNo != "-" && !okulNo.contains("?")
                        if (isValidNoForIndex && !seenOkulNo.add(sessionId to okulNo)) {
                            okulNo = "${okulNo}_DUP_$id"
                            isDuplicate = true
                        }
                        if (isDuplicate) {
                            needsReview = 1
                            reasons.add(ReviewReason.DUPLICATE_ON_MIGRATION.name)
                        }

                        val reasonsJson = ExamResultEntity.encodeReviewReasons(reasons)
                        val matchStatus = if (adSoyad.isNotBlank() && adSoyad != "Listede Yok" && adSoyad != "Belirsiz Eşleşme") {
                            "EXACT_TC"
                        } else {
                            "NONE"
                        }

                        db.execSQL(
                            """
                            INSERT INTO `exam_results_new` (
                                `id`, `examSessionId`, `tcKimlikNo`, `okulNo`, `adSoyad`, `sinifSubesi`,
                                `score1`, `score2`, `score3`, `score4`, `score5`, `score6`, `score7`, `score8`, `score9`, `score10`,
                                `totalScore`, `codedTotalScore`, `hasTotalMismatch`, `isTcValid`, `needsManualReview`,
                                `matchStatus`, `reviewReasons`, `scannedAt`
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                id, sessionId, tc, okulNo, adSoyad, sinif,
                                scores[0], scores[1], scores[2], scores[3], scores[4],
                                scores[5], scores[6], scores[7], scores[8], scores[9],
                                totalScore, codedTotal, hasMismatch, isTcValid, needsReview,
                                matchStatus, reasonsJson, scannedAt
                            )
                        )
                    }
                }

                db.execSQL("DROP TABLE `exam_results`")
                db.execSQL("ALTER TABLE `exam_results_new` RENAME TO `exam_results`")
                applyPartialUniqueIndexes(db)
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `student_roster`")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN examType TEXT NOT NULL DEFAULT 'WRITTEN'")
                db.execSQL("ALTER TABLE exam_sessions ADD COLUMN cevapAnahtari TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exam_results ADD COLUMN scoresJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE exam_results ADD COLUMN studentAnswersJson TEXT NOT NULL DEFAULT '[]'")
            }
        }

        fun applyPartialUniqueIndexes(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_exam_results_examSessionId` ON `exam_results` (`examSessionId`)")
            db.execSQL("DROP INDEX IF EXISTS `index_exam_results_examSessionId_tcKimlikNo`")
            db.execSQL("DROP INDEX IF EXISTS `index_exam_results_examSessionId_okulNo`")
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_exam_results_examSessionId_tcKimlikNo`
                ON `exam_results` (`examSessionId`, `tcKimlikNo`)
                WHERE `tcKimlikNo` NOT IN ('', '-') AND `matchStatus` IN ('EXACT_TC', 'OKUL_NO', 'FUZZY_UNIQUE')
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE UNIQUE INDEX IF NOT EXISTS `index_exam_results_examSessionId_okulNo`
                ON `exam_results` (`examSessionId`, `okulNo`)
                WHERE `okulNo` NOT IN ('', '-', '0') AND `matchStatus` IN ('EXACT_TC', 'OKUL_NO', 'FUZZY_UNIQUE')
                """.trimIndent()
            )
        }

        fun getInstance(context: Context): OptiklikDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    OptiklikDatabase::class.java,
                    "optiklik.db"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            applyPartialUniqueIndexes(db)
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
