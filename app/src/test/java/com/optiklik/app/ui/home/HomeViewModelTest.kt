package com.optiklik.app.ui.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.optiklik.app.data.local.OptiklikDatabase
import com.optiklik.app.data.preferences.TeacherProfilePreferences
import com.optiklik.app.data.repository.OptiklikRepository
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {

    private lateinit var db: OptiklikDatabase
    private lateinit var viewModel: HomeViewModel

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, OptiklikDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val prefs = TeacherProfilePreferences(context)
        viewModel = HomeViewModel(OptiklikRepository(db), prefs)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createSessionImmediatelySetsStatusMessageWhenClassBranchBlank() {
        viewModel.createSession(
            okulAdi = "Atatürk Lisesi",
            egitimOgretimYili = "2026-2027",
            sinavDonemi = "1. Dönem 1. Yazılı Yoklama",
            sinifSubesi = "",
            dersAdi = "Coğrafya"
        )
        assertNotNull(viewModel.statusMessage.value)
        assertTrue(viewModel.statusMessage.value!!.contains("Sınıf / Şube"))
    }

    @Test
    fun createSessionImmediatelySetsStatusMessageWhenCourseBlank() {
        viewModel.createSession(
            okulAdi = "Atatürk Lisesi",
            egitimOgretimYili = "2026-2027",
            sinavDonemi = "1. Dönem 1. Yazılı Yoklama",
            sinifSubesi = "9-A",
            dersAdi = ""
        )
        assertNotNull(viewModel.statusMessage.value)
        assertTrue(viewModel.statusMessage.value!!.contains("Ders Adı"))
    }
}
