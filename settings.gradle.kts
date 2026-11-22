pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // PREFER_PROJECT：兼容本机 GRADLE_USER_HOME/init.d 的阿里云镜像 init 脚本
    repositoriesMode = RepositoriesMode.PREFER_PROJECT
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Switchboard"
include(":app")
