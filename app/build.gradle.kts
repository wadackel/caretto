import java.util.regex.Pattern

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "dev.wadackel.caretto"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.wadackel.caretto"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // Deliberately unsigned. The debug key ships with the SDK and is the same for
            // everyone, so anything signed with it can be replaced by anyone — which for an app
            // that sees every keystroke is not a thing to hand out. Supply a real key to release.
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
    }

    lint {
        // There is no baseline and one suppression, ProtectedPermissions on WRITE_SECURE_SETTINGS
        // in the manifest, which is granted from adb rather than at install. Anything else that
        // warns is a thing to fix rather than a thing to scroll past. Nobody opens the log of a
        // green build.
        warningsAsErrors = true

        disable += setOf(
            // These ask what the newest version is, so they turn red on the day Google publishes
            // something — with no change here, and differently on two machines depending on what
            // each one has learned. OldTargetApi joined the list after CI failed on it while the
            // same lint passed locally. Moving targetSdk is a behavioural change to make and test
            // deliberately, not one to be pushed into by a build going red on its own.
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
            // Suggests merging mipmap-anydpi-v26 into mipmap-anydpi, which minSdk 33 does make
            // redundant. Tried twice; both times aapt2 then fails to resolve mipmap/ic_launcher at
            // all, so the folder keeps its qualifier.
            "ObsoleteSdkInt",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // The notice test reads the list the debug build generates, rather than a copy of it.
            if (test.name == "testDebugUnitTest") {
                test.dependsOn("prepareLibraryDefinitionsDebug")
                test.systemProperty(
                    "caretto.libraryDefinitions",
                    layout.buildDirectory.file("generated/aboutLibraries/debug/res/raw/aboutlibraries.json").get().asFile.path,
                )
            }
        }
    }
}

// About names the commit a build came from, so a phone can be matched to the tree it runs, and
// marks a build from a tree with uncommitted changes, which is not that commit. Read through
// providers so git answers on each build. Without git (a source archive) the name stays 0.1.0.
val gitCommit = providers.exec {
    commandLine("git", "rev-parse", "--short=7", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim() }

val gitDirty = providers.exec {
    commandLine("git", "status", "--porcelain")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }

/** Puts the repository's LICENSE in the APK, where About reads it, so the two cannot disagree. */
abstract class OwnLicenseAsset : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        license.get().asFile.copyTo(output.get().file("licenses/Caretto-LICENSE.txt").asFile, overwrite = true)
    }
}

val ownLicenseAsset = tasks.register<OwnLicenseAsset>("ownLicenseAsset") {
    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(ownLicenseAsset, OwnLicenseAsset::output)
        val name = gitCommit.zip(gitDirty) { commit, dirty ->
            when {
                commit.isEmpty() -> "0.1.0"
                dirty -> "0.1.0+$commit-dirty"
                else -> "0.1.0+$commit"
            }
        }
        variant.outputs.forEach { it.versionName.set(name) }
    }
}

aboutLibraries {
    // Offline because the plugin otherwise downloads each licence text from spdx.org during the
    // build and, when that request fails, ships the entry without its text and says nothing. The
    // texts live in aboutlibraries/licenses instead, and a unit test fails on any entry left empty.
    offlineMode = true
    collect {
        configPath = file("aboutlibraries")
    }
    export {
        excludeFields.addAll("description", "developers", "funding", "scm", "organization")
    }
    library {
        mergePlatformArtifacts = true
        // A BOM only pins versions; nothing of it is in the APK.
        exclusionPatterns.add(Pattern.compile(".*-bom"))
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
