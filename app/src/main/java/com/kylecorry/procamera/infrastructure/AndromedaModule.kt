package com.kylecorry.procamera.infrastructure

import android.content.Context
import com.kylecorry.andromeda.files.IFileSystem
import com.kylecorry.andromeda.files.LocalFileSystem
import com.kylecorry.andromeda.haptics.HapticMotor
import com.kylecorry.andromeda.haptics.IHapticMotor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@InstallIn(SingletonComponent::class)
@Module
object AndromedaModule {

    @Provides
    fun provideFileSystem(@ApplicationContext context: Context): IFileSystem {
        return LocalFileSystem(context)
    }

    @Provides
    fun provideHaptics(@ApplicationContext context: Context): IHapticMotor {
        return HapticMotor(context)
    }

}