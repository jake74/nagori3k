import SwiftUI
import AppKit

/// Cover art for the library. In order: a picture the user has put beside
/// the game (same name, or cover/folder/boxart, .png or .jpg); a picture
/// fetched before; Wikipedia's cover for an article with exactly the game's
/// title. Nothing is bundled with the app, and a game without a match gets a
/// plain tile - no art is better than the wrong art.
@MainActor
final class BoxArtStore: ObservableObject {
    @Published private(set) var images: [URL: NSImage] = [:]
    private var requested: Set<URL> = []

    private static let cacheDirectory: URL = {
        let dir = AppState.supportDirectory.appendingPathComponent("boxart", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    func image(for game: Game) -> NSImage? {
        if let image = images[game.url] { return image }
        if !requested.contains(game.url) {
            requested.insert(game.url)
            Task { await load(game) }
        }
        return nil
    }

    private func load(_ game: Game) async {
        let folder = game.url.deletingLastPathComponent()
        let stem = game.url.deletingPathExtension().lastPathComponent
        for name in [stem, "cover", "folder", "boxart"] {
            for ext in ["png", "jpg", "jpeg"] {
                let candidate = folder.appendingPathComponent(name).appendingPathExtension(ext)
                if let image = NSImage(contentsOf: candidate) { images[game.url] = image; return }
            }
        }
        let cached = Self.cacheDirectory.appendingPathComponent(Self.key(game.title)).appendingPathExtension("img")
        if let image = NSImage(contentsOf: cached) { images[game.url] = image; return }
        let missing = cached.appendingPathExtension("none")
        // A title that had no match is asked about again after a week.
        if let attrs = try? FileManager.default.attributesOfItem(atPath: missing.path),
           let date = attrs[.modificationDate] as? Date, Date().timeIntervalSince(date) < 7 * 86400 { return }

        if let data = await Self.fetchFromWikipedia(title: game.title), let image = NSImage(data: data) {
            try? data.write(to: cached)
            images[game.url] = image
        } else {
            FileManager.default.createFile(atPath: missing.path, contents: Data())
        }
    }

    /// Letters and digits only, lower case: "FreeFall 3050 A.D" and "Freefall 3050 A.D." are the same title.
    nonisolated static func key(_ title: String) -> String {
        String(title.lowercased().unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) })
    }

    nonisolated static func fetchFromWikipedia(title: String) async -> Data? {
        var components = URLComponents(string: "https://en.wikipedia.org/w/api.php")!
        components.queryItems = [
            .init(name: "action", value: "query"), .init(name: "format", value: "json"),
            .init(name: "prop", value: "pageimages"), .init(name: "piprop", value: "thumbnail"),
            .init(name: "pithumbsize", value: "600"), .init(name: "pilicense", value: "any"),
            .init(name: "generator", value: "search"), .init(name: "gsrsearch", value: "\(title) Nuon video game"),
            .init(name: "gsrlimit", value: "5"),
        ]
        guard let url = components.url else { return nil }
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("nagori3k/0.1 (https://github.com/jake74/nagori3k)", forHTTPHeaderField: "User-Agent")
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let query = json["query"] as? [String: Any],
              let pages = query["pages"] as? [String: [String: Any]] else { return nil }
        let wanted = key(title)
        for page in pages.values {
            guard let pageTitle = page["title"] as? String, key(pageTitle) == wanted,
                  let thumbnail = page["thumbnail"] as? [String: Any],
                  let source = thumbnail["source"] as? String, let imageURL = URL(string: source) else { continue }
            var imageRequest = URLRequest(url: imageURL, timeoutInterval: 20)
            imageRequest.setValue("nagori3k/0.1 (https://github.com/jake74/nagori3k)", forHTTPHeaderField: "User-Agent")
            if let (imageData, response) = try? await URLSession.shared.data(for: imageRequest),
               (response as? HTTPURLResponse)?.statusCode == 200 { return imageData }
        }
        return nil
    }
}
