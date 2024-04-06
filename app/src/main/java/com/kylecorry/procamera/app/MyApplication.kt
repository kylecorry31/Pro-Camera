package com.kylecorry.procamera.app

import android.app.Application
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.kylecorry.andromeda.preferences.PreferenceMigration
import com.kylecorry.andromeda.preferences.PreferenceMigrator
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application(), CameraXConfig.Provider {

    override fun onCreate() {
        super.onCreate()
        migratePreferences()
    }

    private fun migratePreferences() {
        val key = "pref_version"
        val version = 0
        val migrations = listOf<PreferenceMigration>()
        PreferenceMigrator(this, key).migrate(version, migrations)
    }

    override fun getCameraXConfig(): CameraXConfig {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setMinimumLoggingLevel(Log.ERROR).build()
    }
}