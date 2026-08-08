// CameraView, consumed as source from the git submodule at libs/CameraView (a fork of
// https://github.com/natario1/CameraView with MPEG-2 TS output support for
// encrypt-while-recording). Only the build config and manifest live here; the Java and
// resources come from the submodule, so the fork stays a pristine, publishable checkout.
plugins {
    id("com.android.library")
}

val cameraViewMain = "../libs/CameraView/cameraview/src/main"

android {
    namespace = "com.otaliastudios.cameraview"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    sourceSets {
        getByName("main") {
            java.setSrcDirs(listOf("$cameraViewMain/java"))
            res.setSrcDirs(listOf("$cameraViewMain/res"))
            // Manifest stays local: the fork's still carries the legacy package=
            // attribute that modern AGP rejects.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    api("androidx.exifinterface:exifinterface:1.3.3")
    api("androidx.lifecycle:lifecycle-common:2.3.1")
    api("com.google.android.gms:play-services-tasks:17.2.1")
    api("androidx.annotation:annotation:1.2.0")
    api("com.otaliastudios.opengl:egloo:0.6.1")
}
