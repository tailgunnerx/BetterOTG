package com.tailgunnerx.betterotg.uvc

enum class UvcFormat(val code: Int, val label: String) {
    MJPEG(1, "MJPEG"),
    YUYV(2, "YUYV");

    companion object {
        fun fromCode(code: Int): UvcFormat = entries.firstOrNull { it.code == code } ?: MJPEG
    }
}

data class UvcMode(
    val format: UvcFormat,
    val width: Int,
    val height: Int,
    val fps: Int
) {
    val label: String get() = "${width}×${height} (${format.label} ${fps}fps)"
    val key: String get() = "${format.code}:${width}x${height}@${fps}"
}

enum class UvcControlId(val id: Int, val label: String) {
    BRIGHTNESS(0, "Brightness"),
    CONTRAST(1, "Contrast"),
    SATURATION(2, "Saturation"),
    SHARPNESS(3, "Sharpness"),
    GAMMA(4, "Gamma"),
    GAIN(5, "Gain"),
    BACKLIGHT(6, "Backlight"),
    HUE(7, "Hue / Tone"),
    FOCUS_ABS(8, "Manual Focus");

    companion object {
        const val FOCUS_AUTO_ID = 9
    }
}

data class UvcControlRange(
    val control: UvcControlId,
    val min: Int,
    val max: Int,
    val defaultValue: Int,
    val currentValue: Int
)

interface UvcFrameCallback {
    fun onMjpegFrame(data: ByteArray, width: Int, height: Int)
    fun onRgbaFrame(data: ByteArray, width: Int, height: Int)
    fun onHardwareButton(button: Int, state: Int)
}

object NativeUvcBridge {
    init {
        System.loadLibrary("betterotg_uvc")
    }

    external fun nativeOpen(fd: Int): Long
    external fun nativeClose(handle: Long)
    external fun nativeGetSupportedModes(handle: Long): IntArray
    external fun nativeStartStream(
        handle: Long,
        formatCode: Int,
        width: Int,
        height: Int,
        fps: Int,
        callback: UvcFrameCallback
    ): Int
    external fun nativeStopStream(handle: Long)
    external fun nativeGetControlRange(handle: Long, controlId: Int): IntArray?
    external fun nativeSetControl(handle: Long, controlId: Int, value: Int): Boolean
}
