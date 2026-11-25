package com.optiklik.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

enum class ReviewReason(val label: String) {
    TOTAL_MISMATCH("Toplam uyuşmazlığı"),
    TC_OKUL_NO_CONFLICT("T.C. ile Okul No uyuşmuyor"),
    CLASS_MISMATCH("Sınıf uyuşmazlığı"),
    MULTI_MARK("Çoklu işaretleme tespit edildi"),
    MAX_SCORE_EXCEEDED("Maksimum soru puanı aşıldı"),
    MISSING_IDENTITY("T.C. veya Okul No kodlanmamış"),
    AMBIGUOUS_MATCH("Belirsiz öğrenci eşleşmesi"),
    UNMATCHED_STUDENT("Öğrenci rehberde bulunamadı"),
    MULTIPLE_FORMS("Sayfa başına tek form yazdırın"),
    DUPLICATE_ON_MIGRATION("Migration sırasında mükerrer kayıt tespit edildi");

    companion object {
        fun fromRaw(raw: String): ReviewReason? = entries.firstOrNull { it.name == raw }

        fun labelFor(raw: String): String = fromRaw(raw)?.label ?: raw
    }
}

@Entity(
    tableName = "exam_results",
    foreignKeys = [
        ForeignKey(
            entity = ExamSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["examSessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["examSessionId"]),
        Index(value = ["examSessionId", "tcKimlikNo"], unique = true),
        Index(value = ["examSessionId", "okulNo"], unique = true)
    ]
)
data class ExamResultEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val examSessionId: Long,
    val tcKimlikNo: String,
    val okulNo: String = "-",
    val adSoyad: String = "Listede Yok",
    val sinifSubesi: String = "",
    val score1: Int,
    val score2: Int,
    val score3: Int,
    val score4: Int,
    val score5: Int,
    val score6: Int = 0,
    val score7: Int = 0,
    val score8: Int = 0,
    val score9: Int = 0,
    val score10: Int = 0,
    val totalScore: Int,
    val codedTotalScore: Int? = null,
    val hasTotalMismatch: Boolean = false,
    val isTcValid: Boolean,
    val needsManualReview: Boolean = false,
    @ColumnInfo(defaultValue = "NONE")
    val matchStatus: String = "NONE",
    @ColumnInfo(defaultValue = "[]")
    val reviewReasons: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val scoresJson: String = "[]",
    @ColumnInfo(defaultValue = "[]")
    val studentAnswersJson: String = "[]",
    val scannedAt: Long = System.currentTimeMillis()
) {
    fun scoresList(): List<Int> = listOf(
        score1, score2, score3, score4, score5, score6, score7, score8, score9, score10
    )

    fun parsedScoresJson(): List<Int> = decodeIntArray(scoresJson)

    fun parsedStudentAnswers(): List<String> = decodeStringArray(studentAnswersJson)

    fun parsedReviewReasons(): List<String> = decodeReviewReasons(reviewReasons)

    fun parsedReviewReasonEnums(): List<ReviewReason> =
        parsedReviewReasons().mapNotNull { ReviewReason.fromRaw(it) }

    fun formattedReviewLabels(): List<String> =
        parsedReviewReasons().map { ReviewReason.labelFor(it) }

    companion object {
        fun encodeReviewReasonEnums(reasons: List<ReviewReason>): String =
            encodeReviewReasons(reasons.map { it.name })

        fun encodeReviewReasons(reasons: List<String>): String {
            val clean = reasons.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            return JSONArray().apply { clean.forEach { put(it) } }.toString()
        }

        fun decodeReviewReasons(json: String): List<String> {
            val trimmed = json.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
            return try {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optString(i).trim()
                        if (item.isNotBlank()) add(item)
                    }
                }
            } catch (_: Exception) {
                trimmed.split("|").map { it.trim() }.filter { it.isNotBlank() }
            }
        }

        fun encodeScoresJson(scores: List<Int>): String =
            JSONArray().apply { scores.forEach { put(it) } }.toString()

        fun encodeStudentAnswersJson(answers: List<String>): String =
            JSONArray().apply { answers.forEach { put(it.trim()) } }.toString()

        fun decodeIntArray(json: String): List<Int> {
            val trimmed = json.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
            return try {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        add(arr.optInt(i, 0))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun decodeStringArray(json: String): List<String> {
            val trimmed = json.trim()
            if (trimmed.isEmpty() || trimmed == "[]") return emptyList()
            return try {
                val arr = JSONArray(trimmed)
                buildList {
                    for (i in 0 until arr.length()) {
                        add(arr.optString(i, "").trim())
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
