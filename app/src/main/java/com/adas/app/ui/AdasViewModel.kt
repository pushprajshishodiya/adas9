package com.adas.app.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adas.app.detection.*
import com.adas.app.sensors.SensorFusion
import com.adas.app.vehicle.VehicleProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AdasViewModel(app: Application) : AndroidViewModel(app) {


    private val detector  = VehicleDetector(app)
    var processor         = AdasProcessor(detector, VehicleProfile.load(app), loadCriteria(app))
    val sensorFusion      = SensorFusion(app)

    private val _frame    = MutableStateFlow<AdasFrame?>(null)
    val frame: StateFlow<AdasFrame?> = _frame

    private val _criteria = MutableStateFlow(loadCriteria(app))
    val criteria: StateFlow<CollisionCriteria> = _criteria

    var frontCamEnabled = true
    var rearCamEnabled  = true

    private val mutex   = Mutex()
    private var latestFront: List<TrackedVehicle> = emptyList()
    private var latestRear:  List<TrackedVehicle> = emptyList()
    private var latestBmp:   Bitmap? = null

    init { sensorFusion.start() }

    fun reloadProfile() {
        val p = VehicleProfile.load(getApplication())
        processor = AdasProcessor(detector, p, _criteria.value)
    }

    fun updateCriteria(c: CollisionCriteria) {
        _criteria.value = c
        saveCriteria(getApplication(), c)
        processor.updateCriteria(c)
    }

    fun processFrontFrame(bmp: Bitmap, ts: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val spd = sensorFusion.data.value.gpsSpeedKmh
            processor.processFrame(bmp, CameraType.FRONT, spd, ts) { vehicles ->
                viewModelScope.launch(Dispatchers.Default) {
                    mutex.withLock { latestFront = vehicles; latestBmp = bmp }
                    emit(bmp, ts)
                }
            }
        }
    }

    fun processRearFrame(bmp: Bitmap, ts: Long) {
        viewModelScope.launch(Dispatchers.Default) {
            val spd = sensorFusion.data.value.gpsSpeedKmh
            processor.processFrame(bmp, CameraType.REAR, spd, ts) { vehicles ->
                viewModelScope.launch(Dispatchers.Default) {
                    mutex.withLock { latestRear = vehicles }
                    emit(latestBmp ?: bmp, ts)
                }
            }
        }
    }

    private suspend fun emit(bmp: Bitmap, ts: Long) {
        val sensors = sensorFusion.data.value
        val f = mutex.withLock {
            processor.buildFrame(
                latestFront, latestRear, bmp, sensors,
                sensors.gpsSpeedKmh, ts, frontCamEnabled, rearCamEnabled
            )
        }
        _frame.value = f
    }

    override fun onCleared() {
        super.onCleared()
        sensorFusion.stop()
        detector.close()
        processor.reset()
    }

    companion object {
        private const val CRITERIA_PREF = "collision_criteria"

        fun saveCriteria(ctx: Context, c: CollisionCriteria) {
            ctx.getSharedPreferences("collision_criteria", Context.MODE_PRIVATE).edit().apply {
                putFloat("frontTtcDanger",  c.frontTtcDanger)
                putFloat("frontTtcWarning", c.frontTtcWarning)
                putFloat("frontTtcCaution", c.frontTtcCaution)
                putFloat("frontDistDanger", c.frontDistDanger)
                putFloat("frontDistWarning",c.frontDistWarning)
                putFloat("frontDistCaution",c.frontDistCaution)
                putFloat("rearTtcDanger",   c.rearTtcDanger)
                putFloat("rearTtcWarning",  c.rearTtcWarning)
                putFloat("rearDistDanger",  c.rearDistDanger)
                putFloat("rearDistWarning", c.rearDistWarning)
                putBoolean("vibration",     c.vibrationEnabled)
                putBoolean("speaker",       c.speakerEnabled)
                putString("sensitivity",    c.sensitivityMode.name)
                apply()
            }
        }
        fun loadCriteria(ctx: Context): CollisionCriteria {
            val p = ctx.getSharedPreferences("collision_criteria", Context.MODE_PRIVATE)
            return CollisionCriteria(
                frontTtcDanger  = p.getFloat("frontTtcDanger",  2f),
                frontTtcWarning = p.getFloat("frontTtcWarning", 4f),
                frontTtcCaution = p.getFloat("frontTtcCaution", 7f),
                frontDistDanger = p.getFloat("frontDistDanger", 5f),
                frontDistWarning= p.getFloat("frontDistWarning",15f),
                frontDistCaution= p.getFloat("frontDistCaution",30f),
                rearTtcDanger   = p.getFloat("rearTtcDanger",  3f),
                rearTtcWarning  = p.getFloat("rearTtcWarning", 5f),
                rearDistDanger  = p.getFloat("rearDistDanger", 8f),
                rearDistWarning = p.getFloat("rearDistWarning",20f),
                vibrationEnabled= p.getBoolean("vibration",    true),
                speakerEnabled  = p.getBoolean("speaker",      true),
                sensitivityMode = SensitivityMode.values()
                    .find { it.name == p.getString("sensitivity","NORMAL") }
                    ?: SensitivityMode.NORMAL
            )
        }
    }
}
