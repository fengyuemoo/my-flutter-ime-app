import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 如果是 Flutter 混合项目，可能会有 flutter 插件，为了保证原生编译通过，这里只保留原生核心插件
}

android {
    namespace = "com.example.myapp" // 你的包名
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myapp"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // 让 Android/Kotlin 编译能看到生成的源码目录
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/source/dictdbinfo/main"))
}

dependencies {
    // 基础 Android 依赖
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // ★★★ 核心修改：添加 RecyclerView 依赖 ★★★
    // 只有添加了这一行，你的 CandidateAdapter 才能正常工作
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.flexbox:flexbox:3.0.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("androidx.test:core:1.5.0")
}

// === 自动生成词库指纹常量（构建时） ===
val dictDbAssetFile = file("src/main/assets/dictionary.db")
val generatedDictInfoDir = layout.buildDirectory.dir("generated/source/dictdbinfo/main")

val generateDictDbBuildInfo = tasks.register("generateDictDbBuildInfo") {
    inputs.file(dictDbAssetFile)
    outputs.dir(generatedDictInfoDir)

    doLast {
        if (!dictDbAssetFile.exists()) {
            throw GradleException(
                "找不到 ${dictDbAssetFile.path}，请先运行 build_dict.py 生成 assets/dictionary.db"
            )
        }

        val md = MessageDigest.getInstance("SHA-256")
        dictDbAssetFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sha256 = md.digest().joinToString("") { "%02x".format(it) }
        val size = dictDbAssetFile.length()

        val outDir = generatedDictInfoDir.get().asFile
        val pkgPath = "com/example/myapp/dict/install"
        val outFile = outDir.resolve("$pkgPath/DictDbBuildInfo.kt")
        outFile.parentFile.mkdirs()

        outFile.writeText(
            """
            package com.example.myapp.dict.install

            object DictDbBuildInfo {
                const val ASSET_DB_SHA256: String = "$sha256"
                const val ASSET_DB_SIZE: Long = ${size}L
            }
            """.trimIndent()
        )
    }
}

// === Compose mode isolation guard (build-time) ===
// Ensure CN/EN and Qwerty/T9 implementations do not cross-import each other.
val checkComposeIsolation = tasks.register("checkComposeIsolation") {
    doLast {
        val composeRoot = file("src/main/kotlin/com/example/myapp/ime/compose")
        if (!composeRoot.exists()) return@doLast

        val violations = mutableListOf<String>()

        fun record(file: File, msg: String) {
            violations += "${file.path}: $msg"
        }

        fun importsOf(text: String): List<String> {
            return text.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("import ") }
                .map { it.removePrefix("import ").trim() }
                .toList()
        }

        composeRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val path = f.invariantSeparatorsPath
                val text = f.readText()
                val imports = importsOf(text)

                val isCn = path.contains("/compose/cn/")
                val isEn = path.contains("/compose/en/")
                val isQwerty = path.contains("/qwerty/")
                val isT9 = path.contains("/t9/")

                // CN <-> EN isolation
                if (isCn) {
                    imports.filter { it.startsWith("com.example.myapp.ime.compose.en") }
                        .forEach { imp -> record(f, "CN file must not import EN: $imp") }
                }
                if (isEn) {
                    imports.filter { it.startsWith("com.example.myapp.ime.compose.cn") }
                        .forEach { imp -> record(f, "EN file must not import CN: $imp") }
                }

                // Qwerty <-> T9 isolation (within same language tree)
                if (isQwerty) {
                    imports.filter { it.contains(".t9.") }
                        .forEach { imp -> record(f, "QWERTY file must not import T9: $imp") }
                }
                if (isT9) {
                    imports.filter { it.contains(".qwerty.") }
                        .forEach { imp -> record(f, "T9 file must not import QWERTY: $imp") }
                }
            }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "Compose mode isolation violated:\n" + violations.joinToString("\n")
            )
        }
    }
}

// 只在 Debug / CI 启用隔离校验（Release 默认不做扫描）
val enableComposeIsolationCheck: Boolean = run {
    val isCi = (System.getenv("CI") ?: "").equals("true", ignoreCase = true)
    val taskNames = gradle.startParameter.taskNames
    val isDebugRequested = taskNames.any { it.contains("debug", ignoreCase = true) }
    isCi || isDebugRequested
}

// 确保在编译前先生成；隔离校验仅在 Debug/CI 启用
tasks.named("preBuild") {
    dependsOn(generateDictDbBuildInfo)
    if (enableComposeIsolationCheck) {
        dependsOn(checkComposeIsolation)
    }
}
