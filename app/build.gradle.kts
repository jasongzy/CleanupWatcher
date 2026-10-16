import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val keystoreProperties =
    Properties().apply {
        val source = rootProject.file("keystore.properties")
        if (source.isFile) source.inputStream().use(::load)
    }
val releaseSigning =
    mapOf(
            "storeFile" to "RELEASE_STORE_FILE",
            "storePassword" to "RELEASE_STORE_PASSWORD",
            "keyAlias" to "RELEASE_KEY_ALIAS",
            "keyPassword" to "RELEASE_KEY_PASSWORD",
        )
        .mapValues { (localName, propertyName) ->
            providers.gradleProperty(propertyName).orNull
                ?: keystoreProperties.getProperty(localName)
        }
val releaseStoreFile =
    releaseSigning["storeFile"]?.takeIf { it.isNotBlank() }?.let(rootProject::file)
val validateReleaseSigning =
    tasks.register("validateReleaseSigning") {
        val configured = releaseSigning.values.all { !it.isNullOrBlank() }
        val store = releaseStoreFile
        doLast {
            check(configured) {
                "Release signing requires keystore.properties or RELEASE_* Gradle properties"
            }
            check(store?.isFile == true) { "Release keystore file does not exist" }
        }
    }

tasks.configureEach {
    if (name == "preReleaseBuild") dependsOn(validateReleaseSigning)
}

android {
    namespace = "com.jasongzy.cleanupwatcher"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "com.jasongzy.cleanupwatcher"
        minSdk = 30
        targetSdk = 37
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }
    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["keyAlias"]
            keyPassword = releaseSigning["keyPassword"]
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { jniLibs.useLegacyPackaging = true }
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "zh-rCN")
    }
}

abstract class BuildBackend : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:InputFile abstract val compiler: RegularFileProperty
    @get:InputFiles abstract val sources: ConfigurableFileCollection
    @get:Input abstract val abi: Property<String>
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val binary = outputDirectory.file("${abi.get()}/libcleanupwatcherd.so").get().asFile
        check(binary.parentFile.mkdirs() || binary.parentFile.isDirectory)
        execOperations.exec {
            executable(compiler.get().asFile)
            args(
                "-std=c++20",
                "-O2",
                "-Wall",
                "-Wextra",
                "-Werror",
                "-fPIE",
                "-pie",
                "-static-libstdc++",
                "-D_FILE_OFFSET_BITS=64",
                "-Wl,-z,relro,-z,now",
                "-Wl,-z,max-page-size=16384",
            )
            args(sources.files.filter { it.extension == "cpp" })
            args("-o", binary)
        }
    }
}

val hostOs = System.getProperty("os.name").lowercase()
val nativeHost =
    when {
        hostOs.contains("windows") -> "windows-x86_64"
        hostOs.contains("mac") -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
val buildBackends =
    mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "armv7a-linux-androideabi",
            "x86" to "i686-linux-android",
            "x86_64" to "x86_64-linux-android",
        )
        .map { (abiName, target) ->
            tasks.register<BuildBackend>("buildBackend${abiName.replace('-', '_')}") {
                abi.set(abiName)
                outputDirectory.set(layout.buildDirectory.dir("generated/backend-$abiName"))
                val nativeCompiler =
                    "ndk/${libs.versions.ndk.get()}/toolchains/llvm/prebuilt/$nativeHost/bin/${target}30-clang++" +
                        if (hostOs.contains("windows")) ".cmd" else ""
                compiler.set(
                    androidComponents.sdkComponents.sdkDirectory.map { it.file(nativeCompiler) }
                )
                sources.from(
                    rootProject.fileTree("native") {
                        include("core.*", "daemon.cpp", "lifecycle.*", "browser.*", "records.h")
                    }
                )
            }
        }

androidComponents.onVariants { variant ->
    buildBackends.forEach { task ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task) { it.outputDirectory }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core)
    implementation(libs.appcompat)
    debugImplementation(libs.compose.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
