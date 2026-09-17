// JNI bridge between NdiSender.kt and the NDI Advanced SDK for Android.
//
// Written against the SDK's own NDIlib_Send_H264 example, so the frame
// construction here follows the documented pattern rather than guesswork:
//   - the compressed payload is prefixed by an NDIlib_compressed_packet_t
//     header, delivered through a scatter-gather list so nothing is copied
//   - the video frame's FourCC selects the stream variant: *_highest_bandwidth
//     for the full stream, *_lowest_bandwidth for NDI's required preview stream
//   - packet.version is sizeof(NDIlib_compressed_packet_t)
//
// The SDK itself is licensed and confidential, so it is never committed here.
// See README for how it is injected at build time.

#include <jni.h>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <vector>
#include <android/log.h>

#include "Processing.NDI.Lib.h"

#define LOG_TAG "ndi_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

NDIlib_send_instance_t g_send_instance = nullptr;

// Guards g_send_instance. The Advanced SDK stops a sender after 30 minutes
// without a registered vendor ID, so NdiSendService recreates it periodically
// on the same source name. This mutex keeps that safe against RootEncoder's
// encoder threads sending frames at the same moment.
std::mutex g_send_mutex;

// SPS/PPS (and VPS for H.265), attached to keyframes as the packet's extra data.
std::vector<uint8_t> g_video_extra;
std::mutex g_extra_mutex;

int g_width = 1920;
int g_height = 1080;
int g_fps_n = 30;
int g_fps_d = 1;

int g_sample_rate = 48000;
int g_channels = 1;

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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeCreate(JNIEnv* env, jobject, jstring sourceName) {
    const char* name = env->GetStringUTFChars(sourceName, nullptr);

    NDIlib_send_create_t create_desc;
    create_desc.p_ndi_name = name;
    create_desc.p_groups = nullptr;
    create_desc.clock_video = false; // pacing comes from the encoder timestamps
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

    NDIlib_send_instance_t fresh = NDIlib_send_create(&create_desc);
    env->ReleaseStringUTFChars(sourceName, name);

    if (!fresh) {
        LOGE("NDIlib_send_create failed");
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        NDIlib_send_instance_t old = g_send_instance;
        g_send_instance = fresh;
        if (old) NDIlib_send_destroy(old);
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
Java_com_mantraproductions_ndi_NdiSender_nativeSetVideoFormat(
        JNIEnv*, jobject, jint width, jint height, jint fpsNumerator, jint fpsDenominator) {
    g_width = width;
    g_height = height;
    g_fps_n = fpsNumerator;
    g_fps_d = fpsDenominator > 0 ? fpsDenominator : 1;
    LOGI("Video format %dx%d @ %d/%d", g_width, g_height, g_fps_n, g_fps_d);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSetVideoInfo(
        JNIEnv* env, jobject, jbyteArray sps, jbyteArray pps, jbyteArray vps) {
    std::vector<uint8_t> s = toVector(env, sps);
    std::vector<uint8_t> p = toVector(env, pps);
    std::vector<uint8_t> v = toVector(env, vps);

    std::vector<uint8_t> combined;
    combined.reserve(s.size() + p.size() + v.size());
    // H.265 order is VPS, SPS, PPS; with no VPS this collapses to SPS, PPS.
    combined.insert(combined.end(), v.begin(), v.end());
    combined.insert(combined.end(), s.begin(), s.end());
    combined.insert(combined.end(), p.begin(), p.end());

    std::lock_guard<std::mutex> lock(g_extra_mutex);
    g_video_extra = std::move(combined);
    LOGI("Cached %zu bytes of parameter sets", g_video_extra.size());
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
        jbyteArray data, jboolean isKeyframe, jlong ptsUs,
        jboolean isHevc, jboolean isPreviewStream) {

    jsize data_len = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    if (!data_ptr) return;

    std::vector<uint8_t> extra;
    if (isKeyframe) {
        std::lock_guard<std::mutex> lock(g_extra_mutex);
        extra = g_video_extra;
    }

    // NDI timecodes are in 100ns units; MediaCodec gives microseconds.
    const int64_t timecode = static_cast<int64_t>(ptsUs) * 10;

    NDIlib_compressed_packet_t packet = {};
    packet.version = sizeof(NDIlib_compressed_packet_t);
    packet.fourCC = isHevc ? NDIlib_compressed_FourCC_type_HEVC
                            : NDIlib_compressed_FourCC_type_H264;
    packet.pts = timecode;
    packet.dts = timecode;
    packet.flags = isKeyframe ? NDIlib_compressed_packet_t::flags_keyframe
                               : NDIlib_compressed_packet_t::flags_none;
    packet.data_size = static_cast<uint32_t>(data_len);
    packet.extra_data_size = static_cast<uint32_t>(extra.size());

    // Scatter-gather: header, payload, then parameter sets. No copying.
    const uint8_t* blocks[4];
    int sizes[4];
    int n = 0;
    blocks[n] = reinterpret_cast<const uint8_t*>(&packet);
    sizes[n++] = static_cast<int>(sizeof(NDIlib_compressed_packet_t));
    blocks[n] = reinterpret_cast<const uint8_t*>(data_ptr);
    sizes[n++] = static_cast<int>(data_len);
    if (!extra.empty()) {
        blocks[n] = extra.data();
        sizes[n++] = static_cast<int>(extra.size());
    }
    blocks[n] = nullptr;
    sizes[n] = 0;

    NDIlib_frame_scatter_t scatter = {};
    scatter.p_data_blocks = blocks;
    scatter.p_data_blocks_size = sizes;

    NDIlib_video_frame_v2_t frame = {};
    // Highest bandwidth is the full stream; lowest is the preview stream NDI
    // expects alongside it on the compressed path.
    NDIlib_FourCC_video_type_ex_e stream_type;
    if (isHevc) {
        stream_type = isPreviewStream ? NDIlib_FourCC_video_type_ex_HEVC_lowest_bandwidth
                                       : NDIlib_FourCC_video_type_ex_HEVC_highest_bandwidth;
    } else {
        stream_type = isPreviewStream ? NDIlib_FourCC_video_type_ex_H264_lowest_bandwidth
                                       : NDIlib_FourCC_video_type_ex_H264_highest_bandwidth;
    }
    frame.FourCC = (NDIlib_FourCC_video_type_e) stream_type;
    frame.xres = g_width;
    frame.yres = g_height;
    frame.p_data = nullptr;          // supplied through the scatter list
    frame.data_size_in_bytes = 0;
    frame.frame_rate_N = g_fps_n;
    frame.frame_rate_D = g_fps_d;
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.picture_aspect_ratio = g_height > 0 ? (float) g_width / (float) g_height : 0.0f;
    frame.timecode = timecode;

    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        if (g_send_instance) {
            // Synchronous rather than async: the async variant requires the
            // buffers to stay valid past the call, which these don't.
            NDIlib_send_send_video_scatter(g_send_instance, &frame, &scatter);
        }
        // else: mid-reconnect, dropping one frame is fine.
    }

    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendAudio(
        JNIEnv* env, jobject, jbyteArray data, jbyteArray extraData, jint sampleCount, jlong ptsUs) {

    jsize data_len = env->GetArrayLength(data);
    jbyte* data_ptr = env->GetByteArrayElements(data, nullptr);
    if (!data_ptr) return;

    std::vector<uint8_t> extra = toVector(env, extraData);
    const int64_t timecode = static_cast<int64_t>(ptsUs) * 10;

    NDIlib_compressed_packet_t packet = {};
    packet.version = sizeof(NDIlib_compressed_packet_t);
    packet.fourCC = NDIlib_compressed_FourCC_type_AAC;
    packet.flags = NDIlib_compressed_packet_t::flags_keyframe; // every AAC frame is one
    packet.pts = timecode;
    packet.dts = timecode;
    packet.data_size = static_cast<uint32_t>(data_len);
    packet.extra_data_size = static_cast<uint32_t>(extra.size());

    const uint8_t* blocks[4];
    int sizes[4];
    int n = 0;
    blocks[n] = reinterpret_cast<const uint8_t*>(&packet);
    sizes[n++] = static_cast<int>(sizeof(NDIlib_compressed_packet_t));
    blocks[n] = reinterpret_cast<const uint8_t*>(data_ptr);
    sizes[n++] = static_cast<int>(data_len);
    if (!extra.empty()) {
        blocks[n] = extra.data();
        sizes[n++] = static_cast<int>(extra.size());
    }
    blocks[n] = nullptr;
    sizes[n] = 0;

    NDIlib_frame_scatter_t scatter = {};
    scatter.p_data_blocks = blocks;
    scatter.p_data_blocks_size = sizes;

    NDIlib_audio_frame_v3_t frame = {};
    frame.sample_rate = g_sample_rate;
    frame.no_channels = g_channels;
    frame.no_samples = sampleCount;
    frame.FourCC = (NDIlib_FourCC_audio_type_e) NDIlib_FourCC_audio_type_ex_AAC;
    frame.timecode = timecode;
    frame.p_data = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        if (g_send_instance) {
            NDIlib_send_send_audio_scatter(g_send_instance, &frame, &scatter);
        }
    }

    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
}
