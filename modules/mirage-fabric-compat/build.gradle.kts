plugins {
    java
    id("application")
}

dependencies {
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
    implementation(libs.gson)
    // 运行时桥接 stub 内部转发到 MG 自家 API
    implementation(project(":api:mirage-api"))
    // 生命周期事件类需要引用原版 26.2 的 Minecraft / MinecraftServer 真类；
    // 与 mirage-mixin / mirage-neoforge-compat 同款：仅 compileOnly，不进产出 jar。
    compileOnly(rootProject.files("../mc-shili/versions/26.2/26.2.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"))
    // MC jar 的签名里引用了这两个库的类型，javac 读取 26.2 类文件时需要
    compileOnly(rootProject.files("../mc-shili/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"))
    // 网络 API（PacketSender / FabricNetworkingBridge）引用 io.netty.channel/buffer；
    // ChannelFutureListener 在 netty-transport 的 io.netty.channel 包（netty 4.2 无 netty-channel 构件）
    compileOnly(rootProject.files("../mc-shili/libraries/io/netty/netty-transport/4.2.15.Final/netty-transport-4.2.15.Final.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/io/netty/netty-buffer/4.2.15.Final/netty-buffer-4.2.15.Final.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar"))
    // 26.2 类文件签名带 jspecify 注解，javac 符号补全需要它
    compileOnly(rootProject.files("../mc-shili/libraries/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"))
}

// 生命周期事件类要读 v69 class（MC 26.2 基于 JDK 25），toolchain 用 25、字节码保持 21。
// 与 mirage-neoforge-compat 保持一致。
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

application {
    mainClass.set("io.mirage.fabric.Main")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.mirage.fabric.Main")
    }
    // net.fabricmc.* 是运行时桥接 stub，可进 jar；本模块不放置 net.minecraft.* 镜像，无需 exclude。
}

description = "Mirage Fabric Compat — clean-room fabric.mod.json 解析 + Fabric 注解/Entrypoint 字节码扫描 + 运行时 Fabric API 桥接 stub"
