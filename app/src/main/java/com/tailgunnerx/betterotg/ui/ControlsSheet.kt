package com.tailgunnerx.betterotg.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailgunnerx.betterotg.BuildConfig
import com.tailgunnerx.betterotg.data.UserSettings
import com.tailgunnerx.betterotg.media.ScaleOverlayMode
import com.tailgunnerx.betterotg.uvc.CameraConnectionState
import com.tailgunnerx.betterotg.uvc.UvcControlId
import com.tailgunnerx.betterotg.uvc.UvcControlRange
import kotlin.math.roundToInt

const val GITHUB_URL = "https://github.com/tailgunnerx/BetterOTG"

private val AboutCardSurface = Color(0xFF1E293B)
private val DialogSurface = Color(0xFF111827)
private val SubtleText = Color(0xFF94A3B8)
private val AccentEmerald = Color(0xFF00E5A0)
private val DividerHairline = Color(0x22FFFFFF)

@Immutable
data class ChangelogEntry(
    val version: String,
    val date: String,
    val notes: String
)

val CHANGELOG = listOf(
    ChangelogEntry(
        version = "1.0",
        date = "2026-11-25",
        notes = "• Initial release of BetterOTG (100% FOSS & offline USB OTG camera viewer)\n" +
            "• Source-built libusb + libuvc native UVC engine supporting MJPEG and YUYV microscopes, endoscopes & webcams\n" +
            "• Dynamic resolution/FPS selector, 1×–8× pinch-to-zoom, pan, 90° rotation, H/V mirror & Fit/Fill mode\n" +
            "• Measurement reticle overlay (mm / inch) & Dual-Screen freeze comparison\n" +
            "• Live hardware UVC controls (Brightness, Contrast, Saturation, Sharpness, Gamma, Gain, Backlight, Hue, Focus & Reset)\n" +
            "• JPEG snapshots & hardware-accelerated H.264 MP4 recording with optional date/time watermark\n" +
            "• Physical USB camera shutter button support, Keep Screen On, Immersive fullscreen & Picture-in-Picture mode\n" +
            "• Built-in Gallery with multi-select delete, share & video playback\n" +
            "• Settings About section with separate Changelog window & GitHub Repository link"
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlsSheet(
    controls: List<UvcControlRange>,
    settings: UserSettings,
    connectionState: CameraConnectionState,
    liveFps: Int,
    onSetControl: (UvcControlId, Int) -> Unit,
    onSetAutoFocus: (Boolean) -> Unit,
    onResetControls: () -> Unit,
    onToggleWatermark: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleMirrorV: () -> Unit,
    onToggleFill: () -> Unit,
    onSetScale: (ScaleOverlayMode) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var autoFocus by remember { mutableStateOf(true) }
    var showChangelog by remember { mutableStateOf(false) }

    if (showChangelog) {
        ChangelogDialog(onBack = { showChangelog = false })
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Section 1: UVC Camera Hardware Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "UVC Camera Controls",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (controls.isNotEmpty()) {
                    TextButton(onClick = onResetControls) {
                        Text("Reset")
                    }
                }
            }

            if (controls.isEmpty()) {
                Text(
                    "Connect a UVC camera to adjust hardware Brightness, Contrast, Saturation, Sharpness, Gamma, Gain, or Focus.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (controls.any { it.control == UvcControlId.FOCUS_ABS }) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text("Auto Focus", modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoFocus,
                            onCheckedChange = {
                                autoFocus = it
                                onSetAutoFocus(it)
                            }
                        )
                    }
                }

                controls.forEach { range ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(range.control.label, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${range.currentValue}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Slider(
                            value = range.currentValue.toFloat(),
                            onValueChange = { onSetControl(range.control, it.roundToInt()) },
                            valueRange = range.min.toFloat()..range.max.toFloat()
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Section 2: Display & Capture Settings
            Text(
                "Display & Capture",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))

            SettingToggleRow("Keep Screen On", settings.keepScreenOn, onToggleKeepScreenOn)
            SettingToggleRow("Date/Time Watermark", settings.watermarkEnabled, onToggleWatermark)
            SettingToggleRow("Vertical Mirror (Flip V)", settings.mirrorV, onToggleMirrorV)
            SettingToggleRow("Full-Screen Fill (Crop)", settings.fillScreen, onToggleFill)

            Spacer(Modifier.height(8.dp))
            Text("Measurement Reticle Scale", style = MaterialTheme.typography.bodyMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 6.dp)
            ) {
                ScaleOverlayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.scaleOverlay == mode,
                        onClick = { onSetScale(mode) },
                        label = { Text(mode.label) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Section 3: Device & System Information
            Text(
                "Device & System Info",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            val streaming = connectionState as? CameraConnectionState.Streaming
            InfoLine("App", "BetterOTG v${BuildConfig.VERSION_NAME} (FOSS · 100% Offline)")
            if (streaming != null) {
                InfoLine("USB Device", "${streaming.device.displayName} (${streaming.device.vidPid})")
                InfoLine("Stream", "${streaming.activeMode.label} · Live ${liveFps}fps")
            } else {
                InfoLine("USB Device", "Not connected")
            }
            InfoLine("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoLine("Device", "${Build.BRAND} ${Build.MODEL}")

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Section 4: About (matching sibling apps)
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AboutCardSurface)
            ) {
                AboutRow(
                    title = "Changelog",
                    subtitle = "View release notes & version history (v${BuildConfig.VERSION_NAME})",
                    onClick = { showChangelog = true }
                ) {
                    Text(
                        text = "View",
                        color = AccentEmerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                HorizontalDivider(
                    color = DividerHairline,
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                AboutRow(
                    title = "GitHub Repository",
                    subtitle = GITHUB_URL.removePrefix("https://"),
                    subtitleColor = AccentEmerald,
                    onClick = {
                        onDismiss()
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = "Open GitHub Repository",
                        tint = AccentEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AboutRow(
    title: String,
    subtitle: String,
    subtitleColor: Color = SubtleText,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = subtitleColor, fontSize = 11.sp)
        }
        trailing()
    }
}

@Composable
fun ChangelogDialog(onBack: () -> Unit) {
    AlertDialog(
        onDismissRequest = onBack,
        containerColor = DialogSurface,
        titleContentColor = Color.White,
        textContentColor = SubtleText,
        title = {
            Text("Changelog", style = MaterialTheme.typography.titleLarge, color = Color.White)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CHANGELOG.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(color = DividerHairline)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "v${entry.version}",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = entry.date,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (index == 0) AccentEmerald else SubtleText
                            )
                        }
                        Text(
                            text = entry.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = SubtleText
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onBack) {
                Text("Back", color = AccentEmerald)
            }
        }
    )
}

@Composable
private fun SettingToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = SubtleText, fontSize = 13.sp, modifier = Modifier.weight(0.35f))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.65f))
    }
}
