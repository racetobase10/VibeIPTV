package com.vibeiptv.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class MyTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
    }
}
