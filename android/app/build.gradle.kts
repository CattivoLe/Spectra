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
    // One zip per board, packed from the last pio build when that output exists.
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
    // The system QR scanner sheet: no camera permission, the camera never reaches the app.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // HistoryCodec uses org.json, which is only a stub in local unit tests.
    testImplementation("org.json:json:20240303")
}

// A blank chip needs the bootloader and partition table, not just firmware.bin.
// One zip per board that has a pio build on disk. The S3 zip is also written as
// spectra-flash.zip so an older asset name still loads. Missing builds are
// removed so a stale image is not flashed onto the wrong board.
val flashAssets = layout.buildDirectory.dir("generated/flashAssets")

data class FlashTarget(
    val env: String,
    val chip: String,
    val flashSize: Long,
    val bootOffset: Long,
    val asset: String,
)

val flashTargets = listOf(
    FlashTarget("m5stack-cardputer-adv", "esp32s3", 8L * 1024 * 1024, 0L, "spectra-flash-esp32s3.zip"),
    FlashTarget("esp32-c5-n4", "esp32c5", 4L * 1024 * 1024, 0x2000L, "spectra-flash-esp32c5.zip"),
    FlashTarget("esp32-wroom32-ch340", "esp32", 4L * 1024 * 1024, 0x1000L, "spectra-flash-esp32.zip"),
)

tasks.register("packFlashBundle") {
    flashTargets.forEach { target ->
        val built = rootProject.file("../firmware/.pio/build/${target.env}")
        inputs.files(
            built.resolve("bootloader.bin"),
            built.resolve("partitions.bin"),
            built.resolve("firmware.bin"),
        ).optional()
    }
    outputs.dir(flashAssets)
    doLast {
        val dir = flashAssets.get().asFile
        dir.mkdirs()
        val otadata = listOf(
            rootProject.file("../firmware/.pio/build/m5stack-cardputer-adv/boot_app0.bin"),
            rootProject.file("../firmware/.pio/build/esp32-c5-n4/boot_app0.bin"),
            rootProject.file("../firmware/.pio/build/esp32-wroom32-ch340/boot_app0.bin"),
            file("${System.getProperty("user.home")}/.platformio/packages/framework-arduinoespressif32/tools/partitions/boot_app0.bin"),
        ).firstOrNull { it.isFile }
        var cardputer: java.io.File? = null
        for (target in flashTargets) {
            val built = rootProject.file("../firmware/.pio/build/${target.env}")
            val zipFile = dir.resolve(target.asset)
            val bootloader = built.resolve("bootloader.bin")
            val partitions = built.resolve("partitions.bin")
            val firmware = built.resolve("firmware.bin")
            if (!bootloader.isFile || !partitions.isFile || !firmware.isFile || otadata == null) {
                zipFile.delete()
                continue
            }
            val table = partitions.readBytes()
            val otadataOffset = partitionOffset(table, "otadata") ?: 0xe000L
            val appOffset = partitionOffset(table, "app0") ?: 0x10000L
            val manifest = """{"chip":"${target.chip}","flashSize":${target.flashSize},"files":[{"offset":${target.bootOffset},"name":"bootloader.bin"},{"offset":32768,"name":"partitions.bin"},{"offset":$otadataOffset,"name":"boot_app0.bin"},{"offset":$appOffset,"name":"firmware.bin"}]}"""
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
            if (target.chip == "esp32s3") cardputer = zipFile
        }
        val legacy = dir.resolve("spectra-flash.zip")
        val source = cardputer
        if (source != null && source.isFile) source.copyTo(legacy, overwrite = true) else legacy.delete()
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
