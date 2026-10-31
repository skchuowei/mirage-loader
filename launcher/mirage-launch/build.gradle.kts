plugins {
    java
}

// 早期窗口直接用 LWJGL（与 MC 运行时同一份库），这里只做编译期依赖，不打进 jar
val lwjglDir = rootProject.projectDir.parentFile.resolve("mc-shili/libraries/org/lwjgl")

dependencies {
    implementation(project(":api:mirage-api"))
    implementation(project(":core:mirage-loader"))
    implementation(project(":core:mirage-mixin"))
    implementation(project(":core:mirage-eventbus"))
    // phase 2（LS）：入口降级为 thin wrapper，S1–S7/四周期/失败策略全部委托 io.mirage.ls。
    // 依赖方向单向（LS-Interfaces §9 反递归约束：LS 不反向依赖 launcher）；
    // fat jar 经 runtimeClasspath 自动 shade 进 mirage-launch-0.1.0.jar。
    implementation(project(":lifecycle-starter"))
    // 运行时 net.neoforged.* 桥接 stub：随启动 classpath 进入 mcl，
    // NeoForge mod 类链接时解析 net.neoforged.fml.common.Mod 等符号不再 NoClassDefFound
    implementation(project(":modules:mirage-neoforge-compat"))
    // 运行时 net.fabricmc.* 桥接 stub（Event/EventFactory/lifecycle-v1）：随启动 classpath 进入 mcl，
    // Fabric mod 类链接时解析 net.fabricmc.fabric.api.* 符号不再 NoClassDefFound
    implementation(project(":modules:mirage-fabric-compat"))
    // MGScript（MSE）：脚本 mod 运行时。S6 通过 Class.forName(..., mcl) 反射进入
    // io.mirage.mgscript.mc.MirageScriptHost，所以它必须随启动 classpath 一起进 mcl 的 URL 列表；
    // 包名前缀已在 MirageClassLoader 的例外名单里（否则 app 侧会再定义一份 MC 类）。
    implementation(project(":modules:mirage-mgscript"))

    compileOnly(
        files(
            lwjglDir.resolve("lwjgl/3.4.1/lwjgl-3.4.1.jar"),
            lwjglDir.resolve("lwjgl/3.4.1/lwjgl-3.4.1-unsafe.jar"),
            lwjglDir.resolve("lwjgl-glfw/3.4.1/lwjgl-glfw-3.4.1.jar"),
            lwjglDir.resolve("lwjgl-opengl/3.4.1/lwjgl-opengl-3.4.1.jar"),
            lwjglDir.resolve("lwjgl-stb/3.4.1/lwjgl-stb-3.4.1.jar")
        )
    )
}

description = "Mirage Launch — 被启动器调用的入口点（thin wrapper）：日志 tee/banner/早期窗口后单向委托 io.mirage.ls.LifecycleStarter"

// P1.5 部署：fat jar —— 把全部模块 + mirage-deps（asm/mixin，compileOnly 不进 runtimeClasspath）
// shade 进单个 mirage-launch-0.1.0.jar，供 PCL 启动 classpath 直接引用。
// duplicate 用 EXCLUDE，META-INF/services 用 mergeServiceFiles 合并。
val fatJar = tasks.register<Jar>("fatJar") {
    archiveClassifier.set("") // 产出 mirage-launch-0.1.0.jar（覆盖部署名，不带 -fat）
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes("Main-Class" to "io.mirage.launch.Launcher") }
    val depsDir = rootProject.projectDir.parentFile.resolve("mc-shili/libraries/io/mirage/mirage-deps")
    // 必须显式声明对 runtimeClasspath 的构建依赖：下面 `filter{}.map{}` 把 FileCollection 转成
    // 普通 List，丢掉了它携带的 task 依赖信息。后果是只跑 `:launcher:mirage-launch:fatJar`
    // 时 Gradle 不会先重建子模块 jar，把**上一次的旧 class** 打进 fat jar
    // （曾导致 MirageTestMixin 的修改看似成功、实际启动仍在用旧字节码）。
    dependsOn(configurations.runtimeClasspath)
    from(sourceSets["main"].output)
    from(configurations.runtimeClasspath.get().filter { it.exists() }
        .map { if (it.isDirectory) it else zipTree(it) })
    from(fileTree(depsDir) { include("*.jar") }.map { zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**")
}
