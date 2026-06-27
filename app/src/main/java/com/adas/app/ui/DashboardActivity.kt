package com.adas.app.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.adas.app.R
import com.adas.app.camera.AdasService
import com.adas.app.camera.DualCameraManager
import com.adas.app.databinding.ActivityDashboardBinding
import com.adas.app.detection.*
import com.adas.app.utils.AlertManager
import com.adas.app.vehicle.VehicleProfile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var vm: AdasViewModel
    private lateinit var cameras: DualCameraManager
    private lateinit var alerts: AlertManager
    private var barTrackH = 0
    private var currentCriteria = CollisionCriteria()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        vm      = ViewModelProvider(this)[AdasViewModel::class.java]
        alerts  = AlertManager(this)
        cameras = DualCameraManager(this)

        currentCriteria = AdasViewModel.loadCriteria(this)
        alerts.updateCriteria(currentCriteria)

        startService(Intent(this, AdasService::class.java))

        setupDrawer()
        setupCameras()
        observeFrames()

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.END)
        }
        binding.progressLeft.post {
            barTrackH = (binding.progressLeft.parent as View).height
        }
    }

    // ── Drawer ─────────────────────────────────────────────────────────────
    private fun setupDrawer() {
        val nav = binding.navView

        val swFront      = nav.findViewById<Switch>(R.id.swFrontCam)
        val swRear       = nav.findViewById<Switch>(R.id.swRearCam)
        val swVibration  = nav.findViewById<Switch>(R.id.swVibration)
        val swSpeaker    = nav.findViewById<Switch>(R.id.swSpeaker)
        val swAutoRotate = nav.findViewById<Switch>(R.id.swAutoRotate)
        val swMap        = nav.findViewById<Switch>(R.id.swMapOverlay)
        val rgSens       = nav.findViewById<RadioGroup>(R.id.rgSensitivity)
        val sbFrontTtc   = nav.findViewById<SeekBar>(R.id.sbFrontTtc)
        val tvFrontTtcV  = nav.findViewById<TextView>(R.id.tvFrontTtcVal)
        val sbRearTtc    = nav.findViewById<SeekBar>(R.id.sbRearTtc)
        val tvRearTtcV   = nav.findViewById<TextView>(R.id.tvRearTtcVal)
        val tvProfile    = nav.findViewById<TextView>(R.id.tvProfileSummary)
        val btnSetup     = nav.findViewById<Button>(R.id.btnEditProfile)
        val tvHeading    = nav.findViewById<TextView>(R.id.tvHeadingInfo)
        val tvAccel      = nav.findViewById<TextView>(R.id.tvAccelInfo)

        // Load saved criteria into UI
        swVibration.isChecked  = currentCriteria.vibrationEnabled
        swSpeaker.isChecked    = currentCriteria.speakerEnabled

        val profile = VehicleProfile.load(this)
        tvProfile.text = buildString {
            appendLine("${profile.name}  ${profile.make} ${profile.model} ${profile.year}")
            appendLine("W:${profile.widthM}m  WB:${profile.wheelbaseM}m")
            appendLine("Cam H:${profile.cameraHeightM}m  FOV:${profile.cameraFovDeg}°")
            append("Steer ratio: ${profile.steeringRatioFull}:1")
        }

        // ── Camera toggles ───────────────────────────────────────────────
        swFront.setOnCheckedChangeListener { _, on ->
            vm.frontCamEnabled   = on
            cameras.frontEnabled = on
            rebindCameras()
        }
        swRear.setOnCheckedChangeListener { _, on ->
            vm.rearCamEnabled   = on
            cameras.rearEnabled = on
            rebindCameras()
        }

        // ── Alert toggles ────────────────────────────────────────────────
        swVibration.setOnCheckedChangeListener { _, on ->
            currentCriteria = currentCriteria.copy(vibrationEnabled = on)
            applyAndSaveCriteria()
        }
        swSpeaker.setOnCheckedChangeListener { _, on ->
            currentCriteria = currentCriteria.copy(speakerEnabled = on)
            applyAndSaveCriteria()
        }

        // ── Display ──────────────────────────────────────────────────────
        swAutoRotate.setOnCheckedChangeListener { _, on ->
            requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                   else    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        swMap.setOnCheckedChangeListener { _, on ->
            binding.mapFragment.visibility = if (on) View.VISIBLE else View.GONE
        }

        // ── Sensitivity ──────────────────────────────────────────────────
        // Pre-select current sensitivity
        val sensBtn = when (currentCriteria.sensitivityMode) {
            SensitivityMode.LOW      -> R.id.rbLow
            SensitivityMode.NORMAL   -> R.id.rbNormal
            SensitivityMode.HIGH     -> R.id.rbHigh
            SensitivityMode.PARANOID -> R.id.rbParanoid
        }
        rgSens.check(sensBtn)

        rgSens.setOnCheckedChangeListener { _, id ->
            val mode = when (id) {
                R.id.rbLow      -> SensitivityMode.LOW
                R.id.rbHigh     -> SensitivityMode.HIGH
                R.id.rbParanoid -> SensitivityMode.PARANOID
                else            -> SensitivityMode.NORMAL
            }
            currentCriteria = currentCriteria.copy(sensitivityMode = mode)
            applyAndSaveCriteria()
        }

        // ── TTC sliders ──────────────────────────────────────────────────
        sbFrontTtc.progress = ((currentCriteria.frontTtcDanger - 0.5f) / 9.5f * 100).toInt()
        sbRearTtc.progress  = ((currentCriteria.rearTtcDanger  - 0.5f) / 9.5f * 100).toInt()

        sbFrontTtc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                val v = 0.5f + p / 100f * 9.5f
                tvFrontTtcV.text = "${"%.1f".format(v)}s"
                if (user) {
                    currentCriteria = currentCriteria.copy(
                        frontTtcDanger  = v,
                        frontTtcWarning = v * 2f,
                        frontTtcCaution = v * 3.5f
                    )
                    applyAndSaveCriteria()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        sbRearTtc.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                val v = 0.5f + p / 100f * 9.5f
                tvRearTtcV.text = "${"%.1f".format(v)}s"
                if (user) {
                    currentCriteria = currentCriteria.copy(
                        rearTtcDanger  = v,
                        rearTtcWarning = v * 1.7f
                    )
                    applyAndSaveCriteria()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnSetup.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // ── Live sensor data ─────────────────────────────────────────────
        lifecycleScope.launch {
            vm.sensorFusion.data.collectLatest { s ->
                runOnUiThread {
                    tvHeading.text = buildString {
                        appendLine("Heading: ${"%.1f".format(s.headingDeg)}° ${compassDir(s.headingDeg)}")
                        append("Pitch: ${"%.1f".format(s.pitchDeg)}°  Roll: ${"%.1f".format(s.rollDeg)}°")
                    }
                    tvAccel.text = buildString {
                        appendLine("Lateral:  ${"%.2f".format(s.accelX)} m/s²")
                        appendLine("Longit.:  ${"%.2f".format(s.accelY)} m/s²")
                        append("GPS acc:  ${"%.0f".format(s.gpsAccuracyM)} m")
                    }
                }
            }
        }
    }

    private fun applyAndSaveCriteria() {
        vm.updateCriteria(currentCriteria)
        alerts.updateCriteria(currentCriteria)
    }

    // ── Cameras ────────────────────────────────────────────────────────────
    private fun setupCameras() {
        cameras.onFrontFrame = { bmp, ts -> vm.processFrontFrame(bmp, ts) }
        cameras.onRearFrame  = { bmp, ts -> vm.processRearFrame(bmp, ts) }
        val rearTV = binding.previewRear as? TextureView
        cameras.start(this, binding.previewFront, null, rearTV)
    }

    private fun rebindCameras() {
        val rearTV = binding.previewRear as? TextureView
        cameras.rebind(this, binding.previewFront, null, rearTV)
    }

    // ── Frame observation ──────────────────────────────────────────────────
    private fun observeFrames() {
        lifecycleScope.launch {
            vm.frame.collectLatest { f ->
                f ?: return@collectLatest
                runOnUiThread { updateUi(f) }
            }
        }
    }

    // ── UI update ──────────────────────────────────────────────────────────
    private fun updateUi(f: AdasFrame) {
        binding.adasOverlay.apply {
            adasFrame = f
            detScaleX = binding.previewFront.width.toFloat()  / 640f
            detScaleY = binding.previewFront.height.toFloat() / 480f
            invalidate()
        }

        binding.tvEgoSpeed.text = "${f.sensorData.gpsSpeedKmh.roundToInt()}"
        binding.tvHeading.text  = "${compassDir(f.sensorData.headingDeg)} ${"%.0f".format(f.sensorData.headingDeg)}°"

        // Detection source badge
        val src = f.frontVehicles.firstOrNull()?.detectionSource ?: "—"
        binding.tvDetSource.text = "Det: $src"

        // Front
        val fn = f.frontVehicles.minByOrNull { it.distanceM }
        if (fn != null) {
            binding.tvFrontLabel.text = fn.label.uppercase()
            binding.tvFrontDist.text  = "${"%.1f".format(fn.distanceM)}m"
            binding.tvFrontSpeed.text = "Δ${"%.0f".format(fn.relativeSpeedKmh)} · ${"%.0f".format(fn.absoluteSpeedKmh)}km/h"
            binding.cardFront.setCardBackgroundColor(warnColor(fn.warningLevel))
        } else {
            binding.tvFrontLabel.text = "CLEAR"
            binding.tvFrontDist.text  = "—"
            binding.tvFrontSpeed.text = ""
            binding.cardFront.setCardBackgroundColor(warnColor(WarningLevel.SAFE))
        }

        // Rear
        val rn = f.rearVehicles.minByOrNull { it.distanceM }
        if (rn != null) {
            binding.tvRearLabel.text = rn.label.uppercase()
            binding.tvRearDist.text  = "${"%.1f".format(rn.distanceM)}m"
            binding.tvRearSpeed.text = "Δ${"%.0f".format(rn.relativeSpeedKmh)} · ${"%.0f".format(rn.absoluteSpeedKmh)}km/h"
            binding.cardRear.setCardBackgroundColor(warnColor(rn.warningLevel))
        } else {
            binding.tvRearLabel.text = "CLEAR"
            binding.tvRearDist.text  = "—"
            binding.tvRearSpeed.text = ""
            binding.cardRear.setCardBackgroundColor(warnColor(WarningLevel.SAFE))
        }

        // Side bars
        val lane   = f.laneInfo
        val trackH = barTrackH.takeIf { it > 0 } ?: (binding.progressLeft.parent as? View)?.height ?: 0
        setBar(binding.progressLeft,  lane.leftSpaceFraction,  trackH, spaceColor(lane.leftSpaceFraction))
        setBar(binding.progressRight, lane.rightSpaceFraction, trackH, spaceColor(lane.rightSpaceFraction))
        binding.tvLeftPct.text   = "${(lane.leftSpaceFraction  * 100).roundToInt()}%"
        binding.tvRightPct.text  = "${(lane.rightSpaceFraction * 100).roundToInt()}%"
        binding.tvLeftDist.text  = if (lane.leftDistanceM  > 0) "${"%.0f".format(lane.leftDistanceM)}m"  else "—"
        binding.tvRightDist.text = if (lane.rightDistanceM > 0) "${"%.0f".format(lane.rightDistanceM)}m" else "—"

        // Lane offset dot
        val trackW = (binding.laneOffsetBar.parent as? View)?.width ?: 120
        val oFrac  = ((lane.laneOffsetFraction + 1f) / 2f).coerceIn(0f, 1f)
        binding.laneOffsetBar.apply {
            val lp = layoutParams as ViewGroup.LayoutParams
            lp.width = 8; layoutParams = lp
            x = (trackW * oFrac - 4f).coerceAtLeast(0f)
            setBackgroundColor(if (lane.isLaneDeparting) Color.parseColor("#FF1744") else Color.parseColor("#00E5FF"))
        }

        // Steering guide
        val sg = f.steeringGuide
        binding.tvSteeringNotes.text = sg.correctionNotes
        binding.tvSteeringDeg.text   = "Wheel: ${sg.recommendedWheelDeg.roundToInt()}°"
        binding.tvSteeringSpeed.text = "Max: ${sg.recommendedSpeedKmh.roundToInt()} km/h"

        // Warning banner + alerts
        val worst = listOfNotNull(f.frontWarning?.level, f.rearWarning?.level, f.laneWarning?.level)
            .maxByOrNull { it.ordinal }
        if (worst != null && worst != WarningLevel.SAFE) {
            val alertMsg = f.frontWarning?.alertMessage
                ?: f.rearWarning?.alertMessage
                ?: f.laneWarning?.message ?: ""
            binding.tvWarningBanner.text = "⚠  $alertMsg"
            binding.tvWarningBanner.visibility = View.VISIBLE
            binding.tvWarningBanner.setBackgroundColor(warnColor(worst))
            alerts.alert(worst, alertMsg)
        } else {
            binding.tvWarningBanner.visibility = View.GONE
        }

        binding.tvRearCamLabel.text = if (rn != null)
            "${rn.label.uppercase()} ${"%.0f".format(rn.distanceM)}m Δ${"%.0f".format(rn.relativeSpeedKmh)}km/h"
        else "REAR CAM"
    }

    // ── Helpers ────────────────────────────────────────────────────────────
    private fun setBar(v: View, fraction: Float, trackH: Int, color: Int) {
        if (trackH <= 0) return
        val lp = v.layoutParams as ViewGroup.LayoutParams
        lp.height = (trackH * fraction.coerceIn(0f, 1f)).roundToInt()
        v.layoutParams = lp; v.setBackgroundColor(color)
    }

    private fun spaceColor(f: Float) = when {
        f > 0.6f  -> Color.parseColor("#00E676")
        f > 0.35f -> Color.parseColor("#FFD600")
        else      -> Color.parseColor("#FF1744")
    }

    private fun warnColor(l: WarningLevel) = when (l) {
        WarningLevel.DANGER  -> Color.parseColor("#CCFF1744")
        WarningLevel.WARNING -> Color.parseColor("#CCFF6D00")
        WarningLevel.CAUTION -> Color.parseColor("#CCFFD600")
        WarningLevel.SAFE    -> Color.parseColor("#CC00C853")
    }

    private fun compassDir(d: Float) = when (((d + 22.5f) % 360f / 45f).toInt()) {
        0 -> "N"; 1 -> "NE"; 2 -> "E"; 3 -> "SE"
        4 -> "S"; 5 -> "SW"; 6 -> "W"; else -> "NW"
    }

    override fun onDestroy() {
        super.onDestroy()
        cameras.stop()
        alerts.release()
    }
}
