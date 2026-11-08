# scripts/build_msr_probe_mod.ps1
# 构建 MSR P3 的合成探针 mod（真值已知）
#
# 为什么需要它：
#   P3 的产出是「游戏侧状态观测 + 与基线求差」，验收需要**真值已知**的样本 ——
#   这个 mod 注册了什么、订阅了什么全写在源码里，可逐条核对。
#   真实第三方 mod（JEI 等）真值不可控，且本机缺依赖（如 JEI 需要 mezz_config）。
#
# 产物：scripts/fixtures/msr-probe-mod/build/msr-probe-mod.jar
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\build_msr_probe_mod.ps1
#
# 退出码：0 成功；1 依赖缺失 / 编译失败。

[CmdletBinding()]
param(
    [string]$JavaHome = "",
    [string]$OutJar = ""
)

$ErrorActionPreference = "Stop"
trap {
    Write-Host "[ERROR] $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# 编译 classpath：**必须与运行时同源**（探针 mod 用的 API 就是运行时那些 jar 提供的）
# 走共享的版本 json 重建逻辑，避免手列依赖漏掉 fastutil / jspecify 之类。
$repo = Split-Path -Parent $PSScriptRoot
$fixRoot = Join-Path $repo "scripts\fixtures\msr-probe-mod"
$mcRoot = Split-Path -Parent $repo
$libs = Join-Path $mcRoot "mc-shili\libraries"
$verJson = Join-Path $mcRoot "mc-shili\versions\26.2-NeoForge_26.2.0.90\26.2-NeoForge_26.2.0.90.json"

if (-not $JavaHome) {
    $jdk25 = "C:\Program Files\Java\jdk-25.0.4"
    if (Test-Path $jdk25) { $JavaHome = $jdk25 } else { $JavaHome = $env:JAVA_HOME }
}
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar = Join-Path $JavaHome "bin\jar.exe"
if (-not (Test-Path $javac)) { throw "找不到 javac：$javac" }

. (Join-Path $repo "scripts\common\NeoClasspath.ps1")
# MC 本体 + NeoForge universal **不在版本 json 的 libraries 里**，编译必须显式补
$extra = @(
    "net/neoforged/minecraft-client-patched/26.2.0.90/minecraft-client-patched-26.2.0.90.jar",
    "net/neoforged/neoforge/26.2.0.90/neoforge-26.2.0.90-universal.jar"
)
$cpInfo = Get-NeoClasspath -VersionJson $verJson -LibrariesDir $libs -ExtraRelative $extra
if ([string]::IsNullOrWhiteSpace($cpInfo.cp)) { throw "classpath 重建失败（版本 json 解析不出 library）" }
$cp = $cpInfo.cp
Write-Host "编译 classpath: $(($cp -split ';').Count) 项（缺失 $($cpInfo.missing.Count) 个非本平台 library）"

$buildDir = Join-Path $fixRoot "build"
$classesDir = Join-Path $buildDir "classes"
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classesDir | Out-Null

$sources = Get-ChildItem (Join-Path $fixRoot "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { throw "找不到源文件" }
Write-Host "编译 $($sources.Count) 个源文件 ..."

# classpath 有 100+ 项、近 12KB，**不能**走命令行传：
#   - 直接 `-cp $cp`：Windows PowerShell 调 native 时的参数转义会让 javac 收不到 classpath
#     （实测：报「程序包 net.minecraft.* 不存在」）；
#   - `@argfile`：javac 把 argfile 里的 `\` 当转义符，且带引号的长 classpath 仍解析失败。
# 改用 **CLASSPATH 环境变量** —— javac 会读它，完全绕开 native 参数传递。
$env:CLASSPATH = $cp
try {
    & $javac -encoding UTF-8 -nowarn -d $classesDir $sources
} finally {
    $env:CLASSPATH = $null
}
if ($LASTEXITCODE -ne 0) { throw "javac 失败（exit $LASTEXITCODE）" }

# 资源
Copy-Item (Join-Path $fixRoot "resources\*") $classesDir -Recurse -Force

if (-not $OutJar) { $OutJar = Join-Path $buildDir "msr-probe-mod.jar" }
Push-Location $classesDir
try {
    & $jar cf $OutJar .
} finally {
    Pop-Location
}
if ($LASTEXITCODE -ne 0) { throw "jar 打包失败（exit $LASTEXITCODE）" }

Write-Host "[PASS] 探针 mod 已构建: $OutJar ($((Get-Item $OutJar).Length) B)"
exit 0
