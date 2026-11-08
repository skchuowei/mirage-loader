<#
.SYNOPSIS
    MSR P4 生成器入口：从真实 mod jar 算 fake 缺口，产出可直接给沙盒用的 fake pack。

.DESCRIPTION
    只生成 net.neoforged.*（加载器该提供的 API 面）；net.minecraft.* 的缺口如实报告但不生成
    （造出 Minecraft/Font 只会把 CNFE 换成更晚的 NPE＝假覆盖）。

    形状源（可选但强烈建议）：真实 NeoForge 的 universal / bus / loader 三个 jar，
    只读签名（SKIP_CODE），用来认出 enum / interface / annotation 与完整成员表。

.EXAMPLE
    .\scripts\msr_p4_generate_fakes.ps1 -Verify
    # 用 %TEMP%\msr-mods 下缓存的真实 mod 生成 pack，并校验缺口归零

.EXAMPLE
    .\scripts\msr_p4_generate_fakes.ps1 -ModJar "C:\x\jei.jar" -OutPack "C:\x\pack" -Verify

.NOTES
    退出码：0 通过；1 环境/参数问题；2 生成或校验失败。
#>
[CmdletBinding()]
param(
    [string[]]$ModJar = @(),
    [string]$OutPack = "",
    [string]$Report = "",
    [string]$Libraries = "",
    [string]$JavaHome = "",
    [switch]$Verify,
    [switch]$Build,
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common\MsrP4.ps1")

$repo = Get-MsrRepoRoot
$jdk = Find-MsrJdk $JavaHome
if (-not $jdk) {
    Write-Host "[FAIL] 找不到 JDK（用 -JavaHome 指定 JDK 25）" -ForegroundColor Red
    exit 1
}
$javaExe = Join-Path $jdk "bin\java.exe"
$javacExe = Join-Path $jdk "bin\javac.exe"

if (-not $Libraries) { $Libraries = Join-Path (Split-Path -Parent $repo) "mc-shili\libraries" }
if (-not (Test-Path $Libraries)) {
    Write-Host "[FAIL] 找不到 libraries 目录: $Libraries（用 -Libraries 指定）" -ForegroundColor Red
    exit 1
}
$compatClasses = Get-MsrCompatClasses $repo
if (-not (Test-Path $compatClasses)) {
    Write-Host "[FAIL] compat 还没编译: $compatClasses（先跑 gradle :modules:mirage-neoforge-compat:compileJava）" -ForegroundColor Red
    exit 1
}
$loaderClasses = Join-Path $repo "core\mirage-loader\build\classes\java\main"
if (-not (Test-Path $loaderClasses)) {
    Write-Host "[FAIL] loader 还没编译: $loaderClasses（先跑 gradle :core:mirage-loader:compileJava）" -ForegroundColor Red
    exit 1
}

if ($Build) {
    $gradle = Find-MsrGradleBat
    if (-not $gradle) {
        Write-Host "[FAIL] 找不到 gradle（-Build 需要全局 Gradle 9.x）" -ForegroundColor Red
        exit 1
    }
    Write-Host "-- 先编译（compileJava + compat）--" -ForegroundColor Yellow
    $env:JAVA_HOME = $jdk
    & $gradle -p $repo ":core:mirage-loader:compileJava" ":modules:mirage-neoforge-compat:compileJava" --no-daemon -q
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FAIL] gradle 编译失败" -ForegroundColor Red
        exit 1
    }
}

# ---- mod 素材 ----
$mods = New-Object System.Collections.Generic.List[string]
foreach ($m in @($ModJar)) {
    if (-not $m) { continue }
    if (-not (Test-Path $m)) {
        Write-Host "[FAIL] mod jar 不存在: $m" -ForegroundColor Red
        exit 1
    }
    [void]$mods.Add((Resolve-Path -LiteralPath $m).Path)
}
if ($mods.Count -eq 0) {
    $cache = Join-Path $env:TEMP "msr-mods"
    if (Test-Path $cache) {
        Get-ChildItem -Path $cache -Filter "*.jar" | Sort-Object Name | ForEach-Object { [void]$mods.Add($_.FullName) }
    }
}
if ($mods.Count -eq 0) {
    Write-Host "[FAIL] 没有 mod 可分析。先跑 .\scripts\fetch_msr_test_mods.ps1 拉素材，或用 -ModJar 指定" -ForegroundColor Red
    exit 1
}

if (-not $OutPack) { $OutPack = Join-Path $env:TEMP "msr-fake-pack" }
$OutPack = [System.IO.Path]::GetFullPath($OutPack)
if (-not $Report) { $Report = Join-Path $env:TEMP "msr-fake-pack.report.json" }
$Report = [System.IO.Path]::GetFullPath($Report)

$asms = Find-MsrAsmJar
if (-not $asms) {
    Write-Host "[FAIL] 找不到 ASM（gradle 缓存里没有 org.ow2.asm:asm）" -ForegroundColor Red
    exit 1
}
$cp = "$loaderClasses;$asms"

$shapeJars = Find-MsrShapeJars $Libraries
if ($shapeJars.Count -eq 0) {
    Write-Host "[WARN] 没找到 NeoForge 形状源 jar，生成器只能按调用点推（enum/接口可能判错）" -ForegroundColor Yellow
}

$cliArgs = New-Object System.Collections.Generic.List[string]
[void]$cliArgs.Add("io.mirage.loader.FakeGenCli")
[void]$cliArgs.Add("--ref"); [void]$cliArgs.Add($compatClasses)
[void]$cliArgs.Add("--out"); [void]$cliArgs.Add($OutPack)
[void]$cliArgs.Add("--report"); [void]$cliArgs.Add($Report)
foreach ($s in $shapeJars) { [void]$cliArgs.Add("--shape"); [void]$cliArgs.Add($s) }
if ($Verify) { [void]$cliArgs.Add("--verify") }
foreach ($m in $mods) { [void]$cliArgs.Add($m) }

Write-Host "-- P4 生成 --" -ForegroundColor Cyan
Write-Host ("  mod: {0} 个" -f $mods.Count)
Write-Host ("  pack: {0}" -f $OutPack)
Write-Host ("  形状源: {0} 个" -f $shapeJars.Count)
if (-not $Quiet) {
    Write-Host ("  java: {0}" -f $javaExe) -ForegroundColor DarkGray
}

& $javaExe -cp $cp $cliArgs.ToArray()
$code = $LASTEXITCODE
if ($code -eq 0) {
    Write-Host "[OK] P4 生成通过（pack: $OutPack）" -ForegroundColor Green
} else {
    Write-Host "[FAIL] P4 生成失败（退出码 $code）" -ForegroundColor Red
}
exit $code
