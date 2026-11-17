import SwiftUI
import AppKit

/// A newer release, as GitHub describes it.
struct UpdateInfo: Equatable {
    let version: String
    let pageURL: URL
    let notes: String
}

/// Looking for a newer release. One request to GitHub, a quiet banner in the
/// library if there is one, and nothing downloaded or installed: the person
/// follows the link and decides. Nothing about the user is sent, only the
/// app's name and version in the User-Agent, which GitHub insists on.
enum UpdateCheck {
    static let address = "https://api.github.com/repos/jake74/nagori3k/releases/latest"
    static let releasesPage = URL(string: "https://github.com/jake74/nagori3k/releases")!
    static let interval: TimeInterval = 24 * 3600

    /// For testing: --update-url replaces the address for one run.
    @MainActor static var addressOverride: String?

    static var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// "v0.10.1" gives [0, 10, 1]. Reading stops at the first character that
    /// is not a digit or a dot, so "0.2-beta" is 0.2. Empty when there is no number at all.
    static func versionParts(_ text: String) -> [Int] {
        var s = Substring(text.trimmingCharacters(in: .whitespacesAndNewlines))
        if s.first == "v" || s.first == "V" { s = s.dropFirst() }
        let numeric = s.prefix { $0.isASCII && ($0.isNumber || $0 == ".") }
        guard numeric.contains(where: { $0.isNumber }) else { return [] }
        return numeric.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) ?? 0 }
    }

    /// The version as it is shown and remembered: no "v", no suffix.
    static func cleanVersion(_ text: String) -> String {
        versionParts(text).map(String.init).joined(separator: ".")
    }

    /// Component by component, a missing one counting as 0: 0.10.0 is newer than 0.9.2, 0.1 is not newer than 0.1.0.
    static func isNewer(_ latest: String, than current: String) -> Bool {
        let a = versionParts(latest), b = versionParts(current)
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        return false
    }

    enum Outcome {
        case newer(UpdateInfo)
        case upToDate
        case failed(String)
    }

    /// `answered` is true when something came back at all, even an error page:
    /// that is what counts as an attempt for the 24 hours.
    nonisolated static func fetch(_ url: URL, current: String) async -> (outcome: Outcome, answered: Bool) {
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("nagori3k/\(current)", forHTTPHeaderField: "User-Agent")
        let data: Data, response: URLResponse
        do { (data, response) = try await URLSession.shared.data(for: request) }
        catch {
            // The kind of failure goes in the log only; the person is told nothing either way.
            return (.failed("no answer: \((error as NSError).domain) \((error as NSError).code)"), false)
        }
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            return (.failed("HTTP \(http.statusCode)"), true)
        }
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = json["tag_name"] as? String else {
            return (.failed("unreadable answer"), true)
        }
        guard !versionParts(tag).isEmpty else { return (.failed("no version in tag"), true) }
        guard isNewer(tag, than: current) else { return (.upToDate, true) }
        // Only a web address is ever opened; anything else falls back to the releases page.
        var page = releasesPage
        if let text = json["html_url"] as? String, let u = URL(string: text), ["https", "http"].contains(u.scheme?.lowercased()) { page = u }
        return (.newer(UpdateInfo(version: cleanVersion(tag), pageURL: page, notes: json["body"] as? String ?? "")), true)
    }
}

extension AppState {
    private var lastUpdateCheck: Date? {
        let t = UserDefaults.standard.double(forKey: "lastUpdateCheck")
        return t > 0 ? Date(timeIntervalSince1970: t) : nil
    }

    /// The banner shows this: a newer version that has not been skipped.
    var bannerUpdate: UpdateInfo? {
        guard let u = availableUpdate, u.version != skippedUpdateVersion else { return nil }
        return u
    }

    func skipUpdate() {
        if let u = availableUpdate { skippedUpdateVersion = u.version }
    }

    /// When the app opens. Not while a game is running, not if the person has
    /// switched it off, and not more than once a day, unless a test forces it.
    func checkForUpdateAtLaunch() {
        let forced = UpdateCheck.addressOverride != nil
        guard playing == nil else { return }
        if !forced {
            guard checkForUpdates else { return }
            if let last = lastUpdateCheck, Date().timeIntervalSince(last) < UpdateCheck.interval { return }
        }
        Task { await runUpdateCheck(manual: false) }
    }

    func checkForUpdateNow() {
        guard !checkingForUpdate else { return }
        updateStatus = "Checking…"
        Task { await runUpdateCheck(manual: true) }
    }

    private func runUpdateCheck(manual: Bool) async {
        checkingForUpdate = true
        defer { checkingForUpdate = false }
        let current = UpdateCheck.currentVersion
        func log(_ line: String) { FileHandle.standardError.write("nagori3k: update: \(line)\n".data(using: .utf8)!) }
        guard let url = URL(string: UpdateCheck.addressOverride.map { $0.hasPrefix("/") ? "file://" + $0 : $0 } ?? UpdateCheck.address) else {
            log("check failed (bad address)")
            if manual { updateStatus = "Could not check for updates" }
            return
        }
        let (outcome, answered) = await UpdateCheck.fetch(url, current: current)
        if answered { UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "lastUpdateCheck") }
        switch outcome {
        case .newer(let info):
            availableUpdate = info
            // Asking by hand brings back a banner that was dismissed earlier.
            if manual { skippedUpdateVersion = "" }
            if !manual && info.version == skippedUpdateVersion {
                log("skipped \(info.version)")
            } else {
                log("available \(info.version) (this is \(current))")
            }
            if manual { updateStatus = "nagori3k \(info.version) is available" }
        case .upToDate:
            availableUpdate = nil
            log("up to date (\(current))")
            if manual { updateStatus = "You have the latest version (\(current))" }
        case .failed(let reason):
            log("check failed (\(reason))")
            if manual { updateStatus = "Could not check for updates" }
        }
    }
}

/// A slim strip at the top of the library, in the style of the window's own toolbar.
struct UpdateBanner: View {
    @EnvironmentObject var state: AppState
    let update: UpdateInfo
    @State private var showNotes = false

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "arrow.down.circle").foregroundStyle(.secondary)
            Text("nagori3k \(update.version) is available").font(.callout)
            Spacer()
            Button("Download") { NSWorkspace.shared.open(update.pageURL) }
            Button("What’s new") { showNotes = true }
            Button("Skip this version") { state.skipUpdate() }
        }
        .controlSize(.small)
        .padding(.horizontal, 16).padding(.vertical, 6)
        .background(.bar)
        .overlay(alignment: .bottom) { Divider() }
        .sheet(isPresented: $showNotes) {
            VStack(alignment: .leading, spacing: 12) {
                Text("nagori3k \(update.version)").font(.headline)
                ScrollView {
                    Text(update.notes.isEmpty ? "No release notes." : update.notes)
                        .font(.callout).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack { Spacer(); Button("Close") { showNotes = false }.keyboardShortcut(.defaultAction) }
            }
            .padding(20)
            .frame(width: 480, height: 360)
        }
    }
}
