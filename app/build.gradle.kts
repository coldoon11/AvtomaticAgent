plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.autonomousai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.autonomousai"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val handModel = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")
val downloadHandModel by tasks.registering {
    outputs.file(handModel)
    doLast {
        val target = handModel.asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val url = java.net.URI(
                "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
            ).toURL()
            println("Downloading MediaPipe hand_landmarker.task …")
            url.openStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadHandModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    val cameraX = "1.5.0"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
}
