package com.kylecorry.procamera.infrastructure.io

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.kylecorry.andromeda.files.ExternalFileSystem
import com.kylecorry.luna.coroutines.onIO
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaStoreSaver @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun copyToMediaStore(file: File) = onIO {
        // Save the file to the system media store
        val resolver = context.contentResolver
        val photoCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val photoDetails = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val photoContentUri = resolver.insert(photoCollection, photoDetails) ?: return@onIO
        val externalFiles = ExternalFileSystem(context)
        externalFiles.outputStream(photoContentUri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        photoDetails.clear()
        photoDetails.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(photoContentUri, photoDetails, null, null)
    }
}