plugins { java }
dependencies {
    api(project(":api:mirage-api"))
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.asm.util)
    // SpongePowered Mixin（Fabric 重打包），M3 正式启用。
    // mixin jar 自带 ASM 阴影副本，但为对齐 Java 25 / class v69，
    // 我们仍显式依赖 asm 9.9，运行时由 MG 统一类加载。
    implementation(libs.mixin)
    // 编译期需要原版 26.2 客户端类（CreativeModeInventoryScreen / CreativeModeTab /
    // Button / Component 等）及 brigadier（Component 的父接口类型）。仅 compileOnly：
    // 不打进 fat jar，也不做运行期依赖。
    compileOnly(rootProject.files("../mc-shili/versions/26.2/26.2.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"))
    // NeoForge 游戏事件派发入口（运行时由 fat jar 提供，仅编译期可见）
    compileOnly(project(":modules:mirage-neoforge-compat"))
}

// 本模块要直接引用 class 文件版本 69 的原版类（MC 26.2 基于 JDK 25），
// 只有 JDK 25 的 javac 才能读取 v69 class，所以覆盖根项目的 toolchain 21 用 25。
// 但产出字节码仍显式保持 JVM 21（release 21）：实测 JDK 25 javac + --release 21
// 可以正常读取 v69 依赖。若改成 release 25，本模块的 org.gradle.jvm.version 变体属性
// 会变成 25，导致 target 21 的兄弟模块（core:mirage-loader 等）依赖解析失败。
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
description = "Mirage Mixin — 字节码织入管线（桥接 SpongePowered Mixin）"

tasks.register("printClasspath") {
    doLast {
        for (f in configurations.compileClasspath.get().files) {
            println("CP: " + f.absolutePath)
        }
    }
}
