import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Spectra app icon. Run: swift Tools/make_icon.swift Spectra/Assets.xcassets/AppIcon.appiconset/AppIcon.png
//
// Overlapping channel humps, as on the Channels tab, glowing on a deep
// night-blue field. Run from the ios folder.

let size = 1024
let s = CGFloat(size)
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ctx = CGContext(
    data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
    space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
)!

func rgb(_ hex: UInt32, _ a: CGFloat = 1) -> CGColor {
    CGColor(
        srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
        green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255,
        alpha: a
    )
}

// Background: vertical gradient, then a soft glow behind the humps.
let background = CGGradient(colorsSpace: space, colors: [rgb(0x1A1446), rgb(0x0A1328)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(background, start: CGPoint(x: 0, y: s), end: CGPoint(x: 0, y: 0), options: [])
let glow = CGGradient(colorsSpace: space, colors: [rgb(0x3B5BDB, 0.45), rgb(0x3B5BDB, 0)] as CFArray, locations: [0, 1])!
ctx.drawRadialGradient(glow, startCenter: CGPoint(x: s / 2, y: s * 0.36), startRadius: 0,
                       endCenter: CGPoint(x: s / 2, y: s * 0.36), endRadius: s * 0.55, options: [])

let base = s * 0.27

/// A flat-topped hump from `left` to `right`, `height` above the base line.
func hump(left: CGFloat, right: CGFloat, height: CGFloat) -> CGPath {
    let top = base + height
    let rise = (right - left) * 0.24
    let path = CGMutablePath()
    path.move(to: CGPoint(x: left, y: base))
    path.addCurve(to: CGPoint(x: left + rise, y: top),
                  control1: CGPoint(x: left + rise * 0.55, y: base),
                  control2: CGPoint(x: left + rise * 0.45, y: top))
    path.addLine(to: CGPoint(x: right - rise, y: top))
    path.addCurve(to: CGPoint(x: right, y: base),
                  control1: CGPoint(x: right - rise * 0.45, y: top),
                  control2: CGPoint(x: right - rise * 0.55, y: base))
    return path
}

struct Hump {
    var left: CGFloat, right: CGFloat, height: CGFloat, color: UInt32
}

let humps = [
    Hump(left: s * 0.10, right: s * 0.56, height: s * 0.30, color: 0x2EC4B6), // teal
    Hump(left: s * 0.44, right: s * 0.90, height: s * 0.38, color: 0xFF7A59), // coral
    Hump(left: s * 0.25, right: s * 0.75, height: s * 0.50, color: 0x8B6CFF), // violet, loudest
]

for h in humps {
    let path = hump(left: h.left, right: h.right, height: h.height)

    // Fill: bright under the top edge, fading to the base; screen blending
    // makes overlaps glow the way overlapping signals add up.
    ctx.saveGState()
    ctx.setBlendMode(.screen)
    ctx.addPath(path)
    ctx.closePath()
    ctx.clip()
    let fill = CGGradient(colorsSpace: space, colors: [rgb(h.color, 0.75), rgb(h.color, 0.12)] as CFArray, locations: [0, 1])!
    ctx.drawLinearGradient(fill, start: CGPoint(x: 0, y: base + h.height), end: CGPoint(x: 0, y: base), options: [])
    ctx.restoreGState()

    // Edge.
    ctx.saveGState()
    ctx.setBlendMode(.screen)
    ctx.addPath(path)
    ctx.setStrokeColor(rgb(h.color))
    ctx.setLineWidth(s * 0.016)
    ctx.setLineCap(.round)
    ctx.setLineJoin(.round)
    ctx.setShadow(offset: .zero, blur: s * 0.03, color: rgb(h.color, 0.9))
    ctx.strokePath()
    ctx.restoreGState()
}

// Base line.
ctx.setStrokeColor(rgb(0xFFFFFF, 0.55))
ctx.setLineWidth(s * 0.008)
ctx.setLineCap(.round)
ctx.move(to: CGPoint(x: s * 0.08, y: base))
ctx.addLine(to: CGPoint(x: s * 0.92, y: base))
ctx.strokePath()

let image = ctx.makeImage()!
let url = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, image, nil)
CGImageDestinationFinalize(dest)
print("wrote", url.path)
