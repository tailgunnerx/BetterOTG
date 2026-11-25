package com.tailgunnerx.betterotg.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ScaleOverlayMode(val label: String) {
    NONE("Off"),
    MM("mm"),
    INCH("inch")
}

data class FrameTransform(
    val rotationDegrees: Int = 0,
    val mirrorH: Boolean = false,
    val mirrorV: Boolean = false,
    val watermarkEnabled: Boolean = true
)

object FrameProcessor {
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun buildTransformedBitmap(source: Bitmap, transform: FrameTransform): Bitmap {
        val rotated90 = transform.rotationDegrees % 180 != 0
        val outW = if (rotated90) source.height else source.width
        val outH = if (rotated90) source.width else source.height
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawFrameToCanvas(canvas, source, outW, outH, transform)
        return out
    }

    fun drawFrameToCanvas(
        canvas: Canvas,
        source: Bitmap,
        dstW: Int,
        dstH: Int,
        transform: FrameTransform
    ) {
        canvas.drawColor(Color.BLACK)
        val matrix = Matrix()
        matrix.postTranslate(-source.width / 2f, -source.height / 2f)
        if (transform.mirrorH || transform.mirrorV) {
            matrix.postScale(
                if (transform.mirrorH) -1f else 1f,
                if (transform.mirrorV) -1f else 1f
            )
        }
        if (transform.rotationDegrees != 0) {
            matrix.postRotate(transform.rotationDegrees.toFloat())
        }
        val rotated90 = transform.rotationDegrees % 180 != 0
        val effW = if (rotated90) source.height.toFloat() else source.width.toFloat()
        val effH = if (rotated90) source.width.toFloat() else source.height.toFloat()
        val scale = minOf(dstW / effW, dstH / effH)
        matrix.postScale(scale, scale)
        matrix.postTranslate(dstW / 2f, dstH / 2f)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(source, matrix, paint)

        if (transform.watermarkEnabled) {
            val stamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
            val textSize = (minOf(dstW, dstH) * 0.036f).coerceIn(18f, 48f)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                this.textSize = textSize
                typeface = Typeface.MONOSPACE
                setShadowLayer(4f, 2f, 2f, Color.argb(210, 0, 0, 0))
            }
            val margin = textSize * 0.7f
            canvas.drawText(stamp, margin, dstH - margin, textPaint)
        }
    }

    fun saveSnapshot(context: Context, source: Bitmap, transform: FrameTransform): Uri? {
        val rendered = buildTransformedBitmap(source, transform)
        val fileName = "BetterOTG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BetterOTG")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "BetterOTG"
                ).apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    rendered.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Log.e("FrameProcessor", "Snapshot save failed", e)
            null
        } finally {
            rendered.recycle()
        }
    }
}

class Mp4VideoRecorder(
    private val context: Context,
    rawWidth: Int,
    rawHeight: Int,
    private val fps: Int = 25
) {
    companion object {
        private const val TAG = "Mp4VideoRecorder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    // H.264 encoders require even dimensions
    private val width = (rawWidth / 2) * 2
    private val height = (rawHeight / 2) * 2

    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var tempFile: File? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private val lock = Any()

    fun start(): Boolean {
        synchronized(lock) {
            if (running.get()) return true
            return try {
                val outFile = File(context.cacheDir, "rec_${System.currentTimeMillis()}.mp4")
                tempFile = outFile

                val bitRate = (width * height * fps * 0.25f).toInt().coerceIn(1_500_000, 12_000_000)
                val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = encoder.createInputSurface()
                encoder.start()
                codec = encoder

                muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                videoTrackIndex = -1
                muxerStarted = false
                running.set(true)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MP4 recorder", e)
                cleanupLocked()
                false
            }
        }
    }

    fun pushFrame(bitmap: Bitmap, transform: FrameTransform) {
        if (!running.get()) return
        synchronized(lock) {
            val surface = inputSurface ?: return
            if (!running.get()) return
            try {
                val canvas = surface.lockCanvas(null)
                try {
                    FrameProcessor.drawFrameToCanvas(canvas, bitmap, width, height, transform)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drainEncoderLocked(endOfStream = false)
            } catch (e: Exception) {
                Log.w(TAG, "Frame encode skipped", e)
            }
        }
    }

    fun stopAndSave(): Uri? {
        val fileToPublish: File?
        synchronized(lock) {
            if (!running.getAndSet(false)) return null
            try {
                codec?.signalEndOfInputStream()
                drainEncoderLocked(endOfStream = true)
            } catch (e: Exception) {
                Log.w(TAG, "Drain on stop warning", e)
            }
            fileToPublish = if (muxerStarted) tempFile else null
            cleanupLocked()
        }

        val src = fileToPublish ?: return null
        return try {
            publishMp4ToMediaStore(src)
        } finally {
            src.delete()
        }
    }

    private fun drainEncoderLocked(endOfStream: Boolean) {
        val encoder = codec ?: return
        val m = muxer ?: return
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        videoTrackIndex = m.addTrack(encoder.outputFormat)
                        m.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(index)
                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    private fun publishMp4ToMediaStore(srcFile: File): Uri? {
        val fileName = "BetterOTG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.mp4"
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/BetterOTG")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(srcFile).use { input -> input.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                uri
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "BetterOTG"
                ).apply { mkdirs() }
                val dest = File(dir, fileName)
                srcFile.copyTo(dest, overwrite = true)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATA, dest.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish MP4", e)
            null
        }
    }

    private fun cleanupLocked() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        if (muxerStarted) {
            runCatching { muxer?.stop() }
        }
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        videoTrackIndex = -1
    }
}
