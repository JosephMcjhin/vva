package com.example.vva

import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import timber.log.Timber

object ImageCompressor {

    /**
     * 先裁剪尺寸再压缩质量，直到达到目标大小
     * @param bytes 原始图片字节数组
     * @param maxSize 最大允许大小（字节）
     * @param maxWidth 最大宽度（像素），默认 1080
     * @param maxHeight 最大高度（像素），默认 1920
     * @param minQuality 允许的最小压缩质量（默认 10）
     * @param keepAspectRatio 是否保持宽高比（默认 true）
     * @return 压缩后的字节数组
     */
    fun compressImageWithResize(
        bytes: ByteArray,
        maxSize: Int,
        maxWidth: Int = 1080,
        maxHeight: Int = 1920,
        minQuality: Int = 10,
        keepAspectRatio: Boolean = true
    ): ByteArray {
        // 如果原始图片已经小于等于目标大小，直接返回
        if (bytes.size <= maxSize) {
            Timber.d("Original image already under max size: %.1f KB", bytes.size / 1024f)
            return bytes
        }

        Timber.d("Starting compression: original size = %.1f KB", bytes.size / 1024f)

        try {
            // 1. 先进行尺寸压缩
            val resizedBitmap = resizeImage(bytes, maxWidth, maxHeight, keepAspectRatio)
            if (resizedBitmap == null) {
                Timber.w("Failed to resize image, returning original")
                return bytes
            }

            // 2. 再进行质量压缩
            val compressedBytes = compressQualityToTargetSize(
                bitmap = resizedBitmap,
                maxSize = maxSize,
                minQuality = minQuality
            )

            // 回收 Bitmap 资源
            resizedBitmap.recycle()

            return compressedBytes

        } catch (e: Exception) {
            Timber.e(e, "Error during compression")
            return bytes
        }
    }

    /**
     * 尺寸裁剪：将图片缩放到指定范围内
     */
    private fun resizeImage(
        bytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        keepAspectRatio: Boolean = true
    ): Bitmap? {
        try {
            // 第一步：只获取图片尺寸信息
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight

            Timber.d("Original dimensions: ${originalWidth}x${originalHeight}")
            Timber.d("Target dimensions: ${maxWidth}x${maxHeight}")

            // 如果原始尺寸已经小于目标尺寸，直接解码返回
            if (originalWidth <= maxWidth && originalHeight <= maxHeight) {
                Timber.d("No need to resize, using original dimensions")
                val decodeOptions = BitmapFactory.Options().apply {
                    inJustDecodeBounds = false
                    inPreferredConfig = Bitmap.Config.RGB_565 // 使用更省内存的配置
                }
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
            }

            // 计算缩放比例
            val scaleRatio = calculateScaleRatio(
                originalWidth, originalHeight,
                maxWidth, maxHeight,
                keepAspectRatio
            )

            Timber.d("Scale ratio: $scaleRatio")

            // 第二步：根据缩放比例解码图片
            val decodeOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = false
                inSampleSize = calculateInSampleSize(scaleRatio)
                inPreferredConfig = Bitmap.Config.RGB_565
                inPurgeable = true // 允许系统在需要时回收内存
                inInputShareable = true // 可共享的输入流
            }

            var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)

            if (bitmap == null) {
                Timber.w("Failed to decode bitmap after resizing")
                return null
            }

            // 第三步：处理图片旋转（解决 EXIF 方向问题）
            bitmap = rotateBitmapIfRequired(bytes, bitmap)

            // 第四步：如果需要精确尺寸，进行精确缩放
            if (shouldScaleExactly(bitmap, maxWidth, maxHeight)) {
                bitmap = scaleBitmapExactly(bitmap, maxWidth, maxHeight, keepAspectRatio)
            }

            Timber.d("Resized to: ${bitmap.width}x${bitmap.height}")

            return bitmap

        } catch (e: Exception) {
            Timber.e(e, "Error during image resizing")
            return null
        }
    }

    /**
     * 计算缩放比例
     */
    private fun calculateScaleRatio(
        originalWidth: Int,
        originalHeight: Int,
        maxWidth: Int,
        maxHeight: Int,
        keepAspectRatio: Boolean
    ): Float {
        return if (keepAspectRatio) {
            // 保持宽高比，选择最小的缩放比例
            val widthRatio = originalWidth.toFloat() / maxWidth.toFloat()
            val heightRatio = originalHeight.toFloat() / maxHeight.toFloat()
            maxOf(widthRatio, heightRatio)
        } else {
            // 不保持宽高比，分别计算宽高缩放比例
            val widthRatio = originalWidth.toFloat() / maxWidth.toFloat()
            val heightRatio = originalHeight.toFloat() / maxHeight.toFloat()
            minOf(widthRatio, heightRatio) // 选择较小的，确保两边都不超过限制
        }
    }

    /**
     * 计算采样大小（必须是2的幂次）
     */
    private fun calculateInSampleSize(scaleRatio: Float): Int {
        var inSampleSize = 1

        // 如果缩放比例大于1，计算合适的采样大小
        if (scaleRatio > 1) {
            // 找到最接近的2的幂次
            while ((inSampleSize * 2) < scaleRatio) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    /**
     * 判断是否需要精确缩放
     */
    private fun shouldScaleExactly(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Boolean {
        return bitmap.width > maxWidth || bitmap.height > maxHeight
    }

    /**
     * 精确缩放图片
     */
    private fun scaleBitmapExactly(
        bitmap: Bitmap,
        maxWidth: Int,
        maxHeight: Int,
        keepAspectRatio: Boolean
    ): Bitmap {
        val targetWidth: Int
        val targetHeight: Int

        if (keepAspectRatio) {
            // 保持宽高比
            val widthRatio = bitmap.width.toFloat() / maxWidth.toFloat()
            val heightRatio = bitmap.height.toFloat() / maxHeight.toFloat()
            val ratio = maxOf(widthRatio, heightRatio)

            targetWidth = (bitmap.width / ratio).toInt()
            targetHeight = (bitmap.height / ratio).toInt()
        } else {
            // 不保持宽高比，直接缩放到最大尺寸
            targetWidth = maxWidth
            targetHeight = maxHeight
        }

        // 使用 Matrix 进行高质量缩放
        val matrix = Matrix().apply {
            // 计算缩放比例
            val scaleX = targetWidth.toFloat() / bitmap.width
            val scaleY = targetHeight.toFloat() / bitmap.height
            setScale(scaleX, scaleY)
        }

        return Bitmap.createBitmap(
            bitmap, 0, 0,
            bitmap.width, bitmap.height,
            matrix, true
        ).also {
            // 回收原始 Bitmap
            bitmap.recycle()
        }
    }

    /**
     * 质量压缩：将 Bitmap 压缩到指定大小
     */
    private fun compressQualityToTargetSize(
        bitmap: Bitmap,
        maxSize: Int,
        minQuality: Int = 10
    ): ByteArray {
        var currentBytes = ByteArray(0)
        var quality = 80 // 初始质量
        var attempt = 0

        // 先尝试用高质量压缩
        var out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        currentBytes = out.toByteArray()
        out.close()

        Timber.d("After initial compression: quality=$quality, size=%.1f KB",
            currentBytes.size / 1024f)

        // 如果已经满足要求，直接返回
        if (currentBytes.size <= maxSize) {
            return currentBytes
        }

        // 使用二分查找法找到合适质量
        var low = minQuality
        var high = quality
        var mid: Int

        while (low <= high) {
            attempt++
            mid = (low + high) / 2

            out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, mid, out)
            val newBytes = out.toByteArray()
            out.close()

            val newSizeKB = newBytes.size / 1024f
            Timber.v("Attempt $attempt: quality=$mid, size=%.1f KB", newSizeKB)

            when {
                newBytes.size > maxSize -> {
                    // 仍然太大，降低质量
                    high = mid - 1
                }
                newBytes.size < maxSize * 0.9 -> {
                    // 太小，可以尝试提高质量
                    currentBytes = newBytes
                    low = mid + 1
                }
                else -> {
                    // 在可接受范围内
                    currentBytes = newBytes
                    break
                }
            }
        }

        Timber.i("Final compression: size=%.1f KB, attempts=$attempt",
            currentBytes.size / 1024f)

        return currentBytes
    }

    /**
     * 处理图片旋转（根据 EXIF 信息）
     */
    private fun rotateBitmapIfRequired(bytes: ByteArray, bitmap: Bitmap): Bitmap {
        return try {
            // 创建临时文件来读取 EXIF 信息
            val tempFile = File.createTempFile("temp_image", ".jpg")
            tempFile.deleteOnExit()

            FileOutputStream(tempFile).use { fos ->
                fos.write(bytes)
                fos.flush()
            }

            val exif = ExifInterface(tempFile.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                }

                return Bitmap.createBitmap(
                    bitmap, 0, 0,
                    bitmap.width, bitmap.height,
                    matrix, true
                ).also {
                    // 回收原始 Bitmap
                    bitmap.recycle()
                    Timber.d("Rotated image by $rotationDegrees degrees")
                }
            }

            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Error rotating bitmap")
            bitmap
        }
    }

    /**
     * 快速压缩版本（适合实时处理）
     */
    fun quickCompress(
        bytes: ByteArray,
        targetSizeKB: Int = 500,
        maxWidth: Int = 1080,
        maxHeight: Int = 1920
    ): ByteArray {
        val maxSize = targetSizeKB * 1024

        return try {
            // 1. 快速尺寸压缩
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val sampleSize = calculateInSampleSize(
                options.outWidth, options.outHeight,
                maxWidth, maxHeight
            )

            options.inJustDecodeBounds = false
            options.inSampleSize = sampleSize
            options.inPreferredConfig = Bitmap.Config.RGB_565

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return bytes

            // 2. 快速质量压缩（直接使用中等质量）
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
            val compressed = out.toByteArray()

            bitmap.recycle()
            out.close()

            // 3. 如果还是太大，进一步压缩
            if (compressed.size > maxSize) {
                return compressImageWithResize(
                    bytes = bytes,
                    maxSize = maxSize,
                    maxWidth = maxWidth,
                    maxHeight = maxHeight
                )
            }

            compressed
        } catch (e: Exception) {
            Timber.e(e, "Quick compression failed")
            bytes
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        maxWidth: Int,
        maxHeight: Int
    ): Int {
        var inSampleSize = 1

        if (height > maxHeight || width > maxWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= maxHeight &&
                halfWidth / inSampleSize >= maxWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}

// 使用示例
class ImageUploader {

    fun prepareImageForUpload(
        imageBytes: ByteArray,
        targetSizeKB: Int = 500
    ): ByteArray {
        val maxSize = targetSizeKB * 1024

        return ImageCompressor.compressImageWithResize(
            bytes = imageBytes,
            maxSize = maxSize,
            maxWidth = 1080,  // 可根据需求调整
            maxHeight = 1920,
            minQuality = 20,
            keepAspectRatio = true
        )
    }

    // 或者使用快速版本
    fun quickPrepare(imageBytes: ByteArray): ByteArray {
        return ImageCompressor.quickCompress(
            bytes = imageBytes,
            targetSizeKB = 300,
            maxWidth = 800,
            maxHeight = 1200
        )
    }
}