#import "NagoriGameView.h"
#import <GameController/GameController.h>
#import <AudioToolbox/AudioToolbox.h>
#import <OpenGL/gl.h>
#import <OpenGL/glext.h>
#include <atomic>
#include "../../host/nagori_host.h"

static std::atomic<int> padMap[NagoriNuonButtonCount];
static std::atomic<int> keyMap[NagoriNuonButtonCount];
static bool keyHeld[NagoriNuonButtonCount];
static bool coreStarted = false;

static OSStatus RenderAudio(void *, AudioUnitRenderActionFlags *, const AudioTimeStamp *, UInt32, UInt32 frames, AudioBufferList *io)
{
    int16_t *out = (int16_t *)io->mBuffers[0].mData;
    const size_t got = nagori_read_audio(out, frames);
    if (got < frames) memset(out + got * 2, 0, (frames - got) * 2 * sizeof(int16_t));
    return noErr;
}

static GCExtendedGamepad *CurrentPad(void)
{
    return GCController.current.extendedGamepad ?: GCController.controllers.firstObject.extendedGamepad;
}

static bool PadElementPressed(GCExtendedGamepad *pad, NSInteger element)
{
    switch (element) {
    case NagoriPadDpadUp: return pad.dpad.up.pressed;
    case NagoriPadDpadDown: return pad.dpad.down.pressed;
    case NagoriPadDpadLeft: return pad.dpad.left.pressed;
    case NagoriPadDpadRight: return pad.dpad.right.pressed;
    case NagoriPadSouth: return pad.buttonA.pressed;
    case NagoriPadEast: return pad.buttonB.pressed;
    case NagoriPadWest: return pad.buttonX.pressed;
    case NagoriPadNorth: return pad.buttonY.pressed;
    case NagoriPadL1: return pad.leftShoulder.pressed;
    case NagoriPadR1: return pad.rightShoulder.pressed;
    case NagoriPadL2: return pad.leftTrigger.pressed;
    case NagoriPadR2: return pad.rightTrigger.pressed;
    case NagoriPadMenu: return pad.buttonMenu.pressed;
    case NagoriPadOptions: return pad.buttonOptions.pressed;
    case NagoriPadL3: return pad.leftThumbstickButton.pressed;
    case NagoriPadR3: return pad.rightThumbstickButton.pressed;
    }
    return false;
}

@implementation NagoriGameView {
    NSTimer *_timer;
    AudioUnit _audio;
    BOOL _gameLoaded;
    BOOL _contextReady;
}

+ (void)initialize
{
    if (self != [NagoriGameView class]) return;
    for (int i = 0; i < NagoriNuonButtonCount; i++) { padMap[i] = -1; keyMap[i] = -1; }
    GCController.shouldMonitorBackgroundEvents = NO;
    [GCController startWirelessControllerDiscoveryWithCompletionHandler:nil];
}

+ (BOOL)startCoreAt:(NSString *)corePath systemDirectory:(NSString *)systemDirectory saveDirectory:(NSString *)saveDirectory
{
    if (coreStarted) return YES;
    NagoriConfig config = { corePath.fileSystemRepresentation, systemDirectory.fileSystemRepresentation, saveDirectory.fileSystemRepresentation };
    coreStarted = nagori_init(&config);
    return coreStarted;
}

+ (void)setOption:(NSString *)key value:(NSString *)value { nagori_set_option(key.UTF8String, value.UTF8String); }

+ (void)setPadMapping:(NSArray<NSNumber *> *)elements
{
    for (NSUInteger i = 0; i < NagoriNuonButtonCount && i < elements.count; i++) padMap[i] = elements[i].intValue;
}
+ (void)setKeyMapping:(NSArray<NSNumber *> *)keyCodes
{
    for (NSUInteger i = 0; i < NagoriNuonButtonCount && i < keyCodes.count; i++) keyMap[i] = keyCodes[i].intValue;
}

+ (NSInteger)pressedPadElement
{
    GCExtendedGamepad *pad = CurrentPad();
    if (!pad) return -1;
    for (NSInteger e = 0; e < NagoriPadElementCount; e++)
        if (PadElementPressed(pad, e)) return e;
    return -1;
}

+ (NSString *)controllerName
{
    GCController *c = GCController.current ?: GCController.controllers.firstObject;
    return c.extendedGamepad ? (c.vendorName ?: @"Controller") : nil;
}

- (instancetype)initWithFrame:(NSRect)frame
{
    const NSOpenGLPixelFormatAttribute attrs[] = {
        NSOpenGLPFAOpenGLProfile, NSOpenGLProfileVersionLegacy, // the core's renderer is OpenGL 2
        NSOpenGLPFAColorSize, 24, NSOpenGLPFADoubleBuffer, NSOpenGLPFAAccelerated, 0 };
    NSOpenGLPixelFormat *format = [[NSOpenGLPixelFormat alloc] initWithAttributes:attrs];
    if ((self = [super initWithFrame:frame pixelFormat:format]))
        self.wantsBestResolutionOpenGLSurface = YES;
    return self;
}

- (BOOL)acceptsFirstResponder { return YES; }
- (void)viewDidMoveToWindow { [super viewDidMoveToWindow]; [self.window makeFirstResponder:self]; }

- (void)keyDown:(NSEvent *)e
{
    BOOL used = NO;
    for (int i = 0; i < NagoriNuonButtonCount; i++)
        if (keyMap[i] == (int)e.keyCode) { keyHeld[i] = true; used = YES; }
    if (!used) [super keyDown:e];
}
- (void)keyUp:(NSEvent *)e
{
    for (int i = 0; i < NagoriNuonButtonCount; i++)
        if (keyMap[i] == (int)e.keyCode) keyHeld[i] = false;
}
- (void)mouseDragged:(NSEvent *)e { nagori_add_spinner((float)e.deltaX); }

- (void)prepareOpenGL
{
    [super prepareOpenGL];
    GLint one = 1;
    [self.openGLContext setValues:&one forParameter:NSOpenGLContextParameterSwapInterval];
    _contextReady = YES;
    nagori_context_ready();
}

- (BOOL)loadGame:(NSString *)path
{
    if (!coreStarted || _gameLoaded) return NO;
    // The core draws into a frame buffer of the host layer's as soon as it is
    // loaded, so the context has to be the current one.
    [self.openGLContext makeCurrentContext];
    if (!nagori_load_game(path.fileSystemRepresentation)) return NO;
    _gameLoaded = YES;
    memset(keyHeld, 0, sizeof(keyHeld));

    AudioComponentDescription d = { kAudioUnitType_Output, kAudioUnitSubType_DefaultOutput, kAudioUnitManufacturer_Apple, 0, 0 };
    AudioUnit unit;
    if (AudioComponentInstanceNew(AudioComponentFindNext(NULL, &d), &unit) == noErr) {
        AudioStreamBasicDescription f = {};
        f.mSampleRate = nagori_sample_rate();
        f.mFormatID = kAudioFormatLinearPCM;
        f.mFormatFlags = kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked;
        f.mChannelsPerFrame = 2; f.mBitsPerChannel = 16; f.mBytesPerFrame = 4; f.mFramesPerPacket = 1; f.mBytesPerPacket = 4;
        AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0, &f, sizeof(f));
        AURenderCallbackStruct cb = { RenderAudio, NULL };
        AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0, &cb, sizeof(cb));
        if (AudioUnitInitialize(unit) == noErr && AudioOutputUnitStart(unit) == noErr) _audio = unit;
        else AudioComponentInstanceDispose(unit);
    }

    _timer = [NSTimer timerWithTimeInterval:1.0 / 60.0 target:self selector:@selector(runFrame) userInfo:nil repeats:YES];
    [[NSRunLoop mainRunLoop] addTimer:_timer forMode:NSRunLoopCommonModes];
    return YES;
}

- (void)stopGame
{
    [_timer invalidate];
    _timer = nil;
    if (_audio) { AudioOutputUnitStop(_audio); AudioComponentInstanceDispose(_audio); _audio = NULL; }
    if (_gameLoaded) {
        [self.openGLContext makeCurrentContext];
        nagori_unload_game();
        _gameLoaded = NO;
    }
}

- (void)runFrame
{
    if (!_gameLoaded || !_contextReady || self.paused) return;
    [self.openGLContext makeCurrentContext];

    bool down[NagoriNuonButtonCount];
    GCExtendedGamepad *pad = CurrentPad();
    for (int i = 0; i < NagoriNuonButtonCount; i++) {
        down[i] = keyHeld[i];
        const int element = padMap[i];
        if (pad && element >= 0) down[i] |= PadElementPressed(pad, element);
        nagori_set_button((NagoriButton)i, down[i]);
    }
    nagori_set_stick(pad ? pad.leftThumbstick.xAxis.value : 0.0f, pad ? -pad.leftThumbstick.yAxis.value : 0.0f);

    nagori_run_frame();

    // The game's picture, 4:3, centred. A blit, which does not depend on
    // whatever drawing state the core left behind.
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
    [self.openGLContext flushBuffer];
}

- (void)dealloc { [self stopGame]; }

@end
