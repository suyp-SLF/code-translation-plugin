plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.22"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "cn.minmetals.hcm.pm.plugin"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2023.3.6")
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Code Translation Helper"
        ideaVersion {
            sinceBuild = "233"
            // 不设置上限 = 允许在 2024.x / 2025.x 上安装。
            untilBuild = provider { null }
        }
        description = """
            代码平移助手：将只读源码目录中的文件与目标项目对比，支持手动合并。
            - 配置源目录（只读）和目标目录（可写）
            - 工具窗口展示文件差异状态
            - 双击打开只读源 vs 可编辑目标的 Diff 视图
            - 支持单文件拷贝、逐块合并
        """.trimIndent()
        changeNotes = "初始版本：基础文件对比与合并功能"
        vendor {
            name = "Minmetals HCM PM Team"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
