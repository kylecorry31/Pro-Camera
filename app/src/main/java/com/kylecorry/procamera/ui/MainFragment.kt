package com.kylecorry.procamera.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
import androidx.camera.core.ImageCapture.CaptureMode
import androidx.core.view.isVisible
import com.kylecorry.andromeda.alerts.toast
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
import com.kylecorry.luna.coroutines.onIO
import com.kylecorry.luna.coroutines.onMain
import com.kylecorry.procamera.R
import com.kylecorry.procamera.databinding.FragmentMainBinding
import dagger.hilt.android.AndroidEntryPoint
import java.time.Duration
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
        binding.camera.start(
            readFrames = false, captureSettings = ImageCaptureSettings(
                quality = 100,
                captureMode = CAPTURE_MODE_ZERO_SHUTTER_LAG
            )
        )
    }

    override fun onPause() {
        super.onPause()
        binding.camera.stop()
        intervalometer.stop()
        haptics.off()
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
    }

    private fun takePhoto() {
        val fileName = "images/${UUID.randomUUID()}.jpg"
        // TODO: Save to the gallery
        val file = files.getFile(fileName, true)
        inBackground {

            onMain {
                binding.captureButton.isVisible = false
                binding.loadingIndicator.isVisible = true
            }

            onIO {
                binding.camera.capture(file)
            }

            haptics.feedback(HapticFeedbackType.Click)

            onMain {
                binding.captureButton.isVisible = true
                binding.loadingIndicator.isVisible = false
            }
        }

    }

    override fun generateBinding(
        layoutInflater: LayoutInflater,
        container: ViewGroup?
    ): FragmentMainBinding {
        return FragmentMainBinding.inflate(layoutInflater, container, false)
    }
}