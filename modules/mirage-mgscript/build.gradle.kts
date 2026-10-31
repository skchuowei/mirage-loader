plugins {
    java
}

// MGScript（MSE = Mirage Script Engine）：类 Python 的 Mod 脚本语言。
// 设计约束（见 docs/plans/2026-10-31_mgscript-dsl.md）：
//   - 零第三方依赖：不引 Rhino / GraalJS / Jython，体积可控（Q2 裁决：自研解释器）；
//   - 引擎核心（lex/parse/runtime）是纯 Java，不认识任何 MC 类型；
//   - 只有 mc/ 绑定层（MirageScriptHost）引用 net.minecraft.*，且该包由 MirageClassLoader
//     加载（见 MirageClassLoader.loadClass 的例外 3）——所以这里用 compileOnly，
//     绝不能进 api 依赖链，否则 app classloader 会跟着加载 MC 类并定义出第二份。
description = "Mirage Script Engine — MGScript 解释器（词法/语法/求值/标准库）+ MC 绑定层"

// 绑定层要读 v69 class（MC 26.2 基于 JDK 25），toolchain 用 25、字节码保持 21。
// 与 mirage-fabric-compat / mirage-neoforge-compat / mirage-mixin 一致：
// 只有 JDK 25 的 javac 能读 v69，而产出保持 JVM 21 以免变体属性把依赖方抬到 25。
extensions.configure<JavaPluginExtension> {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

dependencies {
    implementation(project(":api:mirage-api"))

    // 绑定层构造 Item/Block、反射订阅 tick 事件所需
    compileOnly(rootProject.files("../mc-shili/versions/26.2/26.2.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar"))
    compileOnly(rootProject.files("../mc-shili/libraries/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar"))
}
