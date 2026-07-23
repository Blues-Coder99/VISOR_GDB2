plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gdbviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gdbviewer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lectura de GeoPackage (SQLite + extensión espacial) - SDK oficial de NGA
    implementation("mil.nga.geopackage:geopackage-android:6.7.2")

    // Mapa libre, sin API key
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Transformación de sistemas de coordenadas (CTM12, MAGNA-SIRGAS, etc.)
    implementation("org.locationtech.proj4j:proj4j:1.3.0")
}
