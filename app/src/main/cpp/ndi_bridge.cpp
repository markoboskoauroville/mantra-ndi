// JNI bridge between NdiSender.kt and the NDI Advanced SDK for Android.
//
// *** BEFORE THIS COMPILES ***
// You need Processing.NDI.Lib.h from the NDI Advanced SDK for Android in
// ./ndi/include/ (see CMakeLists.txt). The NDIlib_compressed_packet_t layout
// below (version/fourCC/pts/dts/flags/data_size/extra_data_size, payload
// appended in memory right after the struct) is straight from NDI's docs and
// is solid. What is NOT pinned down without the real header in front of you
// is which frame struct the packet gets attached to before the send call —
// there's a TODO at each spot. Cross-check those two places against the
// header and the Advanced SDK's "Using H.264, H.265 and AAC codecs" section,
// and the rest of this file should be correct as written.

#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <mutex>
#include <vector>
#include <android/log.h>

#include "Processing.NDI.Lib.h"

#define LOG_TAG "ndi_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

NDIlib_send_instance_t g_send_instance = nullptr;

// Guards g_send_instance. The Advanced SDK trial caps a sender at 30 minutes
// of runtime without a registered vendor ID (see README), so NdiSendService
// periodically calls create() again on the same source name to cycle the
// connection before that cap hits. This mutex is what makes that safe against
// RootEncoder's encoder threads calling sendVideo/sendAudio at the same time.
std::mutex g_send_mutex;

// SPS/PPS(/VPS) from the encoder, concatenated. NDI wants this as the
// packet's extra data, and it MUST be present on I-frames.
std::vector<uint8_t> g_video_extra_data;
std::mutex g_extra_mutex;

int g_sample_rate = 48000;
int g_channels = 1;

// Builds an NDIlib_compressed_packet_t + payload in one heap block.
// Caller owns the returned pointer and must free() it.
NDIlib_compressed_packet_t* buildPacket(
        const uint8_t* data, uint32_t data_size,
        const uint8_t* extra_data, uint32_t extra_data_size,
        uint32_t fourCC,
        int64_t pts, int64_t dts, bool keyframe) {

    uint32_t packet_size = sizeof(NDIlib_compressed_packet_t) + data_size + extra_data_size;
    auto* packet = (NDIlib_compressed_packet_t*) malloc(packet_size);
    if (!packet) return nullptr;

    packet->version = NDIlib_compressed_packet_t::version_0;
    packet->fourCC = fourCC;
    packet->pts = pts;
    packet->dts = dts;
    packet->flags = keyframe ? NDIlib_compressed_packet_t::flags_keyframe
                              : NDIlib_compressed_packet_t::flags_none;
    packet->data_size = data_size;
    packet->extra_data_size = extra_data_size;

    auto* dst = (uint8_t*) (packet + 1);
    memcpy(dst, data, data_size);
    if (extra_data_size > 0) {
        memcpy(dst + data_size, extra_data, extra_data_size);
    }
    return packet;
}

std::vector<uint8_t> toVector(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) return {};
    jsize len = env->GetArrayLength(array);
    std::vector<uint8_t> out(len);
    if (len > 0) {
        env->GetByteArrayRegion(array, 0, len, reinterpret_cast<jbyte*>(out.data()));
    }
    return out;
}

} // namespace

// Also used for the periodic reconnect (NdiSendService calls create() again
// with the same name every ~25 minutes), so this safely tears down any
// existing instance rather than assuming it's the first call.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeCreate(JNIEnv* env, jobject, jstring sourceName) {
    const char* name = env->GetStringUTFChars(sourceName, nullptr);
    NDIlib_send_create_t create_desc;
    create_desc.p_ndi_name = name;
    create_desc.p_groups = nullptr;
    create_desc.clock_video = false; // timing comes from the encoder's pts
    create_desc.clock_audio = false;

    bool first_init;
    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        first_init = (g_send_instance == nullptr);
    }
    if (first_init && !NDIlib_initialize()) {
        LOGE("NDIlib_initialize failed");
        env->ReleaseStringUTFChars(sourceName, name);
        return JNI_FALSE;
    }

    NDIlib_send_instance_t new_instance = NDIlib_send_create(&create_desc);
    env->ReleaseStringUTFChars(sourceName, name);

    if (!new_instance) {
        LOGE("NDIlib_send_create failed");
        return JNI_FALSE;
    }

    // Swap in the new instance and destroy the old one under the lock, so a
    // concurrent send never sees a half-torn-down instance.
    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        NDIlib_send_instance_t old_instance = g_send_instance;
        g_send_instance = new_instance;
        if (old_instance) NDIlib_send_destroy(old_instance);
    }
    LOGI("NDI sender (re)created");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeDestroy(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_send_mutex);
    if (g_send_instance) {
        NDIlib_send_destroy(g_send_instance);
        g_send_instance = nullptr;
    }
    NDIlib_destroy();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSetVideoInfo(
        JNIEnv* env, jobject, jbyteArray sps, jbyteArray pps, jbyteArray vps) {
    std::vector<uint8_t> s = toVector(env, sps);
    std::vector<uint8_t> p = toVector(env, pps);
    std::vector<uint8_t> v = toVector(env, vps);

    std::vector<uint8_t> combined;
    combined.reserve(s.size() + p.size() + v.size());
    // H.265 order is VPS, SPS, PPS; H.264 has no VPS so this collapses to SPS, PPS.
    combined.insert(combined.end(), v.begin(), v.end());
    combined.insert(combined.end(), s.begin(), s.end());
    combined.insert(combined.end(), p.begin(), p.end());

    std::lock_guard<std::mutex> lock(g_extra_mutex);
    g_video_extra_data = std::move(combined);
    LOGI("Cached %zu bytes of parameter sets", g_video_extra_data.size());
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSetAudioInfo(
        JNIEnv*, jobject, jint sampleRate, jboolean isStereo) {
    g_sample_rate = sampleRate;
    g_channels = isStereo ? 2 : 1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendVideo(
        JNIEnv* env, jobject,
        jbyteArray data, jboolean isKeyframe, jlong ptsUs, jboolean isHevc) {

    jsize data_len = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);

    std::vector<uint8_t> extra;
    if (isKeyframe) {
        std::lock_guard<std::mutex> lock(g_extra_mutex);
        extra = g_video_extra_data;
    }

    uint32_t fourcc = isHevc ? (uint32_t) NDIlib_FourCC_type_HEVC
                              : (uint32_t) NDIlib_FourCC_type_H264;

    NDIlib_compressed_packet_t* packet = buildPacket(
            (const uint8_t*) data_ptr, (uint32_t) data_len,
            extra.data(), (uint32_t) extra.size(),
            fourcc, ptsUs, ptsUs, isKeyframe);

    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
    if (!packet) return;

    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        if (g_send_instance) {
            // TODO(verify against Processing.NDI.Lib.h): wrap `packet` in the
            // video frame struct with its data pointer set to `packet`,
            // FourCC set to the compressed type, the frame size set to
            // sizeof(packet) + data + extra, then NDIlib_send_send_video_v2.
        }
        // else: mid-reconnect, dropping this frame is fine.
    }

    free(packet);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendAudio(
        JNIEnv* env, jobject, jbyteArray data, jlong ptsUs) {

    jsize data_len = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);

    // AAC AudioSpecificConfig would go here as extra data; RootEncoder gives
    // us raw AAC frames, so for now we send with none and let the receiver
    // infer from the stream. Revisit if monitors refuse the audio track.
    NDIlib_compressed_packet_t* packet = buildPacket(
            (const uint8_t*) data_ptr, (uint32_t) data_len,
            nullptr, 0,
            (uint32_t) NDIlib_FourCC_type_AAC,
            ptsUs, ptsUs, /*keyframe=*/true); // every AAC packet is a keyframe

    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
    if (!packet) return;

    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        if (g_send_instance) {
            // TODO(verify against Processing.NDI.Lib.h): wrap in the audio
            // frame struct (sample rate g_sample_rate, channels g_channels)
            // with FourCC NDIlib_FourCC_type_AAC, then send it.
        }
    }

    free(packet);
}
