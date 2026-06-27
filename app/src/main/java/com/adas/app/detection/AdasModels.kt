package com.adas.app.detection

import android.graphics.RectF

data class DetectionResult(
    val label: String,
    val confidence: Float,
    val boundingBox: RectF,
    val trackId: Int = -1,
    val source: String = "mlkit"   // "mlkit", "tflite", "api"
)

data class TrackedVehicle(
    val id: Int,
    val label: String,
    val boundingBox: RectF,
    val confidence: Float,
    val distanceM: Float,
    val relativeSpeedKmh: Float,
    val absoluteSpeedKmh: Float,
    val isRear: Boolean,
    val warningLevel: WarningLevel,
    val ttcSeconds: Float = Float.MAX_VALUE,
    val detectionSource: String = "mlkit"
)

data class LaneInfo(
    val leftSpaceFraction: Float = 1f,
    val rightSpaceFraction: Float = 1f,
    val leftDistanceM: Float = -1f,
    val rightDistanceM: Float = -1f,
    val leftObstacleLabel: String? = null,
    val rightObstacleLabel: String? = null,
    val laneOffsetFraction: Float = 0f,
    val isLaneDeparting: Boolean = false,
    val departingSide: Side? = null
)

data class SteeringGuide(
    val recommendedWheelDeg: Float = 0f,
    val recommendedSpeedKmh: Float = 0f,
    val turnRadiusM: Float = Float.MAX_VALUE,
    val direction: SteerDirection = SteerDirection.STRAIGHT,
    val correctionNotes: String = "Keep straight"
)

data class SensorData(
    val headingDeg: Float = 0f,
    val pitchDeg: Float = 0f,
    val rollDeg: Float = 0f,
    val accelX: Float = 0f,
    val accelY: Float = 0f,
    val accelZ: Float = 0f,
    val gpsSpeedKmh: Float = 0f,
    val gpsBearingDeg: Float = 0f,
    val gpsLat: Double = 0.0,
    val gpsLon: Double = 0.0,
    val gpsAccuracyM: Float = 0f
)

data class CollisionCriteria(
    val frontTtcDanger: Float  = 2.0f,   // seconds
    val frontTtcWarning: Float = 4.0f,
    val frontTtcCaution: Float = 7.0f,
    val frontDistDanger: Float  = 5.0f,  // meters
    val frontDistWarning: Float = 15.0f,
    val frontDistCaution: Float = 30.0f,
    val rearTtcDanger: Float   = 3.0f,
    val rearTtcWarning: Float  = 5.0f,
    val rearDistDanger: Float  = 8.0f,
    val rearDistWarning: Float = 20.0f,
    val vibrationEnabled: Boolean = true,
    val speakerEnabled: Boolean = true,
    val orientationLocked: Boolean = false,  // false = auto rotate
    val sensitivityMode: SensitivityMode = SensitivityMode.NORMAL
)

enum class SensitivityMode { LOW, NORMAL, HIGH, PARANOID }

data class AdasFrame(
    val timestamp: Long = 0L,
    val frontVehicles: List<TrackedVehicle> = emptyList(),
    val rearVehicles: List<TrackedVehicle> = emptyList(),
    val laneInfo: LaneInfo = LaneInfo(),
    val steeringGuide: SteeringGuide = SteeringGuide(),
    val sensorData: SensorData = SensorData(),
    val frontWarning: CollisionWarning? = null,
    val rearWarning: CollisionWarning? = null,
    val laneWarning: LaneWarning? = null,
    val frontCamEnabled: Boolean = true,
    val rearCamEnabled: Boolean = true
)

data class CollisionWarning(
    val vehicle: TrackedVehicle,
    val ttcSeconds: Float,
    val level: WarningLevel,
    val isRear: Boolean,
    val alertMessage: String = ""
)

data class LaneWarning(
    val side: Side,
    val offsetFraction: Float,
    val level: WarningLevel,
    val message: String
)

enum class WarningLevel { SAFE, CAUTION, WARNING, DANGER }
enum class Side { LEFT, RIGHT }
enum class SteerDirection { STRAIGHT, TURN_LEFT, TURN_RIGHT, CORRECT_LEFT, CORRECT_RIGHT }
enum class CameraType { FRONT, REAR }
