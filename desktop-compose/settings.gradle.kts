pluginManagement {
    repositories {
        // 与主仓 android/ 一致：先走阿里云镜像（Maven Central / Google / Gradle Plugin），
        // 官方源兜底。desktop-compose 不需要 google()，但留着以后万一要加 Android target。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // androidx.graphics / androidx.lifecycle / androidx.savedstate 这些**只在 Google Maven**，
        // 没有 google() 会解析不到（Compose Multiplatform 的桌面 artifact 依赖它们）
        maven("https://maven.aliyun.com/repository/google")
        google()
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
}

rootProject.name = "desktop-compose"
