plugins {
    java
}

val paperApiVersion = providers.gradleProperty("paper_api_version").get()

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
}

sourceSets {
    main {
        java {
            srcDir(rootProject.file("common/src/main/java"))
        }
    }
}

tasks.jar {
    archiveBaseName.set("MikuVideo")
    manifest {
        attributes(
            "Implementation-Title" to "MikuVideo",
            "Implementation-Version" to project.version
        )
    }
}

tasks.processResources {
    val version = project.version
    inputs.property("version", version)
    filteringCharset = "UTF-8"
    filesMatching("paper-plugin.yml") {
        expand(mapOf("version" to version))
    }
}