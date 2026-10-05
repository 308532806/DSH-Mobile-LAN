// ⚠️ 必须显式导入 Base64：Gradle Kotlin DSL 里裸写 `java.util.Base64` 中的 `java`
// 会被解析成 java 插件扩展（java { }）而不是包名 —— 会报 Unresolved reference: util。
// import 必须放在 plugins 块之前（Kotlin 语法要求 import 先于其它语句）。
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（CI 双架构矩阵 / 模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "app.dsh.mobile"
    // CI 上 compileSdk=35；本地 SDK 只有 33/36/36.1 时可临时降/升到此值，
    // compileSdk 仅决定编译期 API 可见性，不影响运行时行为（targetSdk=28 才是生效阈值）。
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dsh.mobile"
        minSdk = 26
        // 关键决策：targetSdk 28 —— sideload 分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve bionic 二进制（Termux 同款策略）。
        targetSdk = 28
        versionCode = 109
        versionName = "1.3.12-lan"
        ndk {
            abiFilters += listOf(targetAbi)
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c11"
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 固定签名：CI 提供 ANDROID_KEYSTORE_BASE64 时用它替代 AGP 每次构建随机生成的
            // debug 密钥；本地构建（无该环境变量）完全保持原样。
            //
            // 为什么必须固定：GitHub Actions 每次跑在全新 runner 上，AGP 会当场生成一个
            // 随机 debug 密钥库 → 每个版本签名都不同 → 装新版必报“签名不一致”，
            // 用户每次都得先卸载（数据全丢）。固定之后可以覆盖安装。
            //
            // 密钥以 base64 存在 GitHub Secret 里、不进仓库 —— 公开仓库放私钥等于
            // 任何人都能伪造这个 App 的“官方更新”。
            System.getenv("ANDROID_KEYSTORE_BASE64")?.let { b64 ->
                signingConfig = signingConfigs.create("stable") {
                    val ks = File(project.layout.buildDirectory.asFile.get(), "stable-signing.jks")
                    ks.parentFile.mkdirs()
                    ks.writeBytes(Base64.getDecoder().decode(b64))
                    storeFile = ks
                    storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // targetSdk 28 会触发大量 lint 提示（前台服务类型、通知权限等），
        // 这些是刻意的兼容性决策，不阻断构建。
        abortOnError = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 扩展中心解包链路：Termux .deb 的 data.tar.xz 解码（纯 Java 实现，~110KB）
    implementation("org.tukaani:xz:1.10")
    // Shizuku 官方 API（m1.25）：bind 服务才能触发授权弹窗与真实 adb-shell 能力
    // aidl 提供 IShizukuService/IRemoteProcess（进程执行），api 提供授权与 binder 封装
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
}
