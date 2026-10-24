rootProject.name = "mirage-loader"

// 核心加载器
include(":launcher:mirage-launch")
include(":lifecycle-starter")
include(":core:mirage-loader")
include(":core:mirage-mixin")
include(":core:mirage-eventbus")

// Mod 开发者 API
include(":api:mirage-api")

// 可选懒加载模块
include(":modules:mirage-fabric-compat")
include(":modules:mirage-kotlin")
include(":modules:mirage-config")
include(":modules:mirage-network")
include(":modules:mirage-harddecode")
include(":modules:mirage-neoforge-compat")

// 构建工具链
include(":gradle-plugin:mirage-gradle-plugin")

// MDK 模板
include(":mdk:mdk-example")

// 统一项目目录名
project(":launcher:mirage-launch").projectDir = file("launcher/mirage-launch")
project(":core:mirage-loader").projectDir = file("core/mirage-loader")
project(":core:mirage-mixin").projectDir = file("core/mirage-mixin")
project(":core:mirage-eventbus").projectDir = file("core/mirage-eventbus")
project(":api:mirage-api").projectDir = file("api/mirage-api")
project(":modules:mirage-fabric-compat").projectDir = file("modules/mirage-fabric-compat")
project(":modules:mirage-kotlin").projectDir = file("modules/mirage-kotlin")
project(":modules:mirage-config").projectDir = file("modules/mirage-config")
project(":modules:mirage-network").projectDir = file("modules/mirage-network")
project(":modules:mirage-harddecode").projectDir = file("modules/mirage-harddecode")
project(":modules:mirage-neoforge-compat").projectDir = file("modules/mirage-neoforge-compat")
project(":gradle-plugin:mirage-gradle-plugin").projectDir = file("gradle-plugin/mirage-gradle-plugin")
project(":mdk:mdk-example").projectDir = file("mdk/mdk-example")
