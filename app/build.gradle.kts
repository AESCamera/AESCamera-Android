import org.gradle.api.GradleException
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

fun requireLocalProperty(name: String): String =
    localProperties.getProperty(name)
        ?: throw GradleException("Missing '$name' in local.properties")

android {
    namespace = "dev.dineshkumar.aescamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.dineshkumar.aescamera"
        // 26+ required: MediaRecorder.OutputFormat.MPEG_2_TS for encrypt-while-recording
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(requireLocalProperty("storeFile"))
            storePassword = requireLocalProperty("storePassword")
            keyAlias = requireLocalProperty("keyAlias")
            keyPassword = requireLocalProperty("keyPassword")
        }
    }


    buildFeatures {
        viewBinding = true
    }
    testOptions {
        // Let JVM unit tests call android.util.Log etc. as no-ops.
        unitTests.isReturnDefaultValues = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Vendored CameraView 2.7.2, patched for MPEG-2 TS output
    implementation(project(":cameraview"))

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.5.2")

    implementation("com.otaliastudios:zoomlayout:1.9.0")

    // EXIF orientation for decrypted photos (also an api dependency of :cameraview)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}