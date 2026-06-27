package com.adas.app.detection

import android.graphics.Bitmap
import android.graphics.RectF
import com.adas.app.vehicle.VehicleProfile
import kotlin.math.*

class AdasProcessor(
    private val detector: VehicleDetector,
    private var profile: VehicleProfile = VehicleProfile(),
    private var criteria: CollisionCriteria = CollisionCriteria()
) {
    private val distEstimator = DistanceEstimator(profile)

    // Speed history: trackId -> [(timestampMs, distanceM)]
    private val frontHistory = mutableMapOf<Int, ArrayDeque<Pair<Long, Float>>>()
    private val rearHistory  = mutableMapOf<Int, ArrayDeque<Pair<Long, Float>>>()
    private val frontTracks  = mutableMapOf<Int, DetectionResult>()
    private val rearTracks   = mutableMapOf<Int, DetectionResult>()
    private var frontCounter = 0
    private var rearCounter  = 0

    fun updateProfile(p: VehicleProfile) {
        profile = p
        distEstimator.updateProfile(p)
    }

    fun updateCriteria(c: CollisionCriteria) { criteria = c }

    /** Async-friendly: calls back with results */
    fun processFrame(
        bitmap: Bitmap,
        cameraType: CameraType,
        egoSpeedKmh: Float,
        timestampMs: Long,
        onResult: (List<TrackedVehicle>) -> Unit
    ) {
        detector.detect(bitmap) { detections ->
            val tracked = assignTracks(detections, cameraType)
            val history = if (cameraType == CameraType.FRONT) frontHistory else rearHistory

            val vehicles = tracked.map { det ->
                val distM = distEstimator.estimate(
                    det.boundingBox, det.label, bitmap.width, bitmap.height
                )
                val hist = history.getOrPut(det.trackId) { ArrayDeque() }
                hist.addLast(Pair(timestampMs, distM))
                if (hist.size > 20) hist.removeFirst()

                val relSpeed = calcRelativeSpeed(hist)
                val absSpeed = when (cameraType) {
                    CameraType.FRONT -> (egoSpeedKmh - relSpeed).coerceAtLeast(0f)
                    CameraType.REAR  -> (egoSpeedKmh + relSpeed).coerceAtLeast(0f)
                }
                val ttc = if (relSpeed > 0.5f) (distM / (relSpeed / 3.6f)) else Float.MAX_VALUE
                val warn = warningLevel(distM, ttc, cameraType)

                TrackedVehicle(
                    id = det.trackId, label = det.label,
                    boundingBox = det.boundingBox, confidence = det.confidence,
                    distanceM = distM, relativeSpeedKmh = relSpeed,
                    absoluteSpeedKmh = absSpeed, isRear = cameraType == CameraType.REAR,
                    warningLevel = warn, ttcSeconds = ttc,
                    detectionSource = det.source
                )
            }
            onResult(vehicles)
        }
    }

    fun analyzeLane(
        frontVehicles: List<TrackedVehicle>,
        imageW: Int,
        imageH: Int,
        sensor: SensorData
    ): LaneInfo {
        val lZ = 0f..imageW * 0.28f
        val rZ = imageW * 0.72f..imageW.toFloat()

        val leftObs  = frontVehicles.filter { it.boundingBox.centerX() in lZ }
        val rightObs = frontVehicles.filter { it.boundingBox.centerX() in rZ }

        fun spaceScore(obs: List<TrackedVehicle>): Float {
            if (obs.isEmpty()) return 1f
            val cover = obs.maxOf { it.boundingBox.width() / imageW * 2.5f }
            return (1f - cover).coerceIn(0f, 1f)
        }

        val laneOffset = (sensor.rollDeg / 12f).coerceIn(-1f, 1f)
        val isDeparting = abs(laneOffset) > 0.55f

        return LaneInfo(
            leftSpaceFraction  = spaceScore(leftObs),
            rightSpaceFraction = spaceScore(rightObs),
            leftDistanceM      = leftObs.minByOrNull  { it.distanceM }?.distanceM ?: -1f,
            rightDistanceM     = rightObs.minByOrNull { it.distanceM }?.distanceM ?: -1f,
            leftObstacleLabel  = leftObs.minByOrNull  { it.distanceM }?.label,
            rightObstacleLabel = rightObs.minByOrNull { it.distanceM }?.label,
            laneOffsetFraction = laneOffset,
            isLaneDeparting    = isDeparting,
            departingSide      = if (!isDeparting) null
                                 else if (laneOffset < 0) Side.LEFT else Side.RIGHT
        )
    }

    fun buildSteeringGuide(
        lane: LaneInfo,
        sensor: SensorData,
        egoSpeedKmh: Float
    ): SteeringGuide {
        val vMs = (egoSpeedKmh / 3.6f).coerceAtLeast(1f)
        val latAccel = abs(sensor.accelX)
        val curRadius = if (latAccel > 0.1f) (vMs * vMs / latAccel).coerceIn(3f, 500f)
                        else Float.MAX_VALUE

        val dir = when {
            abs(sensor.rollDeg) < 2f && latAccel < 0.05f -> SteerDirection.STRAIGHT
            sensor.accelX < -0.05f -> SteerDirection.TURN_LEFT
            sensor.accelX >  0.05f -> SteerDirection.TURN_RIGHT
            lane.laneOffsetFraction < -0.4f -> SteerDirection.CORRECT_RIGHT
            lane.laneOffsetFraction >  0.4f -> SteerDirection.CORRECT_LEFT
            else -> SteerDirection.STRAIGHT
        }

        val recRadius  = if (curRadius < 400f) curRadius else profile.turningRadiusM
        val wheelDeg   = if (dir == SteerDirection.STRAIGHT) 0f
                         else profile.steeringWheelDegForRadius(recRadius)
        val recSpeed   = profile.recommendedSpeedKmh(recRadius)

        val notes = buildString {
            if (lane.isLaneDeparting)
                append("⚠ Lane departure ${lane.departingSide?.name}! ")
            if (egoSpeedKmh > recSpeed + 10)
                append("Slow to ${recSpeed.toInt()}km/h. ")
            append(when (dir) {
                SteerDirection.CORRECT_LEFT  -> "Steer left ${wheelDeg.toInt()}°"
                SteerDirection.CORRECT_RIGHT -> "Steer right ${wheelDeg.toInt()}°"
                SteerDirection.TURN_LEFT     -> "Turn L — ${wheelDeg.toInt()}° wheel"
                SteerDirection.TURN_RIGHT    -> "Turn R — ${wheelDeg.toInt()}° wheel"
                SteerDirection.STRAIGHT      -> "Keep straight"
            })
        }
        return SteeringGuide(wheelDeg, recSpeed, recRadius, dir, notes)
    }

    fun buildFrame(
        frontVehicles: List<TrackedVehicle>,
        rearVehicles: List<TrackedVehicle>,
        frontBitmap: Bitmap,
        sensor: SensorData,
        egoSpeedKmh: Float,
        ts: Long,
        frontOn: Boolean,
        rearOn: Boolean
    ): AdasFrame {
        val lane     = analyzeLane(frontVehicles, frontBitmap.width, frontBitmap.height, sensor)
        val steering = buildSteeringGuide(lane, sensor, egoSpeedKmh)

        val frontWarn = frontVehicles
            .filter { it.warningLevel != WarningLevel.SAFE }
            .minByOrNull { it.distanceM }
            ?.let {
                CollisionWarning(it, it.ttcSeconds, it.warningLevel, false,
                    "⚠ FRONT: ${it.label} ${it.distanceM.toInt()}m  TTC ${if(it.ttcSeconds<99f) "${"%.1f".format(it.ttcSeconds)}s" else "—"}")
            }

        val rearWarn = rearVehicles
            .filter { it.warningLevel != WarningLevel.SAFE && it.relativeSpeedKmh > 2f }
            .minByOrNull { it.distanceM }
            ?.let {
                CollisionWarning(it, it.ttcSeconds, it.warningLevel, true,
                    "⚠ REAR: ${it.label} approaching ${it.relativeSpeedKmh.toInt()}km/h")
            }

        val laneWarn = if (lane.isLaneDeparting) LaneWarning(
            lane.departingSide ?: Side.LEFT,
            lane.laneOffsetFraction,
            if (abs(lane.laneOffsetFraction) > 0.8f) WarningLevel.DANGER else WarningLevel.WARNING,
            "Lane departure — steer ${if (lane.laneOffsetFraction < 0) "RIGHT" else "LEFT"}"
        ) else null

        return AdasFrame(ts, frontVehicles, rearVehicles, lane, steering,
            sensor, frontWarn, rearWarn, laneWarn, frontOn, rearOn)
    }

    // ── Distance / Speed helpers ──────────────────────────────────────────

    private fun calcRelativeSpeed(history: ArrayDeque<Pair<Long, Float>>): Float {
        if (history.size < 3) return 0f
        // Use linear regression over last N points for smoother speed
        val n = history.size.toFloat()
        val sumT = history.sumOf { (it.first / 1000.0) }.toFloat()
        val sumD = history.sumOf { it.second.toDouble() }.toFloat()
        val sumTD = history.sumOf { (it.first / 1000.0) * it.second }.toFloat()
        val sumT2 = history.sumOf { (it.first / 1000.0).let { t -> t * t } }.toFloat()
        val denom = n * sumT2 - sumT * sumT
        if (abs(denom) < 1e-6f) return 0f
        val slope = (n * sumTD - sumT * sumD) / denom  // m/s, negative = approaching
        return (-slope * 3.6f).coerceIn(-200f, 200f)   // convert to km/h, flip sign
    }

    private fun warningLevel(distM: Float, ttcS: Float, cam: CameraType): WarningLevel {
        val mult = when (criteria.sensitivityMode) {
            SensitivityMode.LOW      -> 0.6f
            SensitivityMode.NORMAL   -> 1.0f
            SensitivityMode.HIGH     -> 1.4f
            SensitivityMode.PARANOID -> 2.0f
        }
        val ttcD: Float; val ttcW: Float; val ttcC: Float
        val dD: Float;   val dW: Float;   val dC: Float
        if (cam == CameraType.FRONT) {
            ttcD = criteria.frontTtcDanger  * mult; ttcW = criteria.frontTtcWarning * mult
            ttcC = criteria.frontTtcCaution * mult
            dD   = criteria.frontDistDanger;         dW  = criteria.frontDistWarning
            dC   = criteria.frontDistCaution
        } else {
            ttcD = criteria.rearTtcDanger  * mult; ttcW = criteria.rearTtcWarning * mult
            ttcC = criteria.rearTtcWarning * mult * 1.5f
            dD   = criteria.rearDistDanger;         dW  = criteria.rearDistWarning
            dC   = criteria.rearDistWarning * 1.5f
        }
        return when {
            ttcS < ttcD || distM < dD -> WarningLevel.DANGER
            ttcS < ttcW || distM < dW -> WarningLevel.WARNING
            ttcS < ttcC || distM < dC -> WarningLevel.CAUTION
            else                       -> WarningLevel.SAFE
        }
    }

    // ── IoU tracker ──────────────────────────────────────────────────────

    private fun assignTracks(dets: List<DetectionResult>, cam: CameraType): List<DetectionResult> {
        val tracks = if (cam == CameraType.FRONT) frontTracks else rearTracks
        val result = mutableListOf<DetectionResult>()
        val used   = mutableSetOf<Int>()
        for (det in dets) {
            var bestId = -1; var bestIou = 0.30f
            for ((id, prev) in tracks) {
                if (id in used || prev.label != det.label) continue
                val iou = iou(det.boundingBox, prev.boundingBox)
                if (iou > bestIou) { bestIou = iou; bestId = id }
            }
            if (bestId == -1) {
                bestId = if (cam == CameraType.FRONT) ++frontCounter else ++rearCounter
            }
            used.add(bestId); tracks[bestId] = det
            result.add(det.copy(trackId = bestId))
        }
        tracks.keys.retainAll(used)
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val il = max(a.left, b.left); val it2 = max(a.top,  b.top)
        val ir = min(a.right,b.right); val ib = min(a.bottom,b.bottom)
        if (ir <= il || ib <= it2) return 0f
        val inter = (ir-il)*(ib-it2)
        return inter / (a.width()*a.height() + b.width()*b.height() - inter)
    }

    fun reset() {
        frontTracks.clear(); rearTracks.clear()
        frontHistory.clear(); rearHistory.clear()
    }
}

