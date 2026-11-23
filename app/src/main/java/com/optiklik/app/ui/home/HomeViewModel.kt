package com.optiklik.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.optiklik.app.data.local.dao.ExamSessionWithStats
import com.optiklik.app.data.preferences.TeacherProfile
import com.optiklik.app.data.preferences.TeacherProfilePreferences
import com.optiklik.app.data.repository.OptiklikRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HomeViewModel(
    private val repository: OptiklikRepository,
    private val teacherProfilePrefs: TeacherProfilePreferences
) : ViewModel() {

    val teacherProfile: StateFlow<TeacherProfile> = teacherProfilePrefs.profile

    val sessions: StateFlow<List<ExamSessionWithStats>> =
        repository.observeSessionsWithStats()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val directoryCount: StateFlow<Int> =
        repository.observeDirectoryCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun updateTeacherProfile(
        adSoyad: String,
        brans: String,
        varsayilanYil: String,
        okullar: List<String>
    ) {
        teacherProfilePrefs.updateProfile(adSoyad, brans, varsayilanYil, okullar)
        _statusMessage.value = "Öğretmen profili kaydedildi."
    }

    fun createSession(
        okulAdi: String,
        egitimOgretimYili: String,
        sinavDonemi: String,
        sinifSubesi: String,
        dersAdi: String,
        soruSayisi: Int = 10,
        soruPuanlari: String = "10,10,10,10,10,10,10,10,10,10",
        onCreated: (Long) -> Unit = {}
    ) {
        if (sinavDonemi.isBlank()) {
            _statusMessage.value = "Sınav kaydedilmedi: Sınav dönemi seçilmelidir."
            return
        }
        if (sinifSubesi.isBlank()) {
            _statusMessage.value = "Sınav kaydedilmedi: Sınıf / Şube alanı zorunludur."
            return
        }
        if (dersAdi.isBlank()) {
            _statusMessage.value = "Sınav kaydedilmedi: Ders Adı alanı zorunludur."
            return
        }
        val cleanSchool = okulAdi.trim()
        val cleanCourse = dersAdi.trim()
        val cleanPeriod = sinavDonemi.trim()
        val title = "$cleanCourse • $cleanPeriod"

        viewModelScope.launch {
            if (cleanSchool.isNotBlank()) {
                teacherProfilePrefs.addSchoolIfMissing(cleanSchool)
            }
            val id = repository.createSession(
                title = title,
                okulAdi = cleanSchool,
                egitimOgretimYili = egitimOgretimYili,
                sinavDonemi = cleanPeriod,
                sinifSubesi = sinifSubesi,
                dersAdi = cleanCourse,
                soruSayisi = soruSayisi,
                soruPuanlari = soruPuanlari
            )
            onCreated(id)
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    class Factory(
        private val repository: OptiklikRepository,
        private val teacherProfilePrefs: TeacherProfilePreferences
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HomeViewModel(repository, teacherProfilePrefs) as T
        }
    }
}
