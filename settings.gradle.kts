pluginManagement {
    repositories {
        // 华为云镜像（速度最快，推荐）
        maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
        // 阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // 腾讯云镜像
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public") }
        // 备用官方源
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 华为云镜像（速度最快）
        maven { url = uri("https://repo.huaweicloud.com/repository/maven") }
        // 阿里云镜像
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        // 腾讯云镜像
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public") }
        // 备用官方源
        google()
        mavenCentral()
    }
}

rootProject.name = "HandwritingPenBattery"
include(":app")
