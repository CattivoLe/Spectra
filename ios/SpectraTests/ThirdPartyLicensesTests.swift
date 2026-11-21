import Foundation
import Testing
@testable import Spectra

/// The Licenses screen's catalog (`Catalog/licenses.json`, shared with Android) ships in the app and is complete.
struct ThirdPartyLicensesTests {
    @Test func theCatalogShipsWithTheApp() throws {
        let components = ThirdPartyLicenses.bundled
        #expect(components.first?.name == "Spectra")
        #expect(components.first?.group == .app)
        let names = Set(components.map(\.name))
        for expected in ["llama.cpp / ggml", "GRDB.swift", "SQLCipher", "SwiftNIO", "Arduino core for ESP32", "MAC vendor list"] {
            #expect(names.contains(expected), "\(expected) is missing")
        }
        // Android's own libraries are not shipped here.
        for androidOnly in ["OkHttp", "osmdroid", "OpenStreetMap", "Google code scanner"] {
            #expect(!names.contains(androidOnly), "\(androidOnly) is listed on iOS")
        }
    }

    @Test func noticesTheLicensesAskForAreThere() throws {
        for component in ThirdPartyLicenses.bundled {
            if component.license == "MIT" || component.license.hasPrefix("BSD") {
                #expect(!component.copyright.isEmpty, "\(component.name) has no copyright line")
                #expect((component.text ?? "").count > 500, "\(component.name) has no license text")
            }
            if component.text == nil {
                #expect(!component.notice.isEmpty, "\(component.name) shows nothing")
            }
        }
        let spectra = try #require(ThirdPartyLicenses.bundled.first { $0.name == "Spectra" })
        #expect(spectra.copyright == "Copyright (c) 2026 Alexander Omelchuk")
        let nio = try #require(ThirdPartyLicenses.bundled.first { $0.name == "SwiftNIO" })
        #expect(nio.notice.contains("The SwiftNIO Project"))
    }

    @Test func licenseTextIsReflowedForANarrowScreen() {
        let text = "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files.\n\n" +
            "Redistribution and use are permitted provided that:\n" +
            "    * Redistributions of source code must retain the above\n" +
            "      copyright notice.\n\n\n" +
            "   1. Definitions.\n"
        #expect(ThirdPartyLicenses.reflow(text) ==
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files.\n\n" +
            "Redistribution and use are permitted provided that:\n" +
            "* Redistributions of source code must retain the above copyright notice.\n\n" +
            "1. Definitions.")
        // Every shipped text keeps its words: only line breaks change.
        for component in ThirdPartyLicenses.bundled {
            guard let original = component.text else { continue }
            let words = { (s: String) in s.split(whereSeparator: \.isWhitespace) }
            #expect(words(original) == words(ThirdPartyLicenses.reflow(original)), "\(component.name) lost words")
        }
    }
}
