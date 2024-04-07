package com.kylecorry.procamera.infrastructure.camera

import com.kylecorry.andromeda.camera.ICamera

class SensitivityProvider {

    private val standardStart = 25

    fun getValues(camera: ICamera): List<Int> {
        val range = camera.getSensitivityRange() ?: return emptyList()

        val values = mutableListOf(range.start)
        var current = standardStart
        while (current <= range.end) {
            if (current in range) {
                values.add(current)
            }
            current *= 2
        }

        if (values.last() != range.end) {
            values.add(range.end)
        }

        return values
    }

}