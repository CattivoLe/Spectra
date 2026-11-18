plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "alexcattivo.localchat.engine"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 31
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared", "-DCMAKE_BUILD_TYPE=Release")
                cppFlags += listOf("-O3")
            }
        }
        ndk {
            // Модели в разумное время считаются только на 64-битном ARM; x86_64 — для эмулятора на Intel.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        // Отладочная сборка llama.cpp без оптимизаций в десятки раз медленнее: собираем её как релиз всегда.
        debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    api(platform("androidx.compose:compose-bom:2024.09.03"))
    // Наблюдаемое состояние (как @Observable в iOS): mutableStateOf работает и без экрана, в том числе в тестах.
    api("androidx.compose.runtime:runtime")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
