plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "alexcattivo.localchat.kit"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine"))
    implementation("org.json:json:20240303")
    // Шифрование базы (AES-256), как SQLCipher в iOS-версии.
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")
    // Разбор найденных страниц («режим чтения»), как SwiftSoup в iOS-версии.
    implementation("org.jsoup:jsoup:1.15.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Та же схема базы проверяется в обычных JVM-тестах на настоящем SQLite.
    testImplementation("org.xerial:sqlite-jdbc:3.41.2.2")
}
