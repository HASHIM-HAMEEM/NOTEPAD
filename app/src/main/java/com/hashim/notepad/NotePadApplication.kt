package com.hashim.notepad

import android.app.Application

class NotePadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
