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
}
description = "Mirage Mixin — 字节码织入管线（桥接 SpongePowered Mixin）"

tasks.register("printClasspath") {
    doLast {
        for (f in configurations.compileClasspath.get().files) {
            println("CP: " + f.absolutePath)
        }
    }
}
