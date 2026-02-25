plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

android {
    namespace = "fr.berliat.googledrivebackup"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
            implementation("io.github.vinceglb:filekit-core:0.13.0")
        }
        androidMain.dependencies {
            implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3") // Downgraded slightly to match stable if needed, sticking to what was there or close suitable for KMP
            
            // Let's have a gDrive Cloud Backup
            implementation("com.google.android.gms:play-services-auth:21.4.0")
            implementation("com.google.apis:google-api-services-drive:v3-rev20250829-2.0.0")
        }
        iosMain.dependencies {
            
        }
    }
}