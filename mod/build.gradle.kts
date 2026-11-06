plugins {
    id("net.fabricmc.fabric-loom") version "1.19-SNAPSHOT"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val loaderVersion = providers.gradleProperty("loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_version").get()

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

loom {
    mods {
        register("mikuvideo") {
            sourceSet(sourceSets.main.get())
        }
    }
}

sourceSets {
    main {
        java {
            srcDir(rootProject.file("common/src/main/java"))
        }
    }
}

tasks.jar {
    archiveBaseName.set("MikuVideo-Client")
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filteringCharset = "UTF-8"
    filesMatching("fabric.mod.json") {
        expand(mapOf("version" to version))
    }
}