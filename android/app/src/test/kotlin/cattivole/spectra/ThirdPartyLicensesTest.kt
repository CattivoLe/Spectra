package cattivole.spectra

import cattivole.spectra.core.ThirdPartyLicenses
import cattivole.spectra.core.ThirdPartyLicenses.Group
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Licenses screen's catalog (`data/spectra/licenses.json`, shared with iOS) is complete and well formed. */
class ThirdPartyLicensesTest {
    private val json = javaClass.classLoader!!.getResourceAsStream(ThirdPartyLicenses.RESOURCE)!!.bufferedReader().use { it.readText() }

    @Test
    fun everyTextAComponentNamesExists() {
        val root = JSONObject(json)
        val texts = root.getJSONObject("texts")
        val list = root.getJSONArray("components")
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            if (item.isNull("text")) {
                assertTrue(item.getString("name"), item.getString("notice").isNotBlank())
            } else {
                assertTrue(item.getString("name"), texts.has(item.getString("text")))
            }
        }
        for (key in texts.keys()) assertTrue(key, texts.getString(key).length > 500)
    }

    @Test
    fun androidListsItsOwnComponentsAndNotTheIosOnes() {
        val android = ThirdPartyLicenses.parse(json, "android")
        val names = android.map { it.name }
        assertEquals("Spectra", android.first().name)
        assertEquals(Group.APP, android.first().group)
        for (expected in listOf("llama.cpp / ggml", "OkHttp", "osmdroid", "sqlcipher-android", "jsoup", "Brotli decoder", "Google code scanner", "Arduino core for ESP32", "OpenStreetMap")) {
            assertTrue(expected, expected in names)
        }
        for (iosOnly in listOf("GRDB.swift", "SwiftNIO", "SwiftSoup")) assertFalse(iosOnly, iosOnly in names)
        val ios = ThirdPartyLicenses.parse(json, "ios").map { it.name }
        assertTrue("GRDB.swift" in ios)
        assertFalse("OkHttp" in ios)
        assertFalse("OpenStreetMap" in ios)
    }

    @Test
    fun licenseTextIsReflowedForANarrowScreen() {
        val text = "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "of this software and associated documentation files.\n\n" +
            "Redistribution and use are permitted provided that:\n" +
            "    * Redistributions of source code must retain the above\n" +
            "      copyright notice.\n" +
            "    * Neither the name of the company nor the\n" +
            "      names of its contributors may be used.\n\n\n" +
            "   1. Definitions.\n"
        assertEquals(
            "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files.\n\n" +
                "Redistribution and use are permitted provided that:\n" +
                "* Redistributions of source code must retain the above copyright notice.\n" +
                "* Neither the name of the company nor the names of its contributors may be used.\n\n" +
                "1. Definitions.",
            ThirdPartyLicenses.reflow(text),
        )
        // Every shipped text keeps its words: only line breaks change.
        val all = ThirdPartyLicenses.parse(json, "android") + ThirdPartyLicenses.parse(json, "ios")
        for (component in all) {
            val original = component.text ?: continue
            val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() } }
            assertEquals(component.name, words(original), words(ThirdPartyLicenses.reflow(original)))
        }
    }

    @Test
    fun noticesTheLicensesAskForAreThere() {
        val all = ThirdPartyLicenses.parse(json, "android") + ThirdPartyLicenses.parse(json, "ios")
        // MIT and BSD keep their copyright line; the text alone would not satisfy them.
        for (component in all.filter { it.license == "MIT" || it.license.startsWith("BSD") }) {
            assertTrue(component.name, component.copyright.isNotBlank())
            assertNotNull(component.name, component.text)
        }
        val spectra = all.first { it.name == "Spectra" }
        assertEquals("Copyright (c) 2026 Alexander Omelchuk", spectra.copyright)
        assertTrue(spectra.text!!.startsWith("Permission is hereby granted"))
        assertTrue(all.first { it.name == "Arduino core for ESP32" }.text!!.contains("GNU LESSER GENERAL PUBLIC LICENSE"))
        assertTrue(all.first { it.name == "Arduino core for ESP32" }.notice.contains("github.com/CattivoLe/Spectra"))
        assertTrue(all.first { it.name == "OpenStreetMap" }.notice.contains("© OpenStreetMap contributors"))
        assertTrue(all.first { it.name == "SwiftNIO" }.notice.contains("The SwiftNIO Project"))
        assertTrue(all.first { it.name == "OkHttp" }.notice.contains("Mozilla Public License"))
    }
}
