// nagori3k host layer: everything a frontend needs to run the NUON core
// (core/, a libretro core) without RetroArch. Plain C interface so that
// Swift, Objective-C and JNI can all call it.
//
// Threads: nagori_run_frame, nagori_context_ready and nagori_unload must be
// called with the frontend's OpenGL context current. nagori_read_audio may be
// called from an audio thread. The input and option setters may be called
// from any thread.
#ifndef NAGORI_HOST_H
#define NAGORI_HOST_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct NagoriConfig {
    const char *core_path;   // the core library (nuance_libretro.dylib / .so)
    const char *system_dir;  // holds nuance/bios.cof, minibios.cof, minibiosX.cof
    const char *save_dir;    // saves and high scores go here; created by the frontend
} NagoriConfig;

// NUON buttons, as the frontend names them.
typedef enum NagoriButton {
    NAGORI_UP, NAGORI_DOWN, NAGORI_LEFT, NAGORI_RIGHT,
    NAGORI_A, NAGORI_B, NAGORI_START, NAGORI_NUON,
    NAGORI_L, NAGORI_R,
    NAGORI_C_UP, NAGORI_C_LEFT, NAGORI_C_DOWN, NAGORI_C_RIGHT,
    NAGORI_BUTTON_COUNT
} NagoriButton;

// Loads the core. False if the library or one of its entry points is missing.
bool nagori_init(const NagoriConfig *config);

// Core options, by the core's own names (see docs/options.md). May be called
// before or during a game; the core picks changes up on its next frame.
void nagori_set_option(const char *key, const char *value);

// Loads a disc image or program. Call nagori_context_ready before the first frame.
bool nagori_load_game(const char *path);

// With the GL context current, once it exists and again if it is recreated.
void nagori_context_ready(void);

// Runs one 60 Hz frame. The picture ends up in the texture nagori_frame_texture
// names; width and height say how much of it is in use.
void nagori_run_frame(void);
unsigned nagori_frame_texture(unsigned *width, unsigned *height, unsigned *texture_width, unsigned *texture_height);
unsigned nagori_frame_buffer(void); // the frame buffer that texture is attached to, for a blit

// The game's own frame rate, in frames a second, averaged over the last half
// second or so. Not the 60 a second nagori_run_frame is called at: a game
// draws when it has finished a frame. 0 if the core cannot say.
float nagori_game_fps(void);

// Draws the frame rate, white on black, in the top right corner of the
// frame buffer that is bound, which is view_width by view_height pixels.
// Call after the picture has been copied to the screen.
void nagori_draw_fps(int view_width, int view_height);

// Interleaved stereo, 16 bit, at nagori_sample_rate(). Returns frames written;
// the rest of the buffer is the caller's to fill with silence.
size_t nagori_read_audio(int16_t *out, size_t frames);
double nagori_sample_rate(void);

void nagori_set_button(NagoriButton button, bool down);
void nagori_set_stick(float x, float y);      // left stick, -1..1
void nagori_add_spinner(float counts);        // relative turn from a mouse, trackpad or spinner

// Saves, stops the emulation and releases the game. The core stays loaded.
void nagori_unload_game(void);
void nagori_shutdown(void);

// Lines the core logs. Optional; stderr if not set.
void nagori_set_log(void (*log)(const char *line));

#ifdef __cplusplus
}
#endif
#endif
