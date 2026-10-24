plugins { java }
dependencies {
    api(project(":api:mirage-api"))
    implementation(project(":core:mirage-mixin"))
    // MirageTransformer 在本模块，需要 ASM（版本目录里已有 asm-core / asm-commons，
    // mirage-mixin 模块也在用，这里只是补上缺失的声明，不是新增依赖）
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
}
description = "Mirage Loader Core — 类加载隔离、Mod 发现与生命周期"

// 沙盒 fake 表面：net.neoforged.* / net.minecraft.* 的最小录制占位。
// 关键：fake 字节码以「资源」形式打包进 mirage/sandbox-fake/ 前缀，绝不落在 net/... 标准路径，
// 否则会遮蔽运行时 mcl 加载的真 MC / 运行时 stub 类。SandboxClassLoader 从资源读字节码后 defineClass。
sourceSets.create("sandbox") {
    java.srcDir("src/sandbox/java")
}

tasks.named<JavaCompile>("compileSandboxJava") {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// 让 fake 表面能引用 io.mirage.loader.sandbox.Sink（main 编译产物）
tasks.named<JavaCompile>("compileSandboxJava") {
    classpath += sourceSets["main"].output
}

tasks.jar {
    dependsOn("sandboxClasses")
    from(sourceSets["sandbox"].output.classesDirs) {
        into("mirage/sandbox-fake")
    }
}
