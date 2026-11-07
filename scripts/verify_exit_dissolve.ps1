# scripts/verify_exit_dissolve.ps1
# 加载界面「仪表盘 → 标题画面」收尾交叉溶解回归（纯 JVM，不启动游戏、不依赖 MC / gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_exit_dissolve.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_exit_dissolve.ps1 -Keep
#
# 背景（2026-11-07 用户第二次录屏反馈「过渡是还会…」）：
#   逐帧取证结论：早期窗口的交接淡出其实是好的（纯红恰在 handoff.begin 出现），
#   真正剩下的硬切在交接之后——主窗口的加载遮罩（LoadingOverlay）是「满不透明」消失的：
#   21.63s 还是满屏仪表盘，21.67s 已是 MC 自己的 Mojang Logo，中间零帧过渡。
#   遮罩自身的淡出由 MC 的 fadeOutStart 控制，我们只能反射读私有字段，读不到会静默回退成
#   不透明，不能把收尾押在上面。于是把收尾挪到「我们自己确定会被调用」的标题画面：
#   标题画面第一帧整屏盖住（与遮罩最后一帧同源同数据，无接缝），随后 600ms 线性淡出。
#
# 为什么能离线测：
#   把溶解的纯逻辑抽成 ExitDissolve（只有时间与 alpha），不碰 GL / GuiGraphics，
#   于是「未启动即不画」「600ms 归零」「一个会话只溶解一次」变成可断言行为。
#   ForgeStyleOverlay / MirageDebug 依赖 MC 类，只做源码守卫（见 G1–G8）。
#
# 覆盖：
#   1) 行为断言：未启动 alpha=0；启动即 255；t=300ms 为 128；t>=600ms 归零；重复 begin 无效；
#   2) 源码守卫：标题画面首帧画溶解 / 满不透明才补 / 用最后一帧视图快照 / 只触发一次；
#   3) 变异检验：X1 alpha 恒 255 / X2 begin 无条件放行 / X3 去掉「满不透明」门槛 /
#      X4 去掉标题画面的溶解接线。
#   原版全绿 + 4 种变异全红才算通过（经验 C59 / C60）。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$dissolveSrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ExitDissolve.java"
$overlaySrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ForgeStyleOverlay.java"
$debugSrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\MirageDebug.java"
foreach ($f in @($dissolveSrc, $overlaySrc, $debugSrc)) {
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

$work = Join-Path $env:TEMP ("mirage-exitdissolve-" + $PID)
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

$harnessSrc = Join-Path $work "ExitDissolveCheck.java"
$harnessText = @'
package io.mirage.loader;

/** 由 scripts/verify_exit_dissolve.ps1 编译运行；输出固定 ASCII 的 STAT 行给 PowerShell 断言。 */
public final class ExitDissolveCheck {
    public static void main(String[] args) {
        // 生产实参：600ms
        ExitDissolve d = new ExitDissolve(600L);

        // 未启动时不画（alpha 必须是 0，否则标题画面会无故盖红屏）
        System.out.println("STAT beforeStart=" + d.alpha255(1000L));
        System.out.println("STAT startedBefore=" + d.started());

        boolean beginOk = d.begin(1000L);
        System.out.println("STAT beginOk=" + beginOk);
        // 幂等：第二次 begin 必须被拒（一个会话只溶解一次）
        System.out.println("STAT beginAgain=" + d.begin(2000L));

        System.out.println("STAT a0=" + d.alpha255(1000L));
        System.out.println("STAT a150=" + d.alpha255(1150L));
        System.out.println("STAT a300=" + d.alpha255(1300L));
        System.out.println("STAT a450=" + d.alpha255(1450L));
        System.out.println("STAT a599=" + d.alpha255(1599L));
        System.out.println("STAT a600=" + d.alpha255(1600L));
        System.out.println("STAT a900=" + d.alpha255(1900L));
        // 归零后再 begin 仍必须被拒（已用完）
        System.out.println("STAT beginAfterDone=" + d.begin(5000L));

        // 单调性：整段 alpha 不允许回弹
        ExitDissolve m = new ExitDissolve(600L);
        m.begin(0L);
        int prev = 256;
        boolean mono = true;
        for (long t = 0; t <= 900; t += 10) {
            int a = m.alpha255(t);
            if (a > prev) { mono = false; }
            prev = a;
        }
        System.out.println("STAT monotonic=" + mono);

        // 极小时长不该除零/越界
        ExitDissolve tiny = new ExitDissolve(0L);
        tiny.begin(0L);
        System.out.println("STAT tiny0=" + tiny.alpha255(0L));
        System.out.println("STAT tiny1=" + tiny.alpha255(1L));
    }
}
'@
# 脚本里生成的 .java 必须不带 BOM，否则 javac 报 illegal character: \ufeff
[System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

function Run-Suite([string]$dissolveText, [string]$tag) {
    $dir = Join-Path $work $tag
    $classes = Join-Path $dir "classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null
    $srcCopy = Join-Path $dir "ExitDissolve.java"
    [System.IO.File]::WriteAllText($srcCopy, $dissolveText, (New-Object System.Text.UTF8Encoding($false)))
    $comp = Invoke-Native $javac @(
        "-J-Duser.language=en", "-encoding", "UTF-8", "-d", $classes,
        $srcCopy, $harnessSrc
    ) "javac-$tag"
    if ($comp.Code -ne 0) {
        return [pscustomobject]@{ Code = -1; Err = $comp.Err; Lines = @() }
    }
    $run = Invoke-Native $java @(
        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
        "-cp", $classes, "io.mirage.loader.ExitDissolveCheck"
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

$dissolveText = [System.IO.File]::ReadAllText($dissolveSrc, [System.Text.Encoding]::UTF8)
$overlayText = [System.IO.File]::ReadAllText($overlaySrc, [System.Text.Encoding]::UTF8)
$debugText = [System.IO.File]::ReadAllText($debugSrc, [System.Text.Encoding]::UTF8)

Write-Host "== 原版 ExitDissolve：编译 + 行为断言 ==" -ForegroundColor Cyan
$base = Run-Suite $dissolveText "base"
if ($base.Code -ne 0) {
    Write-Host "[FAIL] 原版编译/运行失败：$($base.Err)" -ForegroundColor Red
    exit 1
}
Write-Host "   [OK] 编译通过" -ForegroundColor DarkGreen

$beforeStart = [int](StatVal $base.Lines "beforeStart")
$startedBefore = StatVal $base.Lines "startedBefore"
$beginOk = StatVal $base.Lines "beginOk"
$beginAgain = StatVal $base.Lines "beginAgain"
$a0 = [int](StatVal $base.Lines "a0")
$a150 = [int](StatVal $base.Lines "a150")
$a300 = [int](StatVal $base.Lines "a300")
$a450 = [int](StatVal $base.Lines "a450")
$a599 = [int](StatVal $base.Lines "a599")
$a600 = [int](StatVal $base.Lines "a600")
$a900 = [int](StatVal $base.Lines "a900")
$beginAfterDone = StatVal $base.Lines "beginAfterDone"
$monotonic = StatVal $base.Lines "monotonic"
$tiny0 = [int](StatVal $base.Lines "tiny0")
$tiny1 = [int](StatVal $base.Lines "tiny1")

Assert-True ($startedBefore -eq "false") "未启动时 started=false（不该在非首帧就盖屏）"
Assert-True ($beforeStart -eq 0) "未启动时 alpha=0（实际 $beforeStart）"
Assert-True ($beginOk -eq "true") "首次 begin 生效"
Assert-True ($beginAgain -eq "false") "重复 begin 被拒（一个会话只溶解一次）"
Assert-True ($a0 -eq 255) "启动瞬间 alpha=255，与遮罩最后一帧完全一致（实际 $a0）"
Assert-True ($a150 -eq 192) "t=150ms alpha=192（1/4 处，实际 $a150）"
Assert-True ($a300 -eq 128) "t=300ms alpha=128（半程，实际 $a300）"
Assert-True ($a450 -eq 64) "t=450ms alpha=64（3/4 处，实际 $a450）"
Assert-True ($a599 -eq 1) "t=599ms alpha=1（最后一帧仍不是 0，避免提前露底，实际 $a599）"
Assert-True ($a600 -eq 0) "t=600ms alpha 归零（实际 $a600）"
Assert-True ($a900 -eq 0) "归零后持续为 0（实际 $a900）"
Assert-True ($beginAfterDone -eq "false") "归零后再 begin 仍被拒（不重播）"
Assert-True ($monotonic -eq "true") "整段 alpha 单调不回弹"
Assert-True ($tiny0 -eq 255) "fadeMs=0 被夹到 1ms：起点仍是 255（实际 $tiny0）"
Assert-True ($tiny1 -eq 0) "fadeMs=0 被夹到 1ms：1ms 后即归零（不除零，实际 $tiny1）"

# ---------------------------------------------------------------- 源码守卫

Write-Host "== 源码守卫 ==" -ForegroundColor Cyan

Assert-True ($overlayText -match '(?m)^\s*if \(!overlayDrawn\) return;') `
    "遮罩没画过就不在标题画面盖红屏（行首锚定）"
Assert-True ($overlayText -match '(?m)^\s*if \(lastDrawnAlpha < 200\) return;') `
    "只在遮罩「满不透明消失」时才补溶解（已被 MC 正常淡出的不补，行首锚定）"
Assert-True ($overlayText -match '(?m)^\s*if \(EXIT_DISSOLVE\.begin\(System\.currentTimeMillis\(\)\)\) \{') `
    "溶解只启动一次（行首锚定）"
Assert-True ($overlayText -match '(?m)^\s*lastDrawnAlpha = alpha;') `
    "draw() 记录遮罩最后一次的不透明度（行首锚定）"
Assert-True ($overlayText -match '(?m)^\s*lastDrawnView = view\(\);') `
    "draw() 记录最后一帧视图快照（行首锚定）"
Assert-True ($overlayText -match '(?m)^\s*final LoadProgress\.View v = snapshot != null \? snapshot : view\(\);') `
    "溶解用快照而不是现场数据（finish() 已清 meter，行首锚定）"
Assert-True ($overlayText -match '(?m)^\s*step\("溶解背景", \(\) -> MirageDebug\.fillRect\(gge, fill, 0, 0, gw, gh,\s*\r?\n\s*pack\(a, ForgeStyleLayout\.FORGE_RED\)\)\);') `
    "溶解帧先铺满背景红（跨行匹配，避开 CRLF 下行尾锚定失效）"
Assert-True ($debugText -match '(?m)^\s*int dissolve = ForgeStyleOverlay\.exitDissolveAlpha\(\);') `
    "标题画面首帧取溶解 alpha（行首锚定）"
Assert-True ($debugText -match '(?m)^\s*if \(dissolve > 0\) ForgeStyleOverlay\.drawExitFrame\(gge, dissolve\);') `
    "标题画面首帧把仪表盘叠上去（行首锚定）"
Assert-True ($debugText -match '(?m)^\s*ForgeStyleOverlay\.beginExitDissolve\(\);') `
    "onTitleScreenShown 触发溶解（行首锚定）"

# ---------------------------------------------------------------- 变异检验

Write-Host "== 变异检验 ==" -ForegroundColor Cyan

$mutations = @(
    [pscustomobject]@{
        Tag = "X1"; Desc = "alpha 恒 255（永不淡出）"; Kind = "behaviour"; File = "dissolve"
        Old = "return (int) Math.ceil((1.0 - (double) t / (double) fadeMs) * 255.0);"
        New = "return 255;"
        Guard = '(?m)^\s*return \(int\) Math\.ceil\(\(1\.0 - \(double\) t / \(double\) fadeMs\) \* 255\.0\);'
    },
    [pscustomobject]@{
        Tag = "X2"; Desc = "begin 无条件放行（可重复溶解）"; Kind = "behaviour"; File = "dissolve"
        Old = "if (done || startedAt >= 0) return false;"
        New = "if (false) return false;"
        Guard = '(?m)^\s*if \(done \|\| startedAt >= 0\) return false;'
    },
    [pscustomobject]@{
        Tag = "X3"; Desc = "去掉「满不透明才补」门槛（纯守卫）"; Kind = "guard"; File = "overlay"
        Old = "if (lastDrawnAlpha < 200) return;"
        New = ""
        Guard = '(?m)^\s*if \(lastDrawnAlpha < 200\) return;'
    },
    [pscustomobject]@{
        Tag = "X4"; Desc = "去掉标题画面的溶解接线（纯守卫）"; Kind = "guard"; File = "debug"
        Old = "if (dissolve > 0) ForgeStyleOverlay.drawExitFrame(gge, dissolve);"
        New = ""
        Guard = '(?m)^\s*if \(dissolve > 0\) ForgeStyleOverlay\.drawExitFrame\(gge, dissolve\);'
    }
)

foreach ($m in $mutations) {
    $mutDissolve = $dissolveText
    $mutOverlay = $overlayText
    $mutDebug = $debugText
    $target = switch ($m.File) {
        "dissolve" { $dissolveText }
        "overlay" { $overlayText }
        default { $debugText }
    }
    if (-not $target.Contains($m.Old)) {
        Assert-True $false "$($m.Tag) 变异锚点未找到（脚本与源码已脱节）"
        continue
    }
    switch ($m.File) {
        "dissolve" { $mutDissolve = $mutDissolve.Replace($m.Old, $m.New) }
        "overlay" { $mutOverlay = $mutOverlay.Replace($m.Old, $m.New) }
        default { $mutDebug = $mutDebug.Replace($m.Old, $m.New) }
    }

    if ($m.Kind -eq "behaviour") {
        # 行为变异：重新编译 ExitDissolve + 跑同一个 harness，关键行为断言必须不再全绿。
        $r = Run-Suite $mutDissolve ("mut-" + $m.Tag)
        $amid = [int](StatVal $r.Lines "a300")
        $again = StatVal $r.Lines "beginAgain"
        $behaviourRed = ($r.Code -ne 0) -or ($amid -ne 128) -or ($again -ne "false")
        Assert-True $behaviourRed "$($m.Tag) $($m.Desc)：行为断言被抓红（a300=$amid, beginAgain=$again）"
    } else {
        Write-Host "   [OK]   $($m.Tag) 纯守卫变异：只重跑源码守卫（ForgeStyleOverlay/MirageDebug 依赖 MC 类，不离线编译）" -ForegroundColor DarkGreen
    }

    $guardSrc = switch ($m.File) {
        "dissolve" { $mutDissolve }
        "overlay" { $mutOverlay }
        default { $mutDebug }
    }
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
