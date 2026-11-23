package com.optiklik.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(
    tableName = "student_directory",
    primaryKeys = ["tcKimlikNo", "okulAdi"]
)
data class StudentDirectoryEntity(
    val tcKimlikNo: String,
    @ColumnInfo(defaultValue = "")
    val okulAdi: String = "",
    @ColumnInfo(defaultValue = "")
    val sinifSubesi: String = "",
    val okulNo: String,
    val adSoyad: String,
    val updatedAt: Long = System.currentTimeMillis()
)
