import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.windinput.ime"
    // miuix 0.9.3 要求 compileSdk ≥ 37；AGP 8.13 官方推荐上限 36，
    // 已在 gradle.properties 里 suppressUnsupportedCompileSdk 抑制告警
    //
    // ⚠ API 37 起 SDK 平台包带**小版本号**（platforms;android-37.0 / 37.1 / 37.2），
    // 光写 compileSdk = 37 时 AGP 去找 hash "android-37"，新装的 SDK 里没有这个目录，
    // 报的是「Failed to find target with hash string 'android-37'」——看着像 SDK 没装全，
    // 其实是命名变了。compileSdkMinor 把 hash 补成 "android-37.0"。
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "io.github.shuimowang.kunkunime"
        // Personal-use baseline: Android 16 and newer.
        minSdk = 36
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0-preview.3"
        // 仪器测试（androidTest）：自绘控件的无障碍动作等要在真实 Android 运行时里验
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Each APK contains one supported native core. In particular, dependency
    // AARs must not make the app appear installable on unsupported 32-bit ABIs.
    // Both APKs use the same debug/release code and resources.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    // ── 签名 ─────────────────────────────────────────────────────────
    // 显式配置个人签名时 debug 与 release 共用该签名，以便覆盖安装。
    // 密钥文件与密码放 local.properties（已 gitignore）的 signing.* 四项，绝不进仓库。
    // 没给这四项（别人 clone、CI）：debug 使用 Android 工具在当前环境生成的调试密钥，
    // release 保持未签名。任何签名密钥都不提交源码仓库。
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    val personalSigning = listOf(
        "signing.storeFile", "signing.storePassword", "signing.keyAlias", "signing.keyPassword",
    ).map { localProps.getProperty(it) }.takeIf { values -> values.all { it != null } }

    signingConfigs {
        getByName("debug") {
            if (personalSigning != null) {
                val (store, storePass, alias, keyPass) = personalSigning
                storeFile = rootProject.file(store!!)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        debug {
            // Ship the same optimized preview APK that the device smoke installs.
            // It remains debuggable; this is not a release build or a new variant.
            isDebuggable = true
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-preview-entrypoints.pro",
            )
            // AGP applies this build's app mapping when processing androidTest.
            testProguardFiles("proguard-instrumentation.pro")
        }
        release {
            // R8 code/resource shrinking; JNA and UniFFI entry points are in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 同一把：有个人密钥才签 release
            if (personalSigning != null) signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Release names include both version and ABI so split outputs never replace
    // each other. Debug keeps Gradle's app-<abi>-debug.apk names for the scripts.
    applicationVariants.all {
        val variant = this
        if (variant.buildType.name == "release") {
            outputs.all {
                val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
                val abi = requireNotNull(output.getFilter(com.android.build.OutputFile.ABI))
                output.outputFileName =
                    "KunKunIME-Android-${variant.versionName}-$abi.apk"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Assemble dictionaries from this same checkout. Only the small phone-specific
// overlay in android/assets is maintained separately from the Windows data.
val prepareCoreAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    val python = providers.environmentVariable("PYTHON").orNull
        ?: if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    commandLine(python, "scripts/prepare-assets.py")
    inputs.dir(rootProject.file("../WindInput/data"))
    inputs.file(rootProject.file("../WindInput/data_custom/config.toml"))
    inputs.dir(rootProject.file("assets"))
    inputs.dir(rootProject.file("../licenses"))
    inputs.file(rootProject.file("../ORIGINS.md"))
    inputs.file(rootProject.file("../LICENSE"))
    inputs.file(rootProject.file("scripts/prepare-assets.py"))
    outputs.dir(file("src/main/assets/data"))
}

val verifyNativeCore by tasks.registering {
    val nativeCores = listOf("arm64-v8a", "x86_64").map { abi ->
        file("src/main/jniLibs/$abi/libwind_android.so")
    }
    inputs.files(nativeCores).optional()
    doLast {
        for (nativeCore in nativeCores) {
            check(nativeCore.isFile && nativeCore.length() > 0) {
                "Missing Chinese input core: $nativeCore. Run android/scripts/build-rust.sh release before building the APKs."
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareCoreAssets, verifyNativeCore)
}

// 单测直接读 src/main/assets 下的清单、布局、词库（BundledSchemasTest、LayoutParserTest…），
// 不声明为输入的话只改资源时 Gradle 判定测试 up-to-date、跳过不跑，缺文件也照样「通过」
tasks.withType<Test>().configureEach {
    dependsOn(prepareCoreAssets)
    inputs.dir("src/main/assets").withPathSensitivity(PathSensitivity.RELATIVE)
    // FactoryConfigSyncTest 对照主仓出厂配置：主仓改了也要重跑（单独 clone 本仓时没有，不声明）
    rootProject.file("../WindInput/data/config.toml").takeIf { it.isFile }?.let {
        inputs.file(it).withPathSensitivity(PathSensitivity.NONE)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // ExploreByTouchHelper：自绘键盘 / 候选栏的无障碍虚拟节点（TalkBack、uiautomator 都靠它看见键）
    implementation(libs.androidx.customview)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // 设置行（ArrowPreference / SwitchPreference / SliderPreference …），自带图标位与 miuix 的行高、分隔与点按反馈
    implementation(libs.miuix.preference)
    // TOML 解析（布局/设置清单；正式配置链路由 Rust wind-config 负责）
    implementation(libs.tomlj)
    implementation(libs.androidx.recyclerview)
    // UniFFI 生成绑定的运行时依赖（JNA 需 @aar 变体）
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.kotlinx.coroutines)

    testImplementation(libs.junit)
    // android.jar 里的 org.json 在 JVM 单测中只是桩（方法全抛异常）；词库统计的格式化要真解析
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
