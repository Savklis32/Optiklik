package com.optiklik.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDirectoryDao {
    @Query("SELECT COUNT(*) FROM student_directory")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM student_directory")
    suspend fun getCountOnce(): Int

    @Query(
        """
        SELECT * FROM student_directory
        ORDER BY okulAdi ASC, sinifSubesi ASC, CAST(okulNo AS INTEGER) ASC, okulNo ASC, adSoyad ASC
        """
    )
    fun observeAll(): Flow<List<StudentDirectoryEntity>>

    @Query(
        """
        SELECT * FROM student_directory
        WHERE tcKimlikNo = :tcKimlikNo
        ORDER BY CASE WHEN okulAdi = :okulAdi THEN 0 ELSE 1 END, updatedAt DESC
        LIMIT 1
        """
    )
    suspend fun findByTcPrioritizingSchool(tcKimlikNo: String, okulAdi: String): StudentDirectoryEntity?

    @Query("SELECT * FROM student_directory WHERE tcKimlikNo = :tcKimlikNo ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findByTc(tcKimlikNo: String): StudentDirectoryEntity?

    @Query(
        """
        SELECT * FROM student_directory
        WHERE (:okulAdi = '' OR okulAdi = :okulAdi)
          AND (:sinifSubesi = '' OR sinifSubesi = :sinifSubesi)
        ORDER BY CAST(okulNo AS INTEGER) ASC, okulNo ASC, adSoyad ASC
        """
    )
    suspend fun getStudentsForSchoolAndClass(okulAdi: String, sinifSubesi: String): List<StudentDirectoryEntity>

    @Query("SELECT * FROM student_directory")
    suspend fun getAllOnce(): List<StudentDirectoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(student: StudentDirectoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(students: List<StudentDirectoryEntity>)

    @Delete
    suspend fun delete(student: StudentDirectoryEntity)

    @Query("DELETE FROM student_directory WHERE tcKimlikNo = :tcKimlikNo AND okulAdi = :okulAdi")
    suspend fun deleteByKey(tcKimlikNo: String, okulAdi: String)

    @Query("DELETE FROM student_directory WHERE okulAdi = :okulAdi")
    suspend fun deleteBySchool(okulAdi: String)

    @Query("DELETE FROM student_directory")
    suspend fun clearAll()
}
