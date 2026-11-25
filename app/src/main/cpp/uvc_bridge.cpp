#include <jni.h>
#include <android/log.h>
#include <libusb.h>
#include <libuvc/libuvc.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "BetterOTG_UVC"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int FMT_MJPEG = 1;
constexpr int FMT_YUYV = 2;

enum ControlId : int {
    CTRL_BRIGHTNESS = 0,
    CTRL_CONTRAST = 1,
    CTRL_SATURATION = 2,
    CTRL_SHARPNESS = 3,
    CTRL_GAMMA = 4,
    CTRL_GAIN = 5,
    CTRL_BACKLIGHT = 6,
    CTRL_HUE = 7,
    CTRL_FOCUS_ABS = 8,
    CTRL_FOCUS_AUTO = 9
};

struct UvcSession {
    uvc_context_t *uvcCtx = nullptr;
    uvc_device_handle_t *devh = nullptr;
    JavaVM *jvm = nullptr;
    jobject callbackObj = nullptr;
    jmethodID onMjpegMethod = nullptr;
    jmethodID onRgbaMethod = nullptr;
    jmethodID onButtonMethod = nullptr;
    std::atomic<bool> streaming{false};
    std::atomic<uint64_t> frameCount{0};
    std::mutex apiMutex;
    std::vector<uint8_t> rgbaScratch;
};

static inline uint8_t clampByte(int v) {
    return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
}

// Fast BT.601 YUYV -> RGBA8888 conversion
static void yuyvToRgba(const uint8_t *src, size_t srcBytes, uint8_t *dst, int width, int height) {
    const size_t pixelPairs = std::min<size_t>(srcBytes / 4, static_cast<size_t>(width) * height / 2);
    for (size_t i = 0; i < pixelPairs; ++i) {
        const int y0 = src[i * 4 + 0];
        const int u  = src[i * 4 + 1] - 128;
        const int y1 = src[i * 4 + 2];
        const int v  = src[i * 4 + 3] - 128;

        const int rDelta = (359 * v) >> 8;
        const int gDelta = -((88 * u + 183 * v) >> 8);
        const int bDelta = (454 * u) >> 8;

        size_t o = i * 8;
        dst[o + 0] = clampByte(y0 + rDelta);
        dst[o + 1] = clampByte(y0 + gDelta);
        dst[o + 2] = clampByte(y0 + bDelta);
        dst[o + 3] = 255;

        dst[o + 4] = clampByte(y1 + rDelta);
        dst[o + 5] = clampByte(y1 + gDelta);
        dst[o + 6] = clampByte(y1 + bDelta);
        dst[o + 7] = 255;
    }
}

static JNIEnv *attachCurrentThread(JavaVM *jvm, bool *didAttach) {
    *didAttach = false;
    if (!jvm) return nullptr;
    JNIEnv *env = nullptr;
    jint res = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *didAttach = true;
            return env;
        }
        return nullptr;
    }
    return (res == JNI_OK) ? env : nullptr;
}

static void onUvcFrame(uvc_frame_t *frame, void *ptr) {
    auto *session = static_cast<UvcSession *>(ptr);
    if (!session || !session->streaming.load(std::memory_order_relaxed) || !frame || !frame->data || frame->data_bytes == 0) {
        return;
    }
    if (!session->callbackObj) return;

    const uint64_t count = ++session->frameCount;
    if (count == 1) {
        LOGI("First UVC frame received! fmt=%d %ux%u bytes=%zu",
             frame->frame_format, frame->width, frame->height, frame->data_bytes);
    }

    bool didAttach = false;
    JNIEnv *env = attachCurrentThread(session->jvm, &didAttach);
    if (!env) return;

    const int w = static_cast<int>(frame->width);
    const int h = static_cast<int>(frame->height);

    if (frame->frame_format == UVC_FRAME_FORMAT_MJPEG && session->onMjpegMethod) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(frame->data_bytes));
        if (arr) {
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(frame->data_bytes),
                                    reinterpret_cast<const jbyte *>(frame->data));
            env->CallVoidMethod(session->callbackObj, session->onMjpegMethod, arr, w, h);
            env->DeleteLocalRef(arr);
        }
    } else if ((frame->frame_format == UVC_FRAME_FORMAT_YUYV || frame->frame_format == UVC_FRAME_FORMAT_UYVY) &&
               session->onRgbaMethod && w > 0 && h > 0) {
        const size_t rgbaSize = static_cast<size_t>(w) * static_cast<size_t>(h) * 4;
        if (session->rgbaScratch.size() < rgbaSize) {
            session->rgbaScratch.resize(rgbaSize);
        }
        yuyvToRgba(static_cast<const uint8_t *>(frame->data), frame->data_bytes,
                   session->rgbaScratch.data(), w, h);
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(rgbaSize));
        if (arr) {
            env->SetByteArrayRegion(arr, 0, static_cast<jsize>(rgbaSize),
                                    reinterpret_cast<const jbyte *>(session->rgbaScratch.data()));
            env->CallVoidMethod(session->callbackObj, session->onRgbaMethod, arr, w, h);
            env->DeleteLocalRef(arr);
        }
    }

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (didAttach) {
        session->jvm->DetachCurrentThread();
    }
}

static void onUvcButton(int button, int state, void *ptr) {
    auto *session = static_cast<UvcSession *>(ptr);
    if (!session || !session->callbackObj || !session->onButtonMethod) return;
    if (state != 1) return;

    bool didAttach = false;
    JNIEnv *env = attachCurrentThread(session->jvm, &didAttach);
    if (!env) return;

    env->CallVoidMethod(session->callbackObj, session->onButtonMethod, button, state);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (didAttach) {
        session->jvm->DetachCurrentThread();
    }
}

template <typename T, typename Getter>
static bool queryRange(uvc_device_handle_t *devh, Getter getter, int out[4]) {
    T minV = 0, maxV = 0, defV = 0, curV = 0;
    if (getter(devh, &minV, UVC_GET_MIN) != UVC_SUCCESS) return false;
    if (getter(devh, &maxV, UVC_GET_MAX) != UVC_SUCCESS) return false;
    if (getter(devh, &defV, UVC_GET_DEF) != UVC_SUCCESS) defV = minV;
    if (getter(devh, &curV, UVC_GET_CUR) != UVC_SUCCESS) curV = defV;
    out[0] = static_cast<int>(minV);
    out[1] = static_cast<int>(maxV);
    out[2] = static_cast<int>(defV);
    out[3] = static_cast<int>(curV);
    return out[1] > out[0];
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeOpen(JNIEnv *env, jobject, jint fd) {
    auto *session = new UvcSession();
    env->GetJavaVM(&session->jvm);

    // Must be set globally BEFORE uvc_init(ctx, nullptr) so libusb skips /dev/bus/usb scan
    // AND libuvc owns the libusb context (own_usb_ctx=1) and spawns its event handler thread!
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);

    uvc_error_t ures = uvc_init(&session->uvcCtx, nullptr);
    if (ures != UVC_SUCCESS) {
        LOGE("uvc_init failed: %s (%d)", uvc_strerror(ures), ures);
        delete session;
        return 0;
    }

    ures = uvc_wrap(fd, session->uvcCtx, &session->devh);
    if (ures != UVC_SUCCESS) {
        LOGE("uvc_wrap(fd=%d) failed: %s (%d)", fd, uvc_strerror(ures), ures);
        uvc_exit(session->uvcCtx);
        delete session;
        return 0;
    }

    LOGI("Opened UVC device via fd=%d (libuvc event thread active)", fd);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeClose(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<UvcSession *>(handle);
    if (!session) return;

    {
        std::lock_guard<std::mutex> lock(session->apiMutex);
        if (session->streaming.exchange(false) && session->devh) {
            uvc_stop_streaming(session->devh);
        }
        if (session->devh) {
            uvc_close(session->devh);
            session->devh = nullptr;
        }
        if (session->uvcCtx) {
            uvc_exit(session->uvcCtx);
            session->uvcCtx = nullptr;
        }
        if (session->callbackObj) {
            env->DeleteGlobalRef(session->callbackObj);
            session->callbackObj = nullptr;
        }
    }
    delete session;
    LOGI("Closed UVC session");
}

JNIEXPORT jintArray JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeGetSupportedModes(JNIEnv *env, jobject, jlong handle) {
    auto *session = reinterpret_cast<UvcSession *>(handle);
    if (!session || !session->devh) return env->NewIntArray(0);

    std::lock_guard<std::mutex> lock(session->apiMutex);
    std::vector<int> modes;

    const uvc_format_desc_t *fmt = uvc_get_format_descs(session->devh);
    while (fmt) {
        int fmtCode = 0;
        if (fmt->bDescriptorSubtype == UVC_VS_FORMAT_MJPEG) {
            fmtCode = FMT_MJPEG;
        } else if (fmt->bDescriptorSubtype == UVC_VS_FORMAT_UNCOMPRESSED) {
            fmtCode = FMT_YUYV;
        }
        if (fmtCode != 0) {
            const uvc_frame_desc_t *frm = fmt->frame_descs;
            while (frm) {
                int fps = 30;
                if (frm->dwDefaultFrameInterval > 0) {
                    fps = static_cast<int>(10000000 / frm->dwDefaultFrameInterval);
                    if (fps <= 0) fps = 30;
                }
                LOGI("Mode: fmt=%d %ux%u@%dfps", fmtCode, frm->wWidth, frm->wHeight, fps);
                modes.push_back(fmtCode);
                modes.push_back(static_cast<int>(frm->wWidth));
                modes.push_back(static_cast<int>(frm->wHeight));
                modes.push_back(fps);
                frm = frm->next;
            }
        }
        fmt = fmt->next;
    }

    jintArray out = env->NewIntArray(static_cast<jsize>(modes.size()));
    if (out && !modes.empty()) {
        env->SetIntArrayRegion(out, 0, static_cast<jsize>(modes.size()), modes.data());
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeStartStream(
    JNIEnv *env, jobject, jlong handle, jint formatCode, jint width, jint height, jint fps, jobject callback) {
    auto *session = reinterpret_cast<UvcSession *>(handle);
    if (!session || !session->devh || !callback) return -1;

    std::lock_guard<std::mutex> lock(session->apiMutex);
    if (session->streaming.exchange(false)) {
        uvc_stop_streaming(session->devh);
    }

    if (session->callbackObj) {
        env->DeleteGlobalRef(session->callbackObj);
        session->callbackObj = nullptr;
    }
    session->callbackObj = env->NewGlobalRef(callback);
    jclass cls = env->GetObjectClass(callback);
    session->onMjpegMethod = env->GetMethodID(cls, "onMjpegFrame", "([BII)V");
    session->onRgbaMethod = env->GetMethodID(cls, "onRgbaFrame", "([BII)V");
    session->onButtonMethod = env->GetMethodID(cls, "onHardwareButton", "(II)V");
    env->DeleteLocalRef(cls);

    uvc_frame_format uvcFmt = (formatCode == FMT_MJPEG) ? UVC_FRAME_FORMAT_MJPEG : UVC_FRAME_FORMAT_YUYV;
    uvc_stream_ctrl_t ctrl;
    std::memset(&ctrl, 0, sizeof(ctrl));
    uvc_error_t res = uvc_get_stream_ctrl_format_size(session->devh, &ctrl, uvcFmt, width, height, fps);
    if (res != UVC_SUCCESS) {
        res = uvc_get_stream_ctrl_format_size(session->devh, &ctrl, uvcFmt, width, height, 0);
    }
    if (res != UVC_SUCCESS) {
        LOGE("uvc_get_stream_ctrl_format_size(%d, %dx%d@%d) failed: %s (%d)",
             formatCode, width, height, fps, uvc_strerror(res), res);
        return static_cast<jint>(res);
    }

    LOGI("Negotiated stream: fmt=%d %dx%d@%d maxFrame=%u maxPayload=%u if=%u",
         formatCode, width, height, fps,
         ctrl.dwMaxVideoFrameSize, ctrl.dwMaxPayloadTransferSize, ctrl.bInterfaceNumber);

    uvc_set_button_callback(session->devh, onUvcButton, session);
    session->frameCount.store(0);
    session->streaming.store(true);
    res = uvc_start_streaming(session->devh, &ctrl, onUvcFrame, session, 0);
    if (res != UVC_SUCCESS) {
        session->streaming.store(false);
        LOGE("uvc_start_streaming failed: %s (%d)", uvc_strerror(res), res);
        return static_cast<jint>(res);
    }

    LOGI("Streaming started: fmt=%d %dx%d@%d", formatCode, width, height, fps);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeStopStream(JNIEnv *, jobject, jlong handle) {
    auto *session = reinterpret_cast<UvcSession *>(handle);
    if (!session || !session->devh) return;
    std::lock_guard<std::mutex> lock(session->apiMutex);
    if (session->streaming.exchange(false)) {
        uvc_stop_streaming(session->devh);
        LOGI("Streaming stopped");
    }
}

JNIEXPORT jintArray JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeGetControlRange(JNIEnv *env, jobject, jlong handle, jint controlId) {
    auto *session = reinterpret_cast<UvcSession *>(handle);
    if (!session || !session->devh) return nullptr;

    std::lock_guard<std::mutex> lock(session->apiMutex);
    int out[4] = {0, 0, 0, 0};
    bool ok = false;

    switch (controlId) {
        case CTRL_BRIGHTNESS: ok = queryRange<int16_t>(session->devh, uvc_get_brightness, out); break;
        case CTRL_CONTRAST:   ok = queryRange<uint16_t>(session->devh, uvc_get_contrast, out); break;
        case CTRL_SATURATION: ok = queryRange<uint16_t>(session->devh, uvc_get_saturation, out); break;
        case CTRL_SHARPNESS:  ok = queryRange<uint16_t>(session->devh, uvc_get_sharpness, out); break;
        case CTRL_GAMMA:      ok = queryRange<uint16_t>(session->devh, uvc_get_gamma, out); break;
        case CTRL_GAIN:       ok = queryRange<uint16_t>(session->devh, uvc_get_gain, out); break;
        case CTRL_BACKLIGHT:  ok = queryRange<uint16_t>(session->devh, uvc_get_backlight_compensation, out); break;
        case CTRL_HUE:        ok = queryRange<int16_t>(session->devh, uvc_get_hue, out); break;
        case CTRL_FOCUS_ABS:  ok = queryRange<uint16_t>(session->devh, uvc_get_focus_abs, out); break;
        default: break;
    }

    if (!ok) return nullptr;
    jintArray arr = env->NewIntArray(4);
    if (arr) env->SetIntArrayRegion(arr, 0, 4, out);
    return arr;
}

JNIEXPORT jboolean JNICALL
Java_com_tailgunnerx_betterotg_uvc_NativeUvcBridge_nativeSetControl(JNIEnv *, jobject, jlong handle, jint controlId, jint value) {
    auto *session = reinterpret_cast<UvcSession *>(handle);
    if (!session || !session->devh) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(session->apiMutex);
    uvc_error_t res = UVC_ERROR_NOT_SUPPORTED;
    switch (controlId) {
        case CTRL_BRIGHTNESS: res = uvc_set_brightness(session->devh, static_cast<int16_t>(value)); break;
        case CTRL_CONTRAST:   res = uvc_set_contrast(session->devh, static_cast<uint16_t>(value)); break;
        case CTRL_SATURATION: res = uvc_set_saturation(session->devh, static_cast<uint16_t>(value)); break;
        case CTRL_SHARPNESS:  res = uvc_set_sharpness(session->devh, static_cast<uint16_t>(value)); break;
        case CTRL_GAMMA:      res = uvc_set_gamma(session->devh, static_cast<uint16_t>(value)); break;
        case CTRL_GAIN:       res = uvc_set_gain(session->devh, static_cast<uint16_t>(value)); break;
        case CTRL_BACKLIGHT:  res = uvc_set_backlight_compensation(session->devh, static_cast<uint16_t>(value)); break;
        case CTRL_HUE:        res = uvc_set_hue(session->devh, static_cast<int16_t>(value)); break;
        case CTRL_FOCUS_ABS:
            uvc_set_focus_auto(session->devh, 0);
            res = uvc_set_focus_abs(session->devh, static_cast<uint16_t>(value));
            break;
        case CTRL_FOCUS_AUTO:
            res = uvc_set_focus_auto(session->devh, static_cast<uint8_t>(value ? 1 : 0));
            break;
        default: break;
    }
    return (res == UVC_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
