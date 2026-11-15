import SwiftUI
import AppKit

/// One playable file in the library.
struct Game: Identifiable, Hashable {
    let url: URL
    var id: URL { url }
    var title: String { url.deletingPathExtension().lastPathComponent }
}

let nuonButtonNames = ["Up", "Down", "Left", "Right", "A", "B", "Start", "NUON", "L", "R", "C Up", "C Left", "C Down", "C Right"]
let padElementNames = ["D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right",
                       "Cross / A", "Circle / B", "Square / X", "Triangle / Y",
                       "L1", "R1", "L2", "R2", "Menu / Options", "Create / View", "L3", "R3"]
// The defaults follow RetroArch's layout, which is what the core was played with first.
let defaultPadMapping = [0, 1, 2, 3, 5, 4, 12, 13, 8, 9, 7, 6, 10, 11]
// Arrows, Z, X, Return, Tab, A, S, I, J, K, L.
let defaultKeyMapping = [126, 125, 123, 124, 6, 7, 36, 48, 0, 1, 34, 38, 40, 37]

@MainActor
final class AppState: ObservableObject {
    @Published var libraryPath: String { didSet { defaults.set(libraryPath, forKey: "libraryPath"); scanLibrary() } }
    @Published var games: [Game] = []
    @Published var playing: Game?
    @Published var loadError: String?

    // Settings. Each one is stored and, where the core has an option for it, passed on.
    @Published var stickEnabled: Bool { didSet { store("stickEnabled", stickEnabled) } }
    @Published var stickSensitivity: Double { didSet { store("stickSensitivity", stickSensitivity) } }
    @Published var stickWeight: Double { didSet { store("stickWeight", stickWeight) } }
    @Published var stickReversed: Bool { didSet { store("stickReversed", stickReversed) } }
    @Published var scanlines: Bool { didSet { store("scanlines", scanlines) } }
    @Published var limitTo30: Bool { didSet { store("limitTo30", limitTo30) } }
    @Published var skipControllerMessage: Bool { didSet { store("skipControllerMessage", skipControllerMessage) } }
    @Published var keepHighScores: Bool { didSet { store("keepHighScores", keepHighScores) } }
    @Published var padMapping: [Int] { didSet { defaults.set(padMapping, forKey: "padMapping"); applyMappings() } }
    @Published var keyMapping: [Int] { didSet { defaults.set(keyMapping, forKey: "keyMapping"); applyMappings() } }

    private let defaults = UserDefaults.standard
    private var coreStarted = false

    static let supportDirectory: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("nagori3k", isDirectory: true)
    }()
    static var saveDirectory: URL { supportDirectory.appendingPathComponent("saves", isDirectory: true) }

    init() {
        let d = UserDefaults.standard
        d.register(defaults: [
            "stickEnabled": true, "stickSensitivity": 100.0, "stickWeight": 70.0, "stickReversed": false,
            "scanlines": true, "limitTo30": true, "skipControllerMessage": true, "keepHighScores": true,
        ])
        libraryPath = d.string(forKey: "libraryPath") ?? ""
        stickEnabled = d.bool(forKey: "stickEnabled")
        stickSensitivity = d.double(forKey: "stickSensitivity")
        stickWeight = d.double(forKey: "stickWeight")
        stickReversed = d.bool(forKey: "stickReversed")
        scanlines = d.bool(forKey: "scanlines")
        limitTo30 = d.bool(forKey: "limitTo30")
        skipControllerMessage = d.bool(forKey: "skipControllerMessage")
        keepHighScores = d.bool(forKey: "keepHighScores")
        let pad = d.array(forKey: "padMapping") as? [Int] ?? defaultPadMapping
        let keys = d.array(forKey: "keyMapping") as? [Int] ?? defaultKeyMapping
        padMapping = pad.count == nuonButtonNames.count ? pad : defaultPadMapping
        keyMapping = keys.count == nuonButtonNames.count ? keys : defaultKeyMapping
        try? FileManager.default.createDirectory(at: Self.saveDirectory, withIntermediateDirectories: true)
        applyOptions()
        applyMappings()
        scanLibrary()
    }

    private func store(_ key: String, _ value: Any) {
        defaults.set(value, forKey: key)
        applyOptions()
    }

    /// Everything the core is told, in its own option names.
    func applyOptions() {
        func set(_ key: String, _ value: String) { NagoriGameView.setOption(key, value: value) }
        set("nuance_controller", stickEnabled ? "analog stick" : "d-pad only")
        set("nuance_spinner_sensitivity", "\(Int(stickSensitivity))%")
        set("nuance_spinner_inertia", "\(Int(stickWeight))%")
        set("nuance_spinner_invert", stickReversed ? "reversed" : "normal")
        set("nuance_scanlines", scanlines ? "enabled" : "disabled")
        set("nuance_max_fps", limitTo30 ? "30" : "unlimited")
        set("nuance_skip_controller_message", skipControllerMessage ? "enabled" : "disabled")
        set("nuance_t3k_high_scores", keepHighScores ? "keep" : "do not keep")
    }

    func applyMappings() {
        NagoriGameView.setPadMapping(padMapping.map { NSNumber(value: $0) })
        NagoriGameView.setKeyMapping(keyMapping.map { NSNumber(value: $0) })
    }

    func resetMappings() {
        padMapping = defaultPadMapping
        keyMapping = defaultKeyMapping
    }

    // MARK: Library

    func chooseLibraryFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.allowsMultipleSelection = false
        panel.prompt = "Choose"
        panel.message = "Choose the folder that holds your NUON disc images."
        if panel.runModal() == .OK, let url = panel.url { libraryPath = url.path }
    }

    /// Disc images and programs the core can load, in the folder and up to two
    /// levels below it. One entry per title: an .iso wins over anything else
    /// of the same name.
    func scanLibrary() {
        guard !libraryPath.isEmpty else { games = []; return }
        let root = URL(fileURLWithPath: libraryPath, isDirectory: true)
        let wanted = ["iso": 0, "chd": 1, "cof": 2, "run": 3]
        var best: [String: (rank: Int, url: URL)] = [:]
        let fm = FileManager.default
        func visit(_ dir: URL, depth: Int) {
            guard let items = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles]) else { return }
            for item in items {
                let isDir = (try? item.resourceValues(forKeys: [.isDirectoryKey]).isDirectory) ?? false
                if isDir { if depth < 2 { visit(item, depth: depth + 1) }; continue }
                guard let rank = wanted[item.pathExtension.lowercased()] else { continue }
                let key = item.deletingPathExtension().lastPathComponent.lowercased()
                if let existing = best[key], existing.rank <= rank { continue }
                best[key] = (rank, item)
            }
        }
        visit(root, depth: 0)
        games = best.values.map { Game(url: $0.url) }.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    // MARK: Playing

    func startCoreIfNeeded() -> Bool {
        if coreStarted { return true }
        let bundle = Bundle.main
        let core = (bundle.privateFrameworksPath ?? "") + "/nuance_libretro.dylib"
        let system = (bundle.resourcePath ?? "") + "/system"
        coreStarted = NagoriGameView.startCore(at: core, systemDirectory: system, saveDirectory: Self.saveDirectory.path)
        if !coreStarted { loadError = "The emulator core could not be loaded." }
        return coreStarted
    }

    func play(_ game: Game) {
        guard startCoreIfNeeded() else { return }
        applyOptions()
        loadError = nil
        playing = game
    }

    func stop() { playing = nil }

    /// Removes the kept high scores. With a game running, the core also puts
    /// the game's original table back.
    func deleteHighScores() {
        if playing != nil {
            NagoriGameView.setOption("nuance_t3k_high_scores", value: "erase")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { [weak self] in self?.applyOptions() }
        }
        let fm = FileManager.default
        for file in (try? fm.contentsOfDirectory(at: Self.saveDirectory, includingPropertiesForKeys: nil)) ?? []
        where file.pathExtension == "hiscores" {
            try? fm.removeItem(at: file)
        }
    }
}
