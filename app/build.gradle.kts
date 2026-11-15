import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val releaseSigningFile = rootProject.file(".signing/signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
val releaseSigningValues = listOf(
    "GDEBYL_KEYSTORE", "GDEBYL_STORE_PASSWORD", "GDEBYL_KEY_ALIAS", "GDEBYL_KEY_PASSWORD"
).associateWith { name ->
    providers.environmentVariable(name).orNull ?: releaseSigningProperties.getProperty(name)
}
android {
    namespace = "ru.gdebyl"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.gdebyl"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            val store = releaseSigningValues["GDEBYL_KEYSTORE"]
            if (!store.isNullOrBlank()) {
                storeFile = rootProject.file(store)
                storePassword = releaseSigningValues["GDEBYL_STORE_PASSWORD"]
                keyAlias = releaseSigningValues["GDEBYL_KEY_ALIAS"]
                keyPassword = releaseSigningValues["GDEBYL_KEY_PASSWORD"]
            }
        }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("release"); isMinifyEnabled = false } }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("Activity Result API requires Fragment 1.3+; Play Services pulls in 1.1.0")
        }
    }
    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    ksp("androidx.room:room-compiler:2.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}

// Do not silently produce an unsigned release when signing credentials are missing.
tasks.configureEach {
    if (name == "preReleaseBuild") doFirst {
        releaseSigningValues.forEach { (name, value) ->
            require(!value.isNullOrBlank()) { "Set $name in the environment or .signing/signing.properties before building a release APK. See README.md." }
        }
    }
}
