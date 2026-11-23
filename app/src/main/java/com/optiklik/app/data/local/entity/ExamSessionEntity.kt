package com.optiklik.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exam_sessions")
data class ExamSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    @ColumnInfo(defaultValue = "")
    val okulAdi: String = "",
    @ColumnInfo(defaultValue = "2026-2027")
    val egitimOgretimYili: String = "2026-2027",
    val sinavDonemi: String = "1. Dönem 1. Yazılı Yoklama",
    val sinifSubesi: String = "",
    @ColumnInfo(defaultValue = "")
    val dersAdi: String = "",
    @ColumnInfo(defaultValue = "10")
    val soruSayisi: Int = 10,
    @ColumnInfo(defaultValue = "10,10,10,10,10,10,10,10,10,10")
    val soruPuanlari: String = "10,10,10,10,10,10,10,10,10,10",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun parsedMaxScores(): List<Int> {
        val count = soruSayisi.coerceIn(1, 10)
        val parsed = soruPuanlari.split(",").mapNotNull { it.trim().toIntOrNull() }
        return List(10) { idx ->
            if (idx < count) parsed.getOrElse(idx) { 10 }.coerceIn(0, 99) else 0
        }
    }
}
