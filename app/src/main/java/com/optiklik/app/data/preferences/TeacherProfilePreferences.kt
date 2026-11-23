package com.optiklik.app.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

data class TeacherProfile(
    val adSoyad: String = "",
    val brans: String = "",
    val varsayilanYil: String = "",
    val okullar: List<String> = emptyList(),
    val debugModeEnabled: Boolean = false
) {
    val isCompleted: Boolean
        get() = adSoyad.isNotBlank() && brans.isNotBlank()

    fun effectiveYear(): String =
        varsayilanYil.trim().ifBlank { TeacherProfilePreferences.DEFAULT_YEAR }

    fun formattedTeacherLabel(): String {
        return when {
            adSoyad.isNotBlank() && brans.isNotBlank() -> "$adSoyad - $brans"
            adSoyad.isNotBlank() -> adSoyad
            brans.isNotBlank() -> brans
            else -> "-"
        }
    }
}

class TeacherProfilePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _profile = MutableStateFlow(loadProfile())
    val profile: StateFlow<TeacherProfile> = _profile.asStateFlow()

    fun updateProfile(
        adSoyad: String,
        brans: String,
        varsayilanYil: String,
        okullar: List<String>
    ) {
        val cleanName = adSoyad.trim()
        val cleanBranch = brans.trim()
        val cleanYear = varsayilanYil.trim()
        val cleanSchools = okullar.map { it.trim() }.filter { it.isNotBlank() }.distinct()

        val jsonArray = JSONArray().apply {
            cleanSchools.forEach { put(it) }
        }

        prefs.edit()
            .putString(KEY_AD_SOYAD, cleanName)
            .putString(KEY_BRANS, cleanBranch)
            .putString(KEY_VARSAYILAN_YIL, cleanYear)
            .putString(KEY_OKULLAR, jsonArray.toString())
            .apply()

        _profile.value = _profile.value.copy(
            adSoyad = cleanName,
            brans = cleanBranch,
            varsayilanYil = cleanYear,
            okullar = cleanSchools
        )
    }

    fun setDebugModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_MODE, enabled).apply()
        _profile.value = _profile.value.copy(debugModeEnabled = enabled)
    }

    fun addSchoolIfMissing(schoolName: String) {
        val clean = schoolName.trim()
        if (clean.isBlank()) return
        val current = _profile.value
        if (current.okullar.any { it.equals(clean, ignoreCase = true) }) return
        updateProfile(
            adSoyad = current.adSoyad,
            brans = current.brans,
            varsayilanYil = current.varsayilanYil,
            okullar = current.okullar + clean
        )
    }

    private fun loadProfile(): TeacherProfile {
        val adSoyad = prefs.getString(KEY_AD_SOYAD, "").orEmpty()
        val brans = prefs.getString(KEY_BRANS, "").orEmpty()
        val varsayilanYil = prefs.getString(KEY_VARSAYILAN_YIL, "").orEmpty().trim()
        val schoolsJson = prefs.getString(KEY_OKULLAR, "[]").orEmpty()
        val okullar = parseSchools(schoolsJson)
        val debugModeEnabled = prefs.getBoolean(KEY_DEBUG_MODE, false)
        return TeacherProfile(
            adSoyad = adSoyad,
            brans = brans,
            varsayilanYil = varsayilanYil,
            okullar = okullar,
            debugModeEnabled = debugModeEnabled
        )
    }

    private fun parseSchools(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotBlank()) add(item)
                }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "optiklik_teacher_profile"
        private const val KEY_AD_SOYAD = "ad_soyad"
        private const val KEY_BRANS = "brans"
        private const val KEY_VARSAYILAN_YIL = "varsayilan_yil"
        private const val KEY_OKULLAR = "okullar"
        private const val KEY_DEBUG_MODE = "debug_mode_enabled"
        const val DEFAULT_YEAR = "2026-2027"
    }
}
