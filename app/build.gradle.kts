@file:Suppress("UnstableApiUsage")

import com.android.build.api.variant.impl.VariantOutputImpl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.com.android.application)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.parcelize)
    alias(libs.plugins.org.jetbrains.kotlin.plugin.serialization)
    alias(libs.plugins.com.google.devtools.ksp)
    alias(libs.plugins.compose.compiler)
    id("com.mikepenz.aboutlibraries.plugin") version "15.0.4"
    id("com.github.ben-manes.versions") version "0.59.0"
}

//<editor-fold desc="Release 签名">
// 发布密钥与口令放在 local.properties（已被 .gitignore 忽略）。
// 键名与另一分支的工程保持一致：keystore.file / keystore.password / key.alias [/ key.password]
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("local.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun releaseProp(key: String): String? =
    keystoreProperties.getProperty(key)?.takeIf(String::isNotBlank)

val releaseStoreFile = releaseProp("keystore.file")?.let { rootProject.file(it) }
val releaseStorePassword = releaseProp("keystore.password")
val releaseKeyAlias = releaseProp("key.alias")
val releaseKeyPassword = releaseProp("key.password") ?: releaseStorePassword
val hasReleaseSigning = releaseStoreFile != null &&
        releaseStoreFile.exists() &&
        releaseStorePassword != null &&
        releaseKeyAlias != null

// 只有在真的构建 release 时才强制要求签名，debug 构建不受影响。
// 宁可配置期就失败，也不要静默产出未签名 / 错密钥的 APK 被当成正式版发出去。
val buildingRelease =
    gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (buildingRelease && !hasReleaseSigning) {
    error(
        "[signing] 未找到可用的 release 签名配置，拒绝产出未签名 APK。\n" +
                "  请在本机 local.properties 中补充：\n" +
                "    keystore.file=<keystore 路径>\n" +
                "    keystore.password=<store 口令>\n" +
                "    key.alias=<别名>\n" +
                "    key.password=<key 口令，缺省则等同 store 口令>"
    )
}
//</editor-fold>

android {
    compileSdk = 37

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.daisukikaffuchino.han1meviewer"
        minSdk = 29
        targetSdk = 37
        // ────────────────────────────────────────────────────────────────────
        // 版本号规则（26.6.1 起）
        //
        // versionName 从 `26.3.2-mod.26.6` 这种「上游基准 + mod 后缀」改成了**干净的
        // 三段号** `26.6.1`，APK 也就叫 `Han1meViewer-v26.6.1.apk`，不再带 `mod`。
        // 于是版本号里**不再编码「本构建基于哪个上游版本」** —— 那个信息挪到
        // UPSTREAM_BASE_VERSION（见下），AppUpdateChecker 读它做上游比较。
        //
        // versionCode = major*1_000_000 + minor*1_000 + patch，与 versionName 一一对应：
        //     26.6.1 → 26_006_001
        //     26.6.2 → 26_006_002
        //     26.6.3 → 26_006_003
        //     26.6.4 → 26_006_004
        //     26.6.5 → 26_006_005
        //     26.7.0 → 26_007_000
        //     26.7.1 → 26_007_001
        //     26.7.2 → 26_007_002
        //     26.7.3 → 26_007_003
        //     26.8   → 26_008_000
        //     26.8.1 → 26_008_001
        //     26.8.2 → 26_008_002
        //     26.8.3 → 26_008_003   本行在 26.8.3 重发时**复用**（用户明确要求按 8.3 发）：
        //                            网络层回退到 26.8.2 基线重做，getchu 发售表保留，
        //                            日历默认标签改回「已上架」并去掉里番限制。
        //     26.8.4 → 26_008_004   （已发布、已作废：它就是被打回的那版网络改动）
        //     26.9.0 → 26_009_000   9.0：删 getchu / 删一键自愈 / 图片链路加固 / 收藏夹 /
        //                            关注新作提醒 / 更新说明 markdown
        //     26.9.1 → 26_009_001   9.1：修「作者/女优最多十页」（分页死锁）+ hanime 作者页总页数
        //     26.9.2 → 26_009_002   9.2：修「检测更新很慢」——更新源赛跑（不等最慢的源）+
        //                            修钉错的 jsDelivr IP + 首页不再等更新检查
        //     26.9.3 → 26_009_003   9.3：更新源 5→8 条（含 jsDelivr Bunny / GitHub 本体 / 第三方代理）
        //                            + 两条元数据兜底；修「把主动放弃记成失败」的误导日志
        //     26.9.4 → 26_009_004   9.4：作者页分页重做 —— **一页 = 站点自己的一页**
        //                            （不再按 12 条切，修「末页 404 / 作品遗失 / 跳页要拉几十次」）,
        //                            页码条改成「跟着当前页长」（只到 6，点 6 再展开 7/8/9），
        //                            nJAV 女优页用站点自己的页数 + 新增作品搜索框
        //     26.9.5 → 26_009_005   9.5：Pornhub 首页加**站点自己的「推荐」**一行
        //                            （`/recommended`，不是又一个排序；慢的那 1 MB HTML
        //                            单独一档、不挡首屏）+ 越界页码 404 当「到底了」
        //     26.9.6 → 26_009_006   9.6：「推荐」那一行改成**一屏一张的大图轮播**
        //                            （`HomeCategoryStyle.CAROUSEL` + `FeaturedCarousel`），
        //                            自动前进、故事条进度；只改画法，取数没动
        //     26.9.7 → 26_009_007   9.7：大轮播加「换一批」，并把**主页「热门色情视频」**
        //                            接成它的第二个来源（两来源交替：按 1 次就换到另一个）。
        //                            热门那 61 条只有主页 HTML 有 ⇒ 结果缓存、不挡首屏；
        //                            推荐越界 404 回卷第 1 页
        //     26.9.8 → 26_009_008   9.8：①「热门色情视频」**优先显示**（第 0 批改成它，
        //                            首页那次 1 MB+ 的抓取从「推荐」换成「主页」）；
        //                            ②修「换了批，点『更多』还是上一批」—— 「更多」以前写死
        //                            用「推荐」标记，现在跟着当前批次的数据源走
        //                            （热门 → 主页那 61 条；推荐 → `/recommended` 列表）
        //     26.9.9 → 26_009_009   9.9：①修「关注了却还显示关注」—— 读关注状态与写关注状态
        //                            用了两套身份键（写用规范化键、读用原始 url）⇒ 永不匹配；
        //                            现在已关注时显示灰色描边「取消关注」，两处入口统一。
        //                            ②图片加载失败的重试**加上限（5 次）**，预算记在进程级账本里
        //                            （只放界面里会随重组清零 ⇒ 上限形同虚设）+ 10 分钟遗忘窗口。
        //                            ③中转加「半程静默重连」（尚未发出正文字节时上游断了就
        //                            静默换连重试一次，替换响应须身份一致）—— 纯服务端改动。
        //                            ④简繁本地化补全：修简中夹繁体 1 处、繁中按台湾用语改 11 处、
        //                            把 12 处写死中文（不随语言切换）收进资源并补齐三语、
        //                            日期格式改走资源。三语逐键比对零缺失
        // ⚠️ 复用 26_008_003 的代价：装过旧 26.8.3（同码）或 26.8.4（码更大）的设备
        //    收不到这次更新，需要手动重装 —— 这是用户明确选的，不要擅自改成更大的号。
        // 老方案是「260940 / 260941 / 260942」这种递增序号（26.4 / 26.5 / 26.6），
        // 新方案一上来就比它大（26_006_001 > 260_942），不会触发系统的「降级安装」拒绝。
        // ────────────────────────────────────────────────────────────────────
        versionCode = 26_009_009
        versionName = "26.9.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "VERSION_NAME", "\"${versionName}\"")
        buildConfigField("int", "VERSION_CODE", "$versionCode")
        // 本构建基于的上游版本。versionName 已经不带这个信息了，所以单独给一个字段，
        // 「关于」页与检查更新里的「上游最新 x / 本构建基于 x」都读它。
        buildConfigField("String", "UPSTREAM_BASE_VERSION", "\"26.3.2\"")
        buildConfigField("int", "SEARCH_YEAR_RANGE_END", "${Config.thisYear}")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                abiFilters += "arm64-v8a"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = gradle.startParameter.taskRequests.toString().contains("Release")
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 复用本机既有密钥（local.properties 提供路径与口令），
            // 让签名证书指纹与 cpp/chino.h 里的 EXPECTED_SIG_HASH 一致，
            // 否则 native 层的完整性校验会让播放页直接失败。
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_new"
        }

        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appIcon"] = "@mipmap/ic_launcher_debug"
        }
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    lint {
        disable += setOf("EnsureInitializerMetadata")
    }
    namespace = "io.github.daisukikaffuchino.han1meviewer"

    @Suppress("UnstableApiUsage")
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

kotlin {
    compilerOptions {
        jvmTarget.value(JvmTarget.JVM_21)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-jvm-default=enable"
        )
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val apkName = "Han1meViewer-v${output.versionName.get()}.apk"
            (output as VariantOutputImpl).outputFileName = apkName
        }
    }
}

dependencies {
    implementation(libs.aboutlibraries.core)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.datastore.preferences)

    implementation(libs.bundles.android.base)
    implementation(libs.bundles.android.jetpack)

    implementation(platform(libs.compose.compose.bom))
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.compose.ui.ui.tooling.preview)
    implementation(libs.androidx.ui)
    debugImplementation(libs.compose.ui.ui.tooling)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.compose.avatar.cropper)
    implementation(libs.kyant.m3color)
    implementation(libs.sonner)

    implementation(libs.datetime)
    implementation(libs.serialization.json)
    implementation(libs.jsoup)

    implementation(libs.retrofit)
    implementation(libs.converter.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dns.over.https)

    implementation(libs.coil)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.cast)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.mpv.lib)

    ksp(libs.room.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    androidTestImplementation(libs.test.junit)
    testImplementation("junit:junit:4.13.2")
}
