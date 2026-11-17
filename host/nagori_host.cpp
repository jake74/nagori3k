// nagori3k host layer. See nagori_host.h.
#include "nagori_host.h"
#include "../core/src/libretro.h"

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <dlfcn.h>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#if defined(__APPLE__)
#include <OpenGL/gl.h>
#include <OpenGL/glext.h>
#else
#include <GLES3/gl3.h>
#endif

namespace {

struct Core {
    void *handle = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    void (*get_system_av_info)(retro_system_av_info *) = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*run)() = nullptr;
    bool (*load_game)(const retro_game_info *) = nullptr;
    void (*unload_game)() = nullptr;
} core;

std::string systemDir, saveDir;
void (*logSink)(const char *) = nullptr;

std::mutex optionsMutex;
std::map<std::string, std::string> options;
std::atomic<bool> optionsChanged{false};
std::string optionValueScratch; // the core reads the pointer straight away

retro_hw_render_callback hwRender = {};
bool bGameLoaded = false;
bool bContextReady = false;
GLuint fbo = 0, fboTexture = 0, fboDepth = 0;
unsigned textureWidth = 720, textureHeight = 480;
unsigned frameWidth = 720, frameHeight = 480;
double sampleRate = 32000.0;

// Audio: one writer (the frame thread), one reader (the audio thread).
constexpr size_t kRingFrames = 16384; // about half a second
int16_t ring[kRingFrames * 2];
std::atomic<size_t> ringWrite{0}, ringRead{0};

// The game's frame rate: the core's count of frames drawn, sampled as the
// frontend steps it.
unsigned (*gameFrameCount)() = nullptr;
unsigned fpsLastCount = 0;
double fpsLastTime = 0.0;
std::atomic<float> gameFps{0.0f};

double Seconds()
{
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + t.tv_nsec / 1e9;
}

std::atomic<bool> buttons[NAGORI_BUTTON_COUNT];
std::atomic<float> stickX{0.0f}, stickY{0.0f};
std::atomic<float> spinnerOwed{0.0f};

void Log(const char *fmt, ...)
{
    char line[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    if (logSink) logSink(line);
    else fputs(line, stderr);
}

void CoreLog(enum retro_log_level, const char *fmt, ...)
{
    char line[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    if (logSink) logSink(line);
    else fputs(line, stderr);
}

uintptr_t CurrentFramebuffer() { return fbo; }
void *ProcAddress(const char *symbol) { return dlsym(RTLD_DEFAULT, symbol); }

bool Environment(unsigned cmd, void *data)
{
    switch (cmd) {
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        ((retro_log_callback *)data)->log = CoreLog;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        *(const char **)data = systemDir.c_str();
        return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = saveDir.c_str();
        return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_VARIABLES: // the frontend knows the options from docs/options.md
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        retro_variable *var = (retro_variable *)data;
        std::lock_guard<std::mutex> lock(optionsMutex);
        const auto it = options.find(var->key ? var->key : "");
        if (it == options.end()) { var->value = nullptr; return false; }
        optionValueScratch = it->second;
        var->value = optionValueScratch.c_str();
        return true;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = optionsChanged.exchange(false);
        return true;
    case RETRO_ENVIRONMENT_SET_HW_RENDER: {
        retro_hw_render_callback *cb = (retro_hw_render_callback *)data;
        cb->get_current_framebuffer = CurrentFramebuffer;
        cb->get_proc_address = ProcAddress;
        hwRender = *cb;
        return true;
    }
    default:
        return false;
    }
}

void VideoRefresh(const void *, unsigned width, unsigned height, size_t)
{
    if (width && height) { frameWidth = width; frameHeight = height; }
}

size_t AudioBatch(const int16_t *data, size_t frames)
{
    size_t w = ringWrite.load(std::memory_order_relaxed);
    const size_t r = ringRead.load(std::memory_order_acquire);
    for (size_t i = 0; i < frames; i++) {
        const size_t next = (w + 1) % kRingFrames;
        if (next == r) break; // full: drop the rest rather than stall the emulation
        ring[w * 2] = data[i * 2];
        ring[w * 2 + 1] = data[i * 2 + 1];
        w = next;
    }
    ringWrite.store(w, std::memory_order_release);
    return frames;
}
void AudioSample(int16_t left, int16_t right) { const int16_t s[2] = { left, right }; AudioBatch(s, 1); }

void InputPoll() {}

int16_t InputState(unsigned port, unsigned device, unsigned index, unsigned id)
{
    if (port != 0) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        static const int map[16] = {
            /* B */ NAGORI_B, /* Y */ NAGORI_C_LEFT, /* SELECT */ NAGORI_NUON, /* START */ NAGORI_START,
            /* UP */ NAGORI_UP, /* DOWN */ NAGORI_DOWN, /* LEFT */ NAGORI_LEFT, /* RIGHT */ NAGORI_RIGHT,
            /* A */ NAGORI_A, /* X */ NAGORI_C_UP, /* L */ NAGORI_L, /* R */ NAGORI_R,
            /* L2 */ NAGORI_C_DOWN, /* R2 */ NAGORI_C_RIGHT, -1, -1 };
        return (id < 16 && map[id] >= 0 && buttons[map[id]].load()) ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG && index == 0) {
        const float v = (id == 0) ? stickX.load() : stickY.load();
        return (int16_t)(v * 32767.0f);
    }
    if (device == RETRO_DEVICE_MOUSE && id == 0) {
        // Whole counts now, the fraction next time.
        float owed = spinnerOwed.load();
        int whole;
        do { whole = (int)owed; } while (!spinnerOwed.compare_exchange_weak(owed, owed - (float)whole));
        return (int16_t)whole;
    }
    return 0;
}

template <typename T> bool Bind(T &fn, const char *name)
{
    fn = (T)dlsym(core.handle, name);
    if (!fn) Log("nagori: the core has no %s\n", name);
    return fn != nullptr;
}

void DestroyFramebuffer()
{
    if (fbo) glDeleteFramebuffers(1, &fbo);
    if (fboTexture) glDeleteTextures(1, &fboTexture);
    if (fboDepth) glDeleteRenderbuffers(1, &fboDepth);
    fbo = fboTexture = fboDepth = 0;
}

void CreateFramebuffer()
{
    DestroyFramebuffer();
    glGenTextures(1, &fboTexture);
    glBindTexture(GL_TEXTURE_2D, fboTexture);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, (GLsizei)textureWidth, (GLsizei)textureHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, fboTexture, 0);
    if (hwRender.depth) {
        glGenRenderbuffers(1, &fboDepth);
        glBindRenderbuffer(GL_RENDERBUFFER, fboDepth);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, (GLsizei)textureWidth, (GLsizei)textureHeight);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, fboDepth);
        if (hwRender.stencil)
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_STENCIL_ATTACHMENT, GL_RENDERBUFFER, fboDepth);
    }
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
        Log("nagori: the frame buffer is incomplete\n");
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

} // namespace

extern "C" {

void nagori_set_log(void (*log)(const char *line)) { logSink = log; }

bool nagori_init(const NagoriConfig *config)
{
    if (core.handle) return true;
    systemDir = config->system_dir ? config->system_dir : "";
    saveDir = config->save_dir ? config->save_dir : "";
    core.handle = dlopen(config->core_path, RTLD_NOW | RTLD_LOCAL);
    if (!core.handle) { Log("nagori: cannot load the core: %s\n", dlerror()); return false; }
    bool ok = true;
    ok &= Bind(core.init, "retro_init");
    ok &= Bind(core.deinit, "retro_deinit");
    ok &= Bind(core.get_system_av_info, "retro_get_system_av_info");
    ok &= Bind(core.set_environment, "retro_set_environment");
    ok &= Bind(core.set_video_refresh, "retro_set_video_refresh");
    ok &= Bind(core.set_audio_sample, "retro_set_audio_sample");
    ok &= Bind(core.set_audio_sample_batch, "retro_set_audio_sample_batch");
    ok &= Bind(core.set_input_poll, "retro_set_input_poll");
    ok &= Bind(core.set_input_state, "retro_set_input_state");
    ok &= Bind(core.run, "retro_run");
    ok &= Bind(core.load_game, "retro_load_game");
    ok &= Bind(core.unload_game, "retro_unload_game");
    if (!ok) { dlclose(core.handle); core = Core(); return false; }

    gameFrameCount = (unsigned (*)())dlsym(core.handle, "nuance_game_frame_count"); // optional

    core.set_environment(Environment);
    core.set_video_refresh(VideoRefresh);
    core.set_audio_sample(AudioSample);
    core.set_audio_sample_batch(AudioBatch);
    core.set_input_poll(InputPoll);
    core.set_input_state(InputState);
    core.init();
    return true;
}

void nagori_set_option(const char *key, const char *value)
{
    std::lock_guard<std::mutex> lock(optionsMutex);
    options[key] = value;
    optionsChanged.store(true);
}

bool nagori_load_game(const char *path)
{
    if (!core.handle || bGameLoaded) return false;
    retro_game_info game = {};
    game.path = path;
    if (!core.load_game(&game)) { Log("nagori: the core could not load %s\n", path); return false; }
    bGameLoaded = true;
    retro_system_av_info av = {};
    core.get_system_av_info(&av);
    if (av.timing.sample_rate > 0) sampleRate = av.timing.sample_rate;
    if (av.geometry.max_width && av.geometry.max_height) {
        textureWidth = frameWidth = av.geometry.max_width;
        textureHeight = frameHeight = av.geometry.max_height;
    }
    ringRead.store(0); ringWrite.store(0);
    if (bContextReady) nagori_context_ready();
    return true;
}

void nagori_context_ready(void)
{
    bContextReady = true;
    if (!bGameLoaded) return;
    CreateFramebuffer();
    if (hwRender.context_reset) hwRender.context_reset();
}

void nagori_run_frame(void)
{
    if (!bGameLoaded || !fbo) return;
    core.run();
    if (gameFrameCount) {
        const double now = Seconds();
        const unsigned count = gameFrameCount();
        if (fpsLastTime == 0.0 || count < fpsLastCount) { fpsLastTime = now; fpsLastCount = count; }
        else if (now - fpsLastTime >= 0.5) {
            gameFps.store((float)((count - fpsLastCount) / (now - fpsLastTime)));
            fpsLastTime = now; fpsLastCount = count;
        }
    }
    // Leave the context as a frontend expects to find it.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glUseProgram(0);
    glActiveTexture(GL_TEXTURE0);
}

unsigned nagori_frame_texture(unsigned *width, unsigned *height, unsigned *texture_width, unsigned *texture_height)
{
    if (width) *width = frameWidth;
    if (height) *height = frameHeight;
    if (texture_width) *texture_width = textureWidth;
    if (texture_height) *texture_height = textureHeight;
    return fboTexture;
}

unsigned nagori_frame_buffer(void) { return fbo; }

float nagori_game_fps(void) { return gameFps.load(); }

// The counter is drawn with nothing but scissored clears, so it needs no
// shader, texture or font, works the same on OpenGL 2 and OpenGL ES 3, and
// does not care what drawing state the core left behind.
void nagori_draw_fps(int viewWidth, int viewHeight)
{
    // Digits 0-9 on a 3 by 5 grid, top row first, three bits a row.
    static const unsigned short glyphs[10] = {
        075557, 026222, 071747, 071717, 055711, 074717, 074757, 071111, 075757, 075717 };
    int value = (int)(gameFps.load() + 0.5f);
    if (value < 0) value = 0;
    if (value > 999) value = 999;
    char text[4];
    const int length = snprintf(text, sizeof(text), "%d", value);

    const int px = viewHeight / 180 > 2 ? viewHeight / 180 : 2; // one font pixel, in screen pixels
    const int pad = 2 * px;
    const int boxWidth = length * 3 * px + (length - 1) * px + 2 * pad;
    const int boxHeight = 5 * px + 2 * pad;
    const int boxX = viewWidth - boxWidth, boxY = viewHeight - boxHeight; // GL's origin is bottom left

    glEnable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glScissor(boxX, boxY, boxWidth, boxHeight);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glClearColor(1, 1, 1, 1);
    for (int i = 0; i < length; i++) {
        const unsigned short glyph = glyphs[text[i] - '0'];
        for (int row = 0; row < 5; row++)
            for (int col = 0; col < 3; col++)
                if (glyph & (1 << ((4 - row) * 3 + (2 - col)))) {
                    glScissor(boxX + pad + i * 4 * px + col * px, boxY + pad + (4 - row) * px, px, px);
                    glClear(GL_COLOR_BUFFER_BIT);
                }
    }
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0, 0, 0, 1);
}

size_t nagori_read_audio(int16_t *out, size_t frames)
{
    size_t r = ringRead.load(std::memory_order_relaxed);
    const size_t w = ringWrite.load(std::memory_order_acquire);
    size_t n = 0;
    while (n < frames && r != w) {
        out[n * 2] = ring[r * 2];
        out[n * 2 + 1] = ring[r * 2 + 1];
        r = (r + 1) % kRingFrames;
        n++;
    }
    ringRead.store(r, std::memory_order_release);
    return n;
}

double nagori_sample_rate(void) { return sampleRate; }

void nagori_set_button(NagoriButton button, bool down)
{
    if (button >= 0 && button < NAGORI_BUTTON_COUNT) buttons[button].store(down);
}
void nagori_set_stick(float x, float y) { stickX.store(x); stickY.store(y); }
void nagori_add_spinner(float counts)
{
    // Added on the app's input thread while the emulation thread takes whole
    // counts away: a plain load and store here could lose one or the other.
    float owed = spinnerOwed.load();
    while (!spinnerOwed.compare_exchange_weak(owed, owed + counts)) {}
}

void nagori_unload_game(void)
{
    if (!bGameLoaded) return;
    core.unload_game();
    bGameLoaded = false;
    DestroyFramebuffer();
}

void nagori_shutdown(void)
{
    nagori_unload_game();
    // The library is deliberately not closed. With NUANCE_PERF set the core
    // runs a detached sampling thread, and unloading the code from under it
    // crashed the process on the way out (seen in nagori-cli, 2026-11-16).
    // Shutdown happens once, as the process ends, so nothing is lost.
    if (core.handle) { core.deinit(); core = Core(); }
}

}
