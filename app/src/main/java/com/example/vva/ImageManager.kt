package com.example.vva

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class ImageManager(private val context: Context, private val previewView: PreviewView) {

    private companion object {
        const val TAG = "ImageManager"
    }

    // 使用单个后台线程处理所有相机操作（如图片捕获回调）
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null

    /**
     * 启动相机预览并绑定 ImageCapture 用例。
     */
    suspend fun startCamera() {
        // 使用 Coroutine 暂停等待 CameraProvider 初始化
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()

        // 1. 预览配置
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // 2. 图像捕获配置
        imageCapture = ImageCapture.Builder()
            // 目标分辨率和长宽比只是一种“提示”，实际分辨率可能不同，但会接近
            .setTargetResolution(Size(1280, 720))
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            // 在绑定新的用例前解绑所有用例
            cameraProvider.unbindAll()

            // 绑定用例到 LifecycleOwner (Context 必须是 LifecycleOwner，例如 AppCompatActivity)
            cameraProvider.bindToLifecycle(
                (context as androidx.lifecycle.LifecycleOwner),
                selector,
                preview,
                imageCapture
            )
            Timber.i("[Cam] started")
        } catch (e: Exception) {
            Timber.e(e, "[Cam] bind error")
            throw e
        }
    }

    /**
     * 异步捕获一张 JPEG 格式的图片，并以 ByteArray 形式返回。
     * * @return 捕获到的图片字节数组；如果失败则返回空的 ByteArray。
     */
    suspend fun takeJpeg(): ByteArray = suspendCancellableCoroutine { cont ->
        val cap = imageCapture ?: run {
            // imageCapture 未初始化，直接返回空数组
            cont.resume(ByteArray(0))
            return@suspendCancellableCoroutine
        }

        // 使用 cameraExecutor 在后台线程执行捕获回调
        cap.takePicture(
            cameraExecutor, // <-- 在后台线程执行回调，避免主线程阻塞
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var outputBytes: ByteArray? = null
                    try {
                        // ImageProxy 在 CAPTURE_MODE_MINIMIZE_LATENCY 模式下通常返回 JPEG 格式
                        // Planes[0] 包含完整的 JPEG 压缩数据。
                        val buffer: ByteBuffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        outputBytes = bytes
                    } catch (t: Throwable) {
                        Timber.e(t, "[Cam] image processing error")
                    } finally {
                        // 必须关闭 ImageProxy 才能释放资源，允许后续拍摄
                        image.close()
                        // 恢复 Coroutine，返回结果
                        cont.resume(outputBytes ?: ByteArray(0))
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Timber.e(exc, "[Cam] capture error")
                    // 恢复 Coroutine，返回空数组
                    cont.resume(ByteArray(0))
                }
            }
        )
    }

    /**
     * 释放相机资源。
     */
    fun stopCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            cameraProvider.unbindAll()
            Timber.i("[Cam] stopped and unbound")
        }, ContextCompat.getMainExecutor(context))

        // 关闭线程池
        cameraExecutor.shutdown()
    }

    /**
     * 将字节数组保存到缓存文件。
     * @param data 要保存的字节数组。
     * @return 文件的绝对路径，如果保存失败则返回 null。
     */
    fun saveToCache(data: ByteArray): String? {
        // 创建一个时间戳作为文件名，例如 'capture_1701345600000.jpeg'
        val timestamp = System.currentTimeMillis()
        val file = context.externalCacheDir?.resolve("capture_${timestamp}.jpeg")

        return try {
            file?.writeBytes(data)
            Timber.tag(TAG).d("Saved debug image to: ${file?.absolutePath}")
            file?.absolutePath
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save debug image to cache")
            null
        }
    }

    /**
     * 循环压缩图片直到达到目标大小或达到最大尝试次数
     * @param bytes 原始图片字节数组
     * @param maxSize 最大允许大小（字节）
     * @param minQuality 允许的最小压缩质量（默认 10）
     * @return 压缩后的字节数组
     */
    fun compressImageToTargetSize(
        bytes: ByteArray,
        maxSize: Int,
        minQuality: Int = 10
    ): ByteArray {
        // 如果原始图片已经小于等于目标大小，直接返回
        if (bytes.size <= maxSize) {
            return bytes
        }

        var currentBytes = bytes
        var quality = 80 // 初始质量
        var attempt = 0

        Timber.d("Starting compression: original size = %.1f KB", bytes.size / 1024f)

        while (currentBytes.size > maxSize && quality >= minQuality) {
            attempt++

            val bmp = BitmapFactory.decodeByteArray(currentBytes, 0, currentBytes.size)
            if (bmp == null) {
                Timber.w("Failed to decode image at attempt $attempt, returning current bytes")
                break
            }

            try {
                val rotatedBmp = rotateBitmapIfRequired(currentBytes, bmp)
                val out = ByteArrayOutputStream()

                // 使用当前质量进行压缩
                rotatedBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
                val newBytes = out.toByteArray()

                Timber.v(
                    "Compression attempt $attempt: quality=$quality, size=%.1f KB",
                    newBytes.size / 1024f
                )

                // 回收 Bitmap 资源
                if (rotatedBmp != bmp) {
                    bmp.recycle()
                }
                rotatedBmp.recycle()
                currentBytes = newBytes

                // 降低质量
                if (quality > minQuality && quality - minQuality < 10) {
                    quality = minQuality
                } else {
                    quality -= 10
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during compression at attempt $attempt")
                bmp.recycle()
                break
            }
        }

        // 如果经过所有尝试仍然太大，使用最后一次压缩结果
        if (currentBytes.size > maxSize) {
            Timber.w(
                "Image still too large after attempts: %.1f KB",
                currentBytes.size / 1024f
            )
        } else {
            Timber.i(
                "Compression successful: final size = %.1f KB, quality ≈ $quality",
                currentBytes.size / 1024f
            )
        }

        return currentBytes
    }

    /**
     * 根据 EXIF 标记，旋转 Bitmap 到正确的方向。
     * @param originalBytes 原始 JPEG 字节数组，用于读取 EXIF 数据。
     * @param bitmap 已经解码的 Bitmap 对象。
     * @return 旋转后的 Bitmap 对象，如果没有旋转则返回原对象。
     */
    private fun rotateBitmapIfRequired(originalBytes: ByteArray, bitmap: Bitmap): Bitmap {
        return try {
            // 使用 ByteArrayInputStream 从原始字节读取 EXIF
            val inputStream = java.io.ByteArrayInputStream(originalBytes)
            val exif = ExifInterface(inputStream)

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = android.graphics.Matrix()
            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees != 0f) {
                matrix.postRotate(rotationDegrees)

                // 使用 Matrix 创建一个新的、旋转正确的 Bitmap
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    bitmap.width,
                    bitmap.height,
                    matrix,
                    true // 允许过滤
                )
            } else {
                bitmap // 无需旋转，返回原 Bitmap
            }
        } catch (e: Exception) {
            Timber.e(e, "[WS] Error processing EXIF orientation")
            bitmap // 发生错误时，返回原 Bitmap
        }
    }
}