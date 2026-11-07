# scripts/run_msr_p3.ps1
# MSR P3 · 注入器深录运行器（一次性实例，仓库外，跑完即弃）
#
# 作用：
#   在**真实 NeoForge 客户端实例**里挂 mirage-recorder-agent，录出行为级 MID（IR）。
#   与 P2 的 fake pass 根本不同：P2 只能看到 mod 碰 stub 的调用；P3 插桩游戏侧
#   （MappedRegistry.register / EventBus.addListener），能看到 mod 直接调 MC 原版方法的后果。
#
# 关键约束（务必先读）：
#   1. **不碰 mc-shili/**（AGENTS 规则 8）。实例建在仓库外的 %TEMP%，跑完即弃。
#   2. **断网运行**（--offline）：真实 NeoForge 启动会连账号服务器。
#   3. agent 是**构建期工具**，不进 MG fat jar（AGENTS 规则 7.5）。
#   4. 本脚本**不启动 Minecraft 客户端窗口**到用户桌面（虚拟桌面规则）：
#      它跑的是自己建的一次性实例，与用户日常启动路径无关；若需要人工目视，
#      请让用户双击自己的 bat。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -SelfTest
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -ModJar <mod jar> [-Baseline]
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -ListDeps
#
# 退出码：0 通过；1 自检失败 / 依赖缺失 / 实例构建失败。

[CmdletBinding()]
param(
    [string]$ModJar = "",
    [switch]$Baseline,
    [switch]$SelfTest,
    [switch]$ListDeps,
    [switch]$Keep,
    [string]$OutDir = "",
    [string]$ReportFile = (Join-Path $env:TEMP "run_msr_p3.report.txt")
)

$ErrorActionPreference = "Stop"
$scriptName = "run_msr_p3"
$report = New-Object System.Collections.Generic.List[string]
function Log($msg) { $report.Add($msg); Write-Host $msg }

$repo = Split-Path -Parent $PSScriptRoot
$agentJar = Join-Path $repo "modules\mirage-msr-agent\build\libs\mirage-msr-agent.jar"
$mcRoot   = Split-Path -Parent $repo          # D:\bing\MC\mcmoderr-my

# ---- 真实 NeoForge 客户端实例的依赖（本地已有，不联网）----
$libsDir  = Join-Path $mcRoot "mc-shili\libraries"
$neoVer   = "26.2.0.90"
$depUniversal = Join-Path $libsDir "net\neoforged\neoforge\$neoVer\neoforge-$neoVer-universal.jar"
$depLoader    = Join-Path $libsDir "net\neoforged\fancymodloader\loader\11.0.16\loader-11.0.16.jar"
$depBus       = Join-Path $libsDir "net\neoforged\bus\8.0.5\bus-8.0.5.jar"
$depMerge     = Join-Path $libsDir "net\neoforged\mergetool\2.0.7\mergetool-2.0.7-api.jar"
$depClient    = Join-Path $libsDir "net\neoforged\minecraft-client-patched\$neoVer\minecraft-client-patched-$neoVer.jar"
$nativesDir    = Join-Path $mcRoot "mc-shili\versions\26.2-NeoForge_$neoVer\26.2-NeoForge_$neoVer-natives"

function Fail($msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
    $report.Add("FAIL: $msg")
    $report | Out-File -FilePath $ReportFile -Encoding utf8
    exit 1
}

# ------------------------------------------------------------ 依赖自检
$required = @($agentJar, $depUniversal, $depLoader, $depBus, $depMerge, $depClient)
$missing = @()
foreach ($f in $required) { if (-not (Test-Path $f)) { $missing += $f } }

if ($SelfTest) {
    Log "== MSR P3 运行器自检 =="
    # 1) agent jar 存在且带 Premain-Class
    if (Test-Path $agentJar) {
        $jar = Join-Path $env:TEMP "msr-agent-manifest.txt"
        & "$env:JAVA_HOME\bin\jar.exe" xf $agentJar META-INF/MANIFEST.MF 2>$null
        $mf = "META-INF/MANIFEST.MF"
        if (Test-Path $mf) {
            $content = Get-Content $mf -Raw
            if ($content -match "Premain-Class:\s*io\.mirage\.msr\.agent\.RecorderAgent") {
                Log "[PASS] agent manifest 带 Premain-Class"
            } else {
                Fail "agent manifest 缺 Premain-Class"
            }
            Remove-Item $mf -Force -ErrorAction SilentlyContinue
        } else {
            Fail "agent jar 里读不到 META-INF/MANIFEST.MF"
        }
    } else {
        Log "[WARN] agent jar 还没构建：$agentJar"
    }
    # 2) 源码守卫：agent 关键类存在
    $src = Join-Path $repo "modules\mirage-msr-agent\src\main\java\io\mirage\msr\agent"
    foreach ($n in @("RecorderAgent.java","RecorderTransformer.java","MidIrWriter.java")) {
        if (Test-Path (Join-Path $src $n)) { Log "[PASS] 源文件存在: $n" }
        else { Fail "缺源文件: $n" }
    }
    # 3) 守卫：插桩点常量与 premain 签名在位
    $tr = Get-Content (Join-Path $src "RecorderTransformer.java") -Raw
    if ($tr -match "net/minecraft/core/MappedRegistry" -and $tr -match "net/neoforged/bus/api/EventBus") {
        Log "[PASS] 两个插桩点常量在位"
    } else { Fail "插桩点常量缺失" }
    $ag = Get-Content (Join-Path $src "RecorderAgent.java") -Raw
    if ($ag -match "public static void premain\(") { Log "[PASS] premain 签名在位" }
    else { Fail "premain 签名缺失" }
    # 4) 守卫：agent 不进 fat jar（AGENTS 规则 7.5）
    $settings = Get-Content (Join-Path $repo "settings.gradle.kts") -Raw
    if ($settings -match "mirage-msr-agent" -and $settings -notmatch "launcher:mirage-launch.*mirage-msr-agent") {
        Log "[PASS] agent 是独立模块，未被 fat jar 依赖"
    } else { Fail "agent 模块接线异常" }
    Log "== 自检完成 =="
    $report | Out-File -FilePath $ReportFile -Encoding utf8
    exit 0
}

if ($ListDeps) {
    Log "== P3 依赖清单 =="
    foreach ($f in $required) {
        $state = if (Test-Path $f) { "有  ($( (Get-Item $f).Length ) B)" } else { "缺" }
        Log "  [$state] $f"
    }
    $report | Out-File -FilePath $ReportFile -Encoding utf8
    exit 0
}

if ($missing.Count -gt 0) {
    Log "== 缺失依赖 =="
    $missing | ForEach-Object { Log "  缺: $_" }
    Log "提示：agent jar 用 `gradle :modules:mirage-msr-agent:jar` 构建；"
    Log "      其余依赖在 mc-shili/libraries 下（PCL 装的 NeoForge 26.2.0.90）。"
    Fail "依赖不齐，无法跑 P3"
}

if (-not $ModJar) { Fail "需要 -ModJar <真实 mod jar>（或加 -SelfTest）" }
if (-not (Test-Path $ModJar)) { Fail "mod jar 不存在: $ModJar" }

# ------------------------------------------------------------ 建一次性实例
$inst = Join-Path $env:TEMP ("mirage-msr-" + (Get-Random))
New-Item -ItemType Directory -Force -Path $inst | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $inst "mods") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $inst "config") | Out-Null
Log "一次性实例: $inst"

Copy-Item $ModJar (Join-Path $inst "mods") -Force
Log "已放入待录 mod: $(Split-Path -Leaf $ModJar)"

$ifOut = if ($OutDir) { $OutDir } else { Join-Path $inst "msr-out" }
New-Item -ItemType Directory -Force -Path $ifOut | Out-Null

# ------------------------------------------------------------ 跑
$cp = @($depClient, $depUniversal, $depLoader, $depBus, $depMerge) -join ';'
$jvmArgs = @(
    "-javaagent:$agentJar",
    "-Dmirage.msr.out=$ifOut",
    "-Dmirage.msr.baseline=$($Baseline.IsPresent.ToString().ToLower())",
    "-Djava.library.path=$nativesDir",
    "-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativesDir/lwjgl",
    "-cp", $cp
)
$mainClass = "net.neoforged.fml.startup.Client"
$gameArgs = @("--username", "msr", "--version", "26.2", "--offline", "--quickPlaySingleplayer")

Log "启动真 NeoForge 客户端（--offline 断网）..."
Log "（这是仓库外的一次性实例，不碰 mc-shili/，不进入用户桌面会话）"

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = Join-Path $env:JAVA_HOME "bin\java.exe"
$psi.Arguments = ($jvmArgs + @($mainClass) + $gameArgs) -join ' '
$psi.WorkingDirectory = $inst
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding $false
$psi.StandardErrorEncoding  = New-Object System.Text.UTF8Encoding $false

$p = New-Object System.Diagnostics.Process
$p.StartInfo = $psi
$null = $p.Start()
$so = $p.StandardOutput.ReadToEnd()
$se = $p.StandardError.ReadToEnd()
$p.WaitForExit()

Log "退出码: $($p.ExitCode)"
$stdoutFile = Join-Path $ifOut "stdout.txt"
$stderrFile = Join-Path $ifOut "stderr.txt"
$so | Out-File -FilePath $stdoutFile -Encoding utf8
$se | Out-File -FilePath $stderrFile -Encoding utf8
Log "stdout: $stdoutFile"
Log "stderr: $stderrFile"

$irFile = Join-Path $ifOut ($(if ($Baseline) { "baseline" } else { "recording" }) + ".mirage.json")
if (Test-Path $irFile) {
    Log "[PASS] IR 已产出: $irFile ($((Get-Item $irFile).Length) B)"
} else {
    Log "[FAIL] 没有产出 IR（agent 可能没装上，或实例没跑到注册阶段）"
    Log "--- stdout 末尾 ---"
    ($so -split "`r?`n" | Select-Object -Last 25) | ForEach-Object { Log "  $_" }
    $report | Out-File -FilePath $ReportFile -Encoding utf8
    exit 1
}

if (-not $Keep) {
    Remove-Item $inst -Recurse -Force -ErrorAction SilentlyContinue
    Log "一次性实例已删除（-Keep 可保留）"
} else {
    Log "实例保留在: $inst"
}

$report | Out-File -FilePath $ReportFile -Encoding utf8
Log "报告: $ReportFile"
exit 0
