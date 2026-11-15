import SwiftUI
import AppKit

@main
struct NagoriApp: App {
    @StateObject private var state = AppState()

    init() {
        // --snapshot <directory>: write pictures of the screens and quit. For
        // checking layout without anyone looking at the screen.
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--snapshot"), i + 1 < args.count {
            Snapshot.pending = URL(fileURLWithPath: args[i + 1], isDirectory: true)
        }
    }

    var body: some Scene {
        Window("nagori3k", id: "main") {
            ContentView()
                .environmentObject(state)
                .task { await Snapshot.runIfAsked(state: state) }
                .task { LaunchArguments.apply(to: state) }
        }
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Choose Games Folder…") { state.chooseLibraryFolder() }.keyboardShortcut("o")
                Button("Look for Games Again") { state.scanLibrary() }.keyboardShortcut("r")
            }
            CommandMenu("Game") {
                Button(state.isFullScreen ? "Exit Full Screen" : "Enter Full Screen") {
                    (NSApp.mainWindow ?? NSApp.keyWindow)?.toggleFullScreen(nil)
                }
                .keyboardShortcut("f", modifiers: .command)
                Divider()
                Button("Stop and Return to Library") { state.stop() }
                    .keyboardShortcut(".", modifiers: .command)
                    .disabled(state.playing == nil)
            }
        }
        Settings {
            SettingsView().environmentObject(state)
        }
    }
}

enum Snapshot {
    static var pending: URL?

    @MainActor
    static func runIfAsked(state: AppState) async {
        guard let directory = pending else { return }
        pending = nil
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        // Give the box art a moment to arrive.
        let art = BoxArtStore()
        for game in state.games { _ = art.image(for: game) }
        try? await Task.sleep(nanoseconds: 6_000_000_000)

        func write<V: View>(_ view: V, _ name: String, width: CGFloat, height: CGFloat) {
            let renderer = ImageRenderer(content: view.environmentObject(state).frame(width: width, height: height)
                .background(Color(nsColor: .windowBackgroundColor)))
            renderer.scale = 1
            if let image = renderer.nsImage, let tiff = image.tiffRepresentation,
               let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                try? png.write(to: directory.appendingPathComponent(name + ".png"))
            }
        }
        write(LibrarySnapshot(games: state.games, art: art), "library", width: 900, height: 640)
        write(GameSettings(), "settings-game", width: 560, height: 640)
        write(ControlSettings().padding(20), "settings-buttons", width: 600, height: 540)
        NSApp.terminate(nil)
    }
}

/// The library grid without the scroll view, which an image renderer cannot draw.
struct LibrarySnapshot: View {
    let games: [Game]
    @ObservedObject var art: BoxArtStore
    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 170, maximum: 220), spacing: 24)], spacing: 28) {
            ForEach(games) { game in GameTile(game: game, image: art.image(for: game)) }
        }
        .padding(28)
    }
}

/// nagori3k --play <disc image> [--quit-after <seconds>] [--full-screen yes]:
/// start a game straight away, and optionally go to full screen and quit
/// again. For scripts and tests. Every option takes a value: AppKit reads the
/// command line as "-key value" pairs and takes a word left over as a file to
/// open, in which case the window never appears.
enum LaunchArguments {
    @MainActor
    static func apply(to state: AppState) {
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--play"), i + 1 < args.count {
            state.play(Game(url: URL(fileURLWithPath: args[i + 1])))
        }
        if args.contains("--full-screen") {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { NSApp.windows.first { $0.contentView != nil && $0.isVisible }?.toggleFullScreen(nil) }
            // What the window looks like once it has settled, for a test to read.
            DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
                if let w = NSApp.windows.first(where: { $0.styleMask.contains(.fullScreen) }) {
                    let picture = NagoriGameView.pictureSize()
                    FileHandle.standardError.write("nagori3k: full screen: toolbar visible \(w.toolbar?.isVisible ?? false), content \(Int(w.contentLayoutRect.height)) of \(Int(w.frame.height)) points high, isFullScreen \(state.isFullScreen), picture \(Int(picture.width))x\(Int(picture.height)) pixels on a screen of \(Int(w.frame.width))x\(Int(w.frame.height)) points\n".data(using: .utf8)!)
                } else {
                    FileHandle.standardError.write("nagori3k: full screen: no window in full screen\n".data(using: .utf8)!)
                }
            }
        }
        if args.contains("--report") {
            // Where the game's picture sits in the window, for a test to read.
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) {
                func find(_ view: NSView) -> NSView? {
                    if view is NagoriGameView { return view }
                    for sub in view.subviews { if let hit = find(sub) { return hit } }
                    return nil
                }
                guard let w = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil }),
                      let content = w.contentView, let game = find(content) else {
                    FileHandle.standardError.write("nagori3k: report: no game view\n".data(using: .utf8)!); return
                }
                let frame = game.convert(game.bounds, to: nil)
                let clear = w.contentLayoutRect
                let items = w.toolbar?.items.map { $0.label.isEmpty ? $0.itemIdentifier.rawValue : $0.label } ?? []
                FileHandle.standardError.write("nagori3k: report: window \(Int(w.frame.width))x\(Int(w.frame.height)), area clear of the title bar is \(Int(clear.height)) high, game view is \(Int(frame.width))x\(Int(frame.height)) with its top at \(Int(frame.maxY)); toolbar: \(items)\n".data(using: .utf8)!)
            }
        }
        if let i = args.firstIndex(of: "--quit-after"), i + 1 < args.count, let seconds = Double(args[i + 1]) {
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
                state.stop()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { NSApp.terminate(nil) }
            }
        }
    }
}
