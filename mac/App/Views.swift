import SwiftUI
import AppKit

struct ContentView: View {
    @EnvironmentObject var state: AppState

    var body: some View {
        Group {
            if let game = state.playing {
                GamePlayer(game: game)
                    .id(game.url)
                    .ignoresSafeArea()
                    .background(Color.black)
            } else {
                LibraryView()
            }
        }
        .frame(minWidth: 720, minHeight: 540)
        .navigationTitle(state.playing?.title ?? "nagori3k")
        .toolbar {
            if state.playing != nil {
                ToolbarItem(placement: .navigation) {
                    Button { state.stop() } label: { Label("Library", systemImage: "chevron.left") }
                        .help("Stop the game and go back to the library")
                }
            } else {
                ToolbarItem {
                    Button { state.chooseLibraryFolder() } label: { Label("Choose Folder", systemImage: "folder") }
                        .help("Choose the folder that holds your games")
                }
            }
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var state: AppState
    @StateObject private var art = BoxArtStore()
    private let columns = [GridItem(.adaptive(minimum: 170, maximum: 220), spacing: 24)]

    var body: some View {
        if state.libraryPath.isEmpty {
            VStack(spacing: 14) {
                Image(systemName: "opticaldisc").font(.system(size: 52)).foregroundStyle(.secondary)
                Text("Choose the folder that holds your NUON games").font(.title3)
                Text("Disc images (.iso, .chd) in that folder and the folders inside it will appear here.")
                    .foregroundStyle(.secondary)
                Button("Choose Folder…") { state.chooseLibraryFolder() }.controlSize(.large)
            }
            .padding(40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if state.games.isEmpty {
            VStack(spacing: 12) {
                Text("No games found").font(.title3)
                Text(state.libraryPath).font(.callout).foregroundStyle(.secondary)
                Text("nagori3k plays .iso and .chd disc images. If the folder is on a network share, check that it is connected.")
                    .foregroundStyle(.secondary).multilineTextAlignment(.center)
                HStack {
                    Button("Look Again") { state.scanLibrary() }
                    Button("Choose Another Folder…") { state.chooseLibraryFolder() }
                }
            }
            .padding(40)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                if let error = state.loadError {
                    Text(error).foregroundStyle(.red).padding(.top, 12)
                }
                LazyVGrid(columns: columns, spacing: 28) {
                    ForEach(state.games) { game in
                        GameTile(game: game, image: art.image(for: game))
                            .onTapGesture(count: 2) { state.play(game) }
                            .contextMenu {
                                Button("Play") { state.play(game) }
                                Button("Show in Finder") { NSWorkspace.shared.activateFileViewerSelecting([game.url]) }
                            }
                    }
                }
                .padding(28)
            }
        }
    }
}

struct GameTile: View {
    let game: Game
    let image: NSImage?

    var body: some View {
        VStack(spacing: 8) {
            ZStack {
                if let image {
                    Image(nsImage: image).resizable().aspectRatio(contentMode: .fit)
                } else {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(LinearGradient(colors: [Color(white: 0.22), Color(white: 0.12)], startPoint: .top, endPoint: .bottom))
                    Text(game.title)
                        .font(.headline).foregroundStyle(.white.opacity(0.85))
                        .multilineTextAlignment(.center).padding(12)
                }
            }
            .frame(width: 170, height: 240)
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .shadow(radius: 4, y: 2)
            Text(game.title).font(.callout).lineLimit(2).multilineTextAlignment(.center).frame(width: 170)
        }
        .contentShape(Rectangle())
        .help("Double-click to play")
    }
}

/// The running game.
struct GamePlayer: NSViewRepresentable {
    @EnvironmentObject var state: AppState
    let game: Game

    func makeNSView(context: Context) -> NagoriGameView {
        let view = NagoriGameView(frame: NSRect(x: 0, y: 0, width: 960, height: 720))
        let path = game.url.path
        let state = self.state
        // After the view is in its window, so that it has somewhere to draw.
        DispatchQueue.main.async {
            if !view.loadGame(path) {
                state.loadError = "\(game.title) could not be started."
                state.playing = nil
            } else {
                view.window?.makeFirstResponder(view)
            }
        }
        return view
    }

    func updateNSView(_ view: NagoriGameView, context: Context) {}

    static func dismantleNSView(_ view: NagoriGameView, coordinator: ()) {
        view.stopGame()
    }
}
