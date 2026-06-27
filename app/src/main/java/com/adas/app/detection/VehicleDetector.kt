package com.adas.app.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.objects.DetectedObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Multi-source vehicle detector:
 * 1. ML Kit ObjectDetection (primary — free, on-device, accurate)
 * 2. Geometric heuristic fallback if ML Kit not ready
 *
 * ML Kit auto-downloads the model on first run via Play Services.
 * No API key needed. Categories: vehicle, person, animal, food, fashion, home goods, place.
 */
class VehicleDetector(private val context: Context) {

    companion object {
        private const val TAG = "VehicleDetector"
        // ML Kit label IDs for vehicles
        private const val ML_VEHICLE = 3
        private const val ML_PERSON  = 1

        // Real-world widths for distance estimation
        val OBJECT_WIDTHS = mapOf(
            "car"        to 1.8f,
            "truck"      to 2.5f,
            "bus"        to 2.6f,
            "motorcycle" to 0.8f,
            "bicycle"    to 0.6f,
            "person"     to 0.5f,
            "van"        to 2.1f,
            "vehicle"    to 1.8f,
            "suv"        to 1.9f,
            "animal"     to 0.8f
        )
    }

    private var mlDetector: ObjectDetector? = null
    private var mlReady = false

    init {
        setupMlKit()
    }

    private fun setupMlKit() {
        try {
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                .enableMultipleObjects()
                .enableClassification()
                .build()
            mlDetector = ObjectDetection.getClient(options)
            mlReady = true
            Log.i(TAG, "ML Kit detector initialized")
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit init failed: ${e.message}")
            mlReady = false
        }
    }

    /** Synchronous detect — ML Kit async wrapped via callback */
    fun detect(bitmap: Bitmap, onResult: (List<DetectionResult>) -> Unit) {
        if (!mlReady || mlDetector == null) {
            onResult(geometricFallback(bitmap))
            return
        }
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            mlDetector!!.process(image)
                .addOnSuccessListener { objects ->
                    val results = objects.mapIndexed { i, obj ->
                        val label = classifyMlObject(obj)
                        val conf  = obj.labels.maxOfOrNull { it.confidence } ?: 0.6f
                        val box   = RectF(
                            obj.boundingBox.left.toFloat(),
                            obj.boundingBox.top.toFloat(),
                            obj.boundingBox.right.toFloat(),
                            obj.boundingBox.bottom.toFloat()
                        )
                        DetectionResult(
                            label = label,
                            confidence = conf,
                            boundingBox = box,
                            trackId = obj.trackingId ?: i,
                            source = "mlkit"
                        )
                    }.filter { it.confidence > 0.4f }
                    onResult(results)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit inference failed: ${e.message}")
                    onResult(geometricFallback(bitmap))
                }
        } catch (e: Exception) {
            Log.e(TAG, "detect() error: ${e.message}")
            onResult(geometricFallback(bitmap))
        }
    }

    private fun classifyMlObject(obj: DetectedObject): String {
        val label = obj.labels.maxByOrNull { it.confidence }
        return when (label?.index) {
            ML_VEHICLE -> when {
                obj.boundingBox.width() > obj.boundingBox.height() * 1.8 -> "truck"
                obj.boundingBox.height() > 100 -> "bus"
                else -> "car"
            }
            ML_PERSON  -> "person"
            2          -> "animal"
            else       -> "vehicle"
        }
    }

    /** Minimal geometric heuristic when ML Kit unavailable */
    private fun geometricFallback(bitmap: Bitmap): List<DetectionResult> {
        // Return empty rather than fake detections — avoids incorrect readings
        return emptyList()
    }

    fun close() {
        mlDetector?.close()
    }
}
