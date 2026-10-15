import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/**
 * 构建号（形如 v2.1.7.0-opt.11），由 CI 通过 -PbranchBuild=... 传入。
 * 注意：这里绝不能调用 git —— Gradle 配置缓存禁止在配置阶段启动外部进程，会让构建直接失败。
 * 只在配置阶段读取属性，取不到时为空串。
 */
fun branchBuildTag(): String {
    return (project.findProperty("branchBuild") as? String) ?: ""
}

android {
    compileSdk = libs.versions.compile.sdk.get().toInt()
    namespace = "com.qtjay.syncscope"

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.qtjay.syncscope"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = libs.versions.version.code.get().toInt()
        versionName = libs.versions.version.name.get()
        buildConfigField("String", "BRANCH_BUILD", "\"${branchBuildTag()}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui)
    implementation(libs.core.ktx)
    implementation(libs.gson)
    implementation(libs.documentfile)
    // 文件通道（动作面）：FTP、SMB2/3、WebDAV
    implementation(libs.commons.net)
    implementation(libs.smbj)
    implementation(libs.okhttp)
}
