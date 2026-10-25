plugins {
    java
    application
}

group = "io.mirage"
version = "0.4.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

dependencies {
    // phase 2：LS 成为唯一调度入口，S1–S7 阶段类自包含全部启动逻辑，
    // 不再依赖 launcher（防 Launcher→LS→Launcher 循环，见 LS-Interfaces §9 反递归约束）。
    // bootstrap 类（日志 tee / banner / 早期窗口）留在 launcher，由 Launcher.main 单向委托本模块。
    implementation(project(":core:mirage-loader"))
    implementation(project(":core:mirage-mixin"))
    implementation(project(":core:mirage-eventbus"))
    implementation(project(":api:mirage-api"))
    // S2/S6 需要 net.neoforged.fml.ModLoadingContext（NeoForge 配置目录 / 活跃 modId 标记）
    implementation(project(":modules:mirage-neoforge-compat"))
    // S6 入口实例化需要 net.fabricmc.api.ModInitializer / ClientModInitializer 接口判定
    implementation(project(":modules:mirage-fabric-compat"))
}

application {
    mainClass.set("io.mirage.ls.LifecycleStarter")
}
