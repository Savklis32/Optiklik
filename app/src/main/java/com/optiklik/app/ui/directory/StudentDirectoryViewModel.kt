package com.optiklik.app.ui.directory

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.optiklik.app.OptiklikApp
import com.optiklik.app.data.local.entity.StudentDirectoryEntity
import com.optiklik.app.data.preferences.TeacherProfile
import com.optiklik.app.data.preferences.TeacherProfilePreferences
import com.optiklik.app.data.repository.OptiklikRepository
import com.optiklik.app.service.StudentListParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StudentDirectoryViewModel(
    private val repository: OptiklikRepository,
    context: Context
) : ViewModel() {

    private val appContext = context.applicationContext
    private val studentListParser = StudentListParser(appContext)
    private val teacherProfilePrefs: TeacherProfilePreferences =
        (appContext as OptiklikApp).teacherProfile

    val students: StateFlow<List<StudentDirectoryEntity>> =
        repository.observeDirectoryStudents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val teacherProfile: StateFlow<TeacherProfile> = teacherProfilePrefs.profile

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    fun importStudentsForSchool(uri: Uri, okulAdi: String, sinifSubesi: String = "") {
        val cleanSchool = okulAdi.trim()
        if (cleanSchool.isBlank()) {
            _statusMessage.value = "Lütfen bir okul adı seçin veya yazın."
            return
        }
        val cleanClass = if (sinifSubesi.isNotBlank()) {
            OptiklikRepository.normalizeClassBranch(sinifSubesi)
        } else {
            ""
        }
        viewModelScope.launch {
            _isImporting.value = true
            try {
                teacherProfilePrefs.addSchoolIfMissing(cleanSchool)
                val result = studentListParser.parseUriWithReport(
                    uri = uri,
                    okulAdi = cleanSchool,
                    defaultSinifSubesi = cleanClass
                )
                val parsed = result.students
                if (parsed.isEmpty()) {
                    _statusMessage.value = "Dosyada geçerli T.C. Kimlik No içeren öğrenci bulunamadı."
                } else {
                    repository.upsertDirectoryStudents(parsed)
                    val classSuffix = if (cleanClass.isNotBlank()) " ($cleanClass)" else ""
                    val extra = result.infoMessage?.let { " • $it" }.orEmpty()
                    _statusMessage.value =
                        "${parsed.size} öğrenci '$cleanSchool'$classSuffix okuluna eklendi / güncellendi.$extra"
                }
            } catch (e: Exception) {
                _statusMessage.value = "Liste yüklenemedi: ${e.localizedMessage ?: "Bilinmeyen hata"}"
            } finally {
                _isImporting.value = false
            }
        }
    }

    fun saveManualStudent(
        tcKimlikNo: String,
        okulAdi: String,
        sinifSubesi: String,
        okulNo: String,
        adSoyad: String,
        originalStudent: StudentDirectoryEntity? = null
    ) {
        val cleanTc = tcKimlikNo.trim()
        val cleanSchool = okulAdi.trim()
        val cleanClass = OptiklikRepository.normalizeClassBranch(sinifSubesi)
        val cleanNo = okulNo.trim().ifBlank { "-" }
        val cleanName = adSoyad.trim()

        if (cleanTc.length != 11 || cleanName.isBlank()) {
            _statusMessage.value = "Geçerli 11 haneli T.C. Kimlik No ve Ad Soyad girin."
            return
        }

        viewModelScope.launch {
            if (cleanSchool.isNotBlank()) {
                teacherProfilePrefs.addSchoolIfMissing(cleanSchool)
            }
            if (originalStudent != null &&
                (originalStudent.tcKimlikNo != cleanTc || originalStudent.okulAdi != cleanSchool)
            ) {
                repository.deleteDirectoryStudent(originalStudent)
            }
            repository.upsertDirectoryStudent(
                StudentDirectoryEntity(
                    tcKimlikNo = cleanTc,
                    okulAdi = cleanSchool,
                    sinifSubesi = cleanClass,
                    okulNo = cleanNo,
                    adSoyad = cleanName,
                    updatedAt = System.currentTimeMillis()
                )
            )
            _statusMessage.value = if (originalStudent != null) {
                "Öğrenci kaydı güncellendi."
            } else {
                "$cleanName rehbere eklendi."
            }
        }
    }

    fun deleteStudent(student: StudentDirectoryEntity) {
        viewModelScope.launch {
            repository.deleteDirectoryStudent(student)
            _statusMessage.value = "${student.adSoyad} rehberden silindi."
        }
    }

    fun deleteSchoolStudents(okulAdi: String) {
        val clean = okulAdi.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            repository.deleteDirectoryBySchool(clean)
            _statusMessage.value = "'$clean' okuluna ait öğrenci listesi temizlendi."
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    class Factory(
        private val repository: OptiklikRepository,
        private val context: Context
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StudentDirectoryViewModel(repository, context) as T
        }
    }
}
