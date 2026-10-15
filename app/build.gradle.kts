plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val splitKiB = providers.gradleProperty("localValidationSplitKiB").orNull ?: "384"
require(splitKiB in setOf("256", "384", "512")) { "localValidationSplitKiB must be 256, 384 or 512" }
require(splitKiB == "384" || providers.gradleProperty("localValidationReleaseSign").orNull == "true") {
    "Nondefault split budgets are restricted to explicitly signed local validation builds"
}
android {
    namespace = "com.localreader.app"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { buildConfigField("int", "EPUB_SPLIT_KIB", splitKiB); applicationId = "com.localreader.app"; minSdk = 29; targetSdk = 37; versionCode = 8; versionName = "0.6.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // This switch is for same-certificate local QA, never an implicit distribution key.
            if (providers.gradleProperty("localValidationReleaseSign").orNull == "true") signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    // Bundled fonts are streamed to WebView; stored uncompressed they need no per-read inflation.
    androidResources { noCompress.add("ttf") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }
    lint { warningsAsErrors = true; lintConfig = rootProject.file("lint.xml") }
}
kotlin { compilerOptions { allWarningsAsErrors.set(true) } }
val generateUiLicenses = tasks.register<Exec>("generateUiLicenses") {
    inputs.files(rootProject.file("docs/DEPENDENCIES.md"),rootProject.file("scripts/ui/gen_licenses.py"))
    inputs.files(fileTree("src/main/assets/fonts") {include("*-OFL.txt")},file("src/main/assets/reader/vendor/foliate/LICENSE"),fileTree("src/main/assets/notices"))
    outputs.file(file("src/main/assets/licenses.txt"))
    commandLine("python3",rootProject.file("scripts/ui/gen_licenses.py"))
}
tasks.named("preBuild") { dependsOn(generateUiLicenses) }
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.webkit:webkit:1.17.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
