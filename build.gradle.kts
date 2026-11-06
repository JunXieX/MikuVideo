plugins {
    java
}

val projectGroup = providers.gradleProperty("group").get()
val projectVersion = providers.gradleProperty("version").get()

group = projectGroup
version = projectVersion

subprojects {
    group = projectGroup
    version = projectVersion

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "PaperMC" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(25)
        }
    }
}
