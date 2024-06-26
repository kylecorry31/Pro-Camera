package com.kylecorry.procamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
import androidx.core.view.isVisible
import com.kylecorry.andromeda.camera.ImageCaptureSettings
import com.kylecorry.andromeda.core.math.DecimalFormatter
import com.kylecorry.andromeda.core.time.CoroutineTimer
import com.kylecorry.andromeda.core.ui.setOnProgressChangeListener
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
import com.kylecorry.procamera.infrastructure.camera.SensitivityProvider
import com.kylecorry.procamera.infrastructure.io.FileNameGenerator
import com.kylecorry.procamera.infrastructure.io.MediaStoreSaver
import dagger.hilt.android.AndroidEntryPoint
import java.time.Duration
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : BoundFragment<FragmentMainBinding>() {

    // DI
    @Inject
    lateinit var formatter: FormatService

    @Inject
    lateinit var files: IFileSystem

    @Inject
    lateinit var haptics: IHapticMotor

    @Inject
    lateinit var fileNameGenerator: FileNameGenerator

    @Inject
    lateinit var mediaStoreSaver: MediaStoreSaver

    // State
    private var focusPercent by state<Float?>(null)
    private var iso by state<Int?>(null)
    private var shutterSpeed by state<Duration?>(null)
    private var interval by state<Duration?>(null)
    private var isCapturing by state(false)
    private var zoomRatio by state(1f)
    private var sensitivities by state(emptyList<Int>())
    private var cameraStartCounter by state(0)
    private var previousShutterSpeed by state<Duration?>(null)
    private var isCameraRunning by state(false)

    private val queue = CoroutineQueueRunner(1)

    private var hasPendingPhoto = false
    private var turnOffDuringInterval = false

    private val intervalometer = CoroutineTimer {
        if (turnOffDuringInterval) {
            println("INTERVAL")
            hasPendingPhoto = true
            onMain {
                restartCamera()
            }
        } else {
            takePhoto()
        }
    }

    private val delayedPhotoTimer = CoroutineTimer {
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
            val sensitivityNames =
                listOf(getString(R.string.auto)) + sensitivities.map { it.toString() }

            Pickers.item(
                requireContext(),
                getString(R.string.iso),
                sensitivityNames,
                sensitivities.indexOf(iso) + 1,
            ) {
                it ?: return@item
                iso = if (it == 0) {
                    null
                } else {
                    sensitivities[it - 1]
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

        binding.camera.setOnZoomChangeListener {
            zoomRatio = binding.camera.camera?.zoom?.ratio ?: 1f
        }
    }

    @OptIn(ExperimentalZeroShutterLag::class)
    override fun onResume() {
        super.onResume()
        // TODO: Adjust for sensor rotation + display rotation
        binding.camera.setOnReadyListener {
            val camera = binding.camera.camera ?: return@setOnReadyListener
            val sensitivityProvider = SensitivityProvider()
            sensitivities = sensitivityProvider.getValues(camera)
            cameraStartCounter++

            if (hasPendingPhoto) {
                delayedPhotoTimer.once(Duration.ofMillis(500))
            }

        }
        startCamera()
    }

    override fun onPause() {
        super.onPause()
        intervalometer.stop()
        delayedPhotoTimer.stop()
        haptics.off()
        stopCamera()
        isCapturing = false
    }

    override fun onUpdate() {
        super.onUpdate()
        effect("focus", focusPercent, cameraStartCounter) {
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

        effect("iso", iso, cameraStartCounter) {
            val iso = iso
            binding.iso.text = iso?.toString() ?: getString(R.string.auto)
            binding.camera.camera?.setSensitivity(iso)
        }

        effect("shutter_speed", shutterSpeed, cameraStartCounter) {
            val shutterSpeed = shutterSpeed
            binding.shutterSpeed.text =
                shutterSpeed?.let { DecimalFormatter.format(shutterSpeed.toMillis() / 1000f, 2) }
                    ?: getString(R.string.auto)
            binding.camera.camera?.setExposureTime(shutterSpeed)
            val previous = previousShutterSpeed
            previousShutterSpeed = shutterSpeed
            if (shutterSpeed != previous && previous != null && previous > Duration.ofMillis(250)) {
                restartCamera()
            }
        }

        effect("interval", interval, lifecycleHookTrigger.onResume()) {
            val interval = interval
            binding.interval.text =
                interval?.let { DecimalFormatter.format(it.toMillis() / 1000f, 2) }
                    ?: getString(R.string.off)
            if (interval != null) {
                turnOffDuringInterval = interval > Duration.ofSeconds(2)
                intervalometer.interval(interval)
            } else {
                val wasRunning = turnOffDuringInterval
                intervalometer.stop()
                hasPendingPhoto = false
                turnOffDuringInterval = false
                if (wasRunning) {
                    restartCamera()
                }
            }
        }

        effect("capture_button", isCapturing) {
            val isCapturing = isCapturing
            binding.captureButton.isVisible = !isCapturing
            binding.loadingIndicator.isVisible = isCapturing
        }

        effect("zoom", zoomRatio, cameraStartCounter) {
            val zoomRatio = zoomRatio
            binding.zoom.text = DecimalFormatter.format(zoomRatio, 2) + "x"
//            binding.camera.camera?.setZoomRatio(zoomRatio)
        }

        effect("blackout", isCameraRunning, cameraStartCounter) {
            val isCameraRunning = isCameraRunning
            binding.blackout.isVisible = !isCameraRunning
        }
    }

    fun takePhoto() {
        inBackground {
            queue.enqueue {
                val fileName = fileNameGenerator.generate()
                val file = files.getFile(fileName, true)

                isCapturing = true

                onIO {
                    binding.camera.capture(file)
                    mediaStoreSaver.copyToMediaStore(file)
                    file.delete()
                }

                haptics.feedback(HapticFeedbackType.Click)

                isCapturing = false
                hasPendingPhoto = false
                if (turnOffDuringInterval) {
                    onMain {
                        stopCamera()
                    }
                }
            }
        }

    }

    private fun startCamera() {
        binding.camera.start(
            readFrames = false,
            captureSettings = ImageCaptureSettings(
                quality = 100,
                captureMode = CAPTURE_MODE_MAXIMIZE_QUALITY,
                rotation = requireActivity().windowManager.defaultDisplay.rotation
            )
        )
        isCameraRunning = true
    }

    private fun stopCamera() {
        binding.camera.stop()
        isCameraRunning = false
    }

    private fun restartCamera() {
        stopCamera()
        startCamera()
    }

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMainBinding {
        return FragmentMainBinding.inflate(layoutInflater, container, false)
    }
}