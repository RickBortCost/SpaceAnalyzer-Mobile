plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}
android {
    namespace = "it.spaceanalyzer.mobile"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "it.spaceanalyzer.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.0"
        // The same APK runs on the Note9 and the Android 10 CI emulator.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val signingPath = providers.environmentVariable("SAM_KEYSTORE_PATH").orNull
    if (signingPath != null) {
        signingConfigs.create("privateRelease") {
            storeFile = file(signingPath)
            storePassword = providers.environmentVariable("SAM_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SAM_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SAM_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingPath != null) signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.28.3" }
    plugins { create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.85.1" } }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins { create("java") { option("lite") } }
            task.plugins { create("grpc") { option("lite") } }
        }
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
    implementation("io.grpc:grpc-okhttp:1.85.1")
    implementation("io.grpc:grpc-stub:1.85.1")
    implementation("io.grpc:grpc-protobuf-lite:1.85.1")
    implementation("com.google.protobuf:protobuf-javalite:4.28.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("io.grpc:grpc-netty-shaded:1.85.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
