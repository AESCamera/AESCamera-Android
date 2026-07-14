// Vendored from com.otaliastudios:cameraview:2.7.2 (https://github.com/natario1/CameraView)
// so that FullVideoRecorder can be patched to support MPEG-2 TS output for
// encrypt-while-recording. Patch sites are marked with "AESCamera patch" comments.
plugins {
    id("com.android.library")
}

android {
    namespace = "com.otaliastudios.cameraview"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
