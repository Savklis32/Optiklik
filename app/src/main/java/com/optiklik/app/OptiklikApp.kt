package com.optiklik.app

import android.app.Application
import android.util.Log
import com.optiklik.app.data.local.OptiklikDatabase
import com.optiklik.app.data.preferences.TeacherProfilePreferences
import com.optiklik.app.data.repository.OptiklikRepository
import org.opencv.android.OpenCVLoader

class OptiklikApp : Application() {

    val database: OptiklikDatabase by lazy { OptiklikDatabase.getInstance(this) }
    val repository: OptiklikRepository by lazy { OptiklikRepository(database) }
    val teacherProfile: TeacherProfilePreferences by lazy { TeacherProfilePreferences(this) }

    var isOpenCvLoaded: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        isOpenCvLoaded = try {
            OpenCVLoader.initLocal()
        } catch (t: Throwable) {
            Log.w("OptiklikApp", "OpenCV native load failed: ${t.message}")
            false
        }
        if (!isOpenCvLoaded) {
            Log.e("OptiklikApp", "OpenCV initialization failed!")
        } else {
            Log.i("OptiklikApp", "OpenCV initialized successfully.")
        }
    }
}
