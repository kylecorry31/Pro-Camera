package com.kylecorry.procamera.ui

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
import androidx.camera.core.ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
import androidx.core.view.isVisible
import com.kylecorry.andromeda.camera.ImageCaptureSettings
import com.kylecorry.andromeda.core.math.DecimalFormatter
import com.kylecorry.andromeda.core.time.CoroutineTimer
import com.kylecorry.andromeda.core.ui.setOnProgressChangeListener
import com.kylecorry.andromeda.files.ExternalFileSystem
import com.kylecorry.andromeda.files.IFileSystem
import com.kylecorry.andromeda.fragments.BoundFragment
import com.kylecorry.andromeda.fragments.inBackground
import com.kylecorry.andromeda.haptics.HapticFeedbackType
import com.kylecorry.andromeda.haptics.IHapticMotor
import com.kylecorry.andromeda.pickers.Pickers
import com.kylecorry.luna.coroutines.CoroutineQueueRunner
import com.kylecorry.luna.coroutines.onIO
import com.kylecorry.luna.coroutines.onMain
import com.kylecorry.procamera.R
import com.kylecorry.procamera.databinding.FragmentMainBinding
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : BoundFragment<FragmentMainBinding>() {

    @Inject
    lateinit var formatter: FormatService

    @Inject
    lateinit var files: IFileSystem

    @Inject
    lateinit var haptics: IHapticMotor

    private var focusPercent by state<Float?>(null)
    private var iso by state<Int?>(null)
    private var shutterSpeed by state<Duration?>(null)
    private var interval by state<Duration?>(null)
    private var isCapturing by state(false)

    private val queue = CoroutineQueueRunner(1)

    private val intervalometer = CoroutineTimer {
        takePhoto()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.focus.setOnProgressChangeListener { progress, _ ->
            focusPercent = progress / 100f
        }

        binding.captureButton.setOnClickListener {
            takePhoto()
        }

        binding.iso.setOnClickListener {
            Pickers.number(
                requireContext(),
                getString(R.string.iso),
                default = iso,
                allowDecimals = false,
                allowNegative = false,
                hint = getString(R.string.iso)
            ) {
                if (it != null) {
                    iso = it.toInt()
                }
            }
        }

        binding.shutterSpeed.setOnClickListener {
            Pickers.number(
                requireContext(),
                getString(R.string.shutter_speed),
                default = shutterSpeed?.toMillis()?.div(1000f),
                allowDecimals = true,
                allowNegative = false,
                hint = getString(R.string.shutter_speed)
            ) {
                if (it != null) {
                    shutterSpeed = Duration.ofMillis((it.toFloat() * 1000).toLong())
                }
            }
        }

        binding.interval.setOnClickListener {
            CustomUiUtils.pickDuration(
                requireContext(),
                interval,
                getString(R.string.interval),
                showSeconds = true
            ) {
                interval = it
            }
        }
    }

    @OptIn(ExperimentalZeroShutterLag::class)
    override fun onResume() {
        super.onResume()
        // TODO: Adjust for sensor rotation + display rotation
        binding.camera.start(
            readFrames = false, captureSettings = ImageCaptureSettings(
                quality = 100,
                captureMode = CAPTURE_MODE_MAXIMIZE_QUALITY,
                rotation = requireActivity().windowManager.defaultDisplay.rotation
            )
        )
    }

    override fun onPause() {
        super.onPause()
        binding.camera.stop()
        intervalometer.stop()
        haptics.off()
        isCapturing = false
    }

    override fun onUpdate() {
        super.onUpdate()
        effect("focus", focusPercent) {
            val pct = focusPercent
            binding.focusLabel.text = getString(
                R.string.focus_amount, if (pct == null) {
                    getString(R.string.auto)
                } else {
                    DecimalFormatter.format(pct * 100, 0)
                }
            )
            binding.camera.camera?.setFocusDistancePercentage(pct)
        }

        effect("iso", iso) {
            val iso = iso
            binding.iso.text = iso?.toString() ?: getString(R.string.auto)
            binding.camera.camera?.setSensitivity(iso)
        }

        effect("shutter_speed", shutterSpeed) {
            val shutterSpeed = shutterSpeed
            binding.shutterSpeed.text =
                shutterSpeed?.let { DecimalFormatter.format(shutterSpeed.toMillis() / 1000f, 2) }
                    ?: getString(R.string.auto)
            binding.camera.camera?.setExposureTime(shutterSpeed)
        }

        effect("interval", interval, lifecycleHookTrigger.onResume()) {
            val interval = interval
            binding.interval.text =
                interval?.let { DecimalFormatter.format(it.toMillis() / 1000f, 2) }
                    ?: getString(R.string.off)
            if (interval != null) {
                intervalometer.interval(interval)
            } else {
                intervalometer.stop()
            }
        }

        effect("capture_button", isCapturing) {
            val isCapturing = isCapturing
            binding.captureButton.isVisible = !isCapturing
            binding.loadingIndicator.isVisible = isCapturing
        }
    }

    fun takePhoto() {
        inBackground {
            queue.enqueue {
                val fileName = "images/${
                    getString(R.string.app_name).replace(
                        " ",
                        ""
                    )
                }_${LocalDateTime.now()}.jpg"
                val file = files.getFile(fileName, true)

                isCapturing = true

                onIO {
                    binding.camera.capture(file)
                    moveFileToMediaStore(file)
                }

                haptics.feedback(HapticFeedbackType.Click)

                isCapturing = false
            }
        }

    }

    private suspend fun moveFileToMediaStore(file: File) = onIO {
        // Save the file to the system media store
        val resolver = requireContext().contentResolver
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
        val externalFiles = ExternalFileSystem(requireContext())
        externalFiles.outputStream(photoContentUri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        file.delete()

        photoDetails.clear()
        photoDetails.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(photoContentUri, photoDetails, null, null)
    }

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMainBinding {
        return FragmentMainBinding.inflate(layoutInflater, container, false)
    }
}