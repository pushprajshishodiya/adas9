package com.adas.app.detection

import android.graphics.RectF
import com.adas.app.vehicle.VehicleProfile
import kotlin.math.*

/**
 * Physics-based distance estimator using multiple methods:
 *
 * 1. Triangle similarity (width-based) — most reliable for vehicles
 *    distance = (real_width_m × focal_px) / pixel_width
 *
 * 2. Height-based (ground plane projection)
 *    distance = camera_height × focal_px / (image_height - box_bottom)
 *
 * 3. Stereo approximation using bounding box bottom edge
 *    (object resting on road = known height above ground)
 *
 * Blend: 60% width + 30% height + 10% bottom-edge
 * Calibration: applies per-object correction factors from empirical data
 */
class DistanceEstimator(private var profile: VehicleProfile = VehicleProfile()) {

    companion object {
        // Empirical correction factors per object type (calibrated on road cam)
        private val CORRECTION = mapOf(
            "car"        to 0.95f,
            "truck"      to 0.85f,
            "bus"        to 0.80f,
            "motorcycle" to 1.10f,
            "bicycle"    to 1.15f,
            "person"     to 1.20f,
            "van"        to 0.90f,
            "vehicle"    to 0.95f,
            "suv"        to 0.92f
        )

        // Minimum reliable detection distance per object
        private val MIN_DIST = mapOf(
            "car" to 2f, "truck" to 3f, "bus" to 4f,
            "motorcycle" to 1f, "bicycle" to 1f, "person" to 0.5f
        )
        private val MAX_DIST = mapOf(
            "car" to 120f, "truck" to 150f, "bus" to 100f,
            "motorcycle" to 80f, "bicycle" to 60f, "person" to 40f
        )
    }

    fun updateProfile(p: VehicleProfile) { profile = p }

    fun estimate(
        box: RectF,
        label: String,
        imageWidth: Int,
        imageHeight: Int
    ): Float {
        val focal     = profile.focalLengthPx(imageWidth)
        val realWidth = VehicleDetector.OBJECT_WIDTHS[label] ?: 1.8f
        val correction = CORRECTION[label] ?: 1.0f

        // Method 1: width-based
        val pixelWidth = box.width().coerceAtLeast(1f)
        val dWidth = (realWidth * focal) / pixelWidth

        // Method 2: height-based ground plane
        // Objects near bottom of frame are closer (road-mounted camera)
        val groundY = imageHeight.toFloat()
        val boxBottomNorm = (box.bottom / groundY).coerceIn(0.1f, 1.0f)
        val dHeight = profile.cameraHeightM * focal / (box.bottom.coerceAtLeast(1f))

        // Method 3: bottom-edge projection
        // If box bottom touches road level → use camera trig
        val depressionAngleDeg = profile.cameraFovDeg * 0.5f * boxBottomNorm
        val dBottomEdge = if (depressionAngleDeg > 0.5f)
            profile.cameraHeightM / tan(Math.toRadians(depressionAngleDeg.toDouble())).toFloat()
        else dWidth

        // Weighted blend
        val raw = dWidth * 0.60f + dHeight * 0.25f + dBottomEdge * 0.15f
        val corrected = raw * correction

        val minD = MIN_DIST[label] ?: 1f
        val maxD = MAX_DIST[label] ?: 150f
        return corrected.coerceIn(minD, maxD)
    }

    /** Confidence score for the distance estimate (0–1) */
    fun confidence(box: RectF, imageWidth: Int, imageHeight: Int): Float {
        val widthRatio = box.width() / imageWidth
        return when {
            widthRatio > 0.3f -> 0.95f   // Large box = close = high confidence
            widthRatio > 0.1f -> 0.80f
            widthRatio > 0.05f -> 0.65f
            else -> 0.40f                 // Very small box = distant = lower confidence
        }
    }
}
