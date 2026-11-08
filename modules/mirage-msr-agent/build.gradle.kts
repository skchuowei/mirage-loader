plugins { java }
description = "MSR P3 注入器 — 行为级 MID 录制 agent（构建期工具，不进 MG fat jar）"

dependencies {
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
}

tasks.jar {
    // 固定文件名：运行脚本按固定名引用（带版本号的默认名会让脚本到处找）
    archiveFileName.set("mirage-msr-agent.jar")
    manifest {
        attributes(
            "Premain-Class" to "io.mirage.msr.agent.RecorderAgent",
            "Agent-Class" to "io.mirage.msr.agent.RecorderAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
    // agent jar 必须是「自包含」的：javaagent 只加载 jar 自身，
    // ASM 不打进去 ⇒ 运行期 NoClassDefFoundError: org/objectweb/asm/ClassReader。
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("module-info.class")
        exclude("META-INF/versions/**/module-info.class")
        exclude("META-INF/MANIFEST.MF")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
