// JNI bridge between NdiSender.kt and the NDI Advanced SDK for Android.
//
// Lifted from mantra-ndi's own bridge, which is the one part of that app that
// was never in doubt, and extended with the second way out: this app can send
// either compressed packets (NDI HX) or whole frames the SDK compresses itself
// (full NDI, what the documentation calls High Bandwidth).
//
// The compressed path follows the SDK's NDIlib_Send_H264 example rather than
// guesswork:
//   - the payload is prefixed by an NDIlib_compressed_packet_t header and
//     delivered through a scatter-gather list, so nothing is copied
//   - the video frame's FourCC picks the stream variant: *_highest_bandwidth
//     for the full stream, *_lowest_bandwidth for NDI's required preview stream
//   - packet.version is sizeof(NDIlib_compressed_packet_t)
//
// The full path hands NDIlib_send_send_video_v2 an RGBA frame straight out of
// the ImageReader's direct buffer. No copy happens here either: the address is
// the one Android mapped, and the call is synchronous, so the buffer is still
// alive when the SDK has finished reading it.
//
// The SDK itself is licensed and confidential, so it is never committed here.
// See README.md for how it is injected at build time.

#include <jni.h>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <vector>
#include <android/log.h>

// Advanced.h pulls in Lib.h plus the compressed-send API (scatter lists,
// NDIlib_compressed_packet_t, the H264/HEVC bandwidth stream variants).
#include "Processing.NDI.Advanced.h"

#define LOG_TAG "ndi_bridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

NDIlib_send_instance_t g_send_instance = nullptr;

// Guards g_send_instance against the capture thread sending a frame at the
// moment the service is tearing the sender down — which is exactly what a turn
// of the phone does, since a rotation rebuilds the pipeline underneath.
std::mutex g_send_mutex;

// SPS/PPS (and VPS for H.265), attached to keyframes as the packet's extra
// data. A receiver that joins mid-stream has no other way to learn the format.
std::vector<uint8_t> g_video_extra;
std::mutex g_extra_mutex;

int g_width = 1920;
int g_height = 1080;
int g_fps_n = 30;
int g_fps_d = 1;

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
Java_com_mantraproductions_ndi_NdiSender_nativeCreate(
        JNIEnv* env, jobject, jstring sourceName) {
    const char* name = env->GetStringUTFChars(sourceName, nullptr);

    NDIlib_send_create_t create_desc;
    create_desc.p_ndi_name = name;
    create_desc.p_groups = nullptr;
    // Pacing comes from the frames themselves. A screen does not tick: it can
    // sit still for a minute and then change twice in one frame period, and
    // letting the SDK clock the output would hold frames back waiting for a
    // schedule the screen is not keeping.
    create_desc.clock_video = false;
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

/**
 * Clears the cached parameter sets.
 *
 * Called when the pipeline is rebuilt at a new size. SPS and PPS carry the
 * picture dimensions, so the old pair describes a frame that no longer exists;
 * a receiver that joined on the strength of them would decode the new stream
 * into the old geometry and show a torn picture rather than an error.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeClearVideoInfo(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_extra_mutex);
    g_video_extra.clear();
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendCompressed(
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
    const int64_t pts = static_cast<int64_t>(ptsUs) * 10;

    NDIlib_compressed_packet_t packet = {};
    packet.version = sizeof(NDIlib_compressed_packet_t);
    packet.fourCC = isHevc ? NDIlib_compressed_FourCC_type_HEVC
                           : NDIlib_compressed_FourCC_type_H264;
    packet.pts = pts;
    packet.dts = pts;
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
    frame.timecode = pts;

    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        if (g_send_instance) {
            // Synchronous rather than async: the async variant requires the
            // buffers to stay valid past the call, which these do not.
            NDIlib_send_send_video_scatter(g_send_instance, &frame, &scatter);
        }
        // else: mid-rebuild, dropping one frame is fine.
    }

    env->ReleaseByteArrayElements(data, data_ptr, JNI_ABORT);
}

/**
 * Full NDI: one whole uncompressed frame, which the SDK compresses to SpeedHQ
 * on its way out.
 *
 * [buffer] is the ImageReader's own direct ByteBuffer, so `GetDirectBufferAddress`
 * gives the address Android already mapped and nothing is copied on this side.
 * [stride] is the ImageReader's reported row stride in BYTES and is very often
 * wider than width × 4 — a 1080-wide reader commonly hands back rows padded to
 * 1088 or 1152. NDI takes the stride as a field, so the padding is described
 * rather than removed; computing it as width × 4 skews the picture into a
 * diagonal, which is the classic symptom and reads as a broken codec.
 *
 * RGBA rather than BGRA: Android's PixelFormat.RGBA_8888 is R, G, B, A in
 * memory order, and the SDK has a FourCC that says exactly that. Swapping the
 * bytes to reach BGRA would cost a pass over every pixel to arrive somewhere
 * no better.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendRaw(
        JNIEnv* env, jobject,
        jobject buffer, jint width, jint height, jint stride, jlong ptsUs) {

    auto* pixels = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!pixels) {
        LOGE("ImageReader buffer is not direct; nothing can be sent from it");
        return;
    }

    NDIlib_video_frame_v2_t frame = {};
    frame.FourCC = NDIlib_FourCC_video_type_RGBA;
    frame.xres = width;
    frame.yres = height;
    frame.line_stride_in_bytes = stride;
    frame.p_data = pixels;
    frame.frame_rate_N = g_fps_n;
    frame.frame_rate_D = g_fps_d;
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.picture_aspect_ratio = height > 0 ? (float) width / (float) height : 0.0f;
    frame.timecode = static_cast<int64_t>(ptsUs) * 10;

    std::lock_guard<std::mutex> lock(g_send_mutex);
    if (g_send_instance) {
        // Synchronous, because the Image this buffer belongs to is closed the
        // moment this returns. The async variant would hand the SDK an address
        // Android has taken back.
        NDIlib_send_send_video_v2(g_send_instance, &frame);
    }
}

/**
 * How many receivers are attached right now.
 *
 * This is the one honest answer to "is anybody seeing this?". A source
 * advertises whether or not anyone is watching, so a green light that only
 * means "sending" tells nobody anything; this number means a machine has
 * opened the stream.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeConnections(
        JNIEnv*, jobject, jint timeoutMs) {
    NDIlib_send_instance_t send;
    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        send = g_send_instance;
        if (!send) return -1;
    }
    return NDIlib_send_get_no_connections(send, (uint32_t) timeoutMs);
}

/**
 * Tally from whatever mixer is receiving this screen: bit 0 program, bit 1
 * preview, -1 no sender. vMix and OBS set it themselves over NDI, so the phone
 * shows a real tally rather than something this app invented.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeTally(JNIEnv*, jobject, jint timeoutMs) {
    NDIlib_send_instance_t send;
    {
        std::lock_guard<std::mutex> lock(g_send_mutex);
        send = g_send_instance;
        if (!send) return -1;
    }
    NDIlib_tally_t tally = {};
    NDIlib_send_get_tally(send, &tally, (uint32_t) timeoutMs);
    int result = 0;
    if (tally.on_program) result |= 1;
    if (tally.on_preview) result |= 2;
    return result;
}
