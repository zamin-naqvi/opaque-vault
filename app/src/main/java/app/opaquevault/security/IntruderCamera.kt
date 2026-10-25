package app.opaquevault.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Best-effort, foreground-only front-camera capture after a failed unlock. */
class IntruderCamera(context: Context, private val encryptedLog: EncryptedIntruderLog) {
    private val app = context.applicationContext

    fun captureIfPermitted() {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        }.getOrNull() ?: return
        val thread = HandlerThread("intruder-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)
        val closed = AtomicBoolean(false)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        fun closeAll() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { session?.close() }
            runCatching { camera?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
        reader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            try {
                val buffer = image.planes.firstOrNull()?.buffer ?: return@setOnImageAvailableListener
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                try {
                    runCatching { encryptedLog.saveLatestPhoto(jpeg) }
                    runCatching { encryptedLog.append("Front-camera image captured after failed unlock") }
                } finally { jpeg.wipe() }
            } finally {
                image.close(); closeAll()
            }
        }, handler)
        handler.postDelayed({ closeAll() }, 8_000L)
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    if (closed.get()) { device.close(); return }
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            session = configured
                            if (closed.get()) { configured.close(); return }
                            runCatching {
                                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                    addTarget(reader.surface)
                                    set(android.hardware.camera2.CaptureRequest.JPEG_QUALITY, 80.toByte())
                                }.build()
                                configured.capture(request, object : CameraCaptureSession.CaptureCallback() {}, handler)
                            }.onFailure { closeAll() }
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) = closeAll()
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); closeAll() }
                override fun onError(device: CameraDevice, error: Int) { device.close(); closeAll() }
            }, handler)
        } catch (_: Exception) {
            closeAll()
        }
    }
}
