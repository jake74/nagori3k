// The game's view: an OpenGL view that runs the host layer 60 times a
// second, reads the controller and keyboard, and plays the sound. The Swift
// app wraps one of these; everything about the emulator goes through here.
#import <Cocoa/Cocoa.h>

NS_ASSUME_NONNULL_BEGIN

// The NUON buttons, in the order mappings are given.
typedef NS_ENUM(NSInteger, NagoriNuonButton) {
    NagoriNuonUp, NagoriNuonDown, NagoriNuonLeft, NagoriNuonRight,
    NagoriNuonA, NagoriNuonB, NagoriNuonStart, NagoriNuonNuon,
    NagoriNuonL, NagoriNuonR,
    NagoriNuonCUp, NagoriNuonCLeft, NagoriNuonCDown, NagoriNuonCRight,
    NagoriNuonButtonCount
};

// What a game controller has to offer, in the order mappings name them.
typedef NS_ENUM(NSInteger, NagoriPadElement) {
    NagoriPadDpadUp, NagoriPadDpadDown, NagoriPadDpadLeft, NagoriPadDpadRight,
    NagoriPadSouth, NagoriPadEast, NagoriPadWest, NagoriPadNorth,   // cross, circle, square, triangle on a DualSense
    NagoriPadL1, NagoriPadR1, NagoriPadL2, NagoriPadR2,
    NagoriPadMenu, NagoriPadOptions, NagoriPadL3, NagoriPadR3,
    NagoriPadElementCount
};

@interface NagoriGameView : NSOpenGLView

// Loads the emulator core. Once per run of the app.
+ (BOOL)startCoreAt:(NSString *)corePath systemDirectory:(NSString *)systemDirectory saveDirectory:(NSString *)saveDirectory;
// A core option, by the core's own name. Takes effect on the next frame.
+ (void)setOption:(NSString *)key value:(NSString *)value;
// For each NUON button, the controller element (NagoriPadElement) or key code that presses it; -1 for none.
+ (void)setPadMapping:(NSArray<NSNumber *> *)elements;
+ (void)setKeyMapping:(NSArray<NSNumber *> *)keyCodes;
// The controller element being pressed right now, or -1. For the remapping screen.
+ (NSInteger)pressedPadElement;
+ (nullable NSString *)controllerName;

- (instancetype)initWithFrame:(NSRect)frame;
- (BOOL)loadGame:(NSString *)path;
- (void)stopGame;
@property (nonatomic) BOOL paused;

@end

NS_ASSUME_NONNULL_END
