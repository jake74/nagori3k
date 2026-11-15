import SwiftUI
import AppKit
import Carbon.HIToolbox

struct SettingsView: View {
    var body: some View {
        TabView {
            GameSettings().tabItem { Label("Game", systemImage: "gamecontroller") }
            ControlSettings().tabItem { Label("Buttons", systemImage: "keyboard") }
        }
        .frame(width: 560)
    }
}

struct GameSettings: View {
    @EnvironmentObject var state: AppState
    @State private var confirmDelete = false

    var body: some View {
        Form {
            Section("Analogue control") {
                Toggle("Turn the claw with the left stick", isOn: $state.stickEnabled)
                Toggle("Turn the claw with the triggers (L2 left, R2 right)", isOn: $state.triggersSteer)
                LabeledContent("Sensitivity") {
                    HStack {
                        Slider(value: $state.stickSensitivity, in: 10...400, step: 10)
                        Text("\(Int(state.stickSensitivity))%").monospacedDigit().frame(width: 48, alignment: .trailing)
                    }
                }
                .disabled(!state.stickEnabled && !state.triggersSteer)
                LabeledContent("Weight") {
                    HStack {
                        Slider(value: $state.stickWeight, in: 0...100, step: 5)
                        Text("\(Int(state.stickWeight))%").monospacedDigit().frame(width: 48, alignment: .trailing)
                    }
                }
                .disabled(!state.stickEnabled && !state.triggersSteer)
                Toggle("Reverse direction", isOn: $state.stickReversed).disabled(!state.stickEnabled && !state.triggersSteer)
                Text("The stick and the triggers drive a weighted flywheel, like the knob on the arcade Tempest: the heavier it is, the longer the claw keeps moving after you let go. The harder a trigger is squeezed, the faster the claw turns; with this on, the triggers stop pressing the buttons they are mapped to. The d-pad always works as well.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Picture") {
                Toggle("Scanlines", isOn: $state.scanlines)
                Toggle("Stretch to fill the window", isOn: $state.stretchToFill)
                Toggle("In full screen, stretch no wider than 16:9", isOn: $state.stretchTo16by9)
                    .disabled(!state.stretchToFill)
                Text("The game draws a 4:3 picture and has no widescreen mode. Stretched, it fills a wide screen but everything is wider than it should be.")
                    .font(.footnote).foregroundStyle(.secondary)
                Picker("Frame rate limit", selection: $state.frameLimit) {
                    Text("Uncapped").tag(0)
                    Text("30").tag(30)
                    Text("24").tag(24)
                    Text("20").tag(20)
                }
                Text("Tempest 3000 plays faster the faster it draws, so the limit also sets the pace of the game. Uncapped, it runs ahead of the original in quiet moments.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Tempest 3000") {
                Toggle("Skip the “controller configuration has changed” screen", isOn: $state.skipControllerMessage)
                Toggle("Keep high scores", isOn: $state.keepHighScores)
                Text("The original forgot its high scores when it was switched off. Changes to the first setting take effect the next time the game is started.")
                    .font(.footnote).foregroundStyle(.secondary)
                Button("Delete High Scores…", role: .destructive) { confirmDelete = true }
                    .confirmationDialog("Delete the kept high scores?", isPresented: $confirmDelete) {
                        Button("Delete", role: .destructive) { state.deleteHighScores() }
                    } message: {
                        Text("The table goes back to the one the game starts with. This cannot be undone.")
                    }
            }
        }
        .formStyle(.grouped)
    }
}

struct ControlSettings: View {
    @EnvironmentObject var state: AppState
    @State private var capturingPad: Int?
    @State private var capturingKey: Int?
    @State private var keyMonitor: Any?
    @State private var padTimer: Timer?
    @State private var controllerName: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: controllerName == nil ? "gamecontroller" : "gamecontroller.fill")
                Text(controllerName ?? "No controller connected").foregroundStyle(controllerName == nil ? .secondary : .primary)
                Spacer()
                Button("Reset to Defaults") { cancelCapture(); state.resetMappings() }
            }
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 6) {
                GridRow {
                    Text("NUON button").font(.headline)
                    Text("Controller").font(.headline)
                    Text("Keyboard").font(.headline)
                }
                Divider().gridCellColumns(3)
                ForEach(0..<nuonButtonNames.count, id: \.self) { i in
                    GridRow {
                        Text(nuonButtonNames[i])
                        Button {
                            beginPadCapture(i)
                        } label: {
                            Text(capturingPad == i ? "Press a button…" : padName(state.padMapping[i])).frame(width: 150)
                        }
                        Button {
                            beginKeyCapture(i)
                        } label: {
                            Text(capturingKey == i ? "Press a key…" : keyName(state.keyMapping[i])).frame(width: 150)
                        }
                    }
                }
            }
            Text("Click a box, then press the button or key to use. Escape cancels. The left stick is not a button: it is set under Game.")
                .font(.footnote).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(20)
        .onAppear { controllerName = NagoriGameView.controllerName() }
        .onDisappear { cancelCapture() }
    }

    private func padName(_ element: Int) -> String {
        element >= 0 && element < padElementNames.count ? padElementNames[element] : "None"
    }

    private func beginPadCapture(_ index: Int) {
        cancelCapture()
        controllerName = NagoriGameView.controllerName()
        capturingPad = index
        // Wait for everything to be let go first, so that the press that
        // clicked this box (if it came from the controller) is not taken.
        var armed = false
        padTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { _ in
            let pressed = NagoriGameView.pressedPadElement()
            if !armed { armed = pressed < 0; return }
            if pressed >= 0 {
                Task { @MainActor in
                    state.padMapping[index] = pressed
                    cancelCapture()
                }
            }
        }
        installEscapeMonitor()
    }

    private func beginKeyCapture(_ index: Int) {
        cancelCapture()
        capturingKey = index
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if event.keyCode != UInt16(kVK_Escape) { state.keyMapping[index] = Int(event.keyCode) }
            cancelCapture()
            return nil
        }
    }

    private func installEscapeMonitor() {
        keyMonitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if event.keyCode == UInt16(kVK_Escape) { cancelCapture(); return nil }
            return event
        }
    }

    private func cancelCapture() {
        padTimer?.invalidate(); padTimer = nil
        if let keyMonitor { NSEvent.removeMonitor(keyMonitor) }
        keyMonitor = nil
        capturingPad = nil
        capturingKey = nil
    }
}

/// A readable name for a key code, from the current keyboard layout.
func keyName(_ keyCode: Int) -> String {
    if keyCode < 0 { return "None" }
    let special: [Int: String] = [
        kVK_Return: "Return", kVK_Tab: "Tab", kVK_Space: "Space", kVK_Delete: "Delete", kVK_Escape: "Escape",
        kVK_LeftArrow: "←", kVK_RightArrow: "→", kVK_UpArrow: "↑", kVK_DownArrow: "↓",
        kVK_Shift: "Shift", kVK_Control: "Control", kVK_Option: "Option", kVK_Command: "Command",
    ]
    if let name = special[keyCode] { return name }
    guard let source = TISCopyCurrentASCIICapableKeyboardLayoutInputSource()?.takeRetainedValue(),
          let layoutPtr = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData) else { return "Key \(keyCode)" }
    let layoutData = Unmanaged<CFData>.fromOpaque(layoutPtr).takeUnretainedValue() as Data
    var deadKeys: UInt32 = 0
    var chars = [UniChar](repeating: 0, count: 4)
    var length = 0
    let status = layoutData.withUnsafeBytes { raw -> OSStatus in
        guard let layout = raw.bindMemory(to: UCKeyboardLayout.self).baseAddress else { return -1 }
        return UCKeyTranslate(layout, UInt16(keyCode), UInt16(kUCKeyActionDisplay), 0, UInt32(LMGetKbdType()),
                              OptionBits(kUCKeyTranslateNoDeadKeysBit), &deadKeys, chars.count, &length, &chars)
    }
    if status == noErr, length > 0 { return String(utf16CodeUnits: chars, count: length).uppercased() }
    return "Key \(keyCode)"
}
