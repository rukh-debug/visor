plugins {
    id("visor.android.application")
    id("visor.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// "v1.3.2-2-g1f165af" from git describe -> ("1.3.2-dev.2", 10302); ("0.1", 1) without a tag or git.
val gitVersion: Pair<String, Int> = run {
    val described = runCatching {
        providers.exec {
            commandLine("git", "describe", "--tags", "--long", "--match", "v[0-9]*")
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    }.getOrDefault("")
    val m = Regex("""^v(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?-(\d+)-g[0-9a-f]+$""").find(described)
        ?: return@run "0.1" to 1
    val (major, minor, patch, pre, ahead) = m.destructured
    val name = "$major.$minor.$patch$pre" + if (ahead != "0") "-dev.$ahead" else ""
    name to major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

android {
    namespace = "com.macedonga.visor"

    defaultConfig {
        applicationId = "com.macedonga.visor"
        // A release build takes both from its tag (.github/workflows/release.yml); any other build from the last
        // upstream tag, as release.yml counts it, with how many commits it is past it: 1.3.2, or 1.3.2-dev.2.
        versionCode = providers.environmentVariable("VISOR_VERSION_CODE").orNull?.toInt() ?: gitVersion.second
        versionName = providers.environmentVariable("VISOR_VERSION_NAME").orNull ?: gitVersion.first
    }

    // The release key comes from the environment: the CI's secrets, or a shell that exports the same names.
    // It is PKCS12, so the key has the keystore's password.
    val keystore = providers.environmentVariable("VISOR_KEYSTORE").orNull?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = providers.environmentVariable("VISOR_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("VISOR_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("VISOR_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
        aidl = false
        shaders = false
    }


    // The glasses' model is read whole into a direct buffer; stored uncompressed it maps straight in.
    androidResources {
        noCompress += "glb"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:design"))
    implementation(project(":core:protocol"))
    implementation(project(":core:transport"))
    implementation(project(":core:session"))
    implementation(project(":core:identity"))
    implementation(project(":core:connection"))
    implementation(project(":core:media"))
    implementation(project(":core:settings"))
    implementation(project(":core:assistant"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)

    // Navigation 3, for the test client's screens.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.filament.android)
    implementation(libs.gltfio.android)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
}
