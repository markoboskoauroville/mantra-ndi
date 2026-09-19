// NDI discovery and receive, for the Monitor mode.
//
// Frames are requested in compressed form (NDIlib_recv_color_format_ex_compressed_v4),
// so an HX sender's H.264/HEVC arrives untouched and can go straight into the
// phone's hardware decoder rather than being decoded in software by the SDK
// and then re-uploaded as pixels.
//
// SpeedHQ (NDI High Bandwidth) senders also arrive under this format, but
// Android has no SpeedHQ decoder, so those are reported to the Kotlin side as
// unsupported rather than silently showing black. See README.

#include <jni.h>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>

#include "Processing.NDI.Advanced.h"

#define LOG_TAG "ndi_recv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

NDIlib_find_instance_t g_find = nullptr;
std::mutex g_find_mutex;

NDIlib_recv_instance_t g_recv = nullptr;
std::mutex g_recv_mutex;

// Frame kinds handed back to Kotlin.
constexpr int KIND_NONE = 0;
constexpr int KIND_VIDEO = 1;
constexpr int KIND_AUDIO = 2;
constexpr int KIND_UNSUPPORTED = 3;  // SpeedHQ, nothing on the phone can decode it
constexpr int KIND_TOO_BIG = 4;
constexpr int KIND_METADATA = 5;   // camera state reported back by the sender

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiFinder_nativeStart(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_find_mutex);
    if (g_find) return JNI_TRUE;
    if (!NDIlib_initialize()) {
        LOGE("NDIlib_initialize failed");
        return JNI_FALSE;
    }
    NDIlib_find_create_t desc;
    desc.show_local_sources = true; // so the phone's own camera mode is visible too
    desc.p_groups = nullptr;
    desc.p_extra_ips = nullptr;

    g_find = NDIlib_find_create_v2(&desc);
    if (!g_find) {
        LOGE("NDIlib_find_create_v2 failed");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/**
 * Tells the source it is being watched.
 *
 * NDI tally flows backwards: a receiver declares whether it has this source on
 * programme or on preview, and the sender adds up everyone watching. Without
 * this a camera has no way to know a monitor has opened it, which is exactly
 * the light an operator needs: yellow for somebody is looking, red for on air.
 *
 * A monitor declares preview rather than programme, because opening a picture
 * to look at it is not the same as cutting to it, and a camera operator who
 * cannot tell those apart will eventually walk in front of a live camera.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiReceiver_nativeSetTally(
        JNIEnv*, jobject, jboolean onProgram, jboolean onPreview) {
    std::lock_guard<std::mutex> lock(g_recv_mutex);
    if (!g_recv) return;
    NDIlib_tally_t tally;
    tally.on_program = onProgram == JNI_TRUE;
    tally.on_preview = onPreview == JNI_TRUE;
    NDIlib_recv_set_tally(g_recv, &tally);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiFinder_nativeStop(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_find_mutex);
    if (g_find) {
        NDIlib_find_destroy(g_find);
        g_find = nullptr;
    }
}

/** Blocks up to timeoutMs for the source list to change, then returns the names. */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_mantraproductions_ndi_NdiFinder_nativeGetSources(
        JNIEnv* env, jobject, jint timeoutMs) {
    std::lock_guard<std::mutex> lock(g_find_mutex);
    jclass string_class = env->FindClass("java/lang/String");
    if (!g_find) return env->NewObjectArray(0, string_class, nullptr);

    NDIlib_find_wait_for_sources(g_find, (uint32_t) timeoutMs);

    uint32_t count = 0;
    const NDIlib_source_t* sources = NDIlib_find_get_current_sources(g_find, &count);
    if (!sources) count = 0;

    jobjectArray result = env->NewObjectArray((jsize) count, string_class, nullptr);
    for (uint32_t i = 0; i < count; i++) {
        const char* name = sources[i].p_ndi_name ? sources[i].p_ndi_name : "";
        jstring s = env->NewStringUTF(name);
        env->SetObjectArrayElement(result, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiReceiver_nativeConnect(
        JNIEnv* env, jobject, jstring sourceName) {
    const char* name = env->GetStringUTFChars(sourceName, nullptr);

    NDIlib_source_t source;
    source.p_ndi_name = name;
    source.p_url_address = nullptr;

    NDIlib_recv_create_v3_t desc;
    desc.source_to_connect_to = source;
    // Deliver compressed video untouched so the hardware decoder can take it.
    desc.color_format = (NDIlib_recv_color_format_e) NDIlib_recv_color_format_ex_compressed_v4;
    desc.bandwidth = NDIlib_recv_bandwidth_highest;
    desc.allow_video_fields = false;
    desc.p_ndi_recv_name = "Mantra NDI Monitor";

    NDIlib_recv_instance_t fresh = NDIlib_recv_create_v3(&desc);
    env->ReleaseStringUTFChars(sourceName, name);

    if (!fresh) {
        LOGE("NDIlib_recv_create_v3 failed");
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(g_recv_mutex);
    NDIlib_recv_instance_t old = g_recv;
    g_recv = fresh;
    if (old) NDIlib_recv_destroy(old);
    LOGI("Receiver connected");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiReceiver_nativeDisconnect(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_recv_mutex);
    if (g_recv) {
        NDIlib_recv_destroy(g_recv);
        g_recv = nullptr;
    }
}

/**
 * Captures one frame into the caller's direct ByteBuffer.
 *
 * info[] is filled with: size, ptsUs, isKeyframe, fourCC, width, height.
 * Compressed frames arrive with the NDIlib_compressed_packet_t header in
 * front, which is stripped here. For keyframes the parameter sets are emitted
 * ahead of the frame data, which is the Annex-B ordering MediaCodec expects.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_mantraproductions_ndi_NdiReceiver_nativeCapture(
        JNIEnv* env, jobject, jobject directBuffer, jlongArray info, jint timeoutMs) {

    auto* out = (uint8_t*) env->GetDirectBufferAddress(directBuffer);
    jlong capacity = env->GetDirectBufferCapacity(directBuffer);
    if (!out) return KIND_NONE;

    NDIlib_recv_instance_t recv;
    {
        std::lock_guard<std::mutex> lock(g_recv_mutex);
        recv = g_recv;
        if (!recv) return KIND_NONE;
    }

    NDIlib_video_frame_v2_t video = {};
    NDIlib_audio_frame_v3_t audio = {};
    NDIlib_metadata_frame_t metadata = {};

    NDIlib_frame_type_e type = NDIlib_recv_capture_v3(recv, &video, &audio, &metadata, (uint32_t) timeoutMs);

    if (type == NDIlib_frame_type_metadata) {
        int kind = KIND_METADATA;
        jlong values[7] = {0, 0, 0, 0, 0, 0, 0};
        if (metadata.p_data) {
            size_t len = strlen(metadata.p_data);
            if ((jlong) len <= capacity) {
                memcpy(out, metadata.p_data, len);
                values[0] = (jlong) len;
            } else {
                kind = KIND_TOO_BIG;
            }
        }
        env->SetLongArrayRegion(info, 0, 7, values);
        NDIlib_recv_free_metadata(recv, &metadata);
        return kind;
    }

    if (type == NDIlib_frame_type_video) {
        int kind = KIND_VIDEO;
        // The timecode the sender stamped onto this exact frame, in 100ns
        // units. It rides with the picture rather than beside it, so a
        // follower reads the master's clock off the frame it just decoded and
        // there is nothing left to line up afterwards.
        jlong values[7] = {0, 0, 0, 0, video.xres, video.yres, (jlong) video.timecode};

        const uint32_t fourcc = (uint32_t) video.FourCC;
        const bool is_h264 =
                fourcc == (uint32_t) NDIlib_FourCC_video_type_ex_H264_highest_bandwidth ||
                fourcc == (uint32_t) NDIlib_FourCC_video_type_ex_H264_lowest_bandwidth;
        const bool is_hevc =
                fourcc == (uint32_t) NDIlib_FourCC_video_type_ex_HEVC_highest_bandwidth ||
                fourcc == (uint32_t) NDIlib_FourCC_video_type_ex_HEVC_lowest_bandwidth;

        if ((is_h264 || is_hevc) && video.p_data && video.data_size_in_bytes > sizeof(NDIlib_compressed_packet_t)) {
            auto* packet = (const NDIlib_compressed_packet_t*) video.p_data;
            const uint8_t* payload = (const uint8_t*) video.p_data + sizeof(NDIlib_compressed_packet_t);
            const uint8_t* extra = payload + packet->data_size;

            const bool keyframe = (packet->flags & NDIlib_compressed_packet_t::flags_keyframe) != 0;
            const uint32_t extra_size = keyframe ? packet->extra_data_size : 0;
            const uint32_t total = packet->data_size + extra_size;

            if ((jlong) total > capacity) {
                kind = KIND_TOO_BIG;
            } else {
                // Parameter sets first, then the frame itself.
                uint32_t offset = 0;
                if (extra_size > 0) {
                    memcpy(out, extra, extra_size);
                    offset += extra_size;
                }
                memcpy(out + offset, payload, packet->data_size);

                values[0] = total;
                values[1] = packet->pts / 10;  // 100ns to microseconds
                values[2] = keyframe ? 1 : 0;
                values[3] = is_hevc ? 1 : 0;
            }
        } else {
            // SpeedHQ or an uncompressed format; nothing on Android decodes it.
            kind = KIND_UNSUPPORTED;
            values[3] = fourcc;
        }

        env->SetLongArrayRegion(info, 0, 7, values);
        NDIlib_recv_free_video_v2(recv, &video);
        return kind;
    }

    if (type == NDIlib_frame_type_audio) {
        // Monitor is video only for now; drop audio rather than leak it.
        NDIlib_recv_free_audio_v3(recv, &audio);
        return KIND_AUDIO;
    }

    return KIND_NONE;
}

/** Sends an XML command upstream to the connected sender. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiReceiver_nativeSendMetadata(
        JNIEnv* env, jobject, jstring xml) {

    NDIlib_recv_instance_t recv;
    {
        std::lock_guard<std::mutex> lock(g_recv_mutex);
        recv = g_recv;
        if (!recv) return JNI_FALSE;
    }

    const char* data = env->GetStringUTFChars(xml, nullptr);
    NDIlib_metadata_frame_t metadata = {};
    metadata.length = (int) strlen(data) + 1;
    metadata.timecode = NDIlib_send_timecode_synthesize;
    metadata.p_data = const_cast<char*>(data);

    bool ok = NDIlib_recv_send_metadata(recv, &metadata);
    env->ReleaseStringUTFChars(xml, data);
    return ok ? JNI_TRUE : JNI_FALSE;
}
