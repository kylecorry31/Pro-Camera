package com.kylecorry.procamera.infrastructure.io

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileNameGenerator @Inject constructor() {

    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")

    fun generate(): String {
        val datetime = LocalDateTime.now()
        val formattedDate = datetime.format(formatter)

        return "IMG_${formattedDate}.jpg"
    }

}