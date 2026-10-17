plugins { java }

// MDK 模板要 against 真实 MC jar（class 文件版本 69.0 = Java 25），
// 全局 toolchain 是 21，这里单独提到 25。与 build.bat 里用 jdk-25.0.4 保持一致。
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

dependencies {
    implementation(project(":api:mirage-api"))
    // MDK 模板直接 against 本机真实 MC jar 编译（与 build.bat 里的 -cp 保持一致；
    // 这是本机游戏文件，不是新增第三方仓库依赖）
    implementation(files("D:/bing/MC/mcmoderr-my/mc-shili/versions/26.2/26.2.jar"))
    // MC jar 里的类引用 DataFixerUpper（com.mojang.serialization.Keyable）和 gson，
    // 编译期 classpath 也需要带上（与 build.bat 一致）。
    implementation(files("D:/bing/MC/mcmoderr-my/mc-shili/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"))
    implementation(files("D:/bing/MC/mcmoderr-my/mc-shili/libraries/com/google/code/gson/gson/2.14.0/gson-2.14.0.jar"))
}
description = "MDK Example — Mod 开发模板"
