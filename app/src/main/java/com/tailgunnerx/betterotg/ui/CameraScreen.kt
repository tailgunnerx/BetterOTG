package com.tailgunnerx.betterotg.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailgunnerx.betterotg.media.ScaleOverlayMode
import com.tailgunnerx.betterotg.uvc.CameraConnectionState
import com.tailgunnerx.betterotg.uvc.UsbCameraInfo
import com.tailgunnerx.betterotg.uvc.UvcMode

@Composable
fun CameraScreen(
    viewModel: BetterOtgViewModel,
    hasCameraPermission: Boolean,
    isInPipMode: Boolean,
    onRequestCameraPermission: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenGallery: () -> Unit
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()
    val frozenFrame by viewModel.frozenFrame.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val liveFps by viewModel.liveFps.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val recordingSec by viewModel.recordingSeconds.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val controls by viewModel.controls.collectAsState()

    var showHud by remember { mutableStateOf(true) }
    var showControlsSheet by remember { mutableStateOf(false) }
    var showResolutionMenu by remember { mutableStateOf(false) }
    var showDeviceMenu by remember { mutableStateOf(false) }

    var zoom by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    val openControls = {
        viewModel.loadControls()
        showControlsSheet = true
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val isLandscape = maxWidth > maxHeight
        val hudVisible = showHud && !isInPipMode

        val viewportContent: @Composable (Modifier) -> Unit = { mod ->
            CameraViewport(
                modifier = mod
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { if (!isInPipMode) showHud = !showHud },
                            onDoubleTap = {
                                zoom = 1f
                                panOffset = Offset.Zero
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val newZoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                            zoom = newZoom
                            panOffset = if (newZoom <= 1.01f) Offset.Zero else panOffset + pan
                        }
                    },
                liveBmp = currentFrame,
                frozenFrame = frozenFrame,
                connectionState = connectionState,
                settings = settings,
                hasCameraPermission = hasCameraPermission,
                deviceCount = availableDevices.size,
                isLandscape = isLandscape,
                isInPipMode = isInPipMode,
                isRecording = isRecording,
                recordingSec = recordingSec,
                zoom = zoom,
                panOffset = panOffset,
                onResetZoom = {
                    zoom = 1f
                    panOffset = Offset.Zero
                },
                onRequestCameraPermission = onRequestCameraPermission,
                onRefresh = {
                    viewModel.refreshDevices()
                    viewModel.availableDevices.value.firstOrNull()?.let {
                        viewModel.connectDevice(it.device, forceReconnect = true)
                    }
                }
            )
        }

        if (!hudVisible) {
            // Pure full-bleed unobstructed viewfinder (tap anywhere to restore HUD)
            viewportContent(Modifier.fillMaxSize())
        } else if (isLandscape) {
            // Non-overlapping Landscape Layout: Left Rail | (Slim Header + Preview) | Right Rail
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
            ) {
                // Slim Left Tool Rail
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(48.dp)
                        .background(Color(0xFF0A0F1A))
                        .padding(vertical = 4.dp)
                ) {
                    CompactRailButton(Icons.Default.Rotate90DegreesCw, "Rotate", settings.rotationDegrees != 0, viewModel::rotate90)
                    CompactRailButton(Icons.Default.Flip, "Flip H", settings.mirrorH, viewModel::toggleMirrorH)
                    CompactRailButton(Icons.Default.Compare, "Split", frozenFrame != null, viewModel::toggleDualScreenFreeze)
                    CompactRailButton(Icons.Default.Straighten, "Scale", settings.scaleOverlay != ScaleOverlayMode.NONE, viewModel::cycleScaleOverlay)
                    CompactRailButton(
                        if (settings.fillScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        "Fit / Fill",
                        settings.fillScreen,
                        viewModel::toggleFillScreen
                    )
                    CompactRailButton(Icons.Default.PictureInPictureAlt, "PiP", false, onEnterPip)
                }

                // Center Column: Ultra-slim 34dp header ABOVE the preview (never covers camera!)
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .background(Color(0xFF0A0F1A))
                            .padding(horizontal = 8.dp)
                    ) {
                        DevicePickerPill(
                            connectionState = connectionState,
                            availableDevices = availableDevices,
                            expanded = showDeviceMenu,
                            onToggle = {
                                if (it) viewModel.refreshDevices()
                                showDeviceMenu = it
                            },
                            onSelect = { viewModel.connectDevice(it.device, forceReconnect = true) },
                            maxWidthDp = 220
                        )
                        ResolutionPill(
                            connectionState = connectionState,
                            liveFps = liveFps,
                            expanded = showResolutionMenu,
                            onToggle = { showResolutionMenu = it },
                            onSelect = viewModel::switchMode
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "Tap video to hide UI",
                            color = Color(0xFF64748B),
                            fontSize = 10.sp
                        )
                    }

                    viewportContent(Modifier.weight(1f).fillMaxWidth())
                }

                // Slim Right Capture Rail
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(64.dp)
                        .background(Color(0xFF0A0F1A))
                        .padding(vertical = 6.dp)
                ) {
                    CompactRailButton(Icons.Default.Tune, "Controls", false, openControls)

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color(0xFFEF4444), CircleShape)
                            .background(if (isRecording) Color(0xFFEF4444) else Color(0x33EF4444))
                            .clickable(onClick = viewModel::toggleRecording)
                    ) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                            contentDescription = if (isRecording) "Stop Recording" else "Record",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .border(2.5.dp, Color.White, CircleShape)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                            .clickable(onClick = viewModel::takeSnapshot)
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Snapshot", tint = Color(0xFF0A0F1A), modifier = Modifier.size(21.dp))
                    }

                    IconButton(
                        onClick = onOpenGallery,
                        modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        } else {
            // Non-overlapping Portrait Layout: Top Bar | Unobstructed Preview | Bottom Deck
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .displayCutoutPadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0F1A))
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    DevicePickerPill(
                        connectionState = connectionState,
                        availableDevices = availableDevices,
                        expanded = showDeviceMenu,
                        onToggle = {
                            if (it) viewModel.refreshDevices()
                            showDeviceMenu = it
                        },
                        onSelect = { viewModel.connectDevice(it.device, forceReconnect = true) },
                        maxWidthDp = 160
                    )
                    Spacer(Modifier.width(6.dp))
                    ResolutionPill(
                        connectionState = connectionState,
                        liveFps = liveFps,
                        expanded = showResolutionMenu,
                        onToggle = { showResolutionMenu = it },
                        onSelect = viewModel::switchMode
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = viewModel::toggleFillScreen, modifier = Modifier.size(34.dp)) {
                        Icon(
                            if (settings.fillScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                            contentDescription = "Fit / Fill",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(onClick = onEnterPip, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Floating Window", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = openControls, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(19.dp))
                    }
                }

                viewportContent(Modifier.weight(1f).fillMaxWidth())

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0A0F1A))
                        .padding(top = 6.dp, bottom = 10.dp, start = 10.dp, end = 10.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ToolChip(Icons.Default.Rotate90DegreesCw, "${settings.rotationDegrees}°", settings.rotationDegrees != 0, viewModel::rotate90)
                        ToolChip(Icons.Default.Flip, "Flip", settings.mirrorH, viewModel::toggleMirrorH)
                        ToolChip(Icons.Default.Compare, "Split", frozenFrame != null, viewModel::toggleDualScreenFreeze)
                        ToolChip(Icons.Default.Straighten, settings.scaleOverlay.label, settings.scaleOverlay != ScaleOverlayMode.NONE, viewModel::cycleScaleOverlay)
                        ToolChip(Icons.Default.Tune, "UVC", false, openControls)
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = onOpenGallery,
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(Color(0x33FFFFFF))
                        ) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = "Gallery", tint = Color.White, modifier = Modifier.size(21.dp))
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .border(2.5.dp, Color.White, CircleShape)
                                .padding(4.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                                .clickable(onClick = viewModel::takeSnapshot)
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = "Take Snapshot", tint = Color(0xFF0A0F1A), modifier = Modifier.size(23.dp))
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .border(2.dp, Color(0xFFEF4444), CircleShape)
                                .background(if (isRecording) Color(0xFFEF4444) else Color(0x33EF4444))
                                .clickable(onClick = viewModel::toggleRecording)
                        ) {
                            Icon(
                                if (isRecording) Icons.Default.Stop else Icons.Default.Videocam,
                                contentDescription = if (isRecording) "Stop Recording" else "Record Video",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showControlsSheet) {
        ControlsSheet(
            controls = controls,
            settings = settings,
            connectionState = connectionState,
            liveFps = liveFps,
            onSetControl = viewModel::setControl,
            onSetAutoFocus = viewModel::setAutoFocus,
            onResetControls = viewModel::resetControls,
            onToggleWatermark = viewModel::toggleWatermark,
            onToggleKeepScreenOn = viewModel::toggleKeepScreenOn,
            onToggleMirrorV = viewModel::toggleMirrorV,
            onToggleFill = viewModel::toggleFillScreen,
            onSetScale = viewModel::setScaleOverlay,
            onDismiss = { showControlsSheet = false }
        )
    }
}

@Composable
private fun CameraViewport(
    modifier: Modifier,
    liveBmp: Bitmap?,
    frozenFrame: Bitmap?,
    connectionState: CameraConnectionState,
    settings: com.tailgunnerx.betterotg.data.UserSettings,
    hasCameraPermission: Boolean,
    deviceCount: Int,
    isLandscape: Boolean,
    isInPipMode: Boolean,
    isRecording: Boolean,
    recordingSec: Long,
    zoom: Float,
    panOffset: Offset,
    onResetZoom: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    Box(modifier = modifier.background(Color.Black)) {
        if (liveBmp != null) {
            if (frozenFrame != null && !isInPipMode) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Image(
                            bitmap = frozenFrame.asImageBitmap(),
                            contentDescription = "Frozen reference",
                            contentScale = if (settings.fillScreen) ContentScale.Crop else ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        BadgePill(
                            text = "REF",
                            color = Color(0xFF38BDF8),
                            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF00E5A0))
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        LiveFrameView(
                            bitmap = liveBmp,
                            rotationDegrees = settings.rotationDegrees,
                            mirrorH = settings.mirrorH,
                            mirrorV = settings.mirrorV,
                            fillScreen = settings.fillScreen,
                            zoom = zoom,
                            pan = panOffset
                        )
                        BadgePill(
                            text = "LIVE",
                            color = Color(0xFF00E5A0),
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        )
                    }
                }
            } else {
                LiveFrameView(
                    bitmap = liveBmp,
                    rotationDegrees = settings.rotationDegrees,
                    mirrorH = settings.mirrorH,
                    mirrorV = settings.mirrorV,
                    fillScreen = settings.fillScreen,
                    zoom = zoom,
                    pan = panOffset
                )
            }

            if (settings.scaleOverlay != ScaleOverlayMode.NONE && !isInPipMode) {
                ScaleReticleOverlay(mode = settings.scaleOverlay, zoom = zoom)
            }
        } else {
            DisconnectedPlaceholder(
                connectionState = connectionState,
                hasCameraPermission = hasCameraPermission,
                deviceCount = deviceCount,
                isLandscape = isLandscape,
                onRequestCameraPermission = onRequestCameraPermission,
                onRefresh = onRefresh
            )
        }

        if (isRecording && !isInPipMode) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xCCDC2626))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "REC ${formatElapsed(recordingSec)}",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        if (zoom > 1.05f && !isInPipMode) {
            BadgePill(
                text = String.format("%.1f×", zoom),
                color = Color(0xFF00E5A0),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clickable(onClick = onResetZoom)
            )
        }
    }
}

@Composable
private fun DevicePickerPill(
    connectionState: CameraConnectionState,
    availableDevices: List<UsbCameraInfo>,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelect: (UsbCameraInfo) -> Unit,
    maxWidthDp: Int
) {
    Box {
        val title = when (connectionState) {
            is CameraConnectionState.Streaming -> connectionState.device.displayName
            is CameraConnectionState.Connecting -> "Connecting…"
            is CameraConnectionState.PermissionRequired -> "Grant USB"
            else -> "BetterOTG"
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .widthIn(max = maxWidthDp.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF1E293B))
                .clickable { onToggle(true) }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                Icons.Default.Usb,
                contentDescription = "USB Devices",
                tint = if (connectionState is CameraConnectionState.Streaming) Color(0xFF00E5A0) else Color.White,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onToggle(false) }) {
            if (availableDevices.isEmpty()) {
                DropdownMenuItem(
                    text = { Text("No OTG camera detected") },
                    onClick = { onToggle(false) }
                )
            } else {
                availableDevices.forEach { dev ->
                    DropdownMenuItem(
                        text = { Text("${dev.displayName} (${dev.vidPid})") },
                        onClick = {
                            onSelect(dev)
                            onToggle(false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ResolutionPill(
    connectionState: CameraConnectionState,
    liveFps: Int,
    expanded: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelect: (UvcMode) -> Unit
) {
    val streaming = connectionState as? CameraConnectionState.Streaming ?: return
    Box {
        Text(
            text = "${streaming.activeMode.width}×${streaming.activeMode.height} · ${liveFps}fps",
            color = Color(0xFF00E5A0),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF064E3B))
                .clickable { onToggle(true) }
                .padding(horizontal = 7.dp, vertical = 4.dp)
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { onToggle(false) }) {
            streaming.supportedModes.forEach { mode ->
                val selected = mode == streaming.activeMode
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (selected) "✓ ${mode.label}" else mode.label,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        onToggle(false)
                    }
                )
            }
        }
    }
}

@Composable
private fun CompactRailButton(
    icon: ImageVector,
    contentDesc: String,
    active: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) Color(0x4400E5A0) else Color.Transparent)
    ) {
        Icon(
            icon,
            contentDescription = contentDesc,
            tint = if (active) Color(0xFF00E5A0) else Color.White,
            modifier = Modifier.size(19.dp)
        )
    }
}

@Composable
private fun LiveFrameView(
    bitmap: Bitmap,
    rotationDegrees: Int,
    mirrorH: Boolean,
    mirrorV: Boolean,
    fillScreen: Boolean,
    zoom: Float,
    pan: Offset
) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "Live USB OTG camera feed",
        contentScale = if (fillScreen) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationZ = rotationDegrees.toFloat()
                scaleX = zoom * (if (mirrorH) -1f else 1f)
                scaleY = zoom * (if (mirrorV) -1f else 1f)
                translationX = pan.x
                translationY = pan.y
            }
    )
}

@Composable
private fun ScaleReticleOverlay(mode: ScaleOverlayMode, zoom: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val lineColor = Color(0xCC00E5A0)
        val dimColor = Color(0x88FFFFFF)

        drawLine(dimColor, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5f)
        drawLine(dimColor, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1.5f)

        val baseStep = if (mode == ScaleOverlayMode.MM) 36f else 48f
        val step = baseStep * zoom.coerceAtMost(3f)
        val majorEvery = if (mode == ScaleOverlayMode.MM) 5 else 4

        var i = 1
        while (cx + i * step < size.width) {
            val tickH = if (i % majorEvery == 0) 18f else 9f
            drawLine(lineColor, Offset(cx + i * step, cy - tickH), Offset(cx + i * step, cy + tickH), 2f)
            drawLine(lineColor, Offset(cx - i * step, cy - tickH), Offset(cx - i * step, cy + tickH), 2f)
            i++
        }
        i = 1
        while (cy + i * step < size.height) {
            val tickW = if (i % majorEvery == 0) 18f else 9f
            drawLine(lineColor, Offset(cx - tickW, cy + i * step), Offset(cx + tickW, cy + i * step), 2f)
            drawLine(lineColor, Offset(cx - tickW, cy - i * step), Offset(cx + tickW, cy - i * step), 2f)
            i++
        }
        drawCircle(color = lineColor, radius = 5f, center = Offset(cx, cy))
    }
}

@Composable
private fun DisconnectedPlaceholder(
    connectionState: CameraConnectionState,
    hasCameraPermission: Boolean,
    deviceCount: Int,
    isLandscape: Boolean,
    onRequestCameraPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        if (connectionState is CameraConnectionState.Connecting) {
            CircularProgressIndicator(color = Color(0xFF00E5A0))
            Spacer(Modifier.height(12.dp))
            Text("Opening ${connectionState.device.displayName}…", color = Color.White, fontSize = 15.sp)
            return
        }

        if (connectionState is CameraConnectionState.Streaming) {
            CircularProgressIndicator(color = Color(0xFF00E5A0))
            Spacer(Modifier.height(12.dp))
            Text("Starting video stream (${connectionState.activeMode.label})…", color = Color.White, fontSize = 14.sp)
            return
        }

        Icon(
            Icons.Default.Usb,
            contentDescription = null,
            tint = Color(0xFF00E5A0),
            modifier = Modifier.size(if (isLandscape) 38.dp else 54.dp)
        )
        Spacer(Modifier.height(if (isLandscape) 8.dp else 14.dp))
        Text(
            text = when (connectionState) {
                is CameraConnectionState.Error -> "Camera Connection Issue"
                is CameraConnectionState.PermissionRequired -> "USB Permission Needed"
                else -> "Connect a USB OTG Camera"
            },
            color = Color.White,
            fontSize = if (isLandscape) 16.sp else 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when (connectionState) {
                is CameraConnectionState.Error -> connectionState.message
                is CameraConnectionState.PermissionRequired -> "Approve the Android USB prompt to start live viewing."
                else -> "Plug in your USB OTG microscope, endoscope, or webcam ($deviceCount detected)."
            },
            color = Color(0xFFB0BEC5),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(if (isLandscape) 10.dp else 16.dp))

        if (!hasCameraPermission) {
            Button(
                onClick = onRequestCameraPermission,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5A0), contentColor = Color.Black)
            ) {
                Text("Grant Camera Permission", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(onClick = onRefresh) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Scan / Reconnect USB", color = Color.White, fontSize = 13.sp)
        }
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) Color(0x3300E5A0) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Color(0xFF00E5A0) else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = if (active) Color(0xFF00E5A0) else Color(0xFFCBD5E1),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun BadgePill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xCC0A0F1A),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.border(1.dp, color, RoundedCornerShape(10.dp))
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun formatElapsed(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return String.format("%02d:%02d", m, s)
}
