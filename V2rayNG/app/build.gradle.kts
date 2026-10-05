import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.room3)
}

android {
    namespace = "com.v2ray.ang"
    compileSdk {
        version = release(37)
    }
    ndkVersion = providers.gradleProperty("NDK_VERSION").getOrElse("30.0.16248370")
    val abiFilterList = providers.gradleProperty("ABI_FILTERS")
        .map { it.split(';') }
        .getOrElse(emptyList())

    defaultConfig {
        applicationId = "com.v2ray.ang"
        minSdk {
            version = release(24)
        }
        targetSdk {
            version = release(37)
        }
        versionCode = 746
        versionName = "2.3.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    splits {
        abi {
            isEnable = true
            reset()
            if (!abiFilterList.isNullOrEmpty()) {
                include(*abiFilterList.toTypedArray())
            } else {
                include(
                    "arm64-v8a",
                )
            }
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("123.keystore")
            storePassword = "00000000"
            keyAlias = "123"
            keyPassword = "00000000"
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
        signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
        }
        create("playstore") {
            dimension = "distribution"
        }
    }

    sourceSets {
        named("main") {
            jniLibs.directories.add("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf(
            "en",
            "zh-rCN",
            "zh-rTW",
            "vi",
            "ru",
            "fa",
            "ar",
            "bn",
            "bqi-rIR"
        )
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

androidComponents {
    onVariants { variant ->
        val isFdroid = variant.productFlavors.any { it.first == "distribution" && it.second == "fdroid" }
        val distribution = if (isFdroid) "F-Droid" else "Play Store"
        checkNotNull(variant.buildConfigFields) { "BuildConfig must be enabled for ${variant.name}" }.put(
            "DISTRIBUTION",
            BuildConfigField("String", "\"$distribution\"", null)
        )
        val distributionSuffix = if (isFdroid) "-fdroid" else ""
        val abiVersionCodes = mapOf(
            "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
        )

        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            output.outputFileName.set(output.versionName.map { versionName ->
                "v2rayNG_${versionName}${distributionSuffix}_${abi}.apk"
            })

            val abiVersionCode = abiVersionCodes[abi] ?: return@forEach
            val baseVersionCode = output.versionCode.get()
            // Preserve the published version-code ranges for in-place updates.
            output.versionCode.set(
                if (isFdroid) 5_000_000 + 100 * baseVersionCode + abiVersionCode
                else 4_000_000 + baseVersionCode
            )
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

aboutLibraries {
    offlineMode = true

    collect {
        fetchRemoteLicense = false
        fetchRemoteFunding = false
        includePlatform = false
    }

    export {
        prettyPrint = false
        excludeFields.addAll("funding")
    }

    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
        duplicationRule = com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE
    }
}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose Libraries
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Open source licenses
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose.m3)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Room 3
    implementation(libs.androidx.room3.runtime)
    implementation(libs.androidx.room3.paging)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room3.compiler)

    // Paging 3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // QR Code: CameraX + ZXing
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.compose)
    implementation(libs.core) // zxing core

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Reorderable list
    implementation(libs.reorderable)

    // Widget
    implementation(libs.androidx.glance.appwidget)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)

    // DAO / Paging tests
    testImplementation(libs.androidx.sqlite.bundled.jvm)
    testImplementation(libs.androidx.room3.testing)
    testImplementation(libs.androidx.paging.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
