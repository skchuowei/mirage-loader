plugins {
    java
}

allprojects {
    group = "io.mirage.loader"
    version = "0.1.0"

    repositories {
        mavenCentral()
        maven("https://libraries.minecraft.net")   // MC 官方库
        maven("https://maven.fabricmc.net")        // Fabric API 等
        maven("https://repo.spongepowered.org/repository/maven-public/") // SpongePowered Mixin
    }
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21)) // 开发期用本机 21；M1 跑 MC 时由 PCL 提供 Java 25
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
}
