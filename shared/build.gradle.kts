import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    // Подавляем Beta-предупреждение для expect/actual классов
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // KMP DI (замена Hilt)
            implementation(libs.koin.core)
            // KMP Coroutines + Flow
            implementation(libs.kotlinx.coroutines.core)
            // KMP Settings (замена security-crypto / SharedPreferences)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
        }

        androidMain.dependencies {
            // Koin для Android
            implementation(libs.koin.android)
            // Nordic BLE SDK (только Android!)
            implementation(libs.nordic.ble)
            // Nordic DFU SDK (только Android!)
            implementation(libs.nordic.dfu)
        }

        // iOS-зависимости добавляем при реализации IosBleController (Этап 8)
        // iosMain.dependencies { }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "org.bootmoder.kmp.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}


