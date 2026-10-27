// AutoSpace - libretro frontend shim (r93).
//
// Ported from Notscape Mariner's retro_shim.cpp (Phase J), trimmed
// for the car screen: one JNI library hosts a libretro core (loaded
// by path at runtime - cores are separate .so files, downloaded and
// hash-verified by the app, never linked here), with environment /
// video / audio / input callbacks, an EGL/GLES3 presenter that
// letterboxes the frame to the core's aspect ratio, and an oboe
// output stream fed from a lock-free ring.
//
// Deliberately NOT carried over from Mariner: the DS dual-screen
// presenter and pointer device (no DS console here), the CRT
// chain and shader passes, display curvature and rounded corners,
// the portrait top inset, frame grabs, and fast-forward. The car
// screen is landscape and the playback screen wants a plain,
// legible picture; those looks are Mariner's, not this app's.
//
// Two deliberate deviations from Mariner's create path, both for
// disc games: a cue/m3u/chd loads by PATH ONLY (Mariner handed the
// core the descriptor file's bytes too, which leaves a disc core
// unable to resolve its tracks), while cartridge ROMs still load
// by bytes as Mariner did. And a failed create cleans up the
// dlopen handle instead of leaking it.

#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>
#include "libretro.h"

#define LOG_TAG "AutospaceRetro"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// ---------------------------------------------------------------- core

struct Core {
    void* handle = nullptr;
    decltype(&retro_set_environment) set_environment = nullptr;
    decltype(&retro_set_video_refresh) set_video_refresh = nullptr;
    decltype(&retro_set_audio_sample) set_audio_sample = nullptr;
    decltype(&retro_set_audio_sample_batch) set_audio_sample_batch = nullptr;
    decltype(&retro_set_input_poll) set_input_poll = nullptr;
    decltype(&retro_set_input_state) set_input_state = nullptr;
    decltype(&retro_set_controller_port_device) set_controller_port_device =
        nullptr;
    decltype(&retro_init) init = nullptr;
    decltype(&retro_deinit) deinit = nullptr;
    decltype(&retro_api_version) api_version = nullptr;
    decltype(&retro_get_system_info) get_system_info = nullptr;
    decltype(&retro_get_system_av_info) get_system_av_info = nullptr;
    decltype(&retro_reset) reset = nullptr;
    decltype(&retro_run) run = nullptr;
    decltype(&retro_load_game) load_game = nullptr;
    decltype(&retro_unload_game) unload_game = nullptr;
    decltype(&retro_get_memory_data) get_memory_data = nullptr;
    decltype(&retro_get_memory_size) get_memory_size = nullptr;
    decltype(&retro_serialize_size) serialize_size = nullptr;
    decltype(&retro_serialize) serialize = nullptr;
    decltype(&retro_unserialize) unserialize = nullptr;
    bool loaded = false;
};

Core g_core;
// Core-option values for this launch (key=value pairs handed in at
// create). GET_VARIABLE answers from here; an unknown key returns
// false and the core uses its own default.
std::map<std::string, std::string> g_coreOptions;
std::string g_systemDir;
std::string g_saveDir;
std::string g_sramPath;
retro_pixel_format g_pixelFormat = RETRO_PIXEL_FORMAT_XRGB8888;
double g_fps = 60.0;
int g_sampleRate = 48000;
// Display aspect from the core: SET_GEOMETRY / AV info carry
// aspect_ratio; 0 means "use the buffer shape".
std::atomic<float> g_aspect{0.f};

// Guards retro_run() against save/load: a serialize must never race
// a frame. Held for one frame at a time by the emu loop, and for the
// whole call by save/load.
std::mutex g_runMutex;
// Disc control: the callback structs the core hands us at init. Ext
// is preferred when the core asks for version >= 1; classic is the
// fallback. Stored, never owned: the core owns them.
static retro_disk_control_ext_callback g_diskExt{};
static bool g_diskExtSet = false;
static retro_disk_control_callback g_disk{};
static bool g_diskSet = false;
std::atomic<int32_t> g_inputMask{0};
// Analogue sticks: int16 axes, left-X, left-Y, right-X, right-Y.
// Written by nativeSetAnalog.
std::atomic<int32_t> g_analogLx{0};
std::atomic<int32_t> g_analogLy{0};
std::atomic<int32_t> g_analogRx{0};
std::atomic<int32_t> g_analogRy{0};
std::atomic<bool> g_emuRunning{false};
std::atomic<bool> g_shutdownRequested{false};
// Pause: the emu loop idles instead of running frames, and the
// audio callback plays silence.
std::atomic<bool> g_paused{false};
std::thread g_emuThread;

// ---------------------------------------------------------------- frame

std::mutex g_frameMutex;
std::vector<uint8_t> g_frameRgba;
unsigned g_frameW = 0, g_frameH = 0;
bool g_frameDirty = false;

// ---------------------------------------------------------------- audio

// Single-producer / single-consumer ring of stereo s16 frames
// between the core's audio batch callback (emu thread) and oboe's
// data callback (audio thread).
class AudioRing {
public:
    static constexpr size_t kCapacity = 16384;
    void push(const int16_t* data, size_t frames) {
        for (size_t i = 0; i < frames; i++) {
            size_t w = write_.load(std::memory_order_relaxed);
            size_t next = (w + 1) % kCapacity;
            if (next == read_.load(std::memory_order_acquire)) {
                return; // full: drop the rest of this batch
            }
            buffer_[w * 2] = data[i * 2];
            buffer_[w * 2 + 1] = data[i * 2 + 1];
            write_.store(next, std::memory_order_release);
        }
    }
    size_t pull(int16_t* out, size_t frames) {
        size_t got = 0;
        while (got < frames) {
            size_t r = read_.load(std::memory_order_relaxed);
            if (r == write_.load(std::memory_order_acquire)) break;
            out[got * 2] = buffer_[r * 2];
            out[got * 2 + 1] = buffer_[r * 2 + 1];
            read_.store((r + 1) % kCapacity, std::memory_order_release);
            got++;
        }
        return got;
    }
    void clear() {
        read_.store(0, std::memory_order_release);
        write_.store(0, std::memory_order_release);
    }
private:
    int16_t buffer_[kCapacity * 2];
    std::atomic<size_t> read_{0};
    std::atomic<size_t> write_{0};
};

AudioRing g_audioRing;

class AudioOut : public oboe::AudioStreamDataCallback {
public:
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream* stream, void* audioData,
            int32_t numFrames) override {
        auto* out = static_cast<int16_t*>(audioData);
        if (g_paused.load()) {
            // Drain the ring but play silence.
            g_audioRing.pull(out, static_cast<size_t>(numFrames));
            memset(out, 0, numFrames * 2 * sizeof(int16_t));
            return oboe::DataCallbackResult::Continue;
        }
        size_t got = g_audioRing.pull(out, static_cast<size_t>(numFrames));
        if (got < static_cast<size_t>(numFrames)) {
            memset(out + got * 2, 0,
                   (numFrames - got) * 2 * sizeof(int16_t));
        }
        return oboe::DataCallbackResult::Continue;
    }
    std::shared_ptr<oboe::AudioStream> stream;
};

AudioOut g_audioOut;
bool g_audioStarted = false;

bool audioStart() {
    if (g_audioStarted) return true;
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(g_sampleRate)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setDataCallback(&g_audioOut);
    oboe::Result result = builder.openStream(g_audioOut.stream);
    if (result != oboe::Result::OK || !g_audioOut.stream) {
        LOGE("oboe open failed: %s", oboe::convertToText(result));
        return false;
    }
    result = g_audioOut.stream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("oboe start failed: %s", oboe::convertToText(result));
        return false;
    }
    g_audioStarted = true;
    return true;
}

void audioStop() {
    if (g_audioOut.stream) {
        g_audioOut.stream->stop();
        g_audioOut.stream->close();
        g_audioOut.stream.reset();
    }
    g_audioStarted = false;
    g_audioRing.clear();
}

// ---------------------------------------------------------------- core callbacks

void coreLog(enum retro_log_level level, const char* fmt, ...) {
    char buf[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    int prio = ANDROID_LOG_INFO;
    if (level == RETRO_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == RETRO_LOG_WARN) prio = ANDROID_LOG_WARN;
    else if (level == RETRO_LOG_DEBUG) prio = ANDROID_LOG_DEBUG;
    __android_log_print(prio, LOG_TAG, "core: %s", buf);
}

bool environCb(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = g_systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = g_saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_ASSETS_DIRECTORY:
            *static_cast<const char**>(data) = g_systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            g_pixelFormat = *static_cast<const enum retro_pixel_format*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            auto* msg = static_cast<const struct retro_message*>(data);
            if (msg && msg->msg) LOGI("message: %s", msg->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: {
            auto* log = static_cast<struct retro_log_callback*>(data);
            log->log = &coreLog;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<struct retro_variable*>(data);
            var->value = nullptr;
            if (!var->key) return false;
            auto it = g_coreOptions.find(var->key);
            if (it == g_coreOptions.end()) return false;
            var->value = it->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            auto* geo = static_cast<const struct retro_game_geometry*>(data);
            if (geo && geo->aspect_ratio > 0.f) {
                g_aspect.store(geo->aspect_ratio);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            auto* info = static_cast<const struct retro_system_av_info*>(data);
            if (info) {
                g_fps = info->timing.fps > 0 ? info->timing.fps : 60.0;
                if (info->timing.sample_rate > 0) {
                    g_sampleRate = static_cast<int>(info->timing.sample_rate);
                }
                if (info->geometry.aspect_ratio > 0.f) {
                    g_aspect.store(info->geometry.aspect_ratio);
                }
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_SET_ROTATION:
            return true;
        case RETRO_ENVIRONMENT_GET_OVERSCAN:
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            g_shutdownRequested.store(true);
            return true;
        case RETRO_ENVIRONMENT_GET_DISK_CONTROL_INTERFACE_VERSION:
            *static_cast<unsigned*>(data) = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_EXT_INTERFACE: {
            auto* cb = static_cast<const struct retro_disk_control_ext_callback*>(data);
            if (!cb) return false;
            g_diskExt = *cb;
            g_diskExtSet = true;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_DISK_CONTROL_INTERFACE: {
            auto* cb = static_cast<const struct retro_disk_control_callback*>(data);
            if (!cb) return false;
            g_disk = *cb;
            g_diskSet = true;
            return true;
        }
        default:
            return false;
    }
}

void videoCb(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) return;
    std::lock_guard<std::mutex> lock(g_frameMutex);
    g_frameRgba.assign(static_cast<size_t>(width) * height * 4, 0);
    const auto* src = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; y++) {
        const uint8_t* row = src + y * pitch;
        uint8_t* dst = g_frameRgba.data() + static_cast<size_t>(y) * width * 4;
        for (unsigned x = 0; x < width; x++) {
            uint8_t r = 0, g = 0, b = 0;
            switch (g_pixelFormat) {
                case RETRO_PIXEL_FORMAT_XRGB8888: {
                    uint32_t px;
                    memcpy(&px, row + x * 4, 4);
                    r = static_cast<uint8_t>((px >> 16) & 0xFF);
                    g = static_cast<uint8_t>((px >> 8) & 0xFF);
                    b = static_cast<uint8_t>(px & 0xFF);
                    break;
                }
                case RETRO_PIXEL_FORMAT_RGB565: {
                    uint16_t px;
                    memcpy(&px, row + x * 2, 2);
                    r = static_cast<uint8_t>(((px >> 11) & 0x1F) * 255 / 31);
                    g = static_cast<uint8_t>(((px >> 5) & 0x3F) * 255 / 63);
                    b = static_cast<uint8_t>((px & 0x1F) * 255 / 31);
                    break;
                }
                case RETRO_PIXEL_FORMAT_0RGB1555: {
                    uint16_t px;
                    memcpy(&px, row + x * 2, 2);
                    r = static_cast<uint8_t>(((px >> 10) & 0x1F) * 255 / 31);
                    g = static_cast<uint8_t>(((px >> 5) & 0x1F) * 255 / 31);
                    b = static_cast<uint8_t>((px & 0x1F) * 255 / 31);
                    break;
                }
                default:
                    break;
            }
            dst[x * 4 + 0] = r;
            dst[x * 4 + 1] = g;
            dst[x * 4 + 2] = b;
            dst[x * 4 + 3] = 255;
        }
    }
    g_frameW = width;
    g_frameH = height;
    g_frameDirty = true;
}

void audioCb(int16_t left, int16_t right) {
    int16_t pair[2] = {left, right};
    g_audioRing.push(pair, 1);
}

size_t audioBatchCb(const int16_t* data, size_t frames) {
    g_audioRing.push(data, frames);
    return frames;
}

void inputPollCb() {}

int16_t inputStateCb(unsigned port, unsigned device, unsigned index,
                     unsigned id) {
    if (port == 0 && device == RETRO_DEVICE_JOYPAD) {
        return static_cast<int16_t>((g_inputMask.load() >> id) & 1);
    }
    // Analogue sticks: libretro.h device ANALOG = 5, index LEFT = 0
    // / RIGHT = 1, id X = 0 / Y = 1. Other ports/devices read zero.
    if (port == 0 && device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
            return static_cast<int16_t>(
                id == RETRO_DEVICE_ID_ANALOG_X ? g_analogLx.load()
                                               : g_analogLy.load());
        }
        if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
            return static_cast<int16_t>(
                id == RETRO_DEVICE_ID_ANALOG_X ? g_analogRx.load()
                                               : g_analogRy.load());
        }
    }
    return 0;
}

// ---------------------------------------------------------------- GL presenter

const char* kVertSrc =
    "#version 300 es\n"
    "in vec2 aPos;\n"
    "in vec2 aUv;\n"
    "out vec2 vUv;\n"
    "void main() {\n"
    "  vUv = aUv;\n"
    "  gl_Position = vec4(aPos, 0.0, 1.0);\n"
    "}\n";
const char* kFragSrc =
    "#version 300 es\n"
    "precision mediump float;\n"
    "in vec2 vUv;\n"
    "uniform sampler2D uTex;\n"
    "out vec4 oColor;\n"
    "void main() {\n"
    "  oColor = texture(uTex, vUv);\n"
    "}\n";

GLuint compileShader(GLenum type, const char* src) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &src, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

std::mutex g_windowMutex;
ANativeWindow* g_window = nullptr;
std::atomic<bool> g_renderRunning{false};
std::thread g_renderThread;

void renderLoop() {
    ANativeWindow* window;
    {
        std::lock_guard<std::mutex> lock(g_windowMutex);
        window = g_window;
    }
    if (!window) return;
    ANativeWindow_acquire(window);
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, nullptr, nullptr);
    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8, EGL_NONE
    };
    EGLConfig config;
    EGLint numConfigs = 0;
    eglChooseConfig(display, configAttribs, &config, 1, &numConfigs);
    EGLint format;
    eglGetConfigAttrib(display, config, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(window, 0, 0, format);
    const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
    EGLSurface surface = eglCreateWindowSurface(display, config, window, nullptr);
    eglMakeCurrent(display, surface, surface, context);

    GLuint vs = compileShader(GL_VERTEX_SHADER, kVertSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, kFragSrc);
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint aPos = glGetAttribLocation(program, "aPos");
    GLint aUv = glGetAttribLocation(program, "aUv");
    glUseProgram(program);
    glUniform1i(glGetUniformLocation(program, "uTex"), 0);
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glClearColor(0.f, 0.f, 0.f, 1.f);

    unsigned texW = 0, texH = 0;
    std::vector<uint8_t> upload;
    unsigned uploadW = 0, uploadH = 0;
    bool haveFrame = false;

    const float uvs[8] = {0.f, 1.f, 1.f, 1.f, 0.f, 0.f, 1.f, 0.f};
    while (g_renderRunning.load()) {
        EGLint winW = 0, winH = 0;
        eglQuerySurface(display, surface, EGL_WIDTH, &winW);
        eglQuerySurface(display, surface, EGL_HEIGHT, &winH);
        {
            std::lock_guard<std::mutex> lock(g_frameMutex);
            if (g_frameDirty && g_frameW > 0 && g_frameH > 0) {
                upload = g_frameRgba;
                uploadW = g_frameW;
                uploadH = g_frameH;
                g_frameDirty = false;
                haveFrame = true;
            }
        }
        if (haveFrame && uploadW > 0) {
            glBindTexture(GL_TEXTURE_2D, tex);
            if (uploadW != texW || uploadH != texH) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, uploadW, uploadH, 0,
                             GL_RGBA, GL_UNSIGNED_BYTE, upload.data());
                texW = uploadW;
                texH = uploadH;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, uploadW, uploadH,
                                GL_RGBA, GL_UNSIGNED_BYTE, upload.data());
            }
        }
        // A surface swap (the SurfaceView can replace its window
        // mid-session): follow it, so the presenter never draws for
        // a dead size. Only a successfully created surface swaps in;
        // a failure retries on the next frame. A window taken away
        // entirely ends the loop; the Kotlin wrapper restarts it
        // when a new surface arrives while the game is running.
        {
            std::lock_guard<std::mutex> lock(g_windowMutex);
            if (g_window == nullptr) {
                break;
            }
            if (g_window != window) {
                ANativeWindow* next = g_window;
                ANativeWindow_setBuffersGeometry(next, 0, 0, format);
                EGLSurface nextSurface = eglCreateWindowSurface(
                    display, config, next, nullptr);
                if (nextSurface != EGL_NO_SURFACE) {
                    eglMakeCurrent(display, EGL_NO_SURFACE,
                                   EGL_NO_SURFACE, EGL_NO_CONTEXT);
                    eglDestroySurface(display, surface);
                    ANativeWindow_release(window);
                    window = next;
                    ANativeWindow_acquire(window);
                    surface = nextSurface;
                    eglMakeCurrent(display, surface, surface, context);
                }
            }
        }
        glViewport(0, 0, winW, winH);
        glClear(GL_COLOR_BUFFER_BIT);
        if (haveFrame && texW > 0 && winW > 0 && winH > 0) {
            // Letterbox to the core's display aspect (or the buffer
            // shape when the core reports none), centred. A 0 aspect
            // falls back to the buffer shape, the pre-aspect
            // behaviour; oversampled buffers (NTSC filters) must
            // never stretch the picture super-wide.
            float dispAspect = g_aspect.load();
            if (dispAspect <= 0.f) {
                dispAspect = static_cast<float>(texW) /
                    static_cast<float>(texH);
            }
            float dispH = std::min(static_cast<float>(winH),
                static_cast<float>(winW) / dispAspect);
            float dispW = dispH * dispAspect;
            if (dispW > static_cast<float>(winW)) {
                dispW = static_cast<float>(winW);
                dispH = dispW / dispAspect;
            }
            float sx = dispW / static_cast<float>(winW);
            float sy = dispH / static_cast<float>(winH);
            // The flat quad's corners: v = 1 at the BOTTOM, so the
            // bottom vertices carry v = 1 (the strip's uv mapping,
            // verified against Mariner's quad, not its comment).
            const float pos[8] = {
                -sx, -sy, sx, -sy, -sx, sy, sx, sy
            };
            glVertexAttribPointer(aPos, 2, GL_FLOAT, GL_FALSE, 0, pos);
            glEnableVertexAttribArray(aPos);
            glVertexAttribPointer(aUv, 2, GL_FLOAT, GL_FALSE, 0, uvs);
            glEnableVertexAttribArray(aUv);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        }
        eglSwapBuffers(display, surface);
        std::this_thread::sleep_for(std::chrono::milliseconds(3));
    }
    glDeleteTextures(1, &tex);
    glDeleteProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(display, surface);
    eglDestroyContext(display, context);
    eglTerminate(display);
    ANativeWindow_release(window);
}

// ---------------------------------------------------------------- emu loop + SRAM

void saveSram() {
    if (!g_core.loaded || g_sramPath.empty()) return;
    void* data = g_core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = g_core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return;
    FILE* f = fopen(g_sramPath.c_str(), "wb");
    if (!f) return;
    fwrite(data, 1, size, f);
    fclose(f);
}

void loadSram() {
    if (!g_core.loaded || g_sramPath.empty()) return;
    void* data = g_core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    size_t size = g_core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!data || size == 0) return;
    FILE* f = fopen(g_sramPath.c_str(), "rb");
    if (!f) return;
    fread(data, 1, size, f);
    fclose(f);
}

void emuLoop() {
    using namespace std::chrono;
    while (g_emuRunning.load()) {
        if (g_shutdownRequested.load()) {
            g_emuRunning.store(false);
            break;
        }
        if (g_paused.load()) {
            std::this_thread::sleep_for(std::chrono::milliseconds(16));
            continue;
        }
        auto start = steady_clock::now();
        {
            std::lock_guard<std::mutex> lock(g_runMutex);
            g_core.run();
        }
        double targetMs = 1000.0 / (g_fps > 0 ? g_fps : 60.0);
        auto elapsed = duration_cast<microseconds>(steady_clock::now() - start).count() / 1000.0;
        if (elapsed < targetMs) {
            std::this_thread::sleep_for(duration<double, std::milli>(targetMs - elapsed));
        }
    }
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

template <typename T>
bool loadSym(void* handle, const char* name, T& out) {
    out = reinterpret_cast<T>(dlsym(handle, name));
    return out != nullptr;
}

void cleanupCore() {
    if (g_core.loaded && g_core.unload_game) {
        g_core.unload_game();
    }
    g_core.loaded = false;
    if (g_core.handle) {
        if (g_core.deinit) g_core.deinit();
        dlclose(g_core.handle);
        g_core.handle = nullptr;
    }
    g_diskExtSet = false;
    g_diskSet = false;
}

bool hasDiscExtension(const std::string& path) {
    size_t dot = path.find_last_of('.');
    if (dot == std::string::npos) return false;
    std::string ext = path.substr(dot + 1);
    for (auto& c : ext) c = static_cast<char>(tolower(c));
    return ext == "cue" || ext == "m3u" || ext == "chd";
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_autospace_car_RetroNative_nativeCreate(
        JNIEnv* env, jclass, jstring corePath, jstring romPath,
        jstring systemDir, jstring saveDir, jobjectArray options) {
    // A second create first tears down the previous core, so a
    // re-launch never leaks a handle or runs two cores at once.
    cleanupCore();
    std::string core = jstr(env, corePath);
    std::string rom = jstr(env, romPath);
    g_systemDir = jstr(env, systemDir);
    g_saveDir = jstr(env, saveDir);
    g_coreOptions.clear();
    g_paused.store(false);
    g_shutdownRequested.store(false);
    if (options) {
        jsize count = env->GetArrayLength(options);
        for (jsize i = 0; i < count; i++) {
            auto* item = static_cast<jstring>(
                env->GetObjectArrayElement(options, i));
            if (!item) continue;
            std::string pair = jstr(env, item);
            size_t eq = pair.find('=');
            if (eq != std::string::npos) {
                g_coreOptions[pair.substr(0, eq)] =
                    pair.substr(eq + 1);
            }
            env->DeleteLocalRef(item);
        }
    }

    void* handle = dlopen(core.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        std::string err = std::string("dlopen failed: ") + dlerror();
        return env->NewStringUTF(err.c_str());
    }
    g_core.handle = handle;
    bool ok = true;
    ok = ok && loadSym(handle, "retro_set_environment", g_core.set_environment);
    ok = ok && loadSym(handle, "retro_set_video_refresh", g_core.set_video_refresh);
    ok = ok && loadSym(handle, "retro_set_audio_sample", g_core.set_audio_sample);
    ok = ok && loadSym(handle, "retro_set_audio_sample_batch", g_core.set_audio_sample_batch);
    ok = ok && loadSym(handle, "retro_set_input_poll", g_core.set_input_poll);
    ok = ok && loadSym(handle, "retro_set_input_state", g_core.set_input_state);
    // Controller device: optional — a core without it simply keeps
    // its default device.
    loadSym(handle, "retro_set_controller_port_device",
            g_core.set_controller_port_device);
    ok = ok && loadSym(handle, "retro_init", g_core.init);
    ok = ok && loadSym(handle, "retro_deinit", g_core.deinit);
    ok = ok && loadSym(handle, "retro_api_version", g_core.api_version);
    ok = ok && loadSym(handle, "retro_get_system_info", g_core.get_system_info);
    ok = ok && loadSym(handle, "retro_get_system_av_info", g_core.get_system_av_info);
    ok = ok && loadSym(handle, "retro_reset", g_core.reset);
    ok = ok && loadSym(handle, "retro_run", g_core.run);
    ok = ok && loadSym(handle, "retro_load_game", g_core.load_game);
    ok = ok && loadSym(handle, "retro_unload_game", g_core.unload_game);
    ok = ok && loadSym(handle, "retro_get_memory_data", g_core.get_memory_data);
    ok = ok && loadSym(handle, "retro_get_memory_size", g_core.get_memory_size);
    ok = ok && loadSym(handle, "retro_serialize_size", g_core.serialize_size);
    ok = ok && loadSym(handle, "retro_serialize", g_core.serialize);
    ok = ok && loadSym(handle, "retro_unserialize", g_core.unserialize);
    if (!ok) {
        cleanupCore();
        return env->NewStringUTF("core is missing libretro symbols");
    }
    if (g_core.api_version() != RETRO_API_VERSION) {
        cleanupCore();
        return env->NewStringUTF("core API version mismatch");
    }
    g_core.set_environment(&environCb);
    g_core.set_video_refresh(&videoCb);
    g_core.set_audio_sample(&audioCb);
    g_core.set_audio_sample_batch(&audioBatchCb);
    g_core.set_input_poll(&inputPollCb);
    g_core.set_input_state(&inputStateCb);
    g_core.init();

    struct retro_game_info info {};
    info.path = rom.c_str();
    info.meta = nullptr;
    std::vector<uint8_t> romData;
    if (!hasDiscExtension(rom)) {
        // Cartridge ROMs load by bytes, as Mariner did. Disc
        // descriptors and images load by path only: handing a disc
        // core the descriptor's bytes leaves it unable to resolve
        // its tracks, and a disc image must never be buffered whole.
        FILE* f = fopen(rom.c_str(), "rb");
        if (!f) {
            cleanupCore();
            return env->NewStringUTF("ROM file could not be opened");
        }
        fseek(f, 0, SEEK_END);
        long size = ftell(f);
        fseek(f, 0, SEEK_SET);
        if (size <= 0) {
            fclose(f);
            cleanupCore();
            return env->NewStringUTF("ROM file is empty");
        }
        romData.assign(static_cast<size_t>(size), 0);
        size_t read = fread(romData.data(), 1, romData.size(), f);
        fclose(f);
        if (read != romData.size()) {
            cleanupCore();
            return env->NewStringUTF("ROM file could not be read");
        }
        info.data = romData.data();
        info.size = romData.size();
    }
    if (!g_core.load_game(&info)) {
        cleanupCore();
        return env->NewStringUTF("the core refused this ROM");
    }
    g_core.loaded = true;

    struct retro_system_av_info avInfo {};
    g_core.get_system_av_info(&avInfo);
    if (avInfo.timing.fps > 0) g_fps = avInfo.timing.fps;
    if (avInfo.timing.sample_rate > 0) {
        g_sampleRate = static_cast<int>(avInfo.timing.sample_rate);
    }
    if (avInfo.geometry.aspect_ratio > 0.f) {
        g_aspect.store(avInfo.geometry.aspect_ratio);
    }

    // Battery save next to the ROM's name, in the save dir.
    std::string base = rom;
    size_t slash = base.find_last_of('/');
    if (slash != std::string::npos) base = base.substr(slash + 1);
    g_sramPath = g_saveDir + "/" + base + ".srm";
    loadSram();
    return nullptr;
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeSetSurface(
        JNIEnv* env, jclass, jobject surface) {
    std::lock_guard<std::mutex> lock(g_windowMutex);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
    if (surface) {
        g_window = ANativeWindow_fromSurface(env, surface);
    }
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeStart(JNIEnv*, jclass) {
    if (!g_core.loaded) return;
    audioStart();
    if (!g_emuRunning.load()) {
        g_shutdownRequested.store(false);
        g_emuRunning.store(true);
        g_emuThread = std::thread(&emuLoop);
    }
    if (!g_renderRunning.load()) {
        std::lock_guard<std::mutex> lock(g_windowMutex);
        if (g_window != nullptr) {
            g_renderRunning.store(true);
            g_renderThread = std::thread(&renderLoop);
        }
    }
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeStartRender(JNIEnv*, jclass) {
    // A surface that arrives after start (the SurfaceView is
    // created after the game launch kicks off): start the render
    // thread now that a window exists. A no-op when the render
    // thread is already running or the game never started.
    if (!g_emuRunning.load() || g_renderRunning.load()) return;
    {
        std::lock_guard<std::mutex> lock(g_windowMutex);
        if (g_window == nullptr) return;
    }
    g_renderRunning.store(true);
    g_renderThread = std::thread(&renderLoop);
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeStop(JNIEnv*, jclass) {
    g_emuRunning.store(false);
    if (g_emuThread.joinable()) g_emuThread.join();
    g_renderRunning.store(false);
    if (g_renderThread.joinable()) g_renderThread.join();
    audioStop();
    saveSram();
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeSetPaused(
        JNIEnv*, jclass, jboolean paused) {
    g_paused.store(paused != 0);
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeReset(
        JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_runMutex);
    if (g_core.loaded && g_core.reset) g_core.reset();
}

// Disc control: count / current index, and the atomic swap. Swap
// runs the whole eject -> set -> uneject sequence under
// g_runMutex, so a half-swapped state is impossible if the UI
// thread is interrupted mid-sequence. On a set failure the
// original disc is put back on a best-effort basis so the game is
// not left tray-open and disc-less; the call still reports
// failure so the UI stays paused at the picker.
JNIEXPORT jint JNICALL
Java_app_autospace_car_RetroNative_nativeDiskGetCount(
        JNIEnv*, jclass) {
    if (!g_core.loaded) return 0;
    if (g_diskExtSet && g_diskExt.get_num_images) return static_cast<jint>(g_diskExt.get_num_images());
    if (g_diskSet && g_disk.get_num_images) return static_cast<jint>(g_disk.get_num_images());
    return 0;
}

JNIEXPORT jint JNICALL
Java_app_autospace_car_RetroNative_nativeDiskGetIndex(
        JNIEnv*, jclass) {
    if (!g_core.loaded) return -1;
    if (g_diskExtSet && g_diskExt.get_image_index) return static_cast<jint>(g_diskExt.get_image_index());
    if (g_diskSet && g_disk.get_image_index) return static_cast<jint>(g_disk.get_image_index());
    return -1;
}

JNIEXPORT jboolean JNICALL
Java_app_autospace_car_RetroNative_nativeDiskSwap(
        JNIEnv*, jclass, jint index) {
    if (!g_core.loaded || index < 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_runMutex);
    auto setEject = [&](bool ejected) -> bool {
        if (g_diskExtSet && g_diskExt.set_eject_state) return g_diskExt.set_eject_state(ejected);
        if (g_diskSet && g_disk.set_eject_state) return g_disk.set_eject_state(ejected);
        return false;
    };
    auto setIndex = [&](unsigned i) -> bool {
        if (g_diskExtSet && g_diskExt.set_image_index) return g_diskExt.set_image_index(i);
        if (g_diskSet && g_disk.set_image_index) return g_disk.set_image_index(i);
        return false;
    };
    auto getIndex = [&]() -> unsigned {
        if (g_diskExtSet && g_diskExt.get_image_index) return g_diskExt.get_image_index();
        if (g_diskSet && g_disk.get_image_index) return g_disk.get_image_index();
        return 0;
    };
    unsigned orig = getIndex();
    if (!setEject(true)) return JNI_FALSE;
    if (!setIndex(static_cast<unsigned>(index))) {
        setIndex(orig);
        setEject(false);
        return JNI_FALSE;
    }
    if (!setEject(false)) return JNI_FALSE;
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_app_autospace_car_RetroNative_nativeSaveState(
        JNIEnv* env, jclass, jstring path) {
    std::string p = jstr(env, path);
    std::lock_guard<std::mutex> lock(g_runMutex);
    if (!g_core.loaded) return JNI_FALSE;
    size_t size = g_core.serialize_size();
    if (size == 0) return JNI_FALSE;
    std::vector<uint8_t> buf(size);
    if (!g_core.serialize(buf.data(), size)) return JNI_FALSE;
    FILE* f = fopen(p.c_str(), "wb");
    if (!f) return JNI_FALSE;
    size_t written = fwrite(buf.data(), 1, buf.size(), f);
    fclose(f);
    return written == buf.size() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_app_autospace_car_RetroNative_nativeLoadState(
        JNIEnv* env, jclass, jstring path) {
    std::string p = jstr(env, path);
    FILE* f = fopen(p.c_str(), "rb");
    if (!f) return JNI_FALSE;
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (size <= 0) { fclose(f); return JNI_FALSE; }
    std::vector<uint8_t> buf(static_cast<size_t>(size));
    size_t read = fread(buf.data(), 1, buf.size(), f);
    fclose(f);
    if (read != buf.size()) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_runMutex);
    if (!g_core.loaded) return JNI_FALSE;
    return g_core.unserialize(buf.data(), buf.size())
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeSetInput(
        JNIEnv*, jclass, jint mask) {
    g_inputMask.store(mask);
}

// Analogue sticks: int16 axes, -32767..32767, negative Y = up,
// in left-X, left-Y, right-X, right-Y order.
JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeSetAnalog(
        JNIEnv*, jclass, jint lx, jint ly, jint rx, jint ry) {
    g_analogLx.store(lx);
    g_analogLy.store(ly);
    g_analogRx.store(rx);
    g_analogRy.store(ry);
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeSetControllerDevice(
        JNIEnv*, jclass, jint device) {
    if (g_core.set_controller_port_device) {
        g_core.set_controller_port_device(0, (unsigned)device);
    }
}

JNIEXPORT void JNICALL
Java_app_autospace_car_RetroNative_nativeDestroy(JNIEnv*, jclass) {
    cleanupCore();
    std::lock_guard<std::mutex> lock(g_windowMutex);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = nullptr;
    }
}

} // extern "C"
