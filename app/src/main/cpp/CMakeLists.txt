cmake_minimum_required(VERSION 3.22.1)

project("moonlight-core")

set(MOONLIGHT_CORE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/moonlight-core)
set(COMMON_C_PATH ${MOONLIGHT_CORE_PATH}/moonlight-common-c)

# --- Android NDK Utils ---
include(AndroidNdkModules)
android_ndk_import_module_cpufeatures()

# --- Helper for Architecture ---
if(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    set(ARCH_DIR "armeabi-v7a")
elseif(${ANDROID_ABI} STREQUAL "arm64-v8a")
    set(ARCH_DIR "arm64-v8a")
elseif(${ANDROID_ABI} STREQUAL "x86")
    set(ARCH_DIR "x86")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    set(ARCH_DIR "x86_64")
endif()

# --- OpenSSL (Prebuilt) ---
set(OPENSSL_PATH ${MOONLIGHT_CORE_PATH}/openssl)
add_library(ssl_static STATIC IMPORTED)
set_target_properties(ssl_static PROPERTIES IMPORTED_LOCATION
    "${OPENSSL_PATH}/${ARCH_DIR}/libssl.a")

add_library(crypto_static STATIC IMPORTED)
set_target_properties(crypto_static PROPERTIES IMPORTED_LOCATION
    "${OPENSSL_PATH}/${ARCH_DIR}/libcrypto.a")

include_directories(${OPENSSL_PATH}/include)

# --- Opus (Prebuilt) ---
# Using prebuilt libs instead of compiling from source
set(OPUS_PATH ${MOONLIGHT_CORE_PATH}/libopus)
add_library(opus_static STATIC IMPORTED)
set_target_properties(opus_static PROPERTIES IMPORTED_LOCATION
    "${OPUS_PATH}/${ARCH_DIR}/libopus.a")

include_directories(${OPUS_PATH}/include)

# --- ENet ---
include_directories(${COMMON_C_PATH}/enet/include)

# --- Moonlight Common C ---
include_directories(${COMMON_C_PATH}/src)
include_directories(${COMMON_C_PATH}/reedsolomon)
include_directories(${MOONLIGHT_CORE_PATH})

# Define sources
file(GLOB_RECURSE COMMON_C_SOURCES 
     "${COMMON_C_PATH}/src/*.c"
     "${COMMON_C_PATH}/enet/*.c"
     "${COMMON_C_PATH}/reedsolomon/*.c"
)
list(REMOVE_ITEM COMMON_C_SOURCES "${COMMON_C_PATH}/enet/win32.c")

add_library(moonlight-core SHARED
    ${MOONLIGHT_CORE_PATH}/simplejni.c
    ${MOONLIGHT_CORE_PATH}/minisdl.c
    ${MOONLIGHT_CORE_PATH}/callbacks.c
    ${COMMON_C_SOURCES}
)

target_compile_definitions(moonlight-core PRIVATE
    ANDROID
    HAVE_PTHREAD
    FIXED_POINT
    DISABLE_FLOAT_API
    HAS_SOCKLEN_T
)

target_link_libraries(moonlight-core
    android
    log
    EGL
    GLESv2
    cpufeatures
    ssl_static
    crypto_static
    opus_static
)
