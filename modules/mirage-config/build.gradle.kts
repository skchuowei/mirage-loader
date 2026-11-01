plugins { java }

dependencies {
    // 入口类 ConfigModule 要打日志（io.mirage.api.log.MirageLog）——
    // 该 API 随 fat jar 走 app loader，模块由 MirageClassLoader 加载后引用它时委派给 app loader，
    // 拿到的是同一份，安全。
    implementation(project(":api:mirage-api"))
}

// 模块 jar 由 MirageClassLoader 按需加载（不进 fat jar）。
// 产物名默认就是 <模块id>-<版本>.jar（gradle 用项目名做 archiveBaseName），
// ModuleRegistry.moduleJar 按「文件名以模块 id 开头」匹配 —— 所以项目名不能和模块 id 脱节。

description = "Mirage Config — 可选配置系统（G9 模块懒加载：按 requires 动态加入类加载器）"
