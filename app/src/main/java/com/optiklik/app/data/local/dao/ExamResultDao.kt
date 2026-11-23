package com.optiklik.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.optiklik.app.data.local.entity.ExamResultEntity
import kotlinx.coroutines.flow.Flow

data class ExamResultWithStudent(
    @Embedded val result: ExamResultEntity,
    val schoolNo: String?,
    val fullName: String?
)

@Dao
interface ExamResultDao {
    @Query(
        """
        SELECT r.*,
               COALESCE(
                   NULLIF(NULLIF(r.okulNo, ''), '-'),
                   (SELECT sd.okulNo FROM student_directory sd
                    WHERE sd.tcKimlikNo = r.tcKimlikNo
                    ORDER BY CASE WHEN sd.okulAdi = (SELECT okulAdi FROM exam_sessions WHERE id = :examSessionId) THEN 0 ELSE 1 END, sd.updatedAt DESC
                    LIMIT 1),
                   '-'
               ) AS schoolNo,
               COALESCE(
                   NULLIF(NULLIF(r.adSoyad, ''), 'Listede Yok'),
                   (SELECT sd.adSoyad FROM student_directory sd
                    WHERE sd.tcKimlikNo = r.tcKimlikNo
                    ORDER BY CASE WHEN sd.okulAdi = (SELECT okulAdi FROM exam_sessions WHERE id = :examSessionId) THEN 0 ELSE 1 END, sd.updatedAt DESC
                    LIMIT 1),
                   'Listede Yok'
               ) AS fullName
        FROM exam_results r
        WHERE r.examSessionId = :examSessionId
        ORDER BY r.scannedAt DESC
        """
    )
    fun observeResultsWithStudent(examSessionId: Long): Flow<List<ExamResultWithStudent>>

    @Query(
        """
        SELECT r.*,
               COALESCE(
                   NULLIF(NULLIF(r.okulNo, ''), '-'),
                   (SELECT sd.okulNo FROM student_directory sd
                    WHERE sd.tcKimlikNo = r.tcKimlikNo
                    ORDER BY CASE WHEN sd.okulAdi = (SELECT okulAdi FROM exam_sessions WHERE id = :examSessionId) THEN 0 ELSE 1 END, sd.updatedAt DESC
                    LIMIT 1),
                   '-'
               ) AS schoolNo,
               COALESCE(
                   NULLIF(NULLIF(r.adSoyad, ''), 'Listede Yok'),
                   (SELECT sd.adSoyad FROM student_directory sd
                    WHERE sd.tcKimlikNo = r.tcKimlikNo
                    ORDER BY CASE WHEN sd.okulAdi = (SELECT okulAdi FROM exam_sessions WHERE id = :examSessionId) THEN 0 ELSE 1 END, sd.updatedAt DESC
                    LIMIT 1),
                   'Listede Yok'
               ) AS fullName
        FROM exam_results r
        WHERE r.examSessionId = :examSessionId
        ORDER BY COALESCE(
            NULLIF(NULLIF(r.okulNo, ''), '-'),
            (SELECT sd.okulNo FROM student_directory sd
             WHERE sd.tcKimlikNo = r.tcKimlikNo
             ORDER BY CASE WHEN sd.okulAdi = (SELECT okulAdi FROM exam_sessions WHERE id = :examSessionId) THEN 0 ELSE 1 END, sd.updatedAt DESC
             LIMIT 1),
            r.tcKimlikNo
        ) ASC
        """
    )
    suspend fun getResultsWithStudentOnce(examSessionId: Long): List<ExamResultWithStudent>

    @Query("SELECT * FROM exam_results WHERE examSessionId = :examSessionId")
    suspend fun getResultsOnce(examSessionId: Long): List<ExamResultEntity>

    @Query("SELECT * FROM exam_results WHERE id = :resultId LIMIT 1")
    suspend fun findById(resultId: Long): ExamResultEntity?

    @Query("SELECT * FROM exam_results WHERE examSessionId = :examSessionId AND tcKimlikNo = :tcKimlikNo LIMIT 1")
    suspend fun findByTc(examSessionId: Long, tcKimlikNo: String): ExamResultEntity?

    @Query(
        """
        SELECT * FROM exam_results
        WHERE examSessionId = :examSessionId
          AND (
              (LENGTH(:tcKimlikNo) = 11 AND INSTR(:tcKimlikNo, '?') = 0 AND tcKimlikNo = :tcKimlikNo)
              OR (:okulNo != '' AND :okulNo != '-' AND LTRIM(:okulNo, '0') != '' AND LTRIM(okulNo, '0') != '' AND LTRIM(okulNo, '0') = LTRIM(:okulNo, '0'))
          )
        ORDER BY id ASC
        LIMIT 1
        """
    )
    suspend fun findByTcOrOkulNo(examSessionId: Long, tcKimlikNo: String, okulNo: String): ExamResultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: ExamResultEntity): Long

    @Update
    suspend fun update(result: ExamResultEntity)

    @Update
    suspend fun updateAll(results: List<ExamResultEntity>)

    @Delete
    suspend fun delete(result: ExamResultEntity)

    @Query("DELETE FROM exam_results WHERE id = :resultId")
    suspend fun deleteById(resultId: Long)

    @Query("DELETE FROM exam_results WHERE examSessionId = :examSessionId")
    suspend fun deleteAllForSession(examSessionId: Long)
}
