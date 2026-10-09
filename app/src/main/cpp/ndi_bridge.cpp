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

// v123: the remote control's metadata has its OWN lock. v117 polled and sent it under g_send_mutex, and a metadata
// send waiting on the network held up the video behind it: the picture came in bursts and stopped (MEASURED
// 2.10.2026, Pixel → Nothing: 10-19 fps for five seconds, then nothing, while v113-v114 without metadata held 25 fps).
// Creating or destroying the sender takes BOTH locks, so neither side can ever use a sender being torn down.
std::mutex g_meta_mutex;

// v135: the sound has its own lock too. The SDK allows audio and video to be sent at the same time from different
// threads, and an audio send waiting behind a video frame that waits on the network would starve the microphone's
// reader. Creating or destroying the sender takes all three locks.
std::mutex g_audio_mutex;

// SPS/PPS (and VPS for H.265), attached to keyframes as the packet's extra
// data. A receiver that joins mid-stream has no other way to learn the format.
std::vector<uint8_t> g_video_extra;
std::mutex g_extra_mutex;

int g_width = 1920;
int g_height = 1080;
int g_fps_n = 30;
int g_fps_d = 1;

// v136: one tightly packed 8-bit plane (w x h) turned t quarter turns clockwise into dst (h x w when t is odd).
void rotatePlane(const uint8_t* src, int w, int h, uint8_t* dst, int t) {
    for (int y = 0; y < h; ++y) {
        const uint8_t* row = src + (size_t) y * w;
        for (int x = 0; x < w; ++x) {
            size_t i;
            switch (t) {
                case 1: i = (size_t) x * h + (h - 1 - y); break;            // dst w' = h
                case 2: i = (size_t) (h - 1 - y) * w + (w - 1 - x); break;
                default: i = (size_t) (w - 1 - x) * h + y; break;          // t == 3
            }
            dst[i] = row[x];
        }
    }
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeCreate(
        JNIEnv* env, jobject, jstring sourceName, jstring configJson) {
    const char* name = env->GetStringUTFChars(sourceName, nullptr);
    // v135: the NDI JSON configuration for this one sender (which adapter it may use), or null for the SDK's own.
    const char* config = configJson ? env->GetStringUTFChars(configJson, nullptr) : nullptr;

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
        if (config) env->ReleaseStringUTFChars(configJson, config);
        return JNI_FALSE;
    }

    NDIlib_send_instance_t fresh = NDIlib_send_create_v2(&create_desc, config);
    if (config) LOGI("NDI sender config %s", config);
    env->ReleaseStringUTFChars(sourceName, name);
    if (config) env->ReleaseStringUTFChars(configJson, config);

    if (!fresh) {
        LOGE("NDIlib_send_create failed");
        return JNI_FALSE;
    }

    {
        std::scoped_lock lock(g_send_mutex, g_meta_mutex, g_audio_mutex);
        NDIlib_send_instance_t old = g_send_instance;
        g_send_instance = fresh;
        if (old) NDIlib_send_destroy(old);
    }
    LOGI("NDI sender (re)created");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeDestroy(JNIEnv*, jobject) {
    std::scoped_lock lock(g_send_mutex, g_meta_mutex, g_audio_mutex);
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
 * YUV rather than RGBA, and that is a correction rather than a preference.
 * **A camera cannot write into an RGBA_8888 ImageReader at all** — the format
 * is not in Camera2's stream configuration map, so a session carrying one is
 * refused outright, and it takes the preview and the encoder down with it.
 * That is exactly what a real Pixel 7 did: a black screen and "Camera session
 * could not be configured", caused by a reader that existed only for a mode
 * nobody had switched on. RGBA was right in the screen share, because a
 * VirtualDisplay does produce RGBA; a camera never does.
 *
 * So the reader is YUV_420_888 and its three planes are packed here into I420,
 * which NDI takes natively. One pass over the image, on a thread that is not
 * the camera's.
 *
 * Every stride is read from the reader and never computed. Camera2 pads rows
 * freely — a 1080-wide Y plane commonly arrives at 1088 or 1152 — and the
 * chroma planes carry a *pixel* stride as well, 1 when the phone hands back
 * planar data and 2 when it hands back semi-planar with the other channel
 * interleaved between. Assuming either skews the picture into a diagonal,
 * which every person reads as a broken codec.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendYuv420(
        JNIEnv* env, jobject,
        jobject yBuf, jint yStride,
        jobject uBuf, jint uStride,
        jobject vBuf, jint vStride,
        jint uvPixelStride,
        jint width, jint height, jlong ptsUs, jint turns) {

    auto* y = static_cast<const uint8_t*>(env->GetDirectBufferAddress(yBuf));
    auto* u = static_cast<const uint8_t*>(env->GetDirectBufferAddress(uBuf));
    auto* v = static_cast<const uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (!y || !u || !v) {
        LOGE("ImageReader planes are not direct; nothing can be sent from them");
        return;
    }
    if (width <= 1 || height <= 1) return;

    const int cw = width / 2;
    const int ch = height / 2;
    const size_t needed = (size_t) width * height + (size_t) cw * ch * 2;

    // Kept between frames. Allocating three megabytes thirty times a second is
    // a stutter with no other cause.
    static std::vector<uint8_t> packed;
    if (packed.size() < needed) packed.resize(needed);

    uint8_t* dstY = packed.data();
    uint8_t* dstU = dstY + (size_t) width * height;
    uint8_t* dstV = dstU + (size_t) cw * ch;

    for (int row = 0; row < height; ++row) {
        memcpy(dstY + (size_t) row * width, y + (size_t) row * yStride, (size_t) width);
    }

    if (uvPixelStride == 1) {
        for (int row = 0; row < ch; ++row) {
            memcpy(dstU + (size_t) row * cw, u + (size_t) row * uStride, (size_t) cw);
            memcpy(dstV + (size_t) row * cw, v + (size_t) row * vStride, (size_t) cw);
        }
    } else {
        // Semi-planar: the other channel sits between every sample.
        for (int row = 0; row < ch; ++row) {
            const uint8_t* su = u + (size_t) row * uStride;
            const uint8_t* sv = v + (size_t) row * vStride;
            uint8_t* du = dstU + (size_t) row * cw;
            uint8_t* dv = dstV + (size_t) row * cw;
            for (int col = 0; col < cw; ++col) {
                du[col] = su[(size_t) col * uvPixelStride];
                dv[col] = sv[(size_t) col * uvPixelStride];
            }
        }
    }

    // v136: the frame turned to how the phone is held, plane by plane (the chroma planes at half size).
    const int t = ((turns % 4) + 4) % 4;
    uint8_t* out = packed.data();
    int outW = width, outH = height;
    if (t != 0) {
        static std::vector<uint8_t> turned;
        if (turned.size() < needed) turned.resize(needed);
        if (t % 2 == 1) { outW = height; outH = width; }
        rotatePlane(dstY, width, height, turned.data(), t);
        uint8_t* tu = turned.data() + (size_t) width * height;
        rotatePlane(dstU, cw, ch, tu, t);
        rotatePlane(dstV, cw, ch, tu + (size_t) cw * ch, t);
        out = turned.data();
    }

    NDIlib_video_frame_v2_t frame = {};
    frame.FourCC = NDIlib_FourCC_video_type_I420;
    frame.xres = outW;
    frame.yres = outH;
    frame.line_stride_in_bytes = outW;
    frame.p_data = out;
    frame.frame_rate_N = g_fps_n;
    frame.frame_rate_D = g_fps_d;
    frame.frame_format_type = NDIlib_frame_format_type_progressive;
    frame.picture_aspect_ratio = outH > 0 ? (float) outW / (float) outH : 0.0f;
    frame.timecode = static_cast<int64_t>(ptsUs) * 10;

    std::lock_guard<std::mutex> lock(g_send_mutex);
    if (g_send_instance) {
        // Synchronous, because `packed` is reused by the very next frame. The
        // async variant would hand the SDK a buffer about to be overwritten.
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

/**
 * v117, REMOTE CONTROL: a command a receiver (Mantra Monitor) sent up this source, or null. Polled, never blocking
 * (timeout 0, under the metadata lock since v123), so it never holds up a video frame nor outlives a sender being torn down.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativePollMetadata(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_meta_mutex);
    if (!g_send_instance) return nullptr;
    NDIlib_metadata_frame_t frame = {};
    if (NDIlib_send_capture(g_send_instance, &frame, 0) != NDIlib_frame_type_metadata) return nullptr;
    jstring out = frame.p_data ? env->NewStringUTF(frame.p_data) : nullptr;
    NDIlib_send_free_metadata(g_send_instance, &frame);
    return out;
}

/** v117: the camera's state, down the wire to every receiver, as one metadata frame. */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendMetadata(JNIEnv* env, jobject, jstring xml) {
    std::lock_guard<std::mutex> lock(g_meta_mutex);
    if (!g_send_instance || xml == nullptr) return JNI_FALSE;
    const char* data = env->GetStringUTFChars(xml, nullptr);
    NDIlib_metadata_frame_t frame = {};
    frame.length = (int) strlen(data) + 1;
    frame.timecode = NDIlib_send_timecode_synthesize;
    frame.p_data = const_cast<char*>(data);
    NDIlib_send_send_metadata(g_send_instance, &frame);
    env->ReleaseStringUTFChars(xml, data);
    return JNI_TRUE;
}

/**
 * v135: the sound, inside the stream. 16-bit interleaved PCM straight from the microphone's reader, sent as an NDI
 * audio frame beside the picture, in HX and in full NDI alike. Reference level 0 dB, which is what the SDK asks a
 * sender to use ("most common applications produce audio at reference level"): full scale on the phone is full
 * scale in OBS and vMix. The timecode is synthesised by the SDK from the sample count, so the sound runs on its own
 * clock and never jumps when the picture is rebuilt.
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_mantraproductions_ndi_NdiSender_nativeSendAudio(
        JNIEnv* env, jobject, jbyteArray pcm, jint bytes, jint channels, jint sampleRate) {
    if (pcm == nullptr || bytes <= 0 || channels <= 0 || sampleRate <= 0) return JNI_FALSE;
    const int samples = bytes / (2 * channels);
    if (samples <= 0) return JNI_FALSE;
    jbyte* data = env->GetByteArrayElements(pcm, nullptr);
    if (!data) return JNI_FALSE;

    NDIlib_audio_frame_interleaved_16s_t frame = {};
    frame.sample_rate = sampleRate;
    frame.no_channels = channels;
    frame.no_samples = samples;
    frame.timecode = NDIlib_send_timecode_synthesize;
    frame.reference_level = 0;
    frame.p_data = reinterpret_cast<int16_t*>(data);

    jboolean sent = JNI_FALSE;
    {
        std::lock_guard<std::mutex> lock(g_audio_mutex);
        if (g_send_instance) {
            NDIlib_util_send_send_audio_interleaved_16s(g_send_instance, &frame);
            sent = JNI_TRUE;
        }
    }
    env->ReleaseByteArrayElements(pcm, data, JNI_ABORT);
    return sent;
}
