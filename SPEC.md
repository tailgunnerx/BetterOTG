# BetterOTG — Clean-Room Specification (from REA Analysis)

Target analyzed with REA (`rea-agents@6.10.1` + `jadx-headless-mcp@0.7.1`): `com.dingtai.snakecamera` (**OTG View2 v4.4.0**).
All implementation in **BetterOTG** (`com.tailgunnerx.betterotg`) is 100% clean-room FOSS built from source for F-Droid.

## 1. Hardware & USB OTG Protocol
- **Native Stack:** Standard USB Video Class (UVC) over USB Host (`libusb` + `libuvc`), no proprietary vendor protocol required.
- **USB Device Filter (`res/xml/device_filter.xml`):**
  - Generic UVC / composite classes:
    - `class="239" subclass="2"` (USB Misc / IAD — standard UVC cameras & microscopes)
    - `class="14"` (USB Video Class, including `subclass="9"` and generic video)
    - `class="6"` (Still Imaging), `class="2" subclass="0"`, `class="39" subclass="0"`
  - Explicit VID/PID compatibility entries:
    - `0x2565:0x12E4`, `0x046D:0x08B5`, `0x0581:0x0280`, `0x240C:0x0102`, `0x1B71:0x3002`, `0x05E1:0x0198`, `0xEB1A:0x0B2D`, `0x1C88:0x0007`, `0x1C88:0x003C`
- **Android 10–15 Compatibility:**
  - Request `CAMERA` runtime permission (required by Android 10+ for USB UVC devices) + `UsbManager.requestPermission`.
  - Pass `UsbDeviceConnection.fileDescriptor` into native `libusb_wrap_sys_device` (no root, no `/dev/bus/usb` direct open).
  - Support both **MJPEG** (preferred for higher resolutions/FPS) and **YUYV (uncompressed)** streams.

## 2. Core Features (Full Parity + Microscope Enhancements)
1. **Live USB OTG Preview:**
   - Auto-detect USB camera attach/detach (`ACTION_USB_DEVICE_ATTACHED` / `DETACHED`) + manual device selector.
   - Resolution & FPS picker populated dynamically from UVC format/frame descriptors.
   - Display mode: **Fit / Fullscreen (Fill)** toggle + **Immersive Full-Screen** (tap to hide/show HUD).
   - **Floating PiP / Window Mode** (Android native Picture-in-Picture on API 26+, zero `SYSTEM_ALERT_WINDOW` abuse).
2. **View Transforms & Measurement Overlays:**
   - Rotate (`0° / 90° / 180° / 270°`), Horizontal Mirror, Vertical Mirror.
   - Pinch-to-zoom & pan (digital zoom 1×–8×, essential for OTG microscopes).
   - **Measurement Ruler / Reticle Scale Overlay:** `None` / `mm scale` / `inch scale` (+ crosshair).
   - **Dual-Screen Comparison Mode:** freeze a reference snapshot side-by-side against the live camera feed.
3. **UVC Camera Controls (Processing Unit & Camera Terminal):**
   - `Brightness`, `Contrast`, `Saturation`, `Sharpness`, `Gamma`, `Gain`, `Backlight Compensation`, `Hue (Tone)`, `Focus` + `Auto Focus`, and `Reset to Defaults` (dynamically enabled per connected camera's UVC capabilities).
4. **Photo & Video Capture:**
   - **Snapshot (JPEG):** saves transformed + optionally timestamp-watermarked frame to `Pictures/BetterOTG` via `MediaStore`.
   - **Video Recording (MP4 / H.264):** hardware-accelerated `MediaCodec` (`video/avc`, 25–30 fps) + `MediaMuxer` saved to `Movies/BetterOTG` via `MediaStore`, with optional microphone audio (`RECORD_AUDIO` requested only if user enables audio).
   - **Date/Time Watermark:** toggleable timestamp stamp baked into captured photos and videos.
5. **Built-in Gallery & Playback:**
   - Tabbed `Images` / `Videos` grid scoped to `BetterOTG` media.
   - Full-screen photo viewer, video playback, `Select All`, multi-delete, and system Share sheet.
6. **Device & System Info:**
   - Connected USB device info (VID:PID, manufacturer, product, active format/resolution/FPS) + Android OS / brand / model info.

## 3. Stripped from Original (Privacy & F-Droid)
- Zero AdMob / Google Play Services / `AD_ID` / `INTERNET` permission (100% offline app!).
- Zero Bugly / crash trackers / `RECEIVE_BOOT_COMPLETED` / `SYSTEM_ALERT_WINDOW`.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
