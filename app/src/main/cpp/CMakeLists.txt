cmake_minimum_required(VERSION 3.22.1)
project(betterotg_uvc LANGUAGES C CXX)

set(LIBUSB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libusb")
set(LIBUVC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/libuvc")

# Generate libuvc_config.h (no libjpeg needed; Android decodes MJPEG natively)
set(libuvc_VERSION_MAJOR 0)
set(libuvc_VERSION_MINOR 0)
set(libuvc_VERSION_PATCH 7)
set(libuvc_VERSION "0.0.7")
set(LIBUVC_HAS_JPEG OFF)
file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/generated/libuvc")
configure_file(
    "${LIBUVC_DIR}/include/libuvc/libuvc_config.h.in"
    "${CMAKE_CURRENT_BINARY_DIR}/generated/libuvc/libuvc_config.h"
    @ONLY
)

# 1. libusb (LGPL-2.1) built from source for Android
add_library(libusb_static STATIC
    "${LIBUSB_DIR}/libusb/core.c"
    "${LIBUSB_DIR}/libusb/descriptor.c"
    "${LIBUSB_DIR}/libusb/hotplug.c"
    "${LIBUSB_DIR}/libusb/io.c"
    "${LIBUSB_DIR}/libusb/strerror.c"
    "${LIBUSB_DIR}/libusb/sync.c"
    "${LIBUSB_DIR}/libusb/os/events_posix.c"
    "${LIBUSB_DIR}/libusb/os/threads_posix.c"
    "${LIBUSB_DIR}/libusb/os/linux_usbfs.c"
    "${LIBUSB_DIR}/libusb/os/linux_netlink.c"
)
target_include_directories(libusb_static
    PUBLIC
        "${LIBUSB_DIR}/libusb"
    PRIVATE
        "${LIBUSB_DIR}/android"
        "${LIBUSB_DIR}/libusb/os"
)
target_compile_definitions(libusb_static PRIVATE
    HAVE_CONFIG_H=1
    PLATFORM_POSIX=1
    OS_LINUX=1
    THREADS_POSIX=1
    _GNU_SOURCE=1
)
target_compile_options(libusb_static PRIVATE -Wno-unused-parameter -Wno-sign-compare)

# 2. libuvc (BSD-3-Clause) built from source
add_library(libuvc_static STATIC
    "${LIBUVC_DIR}/src/ctrl.c"
    "${LIBUVC_DIR}/src/ctrl-gen.c"
    "${LIBUVC_DIR}/src/device.c"
    "${LIBUVC_DIR}/src/diag.c"
    "${LIBUVC_DIR}/src/frame.c"
    "${LIBUVC_DIR}/src/init.c"
    "${LIBUVC_DIR}/src/misc.c"
    "${LIBUVC_DIR}/src/stream.c"
)
target_include_directories(libuvc_static
    PUBLIC
        "${LIBUVC_DIR}/include"
        "${CMAKE_CURRENT_BINARY_DIR}/generated"
    PRIVATE
        "${LIBUSB_DIR}/libusb"
)
target_link_libraries(libuvc_static PUBLIC libusb_static)
target_compile_definitions(libuvc_static PRIVATE LIBUVC_NUM_TRANSFER_BUFS=8)
target_compile_options(libuvc_static PRIVATE -Wno-unused-parameter -Wno-sign-compare -Wno-deprecated-declarations)

# 3. BetterOTG JNI Bridge
add_library(betterotg_uvc SHARED
    uvc_bridge.cpp
)
target_include_directories(betterotg_uvc PRIVATE
    "${LIBUVC_DIR}/include"
    "${CMAKE_CURRENT_BINARY_DIR}/generated"
    "${LIBUSB_DIR}/libusb"
)
target_link_libraries(betterotg_uvc
    libuvc_static
    libusb_static
    log
    android
)
