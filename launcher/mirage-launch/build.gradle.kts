plugins {
    java
}

dependencies {
    implementation(project(":api:mirage-api"))
    implementation(project(":core:mirage-loader"))
    implementation(project(":core:mirage-mixin"))
    implementation(project(":core:mirage-eventbus"))
    // 运行时 net.neoforged.* 桥接 stub：随启动 classpath 进入 mcl，
    // NeoForge mod 类链接时解析 net.neoforged.fml.common.Mod 等符号不再 NoClassDefFound
    implementation(project(":modules:mirage-neoforge-compat"))
}

description = "Mirage Launch — 被启动器调用的入口点，负责接管 JVM 并反射调用原版 Main"
