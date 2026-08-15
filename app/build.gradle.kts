import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Ключ подписи держим вне репозитория: keystore.properties и .jks в .gitignore.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "vip.sazanuwu.vrgproxy"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "vip.sazanuwu.vrgproxy"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Ссылка на подписку приходит из переменной окружения, а не из кода —
        // чтобы её не выцепили сканеры публичных репозиториев. В собранном APK
        // строка всё равно видна (strings apk | grep https), так что от чтения
        // самого файла это не защищает.
        val subscriptionUrl = System.getenv("SUBSCRIPTION_URL").orEmpty()
        buildConfigField("String", "DEFAULT_SUBSCRIPTION", "\"$subscriptionUrl\"")
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    // Ядро весит ~50 МБ на архитектуру, поэтому кроме универсального APK
    // собираем отдельные: под конкретный телефон качать вдвое меньше.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // Ядро должно лежать на диске как настоящий файл, иначе его нельзя запустить.
            useLegacyPackaging = true
        }
        resources.excludes += setOf("META-INF/*")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.yaml:snakeyaml:2.3")
}
