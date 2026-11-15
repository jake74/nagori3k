// nagori-cli: the smallest program that plays a NUON game through the host
// layer - a window, sound, a game controller and the keyboard. It exists to
// prove the host layer before the real app is built on it.
//
//   nagori-cli [--core <dylib>] [--system <dir>] [--saves <dir>] [--seconds <n>] [--mute]
//              [--option key=value]... <disc image>
//
// Keys: arrows = d-pad, Z = A (fire), X = B, Return = Start, Tab = NUON,
// A/S = L/R, I J K L = C up/left/down/right, Esc = quit.
#import <Cocoa/Cocoa.h>
#import <GameController/GameController.h>
#import <AudioToolbox/AudioToolbox.h>
#import <OpenGL/gl.h>
#import <OpenGL/glext.h>
#include <string>
#include <vector>
#include "../host/nagori_host.h"

static OSStatus RenderAudio(void *, AudioUnitRenderActionFlags *, const AudioTimeStamp *, UInt32, UInt32 frames, AudioBufferList *io)
{
    int16_t *out = (int16_t *)io->mBuffers[0].mData;
    const size_t got = nagori_read_audio(out, frames);
    if (got < frames) memset(out + got * 2, 0, (frames - got) * 2 * sizeof(int16_t));
    return noErr;
}

@interface GameView : NSOpenGLView
@property (nonatomic) BOOL keys;
@end

static bool keyDown[NAGORI_BUTTON_COUNT];
static unsigned long framesRun = 0;
static double secondsLimit = 0;
static bool muted = false;
static bool debug = false;
static double reloadAt = 0;
static std::string reloadPath;
static CFAbsoluteTime startTime;

static int ButtonForKey(unsigned short code)
{
    switch (code) {
    case 126: return NAGORI_UP;   case 125: return NAGORI_DOWN;
    case 123: return NAGORI_LEFT; case 124: return NAGORI_RIGHT;
    case 6:   return NAGORI_A;    case 7:   return NAGORI_B;      // Z, X
    case 36:  return NAGORI_START; case 48: return NAGORI_NUON;   // Return, Tab
    case 0:   return NAGORI_L;    case 1:   return NAGORI_R;      // A, S
    case 34:  return NAGORI_C_UP; case 38:  return NAGORI_C_LEFT; // I, J
    case 40:  return NAGORI_C_DOWN; case 37: return NAGORI_C_RIGHT; // K, L
    }
    return -1;
}

@implementation GameView
- (BOOL)acceptsFirstResponder { return YES; }
- (void)keyDown:(NSEvent *)e
{
    if (e.keyCode == 53) { [NSApp terminate:nil]; return; }
    const int b = ButtonForKey(e.keyCode);
    if (b >= 0) keyDown[b] = true;
}
- (void)keyUp:(NSEvent *)e
{
    const int b = ButtonForKey(e.keyCode);
    if (b >= 0) keyDown[b] = false;
}
- (void)mouseDragged:(NSEvent *)e { nagori_add_spinner((float)e.deltaX); }

- (void)prepareOpenGL
{
    [super prepareOpenGL];
    GLint one = 1;
    [self.openGLContext setValues:&one forParameter:NSOpenGLContextParameterSwapInterval];
    nagori_context_ready();
}

- (void)runFrame
{
    [self.openGLContext makeCurrentContext];

    // Controller first, keyboard as well.
    bool down[NAGORI_BUTTON_COUNT];
    memcpy(down, keyDown, sizeof(down));
    float sx = 0, sy = 0;
    GCExtendedGamepad *pad = GCController.current.extendedGamepad ?: GCController.controllers.firstObject.extendedGamepad;
    if (pad) {
        down[NAGORI_UP] |= pad.dpad.up.pressed;       down[NAGORI_DOWN] |= pad.dpad.down.pressed;
        down[NAGORI_LEFT] |= pad.dpad.left.pressed;   down[NAGORI_RIGHT] |= pad.dpad.right.pressed;
        down[NAGORI_A] |= pad.buttonB.pressed;        // right face button, as RetroArch has it
        down[NAGORI_B] |= pad.buttonA.pressed;        // bottom face button
        down[NAGORI_C_LEFT] |= pad.buttonX.pressed;   down[NAGORI_C_UP] |= pad.buttonY.pressed;
        down[NAGORI_L] |= pad.leftShoulder.pressed;   down[NAGORI_R] |= pad.rightShoulder.pressed;
        down[NAGORI_C_DOWN] |= pad.leftTrigger.pressed; down[NAGORI_C_RIGHT] |= pad.rightTrigger.pressed;
        down[NAGORI_START] |= pad.buttonMenu.pressed;
        down[NAGORI_NUON] |= pad.buttonOptions.pressed;
        sx = pad.leftThumbstick.xAxis.value;
        sy = -pad.leftThumbstick.yAxis.value; // libretro: down is positive
    }
    for (int b = 0; b < NAGORI_BUTTON_COUNT; b++) nagori_set_button((NagoriButton)b, down[b]);
    nagori_set_stick(sx, sy);

    nagori_run_frame();

    // The game's picture, 4:3, centred in the window. Copied from the host
    // layer's frame buffer with a blit, which does not depend on whatever
    // drawing state the core left behind.
    unsigned w, h, tw, th;
    nagori_frame_texture(&w, &h, &tw, &th);
    const GLuint source = nagori_frame_buffer();
    const NSRect backing = [self convertRectToBacking:self.bounds];
    const int vw = (int)backing.size.width, vh = (int)backing.size.height;
    int pw = vw, ph = vw * 3 / 4;
    if (ph > vh) { ph = vh; pw = vh * 4 / 3; }
    const int px = (vw - pw) / 2, py = (vh - ph) / 2;
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glViewport(0, 0, vw, vh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    if (source) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, source);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, (GLint)w, (GLint)h, px, py, px + pw, py + ph, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    if (debug && (framesRun == 600 || framesRun == 120)) {
        unsigned char c[4] = {}, q[4] = {};
        glReadPixels(vw / 2, vh / 2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, c);
        glReadPixels(px + pw / 4, py + ph / 4, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, q);
        fprintf(stderr, "nagori-cli: frame %lu: window %dx%d, picture %dx%d at %d,%d, source fbo %u (%ux%u); window pixels %02X%02X%02X %02X%02X%02X; GL error 0x%04X; controllers %lu%s\n",
            framesRun, vw, vh, pw, ph, px, py, source, w, h, c[0], c[1], c[2], q[0], q[1], q[2], glGetError(),
            (unsigned long)GCController.controllers.count, pad ? " (one in use)" : "");
    }
    [self.openGLContext flushBuffer];

    framesRun++;
    if (reloadAt > 0 && CFAbsoluteTimeGetCurrent() - startTime >= reloadAt) {
        // Test of the host layer: stop the game and start it again in the same process.
        reloadAt = 0;
        nagori_unload_game();
        const bool ok = nagori_load_game(reloadPath.c_str());
        fprintf(stderr, "nagori-cli: reloaded the game: %s\n", ok ? "ok" : "FAILED");
    }
    if (secondsLimit > 0 && CFAbsoluteTimeGetCurrent() - startTime >= secondsLimit)
        [NSApp terminate:nil];
}
@end

@interface AppDelegate : NSObject <NSApplicationDelegate>
@property (strong) NSWindow *window;
@property (strong) GameView *view;
@property (strong) NSTimer *timer;
@property AudioUnit audio;
@end

@implementation AppDelegate
- (void)applicationDidFinishLaunching:(NSNotification *)n
{
    const NSOpenGLPixelFormatAttribute attrs[] = {
        NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersionLegacy, // the core's renderer is OpenGL 2
        NSOpenGLPFAColorSize, 24, NSOpenGLPFADoubleBuffer, NSOpenGLPFAAccelerated, 0 };
    NSOpenGLPixelFormat *format = [[NSOpenGLPixelFormat alloc] initWithAttributes:attrs];
    const NSRect rect = NSMakeRect(0, 0, 960, 720);
    self.window = [[NSWindow alloc] initWithContentRect:rect
        styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskResizable | NSWindowStyleMaskMiniaturizable
        backing:NSBackingStoreBuffered defer:NO];
    self.window.title = @"nagori3k";
    self.view = [[GameView alloc] initWithFrame:rect pixelFormat:format];
    self.view.wantsBestResolutionOpenGLSurface = YES;
    self.window.contentView = self.view;
    [self.window center];
    [self.window makeKeyAndOrderFront:nil];
    [self.window makeFirstResponder:self.view];
    [NSApp activateIgnoringOtherApps:YES];

    // Sound: the default output, fed from the host layer's ring.
    AudioComponentDescription d = { kAudioUnitType_Output, kAudioUnitSubType_DefaultOutput, kAudioUnitManufacturer_Apple, 0, 0 };
    AudioUnit unit;
    if (!muted && AudioComponentInstanceNew(AudioComponentFindNext(NULL, &d), &unit) == noErr) {
        AudioStreamBasicDescription f = {};
        f.mSampleRate = nagori_sample_rate();
        f.mFormatID = kAudioFormatLinearPCM;
        f.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
        f.mChannelsPerFrame = 2; f.mBitsPerChannel = 16; f.mBytesPerFrame = 4; f.mFramesPerPacket = 1; f.mBytesPerPacket = 4;
        AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &f, sizeof(f));
        AURenderCallbackStruct cb = { RenderAudio, NULL };
        AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &cb, sizeof(cb));
        if (AudioUnitInitialize(unit) == noErr && AudioOutputUnitStart(unit) == noErr)
            self.audio = unit;
    }

    // Controllers: say so when one arrives, and look for wireless ones.
    [[NSNotificationCenter defaultCenter] addObserverForName:GCControllerDidConnectNotification object:nil queue:nil
        usingBlock:^(NSNotification *note) {
            GCController *c = note.object;
            fprintf(stderr, "nagori-cli: controller connected: %s%s\n", c.vendorName.UTF8String ?: "unknown",
                c.extendedGamepad ? "" : " (not a full gamepad, ignored)");
        }];
    GCController.shouldMonitorBackgroundEvents = YES;
    [GCController startWirelessControllerDiscoveryWithCompletionHandler:nil];

    startTime = CFAbsoluteTimeGetCurrent();
    self.timer = [NSTimer timerWithTimeInterval:1.0 / 60.0 target:self.view selector:@selector(runFrame) userInfo:nil repeats:YES];
    [[NSRunLoop currentRunLoop] addTimer:self.timer forMode:NSRunLoopCommonModes];
}
- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)a { return YES; }
- (void)applicationWillTerminate:(NSNotification *)n
{
    [self.timer invalidate];
    if (self.audio) { AudioOutputUnitStop(self.audio); AudioComponentInstanceDispose(self.audio); }
    [self.view.openGLContext makeCurrentContext];
    nagori_shutdown();
    fprintf(stderr, "nagori-cli: %lu frames in %.1f s\n", framesRun, CFAbsoluteTimeGetCurrent() - startTime);
}
@end

int main(int argc, char **argv)
{
    @autoreleasepool {
        std::string corePath = "build/nuance_libretro.dylib", systemDir = "build/system", gamePath;
        std::string saves = std::string(NSHomeDirectory().UTF8String) + "/Library/Application Support/nagori3k/saves";
        std::vector<std::pair<std::string, std::string>> opts = {
            { "nuance_controller", "analog stick" }, { "nuance_skip_controller_message", "enabled" },
            { "nuance_max_fps", "30" }, { "nuance_t3k_high_scores", "keep" }, { "nuance_scanlines", "disabled" } };
        for (int i = 1; i < argc; i++) {
            const std::string a = argv[i];
            if (a == "--core" && i + 1 < argc) corePath = argv[++i];
            else if (a == "--system" && i + 1 < argc) systemDir = argv[++i];
            else if (a == "--saves" && i + 1 < argc) saves = argv[++i];
            else if (a == "--seconds" && i + 1 < argc) secondsLimit = atof(argv[++i]);
            else if (a == "--mute") muted = true;
            else if (a == "--debug") debug = true;
            else if (a == "--reload-at" && i + 1 < argc) reloadAt = atof(argv[++i]);
            else if (a == "--option" && i + 1 < argc) {
                const std::string kv = argv[++i];
                const size_t eq = kv.find('=');
                if (eq != std::string::npos) opts.push_back({ kv.substr(0, eq), kv.substr(eq + 1) });
            } else gamePath = a;
        }
        if (gamePath.empty()) {
            fprintf(stderr, "usage: nagori-cli [--core <dylib>] [--system <dir>] [--saves <dir>] [--seconds <n>] [--option key=value]... <disc image>\n");
            return 2;
        }
        [[NSFileManager defaultManager] createDirectoryAtPath:@(saves.c_str()) withIntermediateDirectories:YES attributes:nil error:nil];

        // The core changes the working directory, so nothing relative survives it.
        NSString *(^absolute)(const std::string &) = ^(const std::string &p) {
            return [[NSURL fileURLWithPath:@(p.c_str())] URLByStandardizingPath].path; };
        corePath = absolute(corePath).UTF8String; systemDir = absolute(systemDir).UTF8String;
        gamePath = absolute(gamePath).UTF8String; saves = absolute(saves).UTF8String;

        NagoriConfig config = { corePath.c_str(), systemDir.c_str(), saves.c_str() };
        if (!nagori_init(&config)) return 1;
        for (const auto &kv : opts) nagori_set_option(kv.first.c_str(), kv.second.c_str());
        if (!nagori_load_game(gamePath.c_str())) return 1;
        reloadPath = gamePath;

        NSApplication *app = [NSApplication sharedApplication];
        app.activationPolicy = NSApplicationActivationPolicyRegular;
        AppDelegate *delegate = [AppDelegate new];
        app.delegate = delegate;
        [app run];
    }
    return 0;
}
