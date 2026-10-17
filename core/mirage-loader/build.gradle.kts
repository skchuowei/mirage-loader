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
