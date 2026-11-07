# scripts/verify_handoff_pump.ps1
# 窗口交接消息泵回归（纯 JVM，不启动游戏、不依赖 LWJGL / MC / gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_handoff_pump.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_handoff_pump.ps1 -Keep
#
# 背景（2026-11-07 用户实机反馈「窗口交接时会未响应」）：
#   MirageEarlyWindow.handoff() 旧实现是 renderLock.tryAcquire(3, SECONDS) +
#   scheduler.awaitTermination(2, SECONDS) 两段纯阻塞，合计 5s；而 handedOff 置位后
#   渲染任务已被 cancel、不再 glfwPollEvents()，交接线程自己又不抽窗口消息 ——
#   正好踩中 Windows 判定「未响应」的阈值。
#
# 为什么能离线测：
#   把「等待期间持续保活」抽成纯 JDK 的 HandoffPump.await(timeout, ready, pump)，
#   pump 在生产里是 GLFW::glfwPollEvents，在回归里是一个计数器。
#   于是「等待期间消息泵真的被反复调用」变成可断言的行为，而不是口头约定。
#
# 覆盖：
#   1) 行为断言：立即就绪不空转 / 延迟就绪期间 pump >=1 / 永不就绪按时超时且 pump >=1；
#   2) 源码守卫：handoff() 两段等待都走 HandoffPump + GLFW 消息泵，旧阻塞调用不再回来；
#   3) 变异检验：H1/H2 改 HandoffPump 源码重编译跑（行为必须变红），
#      H3/H4 改 handoff 接线文本（MirageEarlyWindow 依赖 LWJGL 无法离线编译，只重跑守卫）。
#   原版全绿 + 4 种变异全红才算通过（经验 A7 / C36 / C59）。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$pumpSrc = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\HandoffPump.java"
$earlySrc = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\MirageEarlyWindow.java"
foreach ($f in @($pumpSrc, $earlySrc)) {
    if (-not (Test-Path $f)) {
        Write-Host "[FAIL] 找不到源码: $f" -ForegroundColor Red
        exit 1
    }
}

# ---------------------------------------------------------------- 工具链定位

function Find-JavaTool([string]$name) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$name.exe") }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($v in @("jdk-25.0.4", "jdk-21.0.11", "jdk-17")) {
        $candidates += "C:\Program Files\Java\$v\bin\$name.exe"
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

$javac = Find-JavaTool "javac"
$java = Find-JavaTool "java"
if (-not $javac -or -not $java) {
    Write-Host "[FAIL] 没找到 javac/java（请设置 JAVA_HOME）" -ForegroundColor Red
    exit 1
}

$work = Join-Path $env:TEMP ("mirage-handoff-" + $PID)
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 不用 Start-Process / 不用 & 2>&1：PS 5.1 会把原生 stderr 变成 NativeCommandError。
# 先读流再 WaitForExit()，避免子进程输出缓冲死锁。
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
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()
    return [pscustomobject]@{
        Tag = $tag; Code = $proc.ExitCode; Out = $outTask.Result; Err = $errTask.Result
    }
}

# ---------------------------------------------------------------- 检查程序

$harnessSrc = Join-Path $work "HandoffPumpCheck.java"
$harnessText = @'
package io.mirage.earlydisplay;

import java.util.concurrent.atomic.AtomicInteger;

/** 由 scripts/verify_handoff_pump.ps1 编译运行；输出固定 ASCII 的 STAT 行给 PowerShell 断言。 */
public final class HandoffPumpCheck {
    public static void main(String[] args) {
        System.out.println("STAT interval=" + HandoffPump.PUMP_INTERVAL_NANOS);

        // 1) 立即就绪：一次 pump 都不该发生（不能为了保活而空转）
        AtomicInteger p1 = new AtomicInteger();
        boolean r1 = HandoffPump.await(200, () -> true, () -> p1.incrementAndGet());
        System.out.println("STAT immediate.result=" + r1);
        System.out.println("STAT immediate.pumps=" + p1.get());

        // 2) 第 3 次检查才就绪：期间必须 pump 过，且不能等到 timeout
        AtomicInteger checks2 = new AtomicInteger();
        AtomicInteger p2 = new AtomicInteger();
        long t2 = System.nanoTime();
        boolean r2 = HandoffPump.await(500, () -> checks2.incrementAndGet() >= 3,
                () -> p2.incrementAndGet());
        long e2 = (System.nanoTime() - t2) / 1_000_000L;
        System.out.println("STAT delayed.result=" + r2);
        System.out.println("STAT delayed.pumps=" + p2.get());
        System.out.println("STAT delayed.checks=" + checks2.get());
        System.out.println("STAT delayed.elapsedMs=" + e2);

        // 3) 永不就绪：必须按时返回 false，且期间 pump 被反复调用（交接窗口才不会「未响应」）
        AtomicInteger p3 = new AtomicInteger();
        long t3 = System.nanoTime();
        boolean r3 = HandoffPump.await(120, () -> false, () -> p3.incrementAndGet());
        long e3 = (System.nanoTime() - t3) / 1_000_000L;
        System.out.println("STAT timeout.result=" + r3);
        System.out.println("STAT timeout.pumps=" + p3.get());
        System.out.println("STAT timeout.elapsedMs=" + e3);

        // 4) timeout <= 0：只判一次条件，不进入等待循环
        AtomicInteger p4 = new AtomicInteger();
        boolean r4 = HandoffPump.await(0, () -> true, () -> p4.incrementAndGet());
        System.out.println("STAT zero.result=" + r4);
        System.out.println("STAT zero.pumps=" + p4.get());
    }
}
'@
# 脚本里生成的 .java 必须不带 BOM，否则 javac 报 illegal character: \ufeff
[System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

function Run-Suite([string]$pumpText, [string]$tag) {
    $dir = Join-Path $work $tag
    $classes = Join-Path $dir "classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $srcCopy = Join-Path $dir "HandoffPump.java"
    [System.IO.File]::WriteAllText($srcCopy, $pumpText, (New-Object System.Text.UTF8Encoding($false)))
    $comp = Invoke-Native $javac @(
        "-J-Duser.language=en", "-encoding", "UTF-8", "-d", $classes,
        $srcCopy, $harnessSrc
    ) "javac-$tag"
    if ($comp.Code -ne 0) {
        return [pscustomobject]@{ Code = -1; Err = $comp.Err; Lines = @() }
    }
    $run = Invoke-Native $java @(
        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
        "-cp", $classes, "io.mirage.earlydisplay.HandoffPumpCheck"
    ) "run-$tag"
    $lines = ($run.Out -split "`r?`n") | Where-Object { $_.Trim() }
    return [pscustomobject]@{ Code = $run.Code; Err = $run.Err; Lines = $lines }
}

function StatVal([string[]]$lines, [string]$key) {
    $m = [regex]::Match(($lines -join "`n"), "(?m)^STAT " + [regex]::Escape($key) + "=(.*)$")
    if (-not $m.Success) { return $null }
    return $m.Groups[1].Value
}

# ---------------------------------------------------------------- 断言框架

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]

function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
    } else {
        $script:failures.Add($desc)
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
    }
}

$pumpText = [System.IO.File]::ReadAllText($pumpSrc, [System.Text.Encoding]::UTF8)
$earlyText = [System.IO.File]::ReadAllText($earlySrc, [System.Text.Encoding]::UTF8)

Write-Host "== 原版 HandoffPump：编译 + 行为断言 ==" -ForegroundColor Cyan
$base = Run-Suite $pumpText "base"
if ($base.Code -ne 0) {
    Write-Host "[FAIL] 原版编译/运行失败：$($base.Err)" -ForegroundColor Red
    exit 1
}
Write-Host "   [OK] 编译通过" -ForegroundColor DarkGreen

$interval = [int](StatVal $base.Lines "interval")
$immResult = StatVal $base.Lines "immediate.result"
$immPumps = [int](StatVal $base.Lines "immediate.pumps")
$delResult = StatVal $base.Lines "delayed.result"
$delPumps = [int](StatVal $base.Lines "delayed.pumps")
$delChecks = [int](StatVal $base.Lines "delayed.checks")
$delElapsed = [int](StatVal $base.Lines "delayed.elapsedMs")
$toResult = StatVal $base.Lines "timeout.result"
$toPumps = [int](StatVal $base.Lines "timeout.pumps")
$toElapsed = [int](StatVal $base.Lines "timeout.elapsedMs")
$zeroResult = StatVal $base.Lines "zero.result"
$zeroPumps = [int](StatVal $base.Lines "zero.pumps")

Assert-True ($interval -eq 10000000) "pump 间隔 = 10ms（实际 $interval ns）"
Assert-True ($immResult -eq "true" -and $immPumps -eq 0) `
    "条件立即就绪时不空转（result=$immResult, pumps=$immPumps）"
Assert-True ($delResult -eq "true") "延迟就绪最终返回 true（result=$delResult）"
Assert-True ($delPumps -ge 1) "延迟就绪期间消息泵被调用（pumps=$delPumps）"
Assert-True ($delChecks -ge 3) "确实做了多次就绪检查（checks=$delChecks）"
Assert-True ($delElapsed -lt 500) "延迟就绪没有等到超时上限（elapsed=$delElapsed ms）"
Assert-True ($toResult -eq "false") "永不就绪按时返回 false（result=$toResult）"
Assert-True ($toPumps -ge 1) "超时路径期间消息泵仍被反复调用（pumps=$toPumps）"
Assert-True ($toElapsed -ge 100 -and $toElapsed -le 800) `
    "超时耗时与 120ms 上限相符（elapsed=$toElapsed ms）"
Assert-True ($zeroResult -eq "true" -and $zeroPumps -eq 0) `
    "timeout<=0 只判一次条件、不 pump（result=$zeroResult, pumps=$zeroPumps）"

# ---------------------------------------------------------------- 源码守卫
# 正则一律行首锚定；Javadoc / 注释里提到同名文本不算「接线还在」（经验 C59）。

function Check-Guards([string]$pump, [string]$early) {
    Assert-True ($pump -match '(?m)^\s*pump\.run\(\);') `
        "HandoffPump 每轮未就绪都调用 pump（行首锚定）"
    Assert-True ($pump -match '(?m)^\s*LockSupport\.parkNanos\(PUMP_INTERVAL_NANOS\);') `
        "pump 之间有固定间隔，不会 CPU 自旋（行首锚定）"
    Assert-True ($pump -match '(?m)^\s*if \(ready\.getAsBoolean\(\)\) return true;') `
        "每轮先判就绪再保活（行首锚定）"
    Assert-True ($pump -match '(?m)^\s*if \(System\.nanoTime\(\) - deadline >= 0\) return false;') `
        "超时返回 false 而不是 true（行首锚定）"
    Assert-True ($early -match '(?m)^\s*boolean faded = HandoffPump\.await\(1200, \(\) -> fade != null && fade\.done\(\), GLFW::glfwPollEvents\);') `
        "handoff 先等仪表盘淡出到纯红（HandoffPump + GLFW 消息泵，行首锚定）"
    Assert-True ($early -match '(?m)^\s*boolean terminated = HandoffPump\.await\(500, scheduler::isTerminated,') `
        "handoff 第二段等待等的是渲染线程真正退出（行首锚定）"
    Assert-True ($early -match '(?m)^\s*GLFW::glfwPollEvents\);') `
        "第二段等待也传了 GLFW 消息泵（行首锚定）"
    Assert-True (-not ($early -match '(?m)^\s*locked = renderLock\.tryAcquire\(3, TimeUnit\.SECONDS\);')) `
        "旧实现 tryAcquire(3s) 裸阻塞没有回来（行首锚定）"
    Assert-True (-not ($early -match '(?m)^\s*scheduler\.awaitTermination\(2, TimeUnit\.SECONDS\);')) `
        "旧实现 awaitTermination(2s) 裸阻塞没有回来（行首锚定）"
}

Write-Host "== 源码守卫 ==" -ForegroundColor Cyan
Check-Guards $pumpText $earlyText

# ---------------------------------------------------------------- 变异检验

Write-Host "== 变异检验 ==" -ForegroundColor Cyan

$mutations = @(
    [pscustomobject]@{
        Tag = "H1"; Desc = "删掉 pump.run()（等待期间不再保活）"; Kind = "behaviour"; File = "pump"
        Old = "            pump.run();"
        New = "            // 变异：等待期间不抽窗口消息"
        Guard = '(?m)^\s*pump\.run\(\);'
    },
    [pscustomobject]@{
        Tag = "H2"; Desc = "超时误返回 true（等不到也说成功）"; Kind = "behaviour"; File = "pump"
        Old = "if (System.nanoTime() - deadline >= 0) return false;"
        New = "if (System.nanoTime() - deadline >= 0) return true;"
        Guard = '(?m)^\s*if \(System\.nanoTime\(\) - deadline >= 0\) return false;'
    },
    [pscustomobject]@{
        Tag = "H3"; Desc = "第一段等待跳过淡出（直接视为完成，纯守卫）"; Kind = "guard"; File = "early"
        Old = "boolean faded = HandoffPump.await(1200, () -> fade != null && fade.done(), GLFW::glfwPollEvents);"
        New = "boolean faded = true;"
        Guard = '(?m)^\s*boolean faded = HandoffPump\.await\(1200, \(\) -> fade != null && fade\.done\(\), GLFW::glfwPollEvents\);'
    },
    [pscustomobject]@{
        Tag = "H4"; Desc = "第二段不再等渲染线程退出（纯守卫）"; Kind = "guard"; File = "early"
        Old = "boolean terminated = HandoffPump.await(500, scheduler::isTerminated,"
        New = "boolean terminated = HandoffPump.await(500, () -> true,"
        Guard = '(?m)^\s*boolean terminated = HandoffPump\.await\(500, scheduler::isTerminated,'
    }
)

foreach ($m in $mutations) {
    $mutPump = $pumpText
    $mutEarly = $earlyText
    if ($m.File -eq "pump") {
        if (-not $mutPump.Contains($m.Old)) {
            Assert-True $false "$($m.Tag) 变异锚点未找到（脚本与源码已脱节）"
            continue
        }
        $mutPump = $mutPump.Replace($m.Old, $m.New)
    } else {
        if (-not $mutEarly.Contains($m.Old)) {
            Assert-True $false "$($m.Tag) 变异锚点未找到（脚本与源码已脱节）"
            continue
        }
        $mutEarly = $mutEarly.Replace($m.Old, $m.New)
    }

    if ($m.Kind -eq "behaviour") {
        # 行为变异：重新编译 HandoffPump + 跑同一个 harness，关键行为断言必须不再全绿。
        $r = Run-Suite $mutPump ("mut-" + $m.Tag)
        $dPumps = [int](StatVal $r.Lines "delayed.pumps")
        $tPumps = [int](StatVal $r.Lines "timeout.pumps")
        $tResult = StatVal $r.Lines "timeout.result"
        $behaviourRed = ($r.Code -ne 0) -or ($dPumps -lt 1) -or ($tPumps -lt 1) -or ($tResult -ne "false")
        Assert-True $behaviourRed "$($m.Tag) $($m.Desc)：行为断言被抓红（delayed.pumps=$dPumps, timeout.pumps=$tPumps, timeout.result=$tResult）"
    } else {
        Write-Host "   [OK]   $($m.Tag) 纯守卫变异：只重跑源码守卫（earlydisplay 依赖 LWJGL，不离线编译）" -ForegroundColor DarkGreen
    }

    $guardSrc = if ($m.File -eq "pump") { $mutPump } else { $mutEarly }
    Assert-True (-not ($guardSrc -match $m.Guard)) `
        "$($m.Tag) $($m.Desc)：对应源码守卫同时失效（守卫本身不空转）"
}

# ---------------------------------------------------------------- 收尾

if (-not $Keep) {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
} else {
    Write-Host "临时目录保留在: $work" -ForegroundColor DarkGray
}

Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host "全部通过：$($script:checks) / $($script:checks)" -ForegroundColor Green
    exit 0
}
Write-Host "失败 $($script:failures.Count) / $($script:checks)：" -ForegroundColor Red
foreach ($f in $script:failures) { Write-Host "  - $f" -ForegroundColor Red }
exit 1
