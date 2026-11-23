package com.optiklik.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.optiklik.app.data.local.entity.ExamSessionEntity
import kotlinx.coroutines.flow.Flow

data class ExamSessionWithStats(
    val id: Long,
    val title: String,
    val okulAdi: String,
    val egitimOgretimYili: String,
    val sinavDonemi: String,
    val sinifSubesi: String,
    val dersAdi: String,
    val soruSayisi: Int,
    val soruPuanlari: String,
    val createdAt: Long,
    val paperCount: Int,
    val averageScore: Double
)

@Dao
interface ExamSessionDao {
    @Query(
        """
        SELECT s.id, s.title, s.okulAdi, s.egitimOgretimYili, s.sinavDonemi, s.sinifSubesi, s.dersAdi,
               s.soruSayisi, s.soruPuanlari, s.createdAt,
               COUNT(r.id) AS paperCount,
               COALESCE(AVG(r.totalScore), 0.0) AS averageScore
        FROM exam_sessions s
        LEFT JOIN exam_results r ON s.id = r.examSessionId
        GROUP BY s.id
        ORDER BY s.createdAt DESC
        """
    )
    fun observeSessionsWithStats(): Flow<List<ExamSessionWithStats>>

    @Query("SELECT * FROM exam_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): ExamSessionEntity?

    @Insert
    suspend fun insert(session: ExamSessionEntity): Long

    @Delete
    suspend fun delete(session: ExamSessionEntity)

    @Query("DELETE FROM exam_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
