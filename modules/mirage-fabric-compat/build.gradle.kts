plugins { java }
dependencies {
    implementation(project(":core:mirage-loader"))
}
description = "Mirage Fabric Compat — 按需加载，解析 fabric.mod.json 桥接至 Mirage 生命周期"
