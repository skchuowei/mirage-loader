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
