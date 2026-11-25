package com.tailgunnerx.betterotg.uvc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class UsbCameraInfo(
    val device: UsbDevice,
    val displayName: String,
    val vidPid: String
)

sealed interface CameraConnectionState {
    data object Disconnected : CameraConnectionState
    data class PermissionRequired(val device: UsbCameraInfo) : CameraConnectionState
    data class Connecting(val device: UsbCameraInfo) : CameraConnectionState
    data class Streaming(
        val device: UsbCameraInfo,
        val activeMode: UvcMode,
        val supportedModes: List<UvcMode>
    ) : CameraConnectionState
    data class Error(val message: String, val device: UsbCameraInfo? = null) : CameraConnectionState
}

class UsbCameraController(
    private val context: Context,
    private val onHardwareSnapshotButton: () -> Unit,
    private val onDecodedFrameForRecorder: (Bitmap) -> Unit
) {
    companion object {
        private const val TAG = "UsbCameraController"
        private const val ACTION_USB_PERMISSION = "com.tailgunnerx.betterotg.USB_PERMISSION"
        private val KNOWN_VIDS = setOf(9573, 1133, 1409, 9228, 7025, 1505, 60186, 7304)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    // Dedicated serialized background executor so USB/libuvc calls NEVER block the Android UI thread
    private val usbExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BetterOTG-UsbIO").apply { isDaemon = true }
    }
    private val decodeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "BetterOTG-Decode").apply { isDaemon = true }
    }
    private val decodingBusy = AtomicBoolean(false)
    private val sessionEpoch = AtomicLong(0L)

    @Volatile private var usbConnection: UsbDeviceConnection? = null
    @Volatile private var activeDevice: UsbDevice? = null
    @Volatile private var nativeHandle: Long = 0L
    private var registered = false

    private val _availableDevices = MutableStateFlow<List<UsbCameraInfo>>(emptyList())
    val availableDevices: StateFlow<List<UsbCameraInfo>> = _availableDevices.asStateFlow()

    private val _connectionState = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    val connectionState: StateFlow<CameraConnectionState> = _connectionState.asStateFlow()

    private val _currentFrame = MutableStateFlow<Bitmap?>(null)
    val currentFrame: StateFlow<Bitmap?> = _currentFrame.asStateFlow()

    private val _liveFps = MutableStateFlow(0)
    val liveFps: StateFlow<Int> = _liveFps.asStateFlow()

    private val _controls = MutableStateFlow<List<UvcControlRange>>(emptyList())
    val controls: StateFlow<List<UvcControlRange>> = _controls.asStateFlow()

    private var frameCounter = 0
    private val fpsWindowStartMs = AtomicLong(System.currentTimeMillis())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDevices()
                    if (device != null && isPotentialCamera(device)) {
                        connectDevice(device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDevices()
                    if (device != null && activeDevice?.deviceId == device.deviceId) {
                        disconnectAsync()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null) {
                        openAuthorizedDeviceAsync(device)
                    } else if (device != null) {
                        _connectionState.value = CameraConnectionState.Error(
                            "USB permission denied for ${device.toCameraInfo().displayName}",
                            device.toCameraInfo()
                        )
                    }
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        registered = true
        refreshDevices()
        _availableDevices.value.firstOrNull()?.let { connectDevice(it.device) }
    }

    fun unregister() {
        if (registered) {
            runCatching { context.unregisterReceiver(usbReceiver) }
            registered = false
        }
        disconnectAsync()
    }

    fun refreshDevices() {
        val list = usbManager.deviceList.values
            .filter { isPotentialCamera(it) }
            .map { it.toCameraInfo() }
        _availableDevices.value = list
    }

    fun connectDevice(device: UsbDevice, preferredModeKey: String? = null, forceReconnect: Boolean = false) {
        val info = device.toCameraInfo()
        val currentState = _connectionState.value
        if (!forceReconnect && activeDevice?.deviceId == device.deviceId &&
            (currentState is CameraConnectionState.Streaming || currentState is CameraConnectionState.Connecting)
        ) {
            return
        }

        if (!usbManager.hasPermission(device)) {
            _connectionState.value = CameraConnectionState.PermissionRequired(info)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(
                context,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(device, pi)
            return
        }
        openAuthorizedDeviceAsync(device, preferredModeKey)
    }

    private fun openAuthorizedDeviceAsync(device: UsbDevice, preferredModeKey: String? = null) {
        val epoch = sessionEpoch.incrementAndGet()
        val info = device.toCameraInfo()
        _connectionState.value = CameraConnectionState.Connecting(info)

        usbExecutor.execute {
            if (sessionEpoch.get() != epoch) return@execute
            disconnectBlocking()
            if (sessionEpoch.get() != epoch) return@execute

            val conn = usbManager.openDevice(device)
            if (conn == null) {
                _connectionState.value = CameraConnectionState.Error(
                    "Could not open USB device. Check OTG cable & Camera permission.",
                    info
                )
                return@execute
            }

            val handle = NativeUvcBridge.nativeOpen(conn.fileDescriptor)
            if (handle == 0L) {
                conn.close()
                _connectionState.value = CameraConnectionState.Error(
                    "Device is not a standard UVC camera (uvc_wrap failed).",
                    info
                )
                return@execute
            }

            if (sessionEpoch.get() != epoch) {
                NativeUvcBridge.nativeClose(handle)
                conn.close()
                return@execute
            }

            usbConnection = conn
            activeDevice = device
            nativeHandle = handle

            val modes = parseModes(NativeUvcBridge.nativeGetSupportedModes(handle))
            val targetMode = pickInitialMode(modes, preferredModeKey)
            startStreamingBlocking(epoch, info, targetMode, modes)
        }
    }

    fun loadControlsAsync() {
        usbExecutor.execute {
            val handle = nativeHandle
            if (handle != 0L) {
                refreshControlsBlocking(handle)
            }
        }
    }

    fun switchMode(mode: UvcMode) {
        val state = _connectionState.value as? CameraConnectionState.Streaming ?: return
        val epoch = sessionEpoch.get()
        usbExecutor.execute {
            val handle = nativeHandle
            if (handle == 0L || sessionEpoch.get() != epoch) return@execute
            NativeUvcBridge.nativeStopStream(handle)
            startStreamingBlocking(epoch, state.device, mode, state.supportedModes)
        }
    }

    private fun startStreamingBlocking(
        epoch: Long,
        info: UsbCameraInfo,
        mode: UvcMode,
        allModes: List<UvcMode>
    ) {
        val handle = nativeHandle
        if (handle == 0L || sessionEpoch.get() != epoch) return

        val callback = object : UvcFrameCallback {
            override fun onMjpegFrame(data: ByteArray, width: Int, height: Int) {
                if (!decodingBusy.compareAndSet(false, true)) return
                decodeExecutor.execute {
                    try {
                        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                        if (bmp != null) {
                            deliverFrame(bmp)
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "MJPEG decode drop", e)
                    } finally {
                        decodingBusy.set(false)
                    }
                }
            }

            override fun onRgbaFrame(data: ByteArray, width: Int, height: Int) {
                if (!decodingBusy.compareAndSet(false, true)) return
                decodeExecutor.execute {
                    try {
                        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(data))
                        deliverFrame(bmp)
                    } catch (e: Throwable) {
                        Log.w(TAG, "RGBA decode drop", e)
                    } finally {
                        decodingBusy.set(false)
                    }
                }
            }

            override fun onHardwareButton(button: Int, state: Int) {
                onHardwareSnapshotButton()
            }
        }

        var rc = NativeUvcBridge.nativeStartStream(
            handle, mode.format.code, mode.width, mode.height, mode.fps, callback
        )
        var actualMode = mode
        if (rc != 0) {
            for (fallback in allModes) {
                if (fallback == mode) continue
                if (sessionEpoch.get() != epoch) return
                rc = NativeUvcBridge.nativeStartStream(
                    handle, fallback.format.code, fallback.width, fallback.height, fallback.fps, callback
                )
                if (rc == 0) {
                    actualMode = fallback
                    break
                }
            }
        }

        if (sessionEpoch.get() != epoch) return

        if (rc != 0) {
            _connectionState.value = CameraConnectionState.Error(
                "Failed to start UVC stream (code $rc). Try unplugging and re-plugging your OTG camera.",
                info
            )
            return
        }

        frameCounter = 0
        fpsWindowStartMs.set(System.currentTimeMillis())
        _connectionState.value = CameraConnectionState.Streaming(info, actualMode, allModes)
    }

    private fun deliverFrame(bmp: Bitmap) {
        _currentFrame.value = bmp
        onDecodedFrameForRecorder(bmp)

        frameCounter++
        val now = System.currentTimeMillis()
        val elapsed = now - fpsWindowStartMs.get()
        if (elapsed >= 1000L) {
            _liveFps.value = ((frameCounter * 1000L) / elapsed).toInt()
            frameCounter = 0
            fpsWindowStartMs.set(now)
        }
    }

    fun setControl(control: UvcControlId, value: Int) {
        usbExecutor.execute {
            val handle = nativeHandle
            if (handle == 0L) return@execute
            if (NativeUvcBridge.nativeSetControl(handle, control.id, value)) {
                _controls.value = _controls.value.map {
                    if (it.control == control) it.copy(currentValue = value) else it
                }
            }
        }
    }

    fun setAutoFocus(enabled: Boolean) {
        usbExecutor.execute {
            val handle = nativeHandle
            if (handle == 0L) return@execute
            NativeUvcBridge.nativeSetControl(handle, UvcControlId.FOCUS_AUTO_ID, if (enabled) 1 else 0)
        }
    }

    fun resetAllControls() {
        usbExecutor.execute {
            val handle = nativeHandle
            if (handle == 0L) return@execute
            val updated = _controls.value.map { range ->
                NativeUvcBridge.nativeSetControl(handle, range.control.id, range.defaultValue)
                range.copy(currentValue = range.defaultValue)
            }
            _controls.value = updated
        }
    }

    private fun refreshControlsBlocking(handle: Long) {
        val supported = buildList {
            for (ctrl in UvcControlId.entries) {
                if (nativeHandle != handle) break
                val arr = NativeUvcBridge.nativeGetControlRange(handle, ctrl.id) ?: continue
                if (arr.size == 4) {
                    add(UvcControlRange(ctrl, arr[0], arr[1], arr[2], arr[3]))
                }
            }
        }
        if (nativeHandle == handle) {
            _controls.value = supported
        }
    }

    fun disconnectAsync() {
        sessionEpoch.incrementAndGet()
        usbExecutor.execute {
            disconnectBlocking()
        }
    }

    private fun disconnectBlocking() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L) {
            NativeUvcBridge.nativeStopStream(handle)
            NativeUvcBridge.nativeClose(handle)
        }
        runCatching { usbConnection?.close() }
        usbConnection = null
        activeDevice = null
        _controls.value = emptyList()
        _liveFps.value = 0
        _currentFrame.value = null
        _connectionState.value = CameraConnectionState.Disconnected
    }

    private fun parseModes(raw: IntArray): List<UvcMode> {
        val list = mutableListOf<UvcMode>()
        var i = 0
        while (i + 3 < raw.size) {
            list += UvcMode(
                format = UvcFormat.fromCode(raw[i]),
                width = raw[i + 1],
                height = raw[i + 2],
                fps = raw[i + 3].coerceIn(5, 60)
            )
            i += 4
        }
        val dedup = list.distinctBy { "${it.format.code}:${it.width}x${it.height}" }
            .sortedWith(
                compareBy<UvcMode> { if (it.format == UvcFormat.MJPEG) 0 else 1 }
                    .thenByDescending { it.width * it.height }
            )
        return dedup.ifEmpty {
            listOf(
                UvcMode(UvcFormat.MJPEG, 1280, 720, 30),
                UvcMode(UvcFormat.MJPEG, 640, 480, 30),
                UvcMode(UvcFormat.YUYV, 640, 480, 30)
            )
        }
    }

    private fun pickInitialMode(modes: List<UvcMode>, preferredKey: String?): UvcMode {
        if (preferredKey != null) {
            modes.firstOrNull { it.key == preferredKey }?.let { return it }
        }
        return modes.firstOrNull { it.format == UvcFormat.MJPEG && it.width == 1280 && it.height == 720 }
            ?: modes.firstOrNull { it.format == UvcFormat.MJPEG && it.width in 640..1280 }
            ?: modes.first()
    }

    private fun isPotentialCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
            device.deviceClass == UsbConstants.USB_CLASS_MISC ||
            device.vendorId in KNOWN_VIDS
        ) {
            return true
        }
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun UsbDevice.toCameraInfo(): UsbCameraInfo {
        val name = productName?.takeIf { it.isNotBlank() }
            ?: manufacturerName?.takeIf { it.isNotBlank() }
            ?: "USB OTG Camera"
        val vidPid = String.format("%04X:%04X", vendorId, productId)
        return UsbCameraInfo(this, name, vidPid)
    }
}
