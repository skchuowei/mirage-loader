plugins {
    java
    id("application")
}

dependencies {
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
    implementation(libs.gson)
    // 运行时桥接：net.neoforged.* stub 内部转发到 MG 自家 DeferredRegister
    implementation(project(":api:mirage-api"))
    // 游戏事件类需要引用原版 26.2 的 Level/Entity/Player 等真类；
    // 与 mirage-mixin 同款：仅 compileOnly，不进 fat jar。
    compileOnly(rootProject.files("../mc-shili/versions/26.2/26.2.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"))
    // MC jar 的签名里引用了这两个库的类型，javac 读取 26.2 类文件时需要
    compileOnly(rootProject.files("../mc-shili/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"))
    // P1.6：26.2 类文件签名带 jspecify 注解，javac 符号补全需要它
    compileOnly(rootProject.files("../mc-shili/libraries/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"))
    // P1.6：FriendlyByteBuf / Identifier.STREAM_CODEC 签名引用 netty ByteBuf
    compileOnly(rootProject.files("../mc-shili/libraries/io/netty/netty-buffer/4.2.15.Final/netty-buffer-4.2.15.Final.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar"))
}

// 事件类要读 v69 class（MC 26.2 基于 JDK 25），toolchain 用 25、字节码保持 21。
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// src/compile-stub/java 下是 net.minecraft.* 的编译期镜像（Registry / ResourceKey /
// Identifier），只为让 NeoForge 风格方法签名通过编译；运行时由 mcl 从真 MC jar 加载。
sourceSets {
    main {
        java {
            srcDir("src/compile-stub/java")
        }
    }
}

application {
    mainClass.set("io.mirage.neoforge.Main")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.mirage.neoforge.Main")
    }
    // 编译期镜像绝不进产出 jar，否则会遮蔽真 MC 类
    exclude("net/minecraft/**")
}

description = "Mirage NeoForge Compat — clean-room neoforge.mods.toml 解析 + NeoForge 注解/DeferredRegister 字节码扫描 + 运行时 net.neoforged.* 桥接 stub"
