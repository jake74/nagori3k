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
        write(ControlSettings(), "settings-buttons", width: 560, height: 520)
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

/// nagori3k --play <disc image> [--quit-after <seconds>]: start a game
/// straight away, and optionally quit again. For scripts and tests.
enum LaunchArguments {
    @MainActor
    static func apply(to state: AppState) {
        let args = CommandLine.arguments
        if let i = args.firstIndex(of: "--play"), i + 1 < args.count {
            state.play(Game(url: URL(fileURLWithPath: args[i + 1])))
        }
        if let i = args.firstIndex(of: "--quit-after"), i + 1 < args.count, let seconds = Double(args[i + 1]) {
            DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
                state.stop()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { NSApp.terminate(nil) }
            }
        }
    }
}
