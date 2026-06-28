package com.example.bibleapp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.example.bibleapp.data.BookmarkStore
import com.example.bibleapp.util.AppLogger

class BibleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
        applySavedTheme()
    }

    private fun applySavedTheme() {
        val mode = when (BookmarkStore.getThemeMode(this)) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO          // 라이트
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM // 시스템
            else -> AppCompatDelegate.MODE_NIGHT_YES      // 다크(기본)
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
