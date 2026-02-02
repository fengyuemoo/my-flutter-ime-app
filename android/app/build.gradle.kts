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

// 确保在编译前先生成
tasks.named("preBuild") {
    dependsOn(generateDictDbBuildInfo)
}
