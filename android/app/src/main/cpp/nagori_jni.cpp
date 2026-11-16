// The Android side of the host layer: JNI entry points for the Kotlin
// activity, the copy of the game's picture to the screen, frame timing, and
// sound through AAudio.
#include <jni.h>
#include <aaudio/AAudio.h>
#include <android/log.h>
#include <GLES3/gl3.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <string>
#include "../../../../../host/nagori_host.h"

namespace {

void LogLine(const char *line) { __android_log_write(ANDROID_LOG_INFO, "nagori3k", line); }

std::string Str(JNIEnv *env, jstring s)
{
    if (!s) return std::string();
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

AAudioStream *audioStream = nullptr;

aaudio_data_callback_result_t AudioCallback(AAudioStream *, void *, void *audioData, int32_t numFrames)
{
    int16_t *out = (int16_t *)audioData;
    const size_t got = nagori_read_audio(out, (size_t)numFrames);
    if (got < (size_t)numFrames) memset(out + got * 2, 0, ((size_t)numFrames - got) * 2 * sizeof(int16_t));
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void StopAudio()
{
    if (!audioStream) return;
    AAudioStream_requestStop(audioStream);
    AAudioStream_close(audioStream);
    audioStream = nullptr;
}

void StartAudio()
{
    if (audioStream) return;
    AAudioStreamBuilder *builder = nullptr;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) return;
    AAudioStreamBuilder_setSampleRate(builder, (int32_t)nagori_sample_rate());
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, AudioCallback, nullptr);
    if (AAudioStreamBuilder_openStream(builder, &audioStream) == AAUDIO_OK) {
        char line[160];
        snprintf(line, sizeof(line), "audio: %d Hz, %d channels, burst %d frames", AAudioStream_getSampleRate(audioStream),
            AAudioStream_getChannelCount(audioStream), AAudioStream_getFramesPerBurst(audioStream));
        LogLine(line);
        AAudioStream_requestStart(audioStream);
    } else {
        audioStream = nullptr;
        LogLine("audio: could not open an output stream");
    }
    AAudioStreamBuilder_delete(builder);
}

double Now()
{
    timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + t.tv_nsec / 1e9;
}

double lastStep = 0.0;
unsigned long framesStepped = 0, framesDrawn = 0;
std::atomic<bool> stretch{false};
std::atomic<bool> showFps{false};
std::atomic<bool> paused{false};

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_gg_dekiru_nagori3k_Native_init(JNIEnv *env, jclass, jstring corePath, jstring systemDir, jstring saveDir, jstring cacheDir, jboolean perf)
{
    // The core unpacks part of a disc image into TMPDIR; Android has no /tmp.
    setenv("TMPDIR", Str(env, cacheDir).c_str(), 1);
    if (perf) setenv("NUANCE_PERF", "1", 1);
    nagori_set_log(LogLine);
    const std::string core = Str(env, corePath), system = Str(env, systemDir), saves = Str(env, saveDir);
    NagoriConfig config = { core.c_str(), system.c_str(), saves.c_str() };
    return nagori_init(&config) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setOption(JNIEnv *env, jclass, jstring key, jstring value)
{
    nagori_set_option(Str(env, key).c_str(), Str(env, value).c_str());
}

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setEnvironment(JNIEnv *env, jclass, jstring key, jstring value)
{
    setenv(Str(env, key).c_str(), Str(env, value).c_str(), 1);
}

JNIEXPORT jboolean JNICALL
Java_gg_dekiru_nagori3k_Native_loadGame(JNIEnv *env, jclass, jstring path)
{
    return nagori_load_game(Str(env, path).c_str()) ? JNI_TRUE : JNI_FALSE;
}

// On the GL thread, when the surface (and with it the context) is created.
JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_contextReady(JNIEnv *, jclass)
{
    nagori_context_ready();
}

// On the GL thread, once per display refresh. The game is stepped sixty
// times a second whatever the display's rate is: on a 120 Hz panel every
// other call only draws the picture again.
JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_drawFrame(JNIEnv *, jclass, jint viewWidth, jint viewHeight)
{
    const double now = Now();
    // Paused: the game is not stepped, and its emulation thread goes idle by
    // itself when no frames are asked for. The last picture stays up.
    if (!paused && now - lastStep >= 1.0 / 60.0 - 0.002) {
        // Keep to the grid while on time; start again from now after a stall.
        lastStep = (now - lastStep < 2.0 / 60.0) ? lastStep + 1.0 / 60.0 : now;
        nagori_run_frame();
        framesStepped++;
    }
    framesDrawn++;

    unsigned w, h, tw, th;
    nagori_frame_texture(&w, &h, &tw, &th);
    const GLuint source = nagori_frame_buffer();
    int pw = viewWidth, ph = viewWidth * 3 / 4;
    if (ph > viewHeight) { ph = viewHeight; pw = viewHeight * 4 / 3; }
    if (stretch) { pw = viewWidth; ph = viewHeight; if (pw * 9 > ph * 16) pw = ph * 16 / 9; }
    const int px = (viewWidth - pw) / 2, py = (viewHeight - ph) / 2;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, viewWidth, viewHeight);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    if (source) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, (GLint)w, (GLint)h, px, py, px + pw, py + ph, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    if (showFps) nagori_draw_fps(viewWidth, viewHeight);

    if (framesDrawn % 600 == 0) {
        unsigned char c[4] = {};
        glReadPixels(px + pw / 2, py + ph / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, c);
        char line[200];
        snprintf(line, sizeof(line), "frames: %lu drawn, %lu stepped; view %dx%d, picture %dx%d; centre pixel %02X%02X%02X; game frame rate %.1f; GL error 0x%04X",
            framesDrawn, framesStepped, viewWidth, viewHeight, pw, ph, c[0], c[1], c[2], nagori_game_fps(), glGetError());
        LogLine(line);
    }
}

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setButton(JNIEnv *, jclass, jint button, jboolean down)
{
    nagori_set_button((NagoriButton)button, down == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setStick(JNIEnv *, jclass, jfloat x, jfloat y)
{
    nagori_set_stick(x, y);
}

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setStretch(JNIEnv *, jclass, jboolean on) { stretch = on == JNI_TRUE; }

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setShowFps(JNIEnv *, jclass, jboolean on) { showFps = on == JNI_TRUE; }

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_setPaused(JNIEnv *, jclass, jboolean on) { paused = on == JNI_TRUE; }

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_startAudio(JNIEnv *, jclass) { StartAudio(); }

JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_stopAudio(JNIEnv *, jclass) { StopAudio(); }

// On the GL thread.
JNIEXPORT void JNICALL
Java_gg_dekiru_nagori3k_Native_unloadGame(JNIEnv *, jclass)
{
    StopAudio();
    nagori_unload_game();
}

}
