plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

group = "fr.berliat.googledrivebackup"

extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "fr.berliat.googledrivebackup"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
    applyDefaultHierarchyTemplate()
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    swiftPMDependencies {
        swiftPackage(
            url = url("https://github.com/google/GoogleSignIn-iOS.git"),
            version = from("9.2.0"),
            products = listOf(product("GoogleSignIn"))
        )
        swiftPackage(
            url = url("https://github.com/google/google-api-objectivec-client-for-rest.git"),
            version = from("5.4.0"),
            products = listOf(product("GoogleAPIClientForREST_Drive"))
        )
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
                implementation("io.github.vinceglb:filekit-core:0.13.0")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
                implementation("com.google.android.gms:play-services-auth:21.5.1")
                implementation("com.google.apis:google-api-services-drive:v3-rev20250829-2.0.0")
            }
        }
    }
}
