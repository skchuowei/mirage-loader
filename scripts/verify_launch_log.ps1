# verify_launch_log.ps1 —— 启动期日志落盘回归验证（纯 JVM，不启动游戏）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_launch_log.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_launch_log.ps1 -Keep
#
# 为什么需要这份回归：
#   log4j 直到 Minecraft 启动后才接管并写 latest.log，在那之前的 S1–S3（mod 发现、MID 缓存、
#   沙盒录制）只进 bat 控制台窗口 —— 2026-10-31 MSR P2 实机冒烟时，`[Sandbox]`/`[MID]` 一行都
#   在 latest.log 里找不到，就是这个原因。Launcher 的 TeeOutputStream 现在会把每行再写一份到
#   <gameDir>/logs/mirage-launch.log。这份脚本守住四件事：
#     1. 文件真的被创建，且带每次启动的分隔头（同一次会话的日志边界可读）；
#     2. stdout 与 stderr 两路都落盘（MirageLog 的 WARN+ 走 stderr，不能漏）；
#     3. 二次启动是 append 而不是覆盖（否则排查只能看最后一次）；
#     4. gameDir 缺失时安静退化（不抛、不建目录），不能因为拿不到路径就影响启动。
#
# 依赖 fat jar（里面才有 io.mirage.launch.Launcher 及其下游）：
#   gradle :launcher:mirage-launch:fatJar
#
# 退出码 0 = 全部通过；1 = 有失败项。

param([switch]$Keep)

$ErrorActionPreference = "Stop"
$script:ok = 0
$script:fail = 0
$script:failures = @()

function Assert-True([bool]$cond, [string]$desc) {
    if ($cond) {
        $script:ok++
        Write-Host "  [OK]   $desc"
    } else {
        $script:fail++
        $script:failures += $desc
        Write-Host "  [FAIL] $desc" -ForegroundColor Red
    }
}

function Assert-Match([string]$text, [string]$pattern, [string]$desc) {
    Assert-True ($text -match $pattern) $desc
}

# ---------------------------------------------------------------- 工具链定位

function Find-JavaTool([string]$name) {
    $cands = @()
    if ($env:JAVA_HOME) { $cands += (Join-Path $env:JAVA_HOME "bin\$name.exe") }
    $cands += (Join-Path $PSHOME "..\bin\$name.exe")
    foreach ($c in $cands) { if (Test-Path $c) { return (Resolve-Path $c).Path } }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "找不到 $name，请设置 JAVA_HOME"
}

$javac = Find-JavaTool "javac"
$java = Find-JavaTool "java"

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 跑一个外部进程，分别取回 stdout / stderr / 退出码。
# 不用 Start-Process：本机 http_proxy 与 HTTP_PROXY 并存，复制环境时会抛"已添加项"。
# 也不用 & 调用 + 2>&1：PS 5.1 会把原生 stderr 变成 NativeCommandError，配合 Stop 会中断脚本。
# 注意用 Arguments 字符串而不是 ArgumentList（后者 .NET Framework 4.8 上没有）。
function Invoke-Native([string]$exe, [string[]]$argv, [string]$tag) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = ($argv | ForEach-Object { Quote-Arg $_ }) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $p = [System.Diagnostics.Process]::Start($psi)
    # 先起异步读，避免任一管道缓冲区写满导致 WaitForExit 卡死
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    $p.WaitForExit()
    return [pscustomobject]@{ Out = $outTask.Result; Err = $errTask.Result; Code = $p.ExitCode; Tag = $tag }
}

# ---------------------------------------------------------------- 前置：fat jar

$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
if (-not (Test-Path $jar)) {
    Write-Host "找不到 $jar" -ForegroundColor Red
    Write-Host "请先运行：gradle :launcher:mirage-launch:fatJar" -ForegroundColor Yellow
    exit 1
}

# ---------------------------------------------------------------- 临时工作区

$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-launchlog-" + $PID)
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$probeDir = Join-Path $tmp "src\io\mirage\probe"
New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
$gameDir = Join-Path $tmp "gamedir"
New-Item -ItemType Directory -Force -Path $gameDir | Out-Null

try {
    # ------------------------------------------------------------ 探针程序
    # 反射调用 private static Launcher.installLogTee(Path)：这是 main 里唯一与落盘相关的入口，
    # 直接跑 main 会把整局游戏拉起来（那是实机冒烟，不是回归）。
    $probe = @'
package io.mirage.probe;

import io.mirage.api.log.MirageLog;

import java.lang.reflect.Method;
import java.nio.file.Path;

public final class LaunchLogProbe {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path gameDir = args.length > 1 && !"null".equals(args[1]) ? Path.of(args[1]) : null;

        Class<?> launcher = Class.forName("io.mirage.launch.Launcher");
        Method tee = launcher.getDeclaredMethod("installLogTee", Path.class);
        tee.setAccessible(true);
        tee.invoke(null, gameDir);

        System.out.println("PROBE-STDOUT-LINE");
        System.err.println("PROBE-STDERR-LINE");
        MirageLog.MIRAGE.info("PROBE-CHANNEL-INFO");
        MirageLog.MIRAGE.warn("PROBE-CHANNEL-WARN");
        System.out.println("PROBE-" + mode + "-END");
    }
}
'@
    # 无 BOM 的 UTF-8：javac 不剥 BOM，带了会报 illegal character: '\ufeff'
    $probeFile = Join-Path $probeDir "LaunchLogProbe.java"
    [System.IO.File]::WriteAllText($probeFile, $probe, (New-Object System.Text.UTF8Encoding($false)))

    $srcRoot = Join-Path $tmp "src"
    $classes = Join-Path $tmp "classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    # -proc:none：fat jar 里带了 Mixin 的 annotation processor（META-INF/services），
    # javac 会顺手拉起来跑，而它依赖 guava —— 探针根本不用注解处理，直接关掉。
    $r = Invoke-Native $javac @("-encoding", "UTF-8", "-proc:none", "-cp", $jar, "-d", $classes, $probeFile) "javac"
    if ($r.Code -ne 0) {
        Write-Host $r.Out
        Write-Host $r.Err -ForegroundColor Red
        throw "javac 失败"
    }

    $cp = $jar + [System.IO.Path]::PathSeparator + $classes
    $logFile = Join-Path $gameDir "logs\mirage-launch.log"

    Write-Host "`n=== 场景 1：正常安装（有 gameDir） ==="
    $r1 = Invoke-Native $java @("-cp", $cp, "io.mirage.probe.LaunchLogProbe", "FIRST", $gameDir) "run1"
    Assert-True ($r1.Code -eq 0) "探针进程正常退出（日志 tee 不该把进程搞崩）"
    Assert-True (Test-Path $logFile) "logs\mirage-launch.log 已创建"

    $text1 = if (Test-Path $logFile) { [System.IO.File]::ReadAllText($logFile, [System.Text.Encoding]::UTF8) } else { "" }
    Assert-Match $text1 '===== Mirage Launch \d+\.\d+\.\d+ @ \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} =====' "文件开头有启动分隔头（含版本与时间）"
    Assert-Match $text1 'PROBE-STDOUT-LINE'  "stdout 行落盘"
    Assert-Match $text1 'PROBE-STDERR-LINE'  "stderr 行落盘（WARN+ 走 stderr，不能漏）"
    Assert-Match $text1 '\[MIRAGE\].*PROBE-CHANNEL-INFO' "MirageLog INFO 通过 tee 落盘"
    Assert-Match $text1 '\[MIRAGE\].*PROBE-CHANNEL-WARN' "MirageLog WARN 通过 tee 落盘"
    Assert-Match $text1 'PROBE-FIRST-END'     "结尾标记落盘（说明缓冲在进程结束前已刷新）"

    Write-Host "`n=== 场景 2：二次启动是 append，不是覆盖 ==="
    $r2 = Invoke-Native $java @("-cp", $cp, "io.mirage.probe.LaunchLogProbe", "SECOND", $gameDir) "run2"
    Assert-True ($r2.Code -eq 0) "第二次探针进程正常退出"
    $text2 = [System.IO.File]::ReadAllText($logFile, [System.Text.Encoding]::UTF8)
    Assert-Match $text2 'PROBE-FIRST-END'  "上一轮内容仍在（append 而非 truncate）"
    Assert-Match $text2 'PROBE-SECOND-END' "本轮内容写入"
    $headers = ([regex]::Matches($text2, '===== Mirage Launch ')).Count
    Assert-True ($headers -eq 2) "分隔头出现 2 次（每次启动一段，实际 $headers）"

    Write-Host "`n=== 场景 3：gameDir 缺失时安静退化 ==="
    $noDir = Join-Path $tmp "nodir-gamedir"
    $r3 = Invoke-Native $java @("-cp", $cp, "io.mirage.probe.LaunchLogProbe", "NODIR", "null") "run3"
    Assert-True ($r3.Code -eq 0) "无 gameDir 也能正常跑完（不因落盘失败中断启动）"
    Assert-Match $r3.Out 'PROBE-NODIR-END' "无 gameDir 时 stdout 仍照常输出"
    Assert-True (-not (Test-Path $noDir)) "无 gameDir 时没有凭空创建目录"

    # ------------------------------------------------------------ 汇总
    Write-Host ""
    if ($script:fail -eq 0) {
        Write-Host "全部通过：$($script:ok) 项" -ForegroundColor Green
    } else {
        Write-Host "失败 $($script:fail) 项 / 通过 $($script:ok) 项" -ForegroundColor Red
        foreach ($f in $script:failures) { Write-Host "  - $f" -ForegroundColor Red }
        Write-Host "`n--- 落盘文件内容 ---" -ForegroundColor Yellow
        if (Test-Path $logFile) { Write-Host $text2 }
        Write-Host "`n--- 场景3 stdout ---"
        Write-Host $r3.Out
    }
} finally {
    if ($Keep) {
        Write-Host "`n临时目录保留：$tmp" -ForegroundColor Yellow
    } else {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

if ($script:fail -eq 0) { exit 0 } else { exit 1 }
