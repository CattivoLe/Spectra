import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import Testing
@testable import Spectra

struct QRTests {
    @Test func linksAndTextAreToldApart() {
        #expect(QRPayload.classify("https://example.com/pay") == .link("https://example.com/pay"))
        #expect(QRPayload.classify("  Scan to pay: bit.ly/3abc  ") == .link("Scan to pay: bit.ly/3abc"))
        #expect(QRPayload.classify("Table 12") == .text("Table 12"))
        #expect(QRPayload.classify("BEGIN:VCARD\nFN:Alex\nEND:VCARD") == .text("BEGIN:VCARD\nFN:Alex\nEND:VCARD"))
    }

    @Test func wifiCodesAreParsedWithEscapes() {
        #expect(QRPayload.classify("WIFI:T:WPA;S:Cafe Wi-Fi;P:secret;;") == .wifi(ssid: "Cafe Wi-Fi", security: "WPA", hidden: false))
        #expect(QRPayload.classify("WIFI:S:Semi\\;colon;T:nopass;H:true;;") == .wifi(ssid: "Semi;colon", security: nil, hidden: true))
        #expect(QRPayload.classify("wifi:T:WEP;S:Old;P:1;") == .wifi(ssid: "Old", security: "WEP", hidden: false))
    }

    @Test func aGeneratedCodeIsReadBack() throws {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data("https://login-example.com.evil.test/verify".utf8)
        filter.correctionLevel = "M"
        let output = try #require(filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)))
        let context = CIContext()
        let image = try #require(context.createCGImage(output, from: output.extent))
        let payloads = try QRDecoder.payloads(in: image)
        #expect(payloads == ["https://login-example.com.evil.test/verify"])
    }

    @Test func aPlainPictureHasNoCode() throws {
        let blank = CIImage(color: .white).cropped(to: CGRect(x: 0, y: 0, width: 200, height: 200))
        let image = try #require(CIContext().createCGImage(blank, from: blank.extent))
        #expect(try QRDecoder.payloads(in: image).isEmpty)
    }
}
