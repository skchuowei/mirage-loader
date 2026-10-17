plugins {
    java
    id("application")
}

dependencies {
    implementation(libs.asm.core)
    implementation(libs.asm.commons)
    implementation(libs.gson)
}

application {
    mainClass.set("io.mirage.harddecode.Main")
}

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.mirage.harddecode.Main")
    }
}

description = "Mirage Hard Decode — A2 静态字节码分析器，输出 MG 静态描述 JSON"
