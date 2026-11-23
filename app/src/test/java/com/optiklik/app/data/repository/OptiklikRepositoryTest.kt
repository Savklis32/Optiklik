package com.optiklik.app.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.optiklik.app.data.local.OptiklikDatabase
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OptiklikRepositoryTest {

    private lateinit var db: OptiklikDatabase
    private lateinit var repository: OptiklikRepository
    private var sessionId: Long = 0L

    @Before
    fun setup() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            OptiklikDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = OptiklikRepository(db)

        sessionId = repository.createSession(
            title = "Coğrafya 1. Yazılı",
            okulAdi = "Atatürk Lisesi",
            egitimOgretimYili = "2026-2027",
            sinavDonemi = "1. Dönem 1. Yazılı Yoklama",
            sinifSubesi = "9-A",
            dersAdi = "Coğrafya"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ambiguousFuzzyMatchMarksAmbiguousAndNeedsManualReview() = runTest {
        repository.upsertDirectoryStudents(
            listOf(
                StudentDirectoryEntity("10000000146", "Atatürk Lisesi", "9-A", "101", "ALİ YILMAZ"),
                StudentDirectoryEntity("10000000148", "Atatürk Lisesi", "9-A", "102", "VELİ DEMİR")
            )
        )

        // 10000000147 is invalid checksum and matches 10/11 digits of BOTH 10000000146 and 10000000148
        val resolved = repository.resolveStudent(sessionId, "10000000147", "")
        assertEquals(MatchStatus.AMBIGUOUS, resolved.matchStatus)
        assertFalse(resolved.isMatchedInDirectory)
        assertTrue(resolved.needsManualReview)
        assertNotNull(resolved.conflictWarning)
    }

    @Test
    fun tcAndOkulNoConflictFlagsManualReviewAndWarning() = runTest {
        repository.upsertDirectoryStudents(
            listOf(
                StudentDirectoryEntity("10000000146", "Atatürk Lisesi", "9-A", "104", "AYŞE YILMAZ")
            )
        )

        val resolved = repository.resolveStudent(sessionId, "10000000146", "999")
        assertEquals(MatchStatus.EXACT_TC, resolved.matchStatus)
        assertTrue(resolved.isMatchedInDirectory)
        assertTrue(resolved.needsManualReview)
        assertNotNull(resolved.conflictWarning)
        assertTrue(resolved.conflictWarning!!.contains("çelişkisi"))
    }

    @Test
    fun duplicateDetectionWorksByTcOrOkulNo() = runTest {
        val initial = ExamResultEntity(
            examSessionId = sessionId,
            tcKimlikNo = "10000000146",
            okulNo = "104",
            adSoyad = "AYŞE YILMAZ",
            sinifSubesi = "9-A",
            score1 = 10, score2 = 10, score3 = 10, score4 = 10, score5 = 10,
            totalScore = 50,
            isTcValid = true
        )
        val firstId = repository.saveResult(initial)

        val dupByOkulNo = repository.findExistingResult(sessionId, "", "00104")
        assertNotNull(dupByOkulNo)
        assertEquals(firstId, dupByOkulNo!!.id)

        val dupByTc = repository.findExistingResult(sessionId, "10000000146", "")
        assertNotNull(dupByTc)
        assertEquals(firstId, dupByTc!!.id)
    }

    @Test
    fun rematchPreservesManualEditsWhenNoDirectoryMatch() = runTest {
        repository.saveResult(
            ExamResultEntity(
                examSessionId = sessionId,
                tcKimlikNo = "99999999999",
                okulNo = "555",
                adSoyad = "ELLE DÜZENLENEN ÖĞRENCİ",
                sinifSubesi = "9-A",
                score1 = 20, score2 = 20, score3 = 20, score4 = 20, score5 = 20,
                totalScore = 100,
                isTcValid = false,
                needsManualReview = true
            )
        )

        val (matched, total) = repository.rematchSessionResults(sessionId)
        assertEquals(0, matched)
        assertEquals(1, total)

        val after = repository.findResultByTc(sessionId, "99999999999")!!
        assertEquals("ELLE DÜZENLENEN ÖĞRENCİ", after.adSoyad)
        assertEquals("555", after.okulNo)
        assertEquals("9-A", after.sinifSubesi)
    }

    @Test
    fun classMismatchProducesWarningWhileAssigningSessionClass() = runTest {
        repository.upsertDirectoryStudents(
            listOf(
                StudentDirectoryEntity("10000000146", "Atatürk Lisesi", "10-B", "104", "AYŞE YILMAZ")
            )
        )

        val resolved = repository.resolveStudent(sessionId, "10000000146", "104")
        assertTrue(resolved.isMatchedInDirectory)
        assertEquals("9-A", resolved.sinifSubesi)
        assertNotNull(resolved.classMismatchWarning)
        assertTrue(resolved.needsManualReview)
        assertNull(resolved.conflictWarning)
    }

    @Test
    fun zeroOkulNoDoesNotCauseFalseDuplicateMatch() = runTest {
        repository.saveResult(
            ExamResultEntity(
                examSessionId = sessionId,
                tcKimlikNo = "10000000146",
                okulNo = "0",
                adSoyad = "ÖĞRENCİ BİR",
                sinifSubesi = "9-A",
                score1 = 10, score2 = 10, score3 = 10, score4 = 10, score5 = 10,
                totalScore = 50,
                isTcValid = true
            )
        )

        val falseMatch = repository.findExistingResult(sessionId, "", "0")
        assertNull(falseMatch)
        val falseMatchZeros = repository.findExistingResult(sessionId, "", "000")
        assertNull(falseMatchZeros)
    }
}
