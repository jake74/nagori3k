// Makes the app icons from art/nagori3k.svg. Run from the repository root:
//   swift tools/make-icons.swift
// Writes mac/AppIcon.icns, the Android launcher foreground and art/nagori3k.png.
import AppKit

let logo = NSImage(contentsOfFile: "art/nagori3k.svg")!

// Draws into a bitmap of exactly `size` pixels and saves it as PNG.
func png(_ size: Int, _ path: String, _ draw: (CGFloat) -> Void) {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    rep.size = NSSize(width: size, height: size)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current!.imageInterpolation = .high
    draw(CGFloat(size))
    NSGraphicsContext.restoreGraphicsState()
    try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: path))
}

// Mac: the square logo clipped to the system icon shape, which fills 824 of
// 1024 points with the margin left clear.
let set = "build/AppIcon.iconset"
try? FileManager.default.removeItem(atPath: set)
try! FileManager.default.createDirectory(atPath: set, withIntermediateDirectories: true)
for (points, scale) in [(16,1),(16,2),(32,1),(32,2),(128,1),(128,2),(256,1),(256,2),(512,1),(512,2)] {
    let name = scale == 1 ? "icon_\(points)x\(points).png" : "icon_\(points)x\(points)@2x.png"
    png(points * scale, "\(set)/\(name)") { s in
        let box = NSRect(x: s * 100 / 1024, y: s * 100 / 1024, width: s * 824 / 1024, height: s * 824 / 1024)
        NSBezierPath(roundedRect: box, xRadius: s * 185 / 1024, yRadius: s * 185 / 1024).addClip()
        logo.draw(in: box)
    }
}
let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", set, "-o", "mac/AppIcon.icns"]
try! iconutil.run(); iconutil.waitUntilExit()

// Android: adaptive icon foreground, 108dp at xxxhdpi. Launchers may crop to
// a circle 66dp across, so the logo is drawn small enough that its artwork
// stays inside that; the rest is black like the logo's own background.
png(432, "android/app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png") { s in
    NSColor.black.setFill(); NSRect(x: 0, y: 0, width: s, height: s).fill()
    let side = s * 0.72
    logo.draw(in: NSRect(x: (s - side) / 2, y: (s - side) / 2, width: side, height: side))
}

// For the README.
png(512, "art/nagori3k.png") { s in logo.draw(in: NSRect(x: 0, y: 0, width: s, height: s)) }
