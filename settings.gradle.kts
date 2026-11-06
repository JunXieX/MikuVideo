pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "MikuVideo"

include("common")
include("plugin")
include("mod")
