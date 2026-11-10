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
        buildConfigField("boolean", "TAGGED", providers.environmentVariable("VISOR_VERSION_NAME").isPresent.toString())
    }

    // The release key comes from the environment: the CI's secrets, or a shell that exports the same names.
    // It is PKCS12, so the key has the keystore's password.
    val keystore = providers.environmentVariable("VISOR_KEYSTORE").orNull?.let(::file)
        ?.also { require(it.exists()) { "VISOR_KEYSTORE points at a missing file: $it" } }
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
            signingConfig = signingConfigs.findByName("release")
            // Phones only; the emulator runs debug builds.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }

    buildFeatures { buildConfig = true }

    // The glasses' model, stored uncompressed so the stage reads it without inflating.
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
    implementation(libs.androidx.browser)
    implementation(libs.visor.kit)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.filament.android)
    implementation(libs.gltfio.android)

    // The Android build is the .aar; the module's main artifact is the desktop jar.
    implementation(libs.zstd.jni) {
        artifact {
            type = "aar"
            extension = "aar"
        }
    }

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)
}
