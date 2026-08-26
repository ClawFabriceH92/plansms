plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.fabrice.plansms"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fabrice.plansms"
        minSdk = 29
        targetSdk = 35
        versionCode = 25
        versionName = "0.9.8"
    }

    // Signature CI : la release est signée avec le keystore fourni via variables
    // d'environnement (GitHub Actions). En local, rien ne change (debug par défaut).
    val ciKeystorePath: String? = System.getenv("PLANSMS_KEYSTORE")
    if (ciKeystorePath != null) {
        signingConfigs {
            create("ci") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("PLANSMS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PLANSMS_KEY_ALIAS") ?: "plansms"
                keyPassword = System.getenv("PLANSMS_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ciKeystorePath != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room (BDD locale)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (scheduling fiable)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Export des enregistrements : FTP/FTPS + envoi email (SMTP)
    implementation("commons-net:commons-net:3.9.0")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
