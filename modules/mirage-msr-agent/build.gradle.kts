plugins { java }
description = "MSR P3 注入器 — 行为级 MID 录制 agent（构建期工具，不进 MG fat jar）"

dependencies {
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.mirage.msr.agent.RecorderAgent",
            "Agent-Class" to "io.mirage.msr.agent.RecorderAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}
