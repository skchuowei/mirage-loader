# scripts/verify_earlyfade.ps1
# 早期窗口交接「仪表盘 → 纯红」淡出回归（纯 JVM，不启动游戏、不依赖 LWJGL / MC / gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_earlyfade.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_earlyfade.ps1 -Keep
#
# 背景（2026-11-07 用户实机录屏反馈「到主界面过渡动画衔接有问题」）：
#   早期窗口（满屏调试仪表盘）在交接时被 Minecraft 的 Mojang 闪屏「硬切」覆盖——
#   最后一帧还是密集仪表盘，下一帧直接变成居中 Logo，衔接生硬。
#   修复：渲染线程在 handedOff 后先把仪表盘溶解到纯红背景（与 Minecraft 闪屏同色），
#   再交还窗口句柄，硬切变溶解。
#
# 为什么能离线测：
#   把淡出的纯逻辑抽成 EarlyFade（状态机 + 叠加层颜色计算），不碰 GL；
#   于是「淡出会收敛到纯红」「叠加层 alpha 随进度增长」变成可断言行为。
#   MirageEarlyWindow 依赖 LWJGL 无法离线编译，只做源码守卫（见 G1–G4）。
#
# 覆盖：
#   1) 行为断言：advance 在 ~10 帧内收敛到 done 且 alpha=1；半程未完成；overlayColour 数学；
#   2) 源码守卫：handoff 先等 fade.done() / renderFrame 画叠加层 / renderTick 推进 fade /
#      渲染线程不再一 handedOff 就立即 return；
#   3) 变异检验：E1 改 advance 永不推进 / E2 改 overlayColour alpha 恒 0 / E3 跳过淡出等待。
#   原版全绿 + 3 种变异全红才算通过（经验 C59 / C60）。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$fadeSrc = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\EarlyFade.java"
$earlySrc = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\MirageEarlyWindow.java"
foreach ($f in @($fadeSrc, $earlySrc)) {
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

$work = Join-Path $env:TEMP ("mirage-earlyfade-" + $PID)
New-Item -ItemType Directory -Force -Path $work | Out-Null

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

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

$harnessSrc = Join-Path $work "EarlyFadeCheck.java"
$harnessText = @'
package io.mirage.earlydisplay;

/** 由 scripts/verify_earlyfade.ps1 编译运行；输出固定 ASCII 的 STAT 行给 PowerShell 断言。 */
public final class EarlyFadeCheck {
    public static void main(String[] args) {
        // 默认参数：500ms @ 20fps -> 约 10 帧淡出
        EarlyFade f = new EarlyFade(500f, 20);
        int calls = 0;
        while (!f.done() && calls < 40) { f.advance(); calls++; }
        System.out.println("STAT done=" + f.done());
        System.out.println("STAT calls=" + calls);
        System.out.println("STAT alphaAtDone=" + f.alpha());

        // 半程检查：推进 9 次，应未完成且 alpha≈0.9
        EarlyFade g = new EarlyFade(500f, 20);
        for (int i = 0; i < 9; i++) { g.advance(); }
        System.out.println("STAT midDone=" + g.done());
        System.out.println("STAT midAlpha=" + g.alpha());

        // 叠加层颜色数学（背景红 0xFF3D32EF）
        int bg = 0xFF3D32EF;
        int o0 = EarlyFade.overlayColour(0f, bg);
        int o1 = EarlyFade.overlayColour(1.0f, bg);
        int o5 = EarlyFade.overlayColour(0.5f, bg);
        System.out.println("STAT o0=" + o0);
        System.out.println("STAT o1=" + o1);
        System.out.println("STAT o5=" + o5);
        System.out.println("STAT o0Alpha=" + ((o0 >> 24) & 0xFF));
        System.out.println("STAT o1Alpha=" + ((o1 >> 24) & 0xFF));
        System.out.println("STAT o5Alpha=" + ((o5 >> 24) & 0xFF));
    }
}
'@
# 脚本里生成的 .java 必须不带 BOM，否则 javac 报 illegal character: \ufeff
[System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

function Run-Suite([string]$fadeText, [string]$tag) {
    $dir = Join-Path $work $tag
    $classes = Join-Path $dir "classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $srcCopy = Join-Path $dir "EarlyFade.java"
    [System.IO.File]::WriteAllText($srcCopy, $fadeText, (New-Object System.Text.UTF8Encoding($false)))
    $comp = Invoke-Native $javac @(
        "-J-Duser.language=en", "-encoding", "UTF-8", "-d", $classes,
        $srcCopy, $harnessSrc
    ) "javac-$tag"
    if ($comp.Code -ne 0) {
        return [pscustomobject]@{ Code = -1; Err = $comp.Err; Lines = @() }
    }
    $run = Invoke-Native $java @(
        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
        "-cp", $classes, "io.mirage.earlydisplay.EarlyFadeCheck"
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

$fadeText = [System.IO.File]::ReadAllText($fadeSrc, [System.Text.Encoding]::UTF8)
$earlyText = [System.IO.File]::ReadAllText($earlySrc, [System.Text.Encoding]::UTF8)

Write-Host "== 原版 EarlyFade：编译 + 行为断言 ==" -ForegroundColor Cyan
$base = Run-Suite $fadeText "base"
if ($base.Code -ne 0) {
    Write-Host "[FAIL] 原版编译/运行失败：$($base.Err)" -ForegroundColor Red
    exit 1
}
Write-Host "   [OK] 编译通过" -ForegroundColor DarkGreen

$done = StatVal $base.Lines "done"
$calls = [int](StatVal $base.Lines "calls")
$alphaAtDone = [double](StatVal $base.Lines "alphaAtDone")
$midDone = StatVal $base.Lines "midDone"
$midAlpha = [double](StatVal $base.Lines "midAlpha")
$o0 = [int](StatVal $base.Lines "o0")
$o1 = [int](StatVal $base.Lines "o1")
$o5 = [int](StatVal $base.Lines "o5")
$o0Alpha = [int](StatVal $base.Lines "o0Alpha")
$o1Alpha = [int](StatVal $base.Lines "o1Alpha")
$o5Alpha = [int](StatVal $base.Lines "o5Alpha")

Assert-True ($done -eq "true") "淡出最终收敛到 done=true"
Assert-True ($calls -ge 9 -and $calls -le 13) "淡出在 9~13 帧内完成（实际 $calls 帧，500ms@20fps 理论 10 帧）"
Assert-True ([Math]::Abs($alphaAtDone - 1.0) -lt 1e-3) "完成时长 alpha 恰为 1.0（实际 $alphaAtDone）"
Assert-True ($midDone -eq "false") "推进 9 次时尚未完成淡出（midDone=$midDone）"
Assert-True ($midAlpha -gt 0.8 -and $midAlpha -lt 0.95) "半程 alpha≈0.9（实际 $midAlpha）"
Assert-True ($o0Alpha -eq 0) "alpha=0 时叠加层全透明（o0Alpha=$o0Alpha）"
Assert-True ($o1Alpha -eq 255) "alpha=1 时叠加层完全不透明（o1Alpha=$o1Alpha）"
Assert-True ($o5Alpha -eq 128) "alpha=0.5 时叠加层半透明（o5Alpha=$o5Alpha）"
Assert-True ($o1 -eq 0xFF3D32EF) "alpha=1 的叠加层即纯背景红（o1=$o1）"

# ---------------------------------------------------------------- 源码守卫

Write-Host "== 源码守卫 ==" -ForegroundColor Cyan

Assert-True ($earlyText -match '(?m)^\s*boolean faded = HandoffPump\.await\(1200, \(\) -> fade != null && fade\.done\(\), GLFW::glfwPollEvents\);') `
    "handoff 先等仪表盘淡出到纯红再交还窗口（行首锚定）"
Assert-True ($earlyText -match '(?m)^\s*int overlay = EarlyFade\.overlayColour\(fade\.alpha\(\), COLOUR_BACKGROUND\);') `
    "renderFrame 在仪表盘上叠一层随 alpha 增长的背景红（行首锚定）"
Assert-True ($earlyText -match '(?m)^\s*fade\.advance\(\);') `
    "renderTick 在 handedOff 后推进淡出（行首锚定）"
Assert-True (-not ($earlyText -match '(?m)^\s*if \(handedOff \|\| crashed \|\| window == NULL\) return;')) `
    "渲染线程不再一 handedOff 就立即 return（旧硬切路径已移除，行首锚定）"

# ---------------------------------------------------------------- 变异检验

Write-Host "== 变异检验 ==" -ForegroundColor Cyan

$mutations = @(
    [pscustomobject]@{
        Tag = "E1"; Desc = "advance 永不推进（alpha+step 改成 alpha+0）"; Kind = "behaviour"; File = "fade"
        Old = "double next = alpha + step;"
        New = "double next = alpha + 0.0;"
        Guard = '(?m)^\s*double next = alpha \+ step;'
    },
    [pscustomobject]@{
        Tag = "E2"; Desc = "overlayColour alpha 恒为 0（叠加层永远透明）"; Kind = "behaviour"; File = "fade"
        Old = "int a = Math.round(Math.min(1f, Math.max(0f, alpha)) * 255f);"
        New = "int a = 0;"
        Guard = '(?m)^\s*int a = Math\.round\(Math\.min\(1f, Math\.max\(0f, alpha\)\) \* 255f\);'
    },
    [pscustomobject]@{
        Tag = "E3"; Desc = "handoff 跳过淡出等待（直接视为完成，纯守卫）"; Kind = "guard"; File = "early"
        Old = "boolean faded = HandoffPump.await(1200, () -> fade != null && fade.done(), GLFW::glfwPollEvents);"
        New = "boolean faded = true;"
        Guard = '(?m)^\s*boolean faded = HandoffPump\.await\(1200, \(\) -> fade != null && fade\.done\(\), GLFW::glfwPollEvents\);'
    }
)

foreach ($m in $mutations) {
    $mutFade = $fadeText
    $mutEarly = $earlyText
    if ($m.File -eq "fade") {
        if (-not $mutFade.Contains($m.Old)) {
            Assert-True $false "$($m.Tag) 变异锚点未找到（脚本与源码已脱节）"
            continue
        }
        $mutFade = $mutFade.Replace($m.Old, $m.New)
    } else {
        if (-not $mutEarly.Contains($m.Old)) {
            Assert-True $false "$($m.Tag) 变异锚点未找到（脚本与源码已脱节）"
            continue
        }
        $mutEarly = $mutEarly.Replace($m.Old, $m.New)
    }

    if ($m.Kind -eq "behaviour") {
        # 行为变异：重新编译 EarlyFade + 跑同一个 harness，关键行为断言必须不再全绿。
        $r = Run-Suite $mutFade ("mut-" + $m.Tag)
        $d = StatVal $r.Lines "done"
        $c = [int](StatVal $r.Lines "calls")
        $a1 = [int](StatVal $r.Lines "o1Alpha")
        $behaviourRed = ($r.Code -ne 0) -or ($d -ne "true") -or ($c -ge 40) -or ($a1 -ne 255)
        Assert-True $behaviourRed "$($m.Tag) $($m.Desc)：行为断言被抓红（done=$d, calls=$c, o1Alpha=$a1）"
    } else {
        Write-Host "   [OK]   $($m.Tag) 纯守卫变异：只重跑源码守卫（earlydisplay 依赖 LWJGL，不离线编译）" -ForegroundColor DarkGreen
    }

    $guardSrc = if ($m.File -eq "fade") { $mutFade } else { $mutEarly }
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
