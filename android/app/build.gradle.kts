import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cattivole.spectra"
    compileSdk = 35

    defaultConfig {
        applicationId = "cattivole.spectra"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // The OUI and Bluetooth SIG tables are shared with the iOS app and live in
    // the top-level data/ directory, loaded from the classpath as spectra/*.tsv.
    // App-only strings stay under src/main/resources.
    sourceSets["main"].resources.srcDir("../../data")
    // spectra-flash.zip, packed from the last pio build when that output exists.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/flashAssets"))

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// The models are plain mutable objects; SpectraModel.generation tells the UI to
// redraw. Strong skipping would skip any row whose object is the same instance
// as before, so a changed network or sensor state would not be drawn.
composeCompiler {
    enableStrongSkippingMode = false
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.brotli:dec:0.1.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // HistoryCodec uses org.json, which is only a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}

// A blank ESP32-S3 needs the bootloader and partition table, not just firmware.bin.
// The zip is an asset when the last `pio run` output is on disk; otherwise the
// app still builds and the flash screen asks for a bundle.
val flashAssets = layout.buildDirectory.dir("generated/flashAssets")

tasks.register("packFlashBundle") {
    val built = rootProject.file("../firmware/.pio/build/m5stack-cardputer-adv")
    inputs.files(
        built.resolve("bootloader.bin"),
        built.resolve("partitions.bin"),
        built.resolve("firmware.bin"),
    ).optional()
    outputs.dir(flashAssets)
    doLast {
        val dir = flashAssets.get().asFile
        dir.mkdirs()
        val zipFile = dir.resolve("spectra-flash.zip")
        val bootloader = built.resolve("bootloader.bin")
        val partitions = built.resolve("partitions.bin")
        val firmware = built.resolve("firmware.bin")
        val otadata = listOf(
            built.resolve("boot_app0.bin"),
            file("${System.getProperty("user.home")}/.platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin"),
        ).firstOrNull { it.isFile }
        if (!bootloader.isFile || !partitions.isFile || !firmware.isFile || otadata == null) {
            zipFile.delete()
            return@doLast
        }
        val table = partitions.readBytes()
        val otadataOffset = partitionOffset(table, "otadata") ?: 0xe000L
        val appOffset = partitionOffset(table, "app0") ?: 0x10000L
        val manifest = """{"chip":"esp32s3","flashSize":8388608,"files":[{"offset":0,"name":"bootloader.bin"},{"offset":32768,"name":"partitions.bin"},{"offset":$otadataOffset,"name":"boot_app0.bin"},{"offset":$appOffset,"name":"firmware.bin"}]}"""
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("flash.json", manifest.toByteArray())
            put("bootloader.bin", bootloader.readBytes())
            put("partitions.bin", partitions.readBytes())
            put("boot_app0.bin", otadata.readBytes())
            put("firmware.bin", firmware.readBytes())
        }
    }
}

tasks.matching { it.name == "preBuild" || (it.name.startsWith("merge") && it.name.endsWith("Assets")) }.configureEach {
    dependsOn("packFlashBundle")
}

/** Offset of a named entry in an ESP-IDF partition table. */
fun partitionOffset(table: ByteArray, label: String): Long? {
    var index = 0
    while (index + 32 <= table.size) {
        val magic = (table[index].toInt() and 0xFF) or ((table[index + 1].toInt() and 0xFF) shl 8)
        if (magic == 0xFFFF) return null
        if (magic != 0x50AA) return null
        val offset = (table[index + 4].toLong() and 0xFF) or
            ((table[index + 5].toLong() and 0xFF) shl 8) or
            ((table[index + 6].toLong() and 0xFF) shl 16) or
            ((table[index + 7].toLong() and 0xFF) shl 24)
        val name = String(table, index + 12, 16, Charsets.UTF_8).substringBefore('\u0000')
        if (name == label) return offset
        index += 32
    }
    return null
}
