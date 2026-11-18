import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val secrets = Properties().apply {
    val config = rootProject.file("secrets.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
val fieldTest = providers.gradleProperty("yuntongFieldTest").orNull == "true"
val fieldSigningStore = providers.gradleProperty("yuntongFieldSigningStore").orNull
val amapKey = if (fieldTest) "" else secrets.getProperty("AMAP_KEY", "").trim()
require(amapKey.isEmpty() || amapKey.matches(Regex("[A-Za-z0-9]{32}"))) {
    "AMAP_KEY must be a 32-character Android Key or blank."
}
android {
    namespace = "cn.yuntong.companion"
    compileSdk = 35
    compileSdkExtension = 15
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.yuntong.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = 116
        versionName = "1.0.0-rc17"
        manifestPlaceholders["AMAP_KEY"] = amapKey
        manifestPlaceholders["APP_LABEL"] = if (fieldTest) "云瞳 · 联调测试" else "云瞳"
        buildConfigField("String", "AMAP_KEY", "\"$amapKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (fieldTest) {
        sourceSets.getByName("debug").manifest.srcFile("src/fieldTest/AndroidManifest.xml")
        buildTypes.getByName("debug") {
            applicationIdSuffix = ".fieldtest"
            versionNameSuffix = "-r01"
        }
        if (fieldSigningStore != null) {
            signingConfigs.getByName("debug").storeFile = rootProject.file(fieldSigningStore)
        }
    }
    buildFeatures { compose=true; buildConfig=true }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES") }
}

val bundledModels = listOf("efficientdet-lite0.tflite", "vosk-model-small-cn-0.22.zip")
if (fieldTest) {
    androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) {
        it.enable = false
    }
}
val prepareModels by tasks.registering {
    doLast {
        val missing = bundledModels.filterNot { layout.projectDirectory.file("src/main/assets/$it").asFile.isFile }
        check(missing.isEmpty()) {
            "Missing assets: ${missing.joinToString()}. Run python tools/fetch_assets.py from the repository root."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareModels) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.amap.api:navi-3dmap:10.0.800_3dmap10.0.800")
    implementation("com.amap.api:search:9.7.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("com.google.android.apps.common.testing.accessibility.framework:accessibility-test-framework:4.1.1")
}
