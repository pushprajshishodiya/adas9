package com.adas.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Dual camera: CameraX (back) + Camera2 (front) simultaneously.
 *
 * CameraX 1.3.x has a single ProcessCameraProvider — you cannot bind
 * two different CameraSelectors from the same provider on the same device.
 *
 * Solution:
 *  • Back camera  → CameraX (handles lifecycle, preview, analysis cleanly)
 *  • Front camera → Camera2 directly (independent session, no CameraX conflict)
 *
 * Tested approach for Realme, Samsung, Xiaomi etc.
 */
class DualCameraManager(private val context: Context) {

    companion object {
        private const val TAG = "DualCameraManager"
        private const val W = 640; private const val H = 480
        private const val SKIP = 3
    }

    // ── CameraX (back) ────────────────────────────────────────────────────
    private var cxProvider: ProcessCameraProvider? = null
    private val cxExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var nFront = 0

    // ── Camera2 (front) ───────────────────────────────────────────────────
    private val cm2 = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cam2Device:  CameraDevice?       = null
    private var cam2Session: CameraCaptureSession? = null
    private var cam2Reader:  ImageReader?         = null
    private var cam2Thread:  HandlerThread?       = null
    private var cam2Handler: Handler?             = null
    private var nRear = 0

    // ── Callbacks ─────────────────────────────────────────────────────────
    var onFrontFrame: ((Bitmap, Long) -> Unit)? = null
    var onRearFrame:  ((Bitmap, Long) -> Unit)? = null

    var frontEnabled = true
    var rearEnabled  = true

    private var savedOwner:   LifecycleOwner? = null
    private var savedFrontPV: PreviewView?    = null
    private var savedRearPV:  PreviewView?    = null
    private var savedRearTV:  TextureView?    = null   // for Camera2 preview

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    fun start(owner: LifecycleOwner, frontPV: PreviewView?, rearPV: PreviewView?,
              rearTV: TextureView? = null) {
        savedOwner = owner; savedFrontPV = frontPV; savedRearPV = rearPV; savedRearTV = rearTV
        if (frontEnabled) startCameraX(owner, frontPV)
        if (rearEnabled)  startCamera2(rearTV, rearPV)
    }

    fun rebind(owner: LifecycleOwner, frontPV: PreviewView?, rearPV: PreviewView?,
               rearTV: TextureView? = null) {
        stop()
        start(owner, frontPV, rearPV, rearTV)
    }

    fun stop() {
        // Stop CameraX
        try { cxProvider?.unbindAll() } catch (_: Exception) {}
        // Stop Camera2
        stopCamera2()
        if (!cxExecutor.isShutdown) cxExecutor.shutdown()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAMERAX — back camera (road ahead)
    // ═══════════════════════════════════════════════════════════════════════

    private fun startCameraX(owner: LifecycleOwner, previewView: PreviewView?) {
        ProcessCameraProvider.getInstance(context).addListener({
            val p = ProcessCameraProvider.getInstance(context).get()
            cxProvider = p
            try {
                p.unbindAll()
                val preview = previewView?.let {
                    Preview.Builder().build().also { pr -> pr.setSurfaceProvider(it.surfaceProvider) }
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(W, H))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build().also { ia ->
                        ia.setAnalyzer(cxExecutor) { proxy ->
                            try {
                                nFront++
                                if (nFront % SKIP == 0) {
                                    val bmp = proxyToBitmap(proxy)
                                    onFrontFrame?.invoke(bmp, System.currentTimeMillis())
                                }
                            } finally { proxy.close() }
                        }
                    }
                val useCases = listOfNotNull(preview, analysis)
                p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *useCases.toTypedArray())
                Log.i(TAG, "CameraX back camera bound ✓")
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bind failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun proxyToBitmap(proxy: ImageProxy): Bitmap {
        val buf   = proxy.planes[0].buffer
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        val bmp = Bitmap.createBitmap(proxy.width, proxy.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
        return bmp
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CAMERA2 — front camera (rear-view of car)
    // ═══════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun startCamera2(rearTV: TextureView?, rearPV: PreviewView?) {
        cam2Thread = HandlerThread("Camera2Front").also { it.start() }
        cam2Handler = Handler(cam2Thread!!.looper)

        // Find front-facing camera ID
        val frontId = cm2.cameraIdList.firstOrNull { id ->
            cm2.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        if (frontId == null) {
            Log.w(TAG, "No front camera found"); return
        }

        // ImageReader for analysis frames
        cam2Reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 3).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image: Image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    nRear++
                    if (nRear % SKIP == 0) {
                        val bmp = yuvToBitmap(image)
                        onRearFrame?.invoke(bmp, System.currentTimeMillis())
                    }
                } finally { image.close() }
            }, cam2Handler)
        }

        cm2.openCamera(frontId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cam2Device = camera
                createCamera2Session(camera, rearTV, rearPV)
                Log.i(TAG, "Camera2 front camera opened ✓")
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); cam2Device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cam2Device = null
                Log.e(TAG, "Camera2 error: $error")
            }
        }, cam2Handler)
    }

    private fun createCamera2Session(camera: CameraDevice, rearTV: TextureView?, rearPV: PreviewView?) {
        val surfaces = mutableListOf<Surface>()

        // Analysis surface (always)
        val analysisSurface = cam2Reader!!.surface
        surfaces.add(analysisSurface)

        // Preview surface — use TextureView if provided, else skip preview
        val previewSurface: Surface? = when {
            rearTV != null -> {
                if (rearTV.isAvailable) {
                    Surface(rearTV.surfaceTexture!!.also {
                        it.setDefaultBufferSize(W, H)
                    })
                } else {
                    rearTV.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            st.setDefaultBufferSize(W, H)
                            // Recreate session with the now-available surface
                            cam2Session?.close()
                            createCamera2Session(camera, rearTV, null)
                        }
                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                    }
                    null
                }
            }
            else -> null
        }
        previewSurface?.let { surfaces.add(it) }

        @Suppress("DEPRECATION")
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                cam2Session = session
                try {
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(analysisSurface)
                        previewSurface?.let { addTarget(it) }
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }.build()
                    session.setRepeatingRequest(req, null, cam2Handler)
                    Log.i(TAG, "Camera2 capture session started ✓")
                } catch (e: Exception) {
                    Log.e(TAG, "Camera2 session request failed: ${e.message}")
                }
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Camera2 session config failed")
            }
        }, cam2Handler)
    }

    /** Convert YUV_420_888 Image to ARGB Bitmap */
    private fun yuvToBitmap(image: Image): Bitmap {
        val yPlane  = image.planes[0]
        val uPlane  = image.planes[1]
        val vPlane  = image.planes[2]
        val yBuf    = yPlane.buffer
        val uBuf    = uPlane.buffer
        val vBuf    = vPlane.buffer

        val ySize = yBuf.remaining()
        val uSize = uBuf.remaining()
        val vSize = vBuf.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuf.get(nv21, 0, ySize)
        vBuf.get(nv21, ySize, vSize)
        uBuf.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, out)
        val jpegBytes = out.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
    }

    private fun stopCamera2() {
        try { cam2Session?.stopRepeating() } catch (_: Exception) {}
        try { cam2Session?.close() }  catch (_: Exception) {}
        try { cam2Device?.close() }   catch (_: Exception) {}
        try { cam2Reader?.close() }   catch (_: Exception) {}
        cam2Session = null; cam2Device = null; cam2Reader = null
        cam2Thread?.quitSafely()
        try { cam2Thread?.join(1000) } catch (_: Exception) {}
        cam2Thread = null; cam2Handler = null
    }
}
