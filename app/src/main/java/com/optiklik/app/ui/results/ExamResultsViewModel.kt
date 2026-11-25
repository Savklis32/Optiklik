package com.optiklik.app.ui.results

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.optiklik.app.OptiklikApp
import com.optiklik.app.data.local.dao.ExamResultWithStudent
import com.optiklik.app.data.local.entity.ExamResultEntity
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import com.optiklik.app.data.preferences.TeacherProfilePreferences
import com.optiklik.app.data.repository.OptiklikRepository
import com.optiklik.app.domain.omr.TcKimlikValidator
import com.optiklik.app.service.ExcelExporter
import com.optiklik.app.service.ExportedExcelFile
import com.optiklik.app.service.StudentListParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResultsUiState(
    val examTitle: String = "",
    val examType: String = "WRITTEN",
    val okulAdi: String = "",
    val egitimOgretimYili: String = "",
    val sinavDonemi: String = "",
    val dersAdi: String = "",
    val sinifSubesi: String = "",
    val soruSayisi: Int = 10,
    val maxScores: List<Int> = List(10) { 10 },
    val answerKey: List<String> = emptyList(),
    val isExporting: Boolean = false,
    val statusMessage: String? = null,
    val exportedFile: ExportedExcelFile? = null
)

class ExamResultsViewModel(
    private val examSessionId: Long,
    private val repository: OptiklikRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val studentListParser = StudentListParser(appContext)
    private val excelExporter = ExcelExporter(appContext)
    private val teacherProfilePrefs: TeacherProfilePreferences =
        (appContext as OptiklikApp).teacherProfile

    val results: StateFlow<List<ExamResultWithStudent>> =
        repository.observeResultsWithStudent(examSessionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rosterCount: StateFlow<Int> =
        repository.observeDirectoryCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _uiState = MutableStateFlow(ResultsUiState())
    val uiState: StateFlow<ResultsUiState> = _uiState.asStateFlow()

    val unscannedStudents: StateFlow<List<StudentDirectoryEntity>> = combine(
        results,
        repository.observeDirectoryStudents(),
        _uiState
    ) { scanned, directory, state ->
        val okul = state.okulAdi.trim()
        val sinif = state.sinifSubesi.trim()
        if (okul.isBlank() && sinif.isBlank()) {
            emptyList()
        } else {
            val scannedTcs = scanned.map { it.result.tcKimlikNo }.toSet()
            directory.filter { student ->
                val matchesSchool = okul.isBlank() || student.okulAdi.equals(okul, ignoreCase = true)
                val matchesClass = sinif.isBlank() || student.sinifSubesi.equals(sinif, ignoreCase = true)
                matchesSchool && matchesClass && student.tcKimlikNo !in scannedTcs
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val session = repository.getSessionById(examSessionId)
            val isTest = session?.isTestExam == true
            val qCount = session?.soruSayisi ?: 10
            _uiState.update {
                it.copy(
                    examTitle = session?.title ?: "Sınav Sonuçları",
                    examType = session?.examType ?: "WRITTEN",
                    okulAdi = session?.okulAdi ?: "",
                    egitimOgretimYili = session?.egitimOgretimYili ?: "",
                    sinavDonemi = session?.sinavDonemi ?: "",
                    dersAdi = session?.dersAdi ?: "",
                    sinifSubesi = session?.sinifSubesi ?: "",
                    soruSayisi = qCount,
                    maxScores = if (isTest) {
                        session?.parsedTestMaxScores() ?: List(qCount) { 0 }
                    } else {
                        session?.parsedMaxScores() ?: List(10) { 10 }
                    },
                    answerKey = if (isTest) session?.parsedAnswerKey().orEmpty() else emptyList()
                )
            }
        }
    }

    fun updateResult(
        original: ExamResultEntity,
        newTc: String,
        newOkulNo: String,
        scores: List<Int>
    ) {
        viewModelScope.launch {
            val cleanTc = newTc.trim()
            val cleanOkulNo = newOkulNo.trim()
            val c = (0 until 10).map { idx -> (scores.getOrNull(idx) ?: 0).coerceIn(0, 99) }
            val resolved = repository.resolveStudent(examSessionId, cleanTc, cleanOkulNo)
            repository.updateResult(
                original.copy(
                    tcKimlikNo = resolved.tcKimlikNo,
                    okulNo = resolved.okulNo,
                    adSoyad = resolved.adSoyad,
                    sinifSubesi = resolved.sinifSubesi,
                    score1 = c[0],
                    score2 = c[1],
                    score3 = c[2],
                    score4 = c[3],
                    score5 = c[4],
                    score6 = c[5],
                    score7 = c[6],
                    score8 = c[7],
                    score9 = c[8],
                    score10 = c[9],
                    totalScore = c.sum(),
                    hasTotalMismatch = false,
                    isTcValid = resolved.isTcValid,
                    needsManualReview = false,
                    matchStatus = resolved.matchStatus.name,
                    reviewReasons = ExamResultEntity.encodeReviewReasonEnums(resolved.reviewReasons)
                )
            )
            _uiState.update { it.copy(statusMessage = "Öğrenci sonucu güncellendi.") }
        }
    }

    fun updateTestResult(
        original: ExamResultEntity,
        newTc: String,
        newOkulNo: String,
        studentAnswers: List<String>
    ) {
        viewModelScope.launch {
            val cleanTc = newTc.trim()
            val cleanOkulNo = newOkulNo.trim()
            val state = _uiState.value
            val count = state.soruSayisi.coerceIn(1, 25)
            val answerKey = state.answerKey
            val maxScores = state.maxScores

            val normalizedAnswers = (0 until count).map { idx ->
                val raw = studentAnswers.getOrElse(idx) { "" }.trim().uppercase(java.util.Locale.ROOT)
                if (raw in listOf("A", "B", "C", "D", "E")) raw else ""
            }
            val computedScores = (0 until count).map { idx ->
                val ans = normalizedAnswers[idx]
                val expected = answerKey.getOrElse(idx) { "" }
                if (ans.isNotBlank() && expected.isNotBlank() && ans == expected) {
                    maxScores.getOrElse(idx) { 0 }.coerceIn(0, 100)
                } else {
                    0
                }
            }
            val resolved = repository.resolveStudent(examSessionId, cleanTc, cleanOkulNo)
            repository.updateResult(
                original.copy(
                    tcKimlikNo = resolved.tcKimlikNo,
                    okulNo = resolved.okulNo,
                    adSoyad = resolved.adSoyad,
                    sinifSubesi = resolved.sinifSubesi,
                    score1 = computedScores.getOrElse(0) { 0 },
                    score2 = computedScores.getOrElse(1) { 0 },
                    score3 = computedScores.getOrElse(2) { 0 },
                    score4 = computedScores.getOrElse(3) { 0 },
                    score5 = computedScores.getOrElse(4) { 0 },
                    score6 = computedScores.getOrElse(5) { 0 },
                    score7 = computedScores.getOrElse(6) { 0 },
                    score8 = computedScores.getOrElse(7) { 0 },
                    score9 = computedScores.getOrElse(8) { 0 },
                    score10 = computedScores.getOrElse(9) { 0 },
                    totalScore = computedScores.sum(),
                    hasTotalMismatch = false,
                    isTcValid = resolved.isTcValid,
                    needsManualReview = false,
                    matchStatus = resolved.matchStatus.name,
                    reviewReasons = ExamResultEntity.encodeReviewReasonEnums(resolved.reviewReasons),
                    scoresJson = ExamResultEntity.encodeScoresJson(computedScores),
                    studentAnswersJson = ExamResultEntity.encodeStudentAnswersJson(normalizedAnswers)
                )
            )
            _uiState.update { it.copy(statusMessage = "Test sonucu güncellendi.") }
        }
    }

    fun deleteResult(resultId: Long) {
        viewModelScope.launch {
            repository.deleteResult(resultId)
        }
    }

    fun clearAllResults() {
        viewModelScope.launch {
            repository.clearSessionResults(examSessionId)
            _uiState.update { it.copy(statusMessage = "Tüm sonuçlar temizlendi.") }
        }
    }

    fun importRoster(uri: Uri) {
        viewModelScope.launch {
            try {
                val students = studentListParser.parseUri(uri, okulAdi = _uiState.value.okulAdi)
                if (students.isEmpty()) {
                    _uiState.update {
                        it.copy(statusMessage = "Dosyada geçerli T.C. Kimlik No içeren öğrenci bulunamadı.")
                    }
                    return@launch
                }
                repository.upsertDirectoryStudents(students)
                val (matched, total) = repository.rematchSessionResults(examSessionId)
                val extra = if (total > 0) " ($matched/$total kağıt eşleşti)" else ""
                _uiState.update {
                    it.copy(statusMessage = "${students.size} öğrenci listeye eklendi$extra.")
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statusMessage = "Liste yüklenemedi: ${e.localizedMessage ?: "Hata"}")
                }
            }
        }
    }

    fun rematchWithDirectory() {
        viewModelScope.launch {
            val (matched, total) = repository.rematchSessionResults(examSessionId)
            _uiState.update {
                it.copy(
                    statusMessage = if (total == 0) {
                        "Eşleştirilecek okunmuş kağıt yok."
                    } else {
                        "$matched / $total öğrenci rehberle eşleştirildi."
                    }
                )
            }
        }
    }

    fun exportToExcel(maskTc: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true) }
            try {
                val list = repository.getResultsWithStudentOnce(examSessionId)
                val state = _uiState.value
                val title = state.examTitle.ifBlank { "Sinav" }
                val teacherLabel = teacherProfilePrefs.profile.value.formattedTeacherLabel()
                val exported = excelExporter.exportExamResults(
                    examTitle = title,
                    okulAdi = state.okulAdi,
                    egitimOgretimYili = state.egitimOgretimYili,
                    sinavDonemi = state.sinavDonemi,
                    dersAdi = state.dersAdi,
                    sinifSubesi = state.sinifSubesi,
                    ogretmenLabel = teacherLabel,
                    soruSayisi = state.soruSayisi,
                    maxScores = state.maxScores,
                    examType = state.examType,
                    answerKey = state.answerKey,
                    maskTc = maskTc,
                    results = list
                )
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportedFile = exported,
                        statusMessage = "Excel kaydedildi: ${exported.savedLocationLabel}"
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        statusMessage = "Excel oluşturulamadı: ${e.localizedMessage ?: "Hata"}"
                    )
                }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun clearExportedFile() {
        _uiState.update { it.copy(exportedFile = null) }
    }

    class Factory(
        private val examSessionId: Long,
        private val repository: OptiklikRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ExamResultsViewModel(examSessionId, repository, context) as T
        }
    }
}
