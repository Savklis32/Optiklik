package com.optiklik.app.data.repository

import com.optiklik.app.data.local.OptiklikDatabase
import com.optiklik.app.data.local.dao.ExamResultWithStudent
import com.optiklik.app.data.local.dao.ExamSessionWithStats
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.local.entity.ExamSessionEntity
import com.optiklik.app.data.local.entity.ReviewReason
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import com.optiklik.app.domain.omr.TcKimlikValidator
import kotlinx.coroutines.flow.Flow
import java.util.Locale

enum class MatchStatus {
    EXACT_TC,
    OKUL_NO,
    FUZZY_UNIQUE,
    AMBIGUOUS,
    NONE
}

data class ResolvedStudent(
    val tcKimlikNo: String,
    val okulNo: String,
    val adSoyad: String,
    val sinifSubesi: String,
    val isMatchedInDirectory: Boolean,
    val isTcValid: Boolean,
    val matchStatus: MatchStatus = MatchStatus.NONE,
    val conflictWarning: String? = null,
    val classMismatchWarning: String? = null,
    val reviewReasons: List<ReviewReason> = emptyList(),
    val needsManualReview: Boolean = false
)

class OptiklikRepository(private val db: OptiklikDatabase) {

    private val sessionDao = db.examSessionDao()
    private val resultDao = db.examResultDao()
    private val directoryDao = db.studentDirectoryDao()

    companion object {
        fun normalizeClassBranch(input: String): String {
            val clean = input.trim()
                .uppercase(Locale("tr", "TR"))
                .replace(Regex("[\\s/]+"), "-")
            val match = Regex("^(\\d{1,2})-?([A-ZÇĞİÖŞÜ]+)$").find(clean)
            return if (match != null) {
                "${match.groupValues[1]}-${match.groupValues[2]}"
            } else {
                clean
            }
        }
    }

    fun observeSessionsWithStats(): Flow<List<ExamSessionWithStats>> =
        sessionDao.observeSessionsWithStats()

    suspend fun getSessionById(sessionId: Long): ExamSessionEntity? =
        sessionDao.getSessionById(sessionId)

    suspend fun createSession(
        title: String,
        okulAdi: String = "",
        egitimOgretimYili: String = "2026-2027",
        sinavDonemi: String,
        sinifSubesi: String,
        dersAdi: String = "",
        soruSayisi: Int = 10,
        soruPuanlari: String = "10,10,10,10,10,10,10,10,10,10"
    ): Long = sessionDao.insert(
        ExamSessionEntity(
            title = title.trim(),
            okulAdi = okulAdi.trim(),
            egitimOgretimYili = egitimOgretimYili.trim().ifBlank { "2026-2027" },
            sinavDonemi = sinavDonemi.trim().ifBlank { "1. Dönem 1. Yazılı Yoklama" },
            sinifSubesi = normalizeClassBranch(sinifSubesi),
            dersAdi = dersAdi.trim(),
            soruSayisi = soruSayisi.coerceIn(1, 10),
            soruPuanlari = soruPuanlari.trim().ifBlank { "10,10,10,10,10,10,10,10,10,10" }
        )
    )

    suspend fun deleteSession(sessionId: Long) =
        sessionDao.deleteById(sessionId)

    fun observeResultsWithStudent(examSessionId: Long): Flow<List<ExamResultWithStudent>> =
        resultDao.observeResultsWithStudent(examSessionId)

    suspend fun getResultsWithStudentOnce(examSessionId: Long): List<ExamResultWithStudent> =
        resultDao.getResultsWithStudentOnce(examSessionId)

    suspend fun findResultByTc(examSessionId: Long, tcKimlikNo: String): ExamResultEntity? =
        resultDao.findByTc(examSessionId, tcKimlikNo)

    suspend fun findExistingResult(
        examSessionId: Long,
        tcKimlikNo: String,
        okulNo: String
    ): ExamResultEntity? = resultDao.findByTcOrOkulNo(examSessionId, tcKimlikNo, okulNo)

    suspend fun getResultById(resultId: Long): ExamResultEntity? =
        resultDao.findById(resultId)

    suspend fun getAllDirectoryStudentsOnce(): List<StudentDirectoryEntity> =
        directoryDao.getAllOnce()

    suspend fun saveResult(result: ExamResultEntity): Long =
        resultDao.insert(result)

    suspend fun updateResult(result: ExamResultEntity) =
        resultDao.update(result)

    suspend fun deleteResult(resultId: Long) =
        resultDao.deleteById(resultId)

    suspend fun clearSessionResults(examSessionId: Long) =
        resultDao.deleteAllForSession(examSessionId)

    // Global Student Directory (e-Okul)
    fun observeDirectoryCount(): Flow<Int> =
        directoryDao.observeCount()

    fun observeDirectoryStudents(): Flow<List<StudentDirectoryEntity>> =
        directoryDao.observeAll()

    suspend fun hasDirectoryStudents(): Boolean =
        directoryDao.getCountOnce() > 0

    suspend fun upsertDirectoryStudent(student: StudentDirectoryEntity) {
        directoryDao.upsert(student)
    }

    suspend fun upsertDirectoryStudents(students: List<StudentDirectoryEntity>) {
        if (students.isNotEmpty()) {
            directoryDao.upsertAll(students)
        }
    }

    suspend fun deleteDirectoryStudent(student: StudentDirectoryEntity) {
        directoryDao.delete(student)
    }

    suspend fun deleteDirectoryBySchool(okulAdi: String) {
        directoryDao.deleteBySchool(okulAdi.trim())
    }

    suspend fun getStudentsForSchoolAndClass(
        okulAdi: String,
        sinifSubesi: String
    ): List<StudentDirectoryEntity> =
        directoryDao.getStudentsForSchoolAndClass(
            okulAdi = okulAdi.trim(),
            sinifSubesi = normalizeClassBranch(sinifSubesi)
        )

    suspend fun findDirectoryStudent(
        tcKimlikNo: String,
        okulAdi: String = ""
    ): StudentDirectoryEntity? {
        return if (okulAdi.isNotBlank()) {
            directoryDao.findByTcPrioritizingSchool(tcKimlikNo, okulAdi.trim())
        } else {
            directoryDao.findByTc(tcKimlikNo)
        }
    }

    /**
     * Dual-key, fault-tolerant student resolution:
     * 1. Exact 11-digit T.C. Kimlik No match (scoped to active exam's school)
     * 2. Okul No match (trimming leading zeros, scoped to active exam's school)
     * 3. 1-digit-tolerant T.C. recovery (only if exactly 1 candidate matches 10/11 digits; >1 -> AMBIGUOUS)
     * Detects T.C./Okul No conflicts and class mismatches, never picking randomly.
     */
    suspend fun resolveStudent(
        examSessionId: Long,
        readTc: String,
        readOkulNo: String = ""
    ): ResolvedStudent {
        val session = sessionDao.getSessionById(examSessionId)
        val allStudents = directoryDao.getAllOnce()
        return resolveStudent(session, readTc, readOkulNo, allStudents)
    }

    fun resolveStudent(
        session: ExamSessionEntity?,
        readTc: String,
        readOkulNo: String,
        preloadedDirectory: List<StudentDirectoryEntity>
    ): ResolvedStudent {
        val sinif = session?.sinifSubesi.orEmpty()
        val okulAdi = session?.okulAdi.orEmpty()

        // Multi-school isolation: when active exam has okulAdi, search ONLY within that school.
        val scopedStudents = if (okulAdi.isNotBlank()) {
            preloadedDirectory.filter { it.okulAdi.equals(okulAdi, ignoreCase = true) }
        } else {
            preloadedDirectory
        }

        fun normalizeNo(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            val stripped = trimmed.trimStart('0')
            return stripped.ifEmpty { if (trimmed.all { it == '0' }) "0" else "" }
        }

        fun checkClassMismatch(student: StudentDirectoryEntity): String? {
            if (sinif.isBlank() || student.sinifSubesi.isBlank()) return null
            val normSession = normalizeClassBranch(sinif)
            val normStudent = normalizeClassBranch(student.sinifSubesi)
            return if (!normSession.equals(normStudent, ignoreCase = true)) {
                "Sınıf uyuşmazlığı: Rehber (${student.sinifSubesi}) ≠ Oturum ($sinif)"
            } else {
                null
            }
        }

        val cleanTc = readTc.trim()
        val cleanOkulNo = normalizeNo(readOkulNo)

        // Priority 1: Exact 11-digit T.C. match within scoped school
        if (cleanTc.length == 11 && !cleanTc.contains('?')) {
            val tcMatches = scopedStudents.filter { it.tcKimlikNo == cleanTc }
            val uniqueMatch = when {
                tcMatches.size == 1 -> tcMatches.first()
                tcMatches.size > 1 && sinif.isNotBlank() -> {
                    val classScoped = tcMatches.filter {
                        normalizeClassBranch(it.sinifSubesi).equals(normalizeClassBranch(sinif), ignoreCase = true)
                    }
                    if (classScoped.size == 1) classScoped.first() else null
                }
                else -> null
            }

            if (tcMatches.size > 1 && uniqueMatch == null) {
                return ResolvedStudent(
                    tcKimlikNo = cleanTc,
                    okulNo = cleanOkulNo.ifBlank { "-" },
                    adSoyad = "Belirsiz Eşleşme",
                    sinifSubesi = sinif,
                    isMatchedInDirectory = false,
                    isTcValid = TcKimlikValidator.isValid(cleanTc),
                    matchStatus = MatchStatus.AMBIGUOUS,
                    conflictWarning = "Belirsiz T.C. eşleşmesi (${tcMatches.size} aday)",
                    reviewReasons = listOf(ReviewReason.AMBIGUOUS_MATCH),
                    needsManualReview = true
                )
            }

            if (uniqueMatch != null) {
                val directoryNo = normalizeNo(uniqueMatch.okulNo)
                val conflict = if (cleanOkulNo.isNotBlank() && !cleanOkulNo.contains('?') &&
                    directoryNo.isNotEmpty() && directoryNo != cleanOkulNo
                ) {
                    "T.C./Okul No çelişkisi: Okunan ($cleanOkulNo) ≠ Rehber (${uniqueMatch.okulNo})"
                } else {
                    null
                }
                val classMismatch = checkClassMismatch(uniqueMatch)
                val reasons = buildList {
                    if (conflict != null) add(ReviewReason.TC_OKUL_NO_CONFLICT)
                    if (classMismatch != null) add(ReviewReason.CLASS_MISMATCH)
                }
                return ResolvedStudent(
                    tcKimlikNo = uniqueMatch.tcKimlikNo,
                    okulNo = uniqueMatch.okulNo.ifBlank { cleanOkulNo.ifBlank { "-" } },
                    adSoyad = uniqueMatch.adSoyad.ifBlank { "Listede Yok" },
                    sinifSubesi = sinif.ifBlank { uniqueMatch.sinifSubesi },
                    isMatchedInDirectory = true,
                    isTcValid = TcKimlikValidator.isValid(uniqueMatch.tcKimlikNo),
                    matchStatus = MatchStatus.EXACT_TC,
                    conflictWarning = conflict,
                    classMismatchWarning = classMismatch,
                    reviewReasons = reasons,
                    needsManualReview = reasons.isNotEmpty()
                )
            }
        }

        // Priority 2: Okul No match (leading zeros stripped)
        if (cleanOkulNo.isNotBlank() && !cleanOkulNo.contains('?')) {
            val noMatches = scopedStudents.filter { st ->
                val stNo = normalizeNo(st.okulNo)
                stNo.isNotEmpty() && stNo == cleanOkulNo
            }
            val uniqueMatch = when {
                noMatches.size == 1 -> noMatches.first()
                noMatches.size > 1 && sinif.isNotBlank() -> {
                    val classScoped = noMatches.filter {
                        normalizeClassBranch(it.sinifSubesi).equals(normalizeClassBranch(sinif), ignoreCase = true)
                    }
                    if (classScoped.size == 1) classScoped.first() else null
                }
                else -> null
            }

            if (noMatches.size > 1 && uniqueMatch == null) {
                return ResolvedStudent(
                    tcKimlikNo = cleanTc.ifBlank { "-" },
                    okulNo = cleanOkulNo,
                    adSoyad = "Belirsiz Eşleşme",
                    sinifSubesi = sinif,
                    isMatchedInDirectory = false,
                    isTcValid = TcKimlikValidator.isValid(cleanTc),
                    matchStatus = MatchStatus.AMBIGUOUS,
                    conflictWarning = "Belirsiz Okul No ($cleanOkulNo): ${noMatches.size} aday bulundu",
                    reviewReasons = listOf(ReviewReason.AMBIGUOUS_MATCH),
                    needsManualReview = true
                )
            }

            if (uniqueMatch != null) {
                val tcConflict = if (cleanTc.length == 11 && !cleanTc.contains('?') && cleanTc != uniqueMatch.tcKimlikNo) {
                    "Okul No ile eşleşti, okunan T.C. ($cleanTc) rehberle (${uniqueMatch.tcKimlikNo}) uyuşmuyor"
                } else {
                    null
                }
                val classMismatch = checkClassMismatch(uniqueMatch)
                val reasons = buildList {
                    if (tcConflict != null) add(ReviewReason.TC_OKUL_NO_CONFLICT)
                    if (classMismatch != null) add(ReviewReason.CLASS_MISMATCH)
                }
                return ResolvedStudent(
                    tcKimlikNo = uniqueMatch.tcKimlikNo,
                    okulNo = uniqueMatch.okulNo.ifBlank { cleanOkulNo },
                    adSoyad = uniqueMatch.adSoyad.ifBlank { "Listede Yok" },
                    sinifSubesi = sinif.ifBlank { uniqueMatch.sinifSubesi },
                    isMatchedInDirectory = true,
                    isTcValid = TcKimlikValidator.isValid(uniqueMatch.tcKimlikNo),
                    matchStatus = MatchStatus.OKUL_NO,
                    conflictWarning = tcConflict,
                    classMismatchWarning = classMismatch,
                    reviewReasons = reasons,
                    needsManualReview = reasons.isNotEmpty()
                )
            }
        }

        // Priority 3: 1-digit-tolerant T.C. recovery (10/11 digits match)
        if (cleanTc.length == 11 && !TcKimlikValidator.isValid(cleanTc)) {
            val fuzzyCandidates = scopedStudents.filter { st ->
                st.tcKimlikNo.length == 11 &&
                    (0 until 11).count { i -> cleanTc[i] == st.tcKimlikNo[i] } >= 10
            }
            if (fuzzyCandidates.size > 1) {
                return ResolvedStudent(
                    tcKimlikNo = cleanTc,
                    okulNo = cleanOkulNo.ifBlank { "-" },
                    adSoyad = "Belirsiz Eşleşme",
                    sinifSubesi = sinif,
                    isMatchedInDirectory = false,
                    isTcValid = false,
                    matchStatus = MatchStatus.AMBIGUOUS,
                    conflictWarning = "Belirsiz 10/11 T.C. eşleşmesi (${fuzzyCandidates.size} aday)",
                    reviewReasons = listOf(ReviewReason.AMBIGUOUS_MATCH),
                    needsManualReview = true
                )
            }
            if (fuzzyCandidates.size == 1) {
                val match = fuzzyCandidates.first()
                val classMismatch = checkClassMismatch(match)
                val reasons = buildList {
                    if (classMismatch != null) add(ReviewReason.CLASS_MISMATCH)
                }
                return ResolvedStudent(
                    tcKimlikNo = match.tcKimlikNo,
                    okulNo = match.okulNo.ifBlank { cleanOkulNo.ifBlank { "-" } },
                    adSoyad = match.adSoyad.ifBlank { "Listede Yok" },
                    sinifSubesi = sinif.ifBlank { match.sinifSubesi },
                    isMatchedInDirectory = true,
                    isTcValid = TcKimlikValidator.isValid(match.tcKimlikNo),
                    matchStatus = MatchStatus.FUZZY_UNIQUE,
                    conflictWarning = "10/11 hane T.C. kurtarma ile eşleşti",
                    classMismatchWarning = classMismatch,
                    reviewReasons = reasons,
                    needsManualReview = true
                )
            }
        }

        return ResolvedStudent(
            tcKimlikNo = cleanTc.ifBlank { "-" },
            okulNo = cleanOkulNo.ifBlank { "-" },
            adSoyad = "Listede Yok",
            sinifSubesi = sinif,
            isMatchedInDirectory = false,
            isTcValid = TcKimlikValidator.isValid(cleanTc),
            matchStatus = MatchStatus.NONE,
            reviewReasons = listOf(ReviewReason.UNMATCHED_STUDENT),
            needsManualReview = true
        )
    }

    suspend fun resolveStudentForTc(
        examSessionId: Long,
        tcKimlikNo: String
    ): Triple<String, String, String> {
        val resolved = resolveStudent(examSessionId, tcKimlikNo, "")
        return Triple(resolved.okulNo, resolved.adSoyad, resolved.sinifSubesi)
    }

    /**
     * Re-matches all existing results in the exam session against the global student directory
     * while preserving manually entered fields (adSoyad, okulNo, sinifSubesi) if no match is found.
     * Returns Pair(matchedCount, totalCount).
     */
    suspend fun rematchSessionResults(examSessionId: Long): Pair<Int, Int> {
        val session = sessionDao.getSessionById(examSessionId)
        val results = resultDao.getResultsOnce(examSessionId)
        if (results.isEmpty()) return 0 to 0
        val directory = directoryDao.getAllOnce()

        var matched = 0
        val updated = results.map { row ->
            val resolved = resolveStudent(session, row.tcKimlikNo, row.okulNo, directory)
            if (resolved.isMatchedInDirectory) {
                matched++
                val existingTotalReason = if (row.hasTotalMismatch) listOf(ReviewReason.TOTAL_MISMATCH) else emptyList()
                val allReasons = (existingTotalReason + resolved.reviewReasons).distinct()
                row.copy(
                    tcKimlikNo = resolved.tcKimlikNo,
                    okulNo = resolved.okulNo,
                    adSoyad = resolved.adSoyad,
                    sinifSubesi = resolved.sinifSubesi,
                    isTcValid = resolved.isTcValid,
                    needsManualReview = row.hasTotalMismatch || resolved.needsManualReview,
                    matchStatus = resolved.matchStatus.name,
                    reviewReasons = ExamResultEntity.encodeReviewReasonEnums(allReasons)
                )
            } else {
                // Preserve user's manually edited fields; only fill "Listede Yok" / "-" if blank
                val preservedName = row.adSoyad.ifBlank { "Listede Yok" }
                val preservedNo = row.okulNo.ifBlank { "-" }
                val preservedClass = row.sinifSubesi.ifBlank { session?.sinifSubesi.orEmpty() }
                val existingReasons = row.parsedReviewReasonEnums()
                val allReasons = (existingReasons + resolved.reviewReasons).distinct()
                row.copy(
                    adSoyad = preservedName,
                    okulNo = preservedNo,
                    sinifSubesi = preservedClass,
                    needsManualReview = row.needsManualReview || resolved.matchStatus == MatchStatus.AMBIGUOUS,
                    matchStatus = resolved.matchStatus.name,
                    reviewReasons = ExamResultEntity.encodeReviewReasonEnums(allReasons)
                )
            }
        }
        resultDao.updateAll(updated)
        return matched to results.size
    }
}
