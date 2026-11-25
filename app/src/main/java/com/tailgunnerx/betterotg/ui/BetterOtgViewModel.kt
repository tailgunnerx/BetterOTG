package com.tailgunnerx.betterotg.ui

import android.app.Application
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tailgunnerx.betterotg.data.SettingsStore
import com.tailgunnerx.betterotg.data.UserSettings
import com.tailgunnerx.betterotg.media.FrameProcessor
import com.tailgunnerx.betterotg.media.FrameTransform
import com.tailgunnerx.betterotg.media.GalleryItem
import com.tailgunnerx.betterotg.media.GalleryRepository
import com.tailgunnerx.betterotg.media.Mp4VideoRecorder
import com.tailgunnerx.betterotg.media.ScaleOverlayMode
import com.tailgunnerx.betterotg.uvc.CameraConnectionState
import com.tailgunnerx.betterotg.uvc.UsbCameraController
import com.tailgunnerx.betterotg.uvc.UsbCameraInfo
import com.tailgunnerx.betterotg.uvc.UvcControlId
import com.tailgunnerx.betterotg.uvc.UvcControlRange
import com.tailgunnerx.betterotg.uvc.UvcMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BetterOtgViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val settingsStore = SettingsStore(appContext)

    val settings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    @Volatile
    private var activeRecorder: Mp4VideoRecorder? = null
    private var recordingTimerJob: Job? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingSeconds = MutableStateFlow(0L)
    val recordingSeconds: StateFlow<Long> = _recordingSeconds.asStateFlow()

    private val _frozenFrame = MutableStateFlow<Bitmap?>(null)
    val frozenFrame: StateFlow<Bitmap?> = _frozenFrame.asStateFlow()

    private val _galleryItems = MutableStateFlow<List<GalleryItem>>(emptyList())
    val galleryItems: StateFlow<List<GalleryItem>> = _galleryItems.asStateFlow()

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private val controller = UsbCameraController(
        context = appContext,
        onHardwareSnapshotButton = { takeSnapshot() },
        onDecodedFrameForRecorder = { bmp ->
            activeRecorder?.pushFrame(bmp, currentTransform())
        }
    )

    val availableDevices: StateFlow<List<UsbCameraInfo>> = controller.availableDevices
    val connectionState: StateFlow<CameraConnectionState> = controller.connectionState
    val currentFrame: StateFlow<Bitmap?> = controller.currentFrame
    val liveFps: StateFlow<Int> = controller.liveFps
    val controls: StateFlow<List<UvcControlRange>> = controller.controls

    fun startUsbMonitoring() {
        controller.register()
        refreshGallery()
    }

    fun stopUsbMonitoring() {
        if (_isRecording.value) stopRecording()
        controller.unregister()
    }

    fun refreshDevices() {
        controller.refreshDevices()
    }

    fun connectDevice(device: UsbDevice, forceReconnect: Boolean = false) {
        controller.connectDevice(device, settings.value.preferredModeKey, forceReconnect)
    }

    fun switchMode(mode: UvcMode) {
        if (_isRecording.value) stopRecording()
        controller.switchMode(mode)
        viewModelScope.launch {
            settingsStore.update { it.copy(preferredModeKey = mode.key) }
        }
    }

    fun rotate90() {
        viewModelScope.launch {
            settingsStore.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
        }
    }

    fun toggleMirrorH() {
        viewModelScope.launch { settingsStore.update { it.copy(mirrorH = !it.mirrorH) } }
    }

    fun toggleMirrorV() {
        viewModelScope.launch { settingsStore.update { it.copy(mirrorV = !it.mirrorV) } }
    }

    fun toggleFillScreen() {
        viewModelScope.launch { settingsStore.update { it.copy(fillScreen = !it.fillScreen) } }
    }

    fun toggleWatermark() {
        viewModelScope.launch { settingsStore.update { it.copy(watermarkEnabled = !it.watermarkEnabled) } }
    }

    fun toggleKeepScreenOn() {
        viewModelScope.launch { settingsStore.update { it.copy(keepScreenOn = !it.keepScreenOn) } }
    }

    fun cycleScaleOverlay() {
        viewModelScope.launch {
            settingsStore.update { s ->
                val next = when (s.scaleOverlay) {
                    ScaleOverlayMode.NONE -> ScaleOverlayMode.MM
                    ScaleOverlayMode.MM -> ScaleOverlayMode.INCH
                    ScaleOverlayMode.INCH -> ScaleOverlayMode.NONE
                }
                s.copy(scaleOverlay = next)
            }
        }
    }

    fun setScaleOverlay(mode: ScaleOverlayMode) {
        viewModelScope.launch { settingsStore.update { it.copy(scaleOverlay = mode) } }
    }

    fun toggleDualScreenFreeze() {
        if (_frozenFrame.value != null) {
            _frozenFrame.value = null
        } else {
            val frame = currentFrame.value
            if (frame != null) {
                _frozenFrame.value = FrameProcessor.buildTransformedBitmap(frame, currentTransform().copy(watermarkEnabled = false))
            } else {
                _toastMessage.tryEmit("No live frame to compare")
            }
        }
    }

    fun takeSnapshot() {
        val frame = currentFrame.value
        if (frame == null) {
            _toastMessage.tryEmit("No active camera frame")
            return
        }
        val copy = frame.copy(Bitmap.Config.ARGB_8888, false)
        val transform = currentTransform()
        viewModelScope.launch(Dispatchers.IO) {
            val uri = FrameProcessor.saveSnapshot(appContext, copy, transform)
            copy.recycle()
            if (uri != null) {
                _toastMessage.tryEmit("Saved snapshot to Pictures/BetterOTG")
                refreshGallery()
            } else {
                _toastMessage.tryEmit("Failed to save snapshot")
            }
        }
    }

    fun toggleRecording() {
        if (_isRecording.value) stopRecording() else startRecording()
    }

    private fun startRecording() {
        val streaming = connectionState.value as? CameraConnectionState.Streaming
        if (streaming == null) {
            _toastMessage.tryEmit("Connect a camera before recording")
            return
        }
        val rot90 = settings.value.rotationDegrees % 180 != 0
        val w = if (rot90) streaming.activeMode.height else streaming.activeMode.width
        val h = if (rot90) streaming.activeMode.width else streaming.activeMode.height
        val rec = Mp4VideoRecorder(appContext, w, h, fps = streaming.activeMode.fps.coerceIn(15, 30))
        if (!rec.start()) {
            _toastMessage.tryEmit("Could not initialize video encoder")
            return
        }
        activeRecorder = rec
        _isRecording.value = true
        _recordingSeconds.value = 0L
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            while (_isRecording.value) {
                delay(1000L)
                _recordingSeconds.value += 1L
            }
        }
    }

    private fun stopRecording() {
        val rec = activeRecorder ?: return
        activeRecorder = null
        _isRecording.value = false
        recordingTimerJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            val uri = rec.stopAndSave()
            if (uri != null) {
                _toastMessage.tryEmit("Saved video to Movies/BetterOTG")
                refreshGallery()
            } else {
                _toastMessage.tryEmit("Recording discarded (no frames encoded)")
            }
        }
    }

    fun loadControls() {
        controller.loadControlsAsync()
    }

    fun setControl(control: UvcControlId, value: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            controller.setControl(control, value)
        }
    }

    fun setAutoFocus(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            controller.setAutoFocus(enabled)
        }
    }

    fun resetControls() {
        viewModelScope.launch(Dispatchers.IO) {
            controller.resetAllControls()
            _toastMessage.tryEmit("Camera controls reset")
        }
    }

    fun refreshGallery() {
        viewModelScope.launch(Dispatchers.IO) {
            _galleryItems.value = GalleryRepository.loadItems(appContext)
        }
    }

    fun deleteGalleryItems(items: List<GalleryItem>) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = GalleryRepository.deleteItems(appContext, items)
            if (count > 0) {
                _toastMessage.tryEmit("Deleted $count item(s)")
                refreshGallery()
            }
        }
    }

    private fun currentTransform(): FrameTransform {
        val s = settings.value
        return FrameTransform(
            rotationDegrees = s.rotationDegrees,
            mirrorH = s.mirrorH,
            mirrorV = s.mirrorV,
            watermarkEnabled = s.watermarkEnabled
        )
    }

    override fun onCleared() {
        stopUsbMonitoring()
        super.onCleared()
    }
}
