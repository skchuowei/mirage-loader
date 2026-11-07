# scripts/verify_loadprogress.ps1
# 加载期「真实进度」模型 LoadProgress 的离线回归（纯 JVM，不启游戏、不依赖 gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_loadprogress.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_loadprogress.ps1 -Keep
#
# 为什么这样测：
#   旧版 MirageDebug.progress 是手写常量，S4/S7 长达几十秒里数字一动不动（用户说的「色块」）。
#   新模型的关键是「分母已知 → 确定条；分母未知 → 不定条 + 秒表」，以及 meter 栈 / 事件环 /
#   计数器的边界语义。这些全是纯逻辑，放进 LoadProgress 后就能离线钉住，不必重写一遍算法。
#
#   脚本把生产源码（或它的一个变异副本）与检查程序一起 javac 编译，跑出固定格式的 STAT 行，
#   在 PowerShell 侧断言。原版必须全绿；随后自动做 4 种变异（改坏生产源码的一个点），
#   每种都必须让断言变红 —— 否则说明用例是空转（经验 C36/A7）。
#
# 4 种变异：
#   M1 snapshot 里 steps==0 改成 steps==-1       → 不定条全变确定条
#   M2 snapshot 的 Math.min(max,size) 改成 size  → 无视 max，渲染出超过 2 根条
#   M3 EVENT_CAPACITY 32 改成 33                 → 事件环容量失守
#   M4 CURSOR_FRACTION 0.2f 改成 1.2f            → 游标宽过整条

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$src = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\LoadProgress.java"
$overlaySrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ForgeStyleOverlay.java"
$layoutSrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ForgeStyleLayout.java"
$earlySrc = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\MirageEarlyWindow.java"
$stageDir = Join-Path $root "lifecycle-starter\src\main\java\io\mirage\ls\stage"

foreach ($f in @($src, $overlaySrc, $layoutSrc, $earlySrc)) {
    if (-not (Test-Path $f)) {
        Write-Host "[FAIL] 找不到源码: $f" -ForegroundColor Red
        exit 1
    }
}

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
    Write-Host "[FAIL] 没找到 javac/java" -ForegroundColor Red
    exit 1
}

$work = Join-Path $env:TEMP ("mirage-loadprogress-" + $PID)
New-Item -ItemType Directory -Force -Path $work | Out-Null
$harnessDir = Join-Path $work "src"
New-Item -ItemType Directory -Force -Path $harnessDir | Out-Null
$harnessSrc = Join-Path $harnessDir "LoadProgressCheck.java"

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 不用 Start-Process / & cmd：PS 5.1 + 本机代理环境变量会抛异常或把 stderr 变成 NativeCommandError
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
        Tag  = $tag
        Code = $proc.ExitCode
        Out  = $outTask.Result
        Err  = $errTask.Result
    }
}

$harnessText = @'
import io.mirage.loader.LoadProgress;

import java.util.List;

/** 只输出固定格式 STAT 行，断言全在 PowerShell 侧。标签一律 ASCII，避免控制台编码干扰。 */
public final class LoadProgressCheck {

    public static void main(String[] args) {
        // ---- 不定条 ----
        LoadProgress.Meter a = LoadProgress.begin("blackbox");
        LoadProgress.Snapshot sa = LoadProgress.snapshot(1).get(0);
        System.out.println("STAT indet.steps=" + sa.steps());
        System.out.println("STAT indet.indeterminate=" + sa.indeterminate());
        System.out.println("STAT indet.fraction=" + sa.fraction());

        // ---- 确定条 + 夹逼 ----
        LoadProgress.Meter b = LoadProgress.begin("countable", 4);
        LoadProgress.step(b);
        LoadProgress.step(b);
        LoadProgress.Snapshot sb = LoadProgress.snapshot(1).get(0);
        System.out.println("STAT det.steps=" + sb.steps());
        System.out.println("STAT det.current=" + sb.current());
        System.out.println("STAT det.fraction=" + sb.fraction());
        LoadProgress.set(b, 99);
        System.out.println("STAT det.clampHigh=" + LoadProgress.snapshot(1).get(0).current());
        LoadProgress.set(b, -5);
        System.out.println("STAT det.clampLow=" + LoadProgress.snapshot(1).get(0).current());

        // ---- meter 栈 / max ----
        LoadProgress.Meter c = LoadProgress.begin("m3", 3);
        LoadProgress.Meter d = LoadProgress.begin("m4", 5);
        System.out.println("STAT stack.size=" + LoadProgress.snapshot(10).size());
        System.out.println("STAT stack.max2=" + LoadProgress.snapshot(2).size());
        System.out.println("STAT stack.last=" + LoadProgress.snapshot(2).get(1).label());
        System.out.println("STAT stack.zero=" + LoadProgress.snapshot(0).size());
        LoadProgress.end(c);
        System.out.println("STAT stack.afterEnd=" + LoadProgress.snapshot(10).size());

        // ---- 主阶段分段（当前段取栈顶第一根确定条） ----
        LoadProgress.stage(3);
        float[] segs = LoadProgress.segments();
        System.out.println("STAT seg.done=" + segs[0] + "," + segs[1] + "," + segs[2]);
        System.out.println("STAT seg.future=" + segs[4] + "," + segs[5] + "," + segs[6]);
        System.out.println("STAT seg.current=" + segs[3]);
        LoadProgress.end(b);
        LoadProgress.end(d);
        System.out.println("STAT seg.indet=" + LoadProgress.segments()[3]);
        System.out.println("STAT stage.index=" + LoadProgress.stageIndex());

        // ---- 计数器 ----
        LoadProgress.counter("mods.total", 12);
        LoadProgress.counter("mods.done", 7);
        LoadProgress.increment("warn");
        LoadProgress.increment("warn");
        System.out.println("STAT counter.total=" + LoadProgress.counterValue("mods.total", -1));
        System.out.println("STAT counter.warn=" + LoadProgress.counterValue("warn", -1));
        System.out.println("STAT counter.missing=" + LoadProgress.counterValue("nope", -1));

        // ---- 事件环（写 40 条，只留 32） ----
        for (int i = 1; i <= 40; i++) LoadProgress.event("T", "e" + i);
        System.out.println("STAT event.count=" + LoadProgress.eventCount());
        List<LoadProgress.Event> last = LoadProgress.events(4);
        System.out.println("STAT event.last=" + last.get(0).text() + "," + last.get(3).text());
        System.out.println("STAT event.max=" + LoadProgress.events(999).size());

        // ---- 游标轨迹 ----
        float maxCursor = 0f;
        float minCursor = 1f;
        for (int f = 0; f < 1000; f++) {
            float v = LoadProgress.cursorLeft(f);
            if (v > maxCursor) maxCursor = v;
            if (v < minCursor) minCursor = v;
        }
        System.out.println("STAT cursor.f0=" + LoadProgress.cursorLeft(0));
        System.out.println("STAT cursor.f50=" + LoadProgress.cursorLeft(50));
        System.out.println("STAT cursor.f100=" + LoadProgress.cursorLeft(100));
        System.out.println("STAT cursor.min=" + minCursor);
        System.out.println("STAT cursor.max=" + maxCursor);
        System.out.println("STAT cursor.fraction=" + LoadProgress.CURSOR_FRACTION);

        // ---- 文案（纯逻辑拼装，渲染侧不许另写一套） ----
        LoadProgress.detail("subtask");
        LoadProgress.View v = LoadProgress.view(3, 4);
        System.out.println("STAT text.total=" + v.totalLabel());
        System.out.println("STAT text.crumb=" + v.breadcrumb());
        System.out.println("STAT text.c1=" + v.counter1());
        System.out.println("STAT text.c2=" + v.counter2());
        System.out.println("STAT text.events=" + v.eventLines().size());
        LoadProgress.Snapshot only = LoadProgress.snapshot(1).get(0);
        System.out.println("STAT text.meter=" + LoadProgress.meterLabel(only));
        System.out.println("STAT text.meterhead=" + LoadProgress.meterHead(only));
        System.out.println("STAT text.metertail=" + LoadProgress.meterTail(only));
        System.out.println("STAT text.channel=" + LoadProgress.channelOf("[LS] x") + ","
                + LoadProgress.channelOf("[Mirage-Mixin] x") + ","
                + LoadProgress.channelOf("[MG-ERROR] x") + "," + LoadProgress.channelOf("plain"));

        // ---- finish：收掉长驻 meter，主条填满 ----
        LoadProgress.finish();
        System.out.println("STAT finish.meters=" + LoadProgress.snapshot(10).size());
        float[] after = LoadProgress.segments();
        System.out.println("STAT finish.segs=" + after[0] + "," + after[3] + "," + after[6]);
        System.out.println("STAT finish.flag=" + LoadProgress.isFinished());
    }
}
'@

# 生成的 .java 必须不带 BOM（javac 会把 BOM 当非法字符）
[System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

# ---------------------------------------------------------------- 断言框架

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]
$script:lines = @()

function Stat([string]$key) {
    $m = $script:lines | Where-Object { $_ -match ("^STAT " + [regex]::Escape($key) + "=(.*)$") }
    if (-not $m) { return $null }
    return ([regex]::Match($m, "=(.*)$")).Groups[1].Value
}

function Num([string]$v) {
    $d = 0.0
    if ($v -and [double]::TryParse($v, [System.Globalization.NumberStyles]::Float,
            [System.Globalization.CultureInfo]::InvariantCulture, [ref]$d)) { return $d }
    return [double]::NaN
}

function Near([string]$v, [double]$expected) {
    $d = Num $v
    return (-not [double]::IsNaN($d)) -and ([math]::Abs($d - $expected) -lt 0.001)
}

function Assert-True([bool]$cond, [string]$desc, [bool]$report) {
    $script:checks++
    if ($cond) {
        if ($report) { Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen }
    } else {
        $script:failures.Add($desc)
        if ($report) { Write-Host "   [FAIL] $desc" -ForegroundColor Red }
    }
}

function Run-Suite([string]$lpPath, [string]$tag, [bool]$report) {
    $script:checks = 0
    $script:failures = New-Object System.Collections.Generic.List[string]
    $classes = Join-Path $work ("classes-" + $tag)
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $compile = Invoke-Native $javac @(
        "-J-Duser.language=en", "-encoding", "UTF-8", "-proc:none",
        "-d", $classes, $lpPath, $harnessSrc
    ) "compile"
    if ($compile.Code -ne 0) {
        Assert-True $false "编译 $tag 通过（javac 输出：$($compile.Err.Trim())）" $report
        return $script:failures.Count
    }

    $run = Invoke-Native $java @(
        "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
        "-cp", $classes, "LoadProgressCheck"
    ) "run"
    if ($run.Code -ne 0) {
        Assert-True $false "检查程序 $tag 正常退出（$($run.Err.Trim())）" $report
        return $script:failures.Count
    }
    $script:lines = ($run.Out -split "`r?`n") | Where-Object { $_.Trim() }
    if ($report) { $script:lines | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray } }

    Assert-True ((Stat "indet.steps") -eq "0") "不定条 steps==0" $report
    Assert-True ((Stat "indet.indeterminate") -eq "true") "不定条 indeterminate==true" $report
    Assert-True (Near (Stat "indet.fraction") -1) "不定条 fraction==-1（不编百分比）" $report
    Assert-True ((Stat "det.steps") -eq "4") "确定条 steps==4" $report
    Assert-True ((Stat "det.current") -eq "2") "step 两次 current==2" $report
    Assert-True (Near (Stat "det.fraction") 0.5) "确定条 fraction==0.5" $report
    Assert-True ((Stat "det.clampHigh") -eq "4") "set(99) 夹到 steps" $report
    Assert-True ((Stat "det.clampLow") -eq "0") "set(-5) 夹到 0" $report
    Assert-True ((Stat "stack.size") -eq "4") "meter 栈深 4" $report
    Assert-True ((Stat "stack.max2") -eq "2") "snapshot(2) 只返回 2 根（Neo 同款上限）" $report
    Assert-True ((Stat "stack.last") -eq "m4") "snapshot 取最近创建的 meter 且保持顺序" $report
    Assert-True ((Stat "stack.zero") -eq "0") "snapshot(0) 返回空" $report
    Assert-True ((Stat "stack.afterEnd") -eq "3") "end() 出栈" $report
    Assert-True ((Stat "seg.done") -eq "1.0,1.0,1.0") "S1..S3 已完成段填满" $report
    Assert-True ((Stat "seg.future") -eq "0.0,0.0,0.0") "S5..S7 未开始段留空" $report
    Assert-True (Near (Stat "seg.current") 0) "当前段取栈顶确定条的真实比例" $report
    Assert-True (Near (Stat "seg.indet") -1) "没有确定条时当前段=-1（不定）" $report
    Assert-True ((Stat "stage.index") -eq "3") "stage(3) 记录当前阶段" $report
    Assert-True ((Stat "counter.total") -eq "12") "counter() 可读写" $report
    Assert-True ((Stat "counter.warn") -eq "2") "increment() 累加" $report
    Assert-True ((Stat "counter.missing") -eq "-1") "缺失计数返回默认值" $report
    Assert-True ((Stat "event.count") -eq "32") "事件环写 40 条后容量仍为 32" $report
    Assert-True ((Stat "event.last") -eq "e37,e40") "事件环保留最新 4 条且顺序旧→新" $report
    Assert-True ((Stat "event.max") -eq "32") "events(999) 不超过环容量" $report
    Assert-True (Near (Stat "cursor.f0") 0) "游标 frame=0 在左端" $report
    Assert-True (Near (Stat "cursor.f50") 0.8) "游标 frame=50 到右端 1-0.2" $report
    Assert-True (Near (Stat "cursor.f100") 0) "游标周期 100 帧" $report
    Assert-True (Near (Stat "cursor.min") 0) "游标最小左端 0" $report
    Assert-True ((Num (Stat "cursor.max")) -le 0.801) "游标最大左端 <= 1-CURSOR_FRACTION" $report
    Assert-True (Near (Stat "cursor.fraction") 0.2) "游标宽度常量 0.2" $report
    Assert-True ((Stat "text.total") -match "S4/7") "总标签含真实阶段号 S4/7" $report
    Assert-True ((Stat "text.crumb") -match "S4>") "面包屑把当前阶段标为 S4>" $report
    Assert-True ((Stat "text.c1") -match "mods 7/12") "计数行 1 来自真实计数器" $report
    Assert-True ((Stat "text.c2") -match "warn 2") "计数行 2 来自真实计数器" $report
    Assert-True ((Stat "text.events") -eq "4") "view() 只给 4 行事件" $report
    Assert-True ((Stat "text.meter") -match "blackbox") "meterLabel 含任务名" $report
    Assert-True ((Stat "text.meterhead") -eq "blackbox") "meterHead 只含任务名（左对齐）" $report
    Assert-True ((Stat "text.metertail") -match "s") "meterTail 含秒表（右对齐）" $report
    Assert-True ((Stat "text.channel") -eq "LS,MIXIN,ERROR,MC") "通道判定覆盖 LS/Mixin/ERROR/原版" $report
    Assert-True ((Stat "finish.meters") -eq "0") "finish() 收掉所有长驻 meter" $report
    Assert-True ((Stat "finish.segs") -eq "1.0,1.0,1.0") "finish() 后主条 7 段全满" $report
    Assert-True ((Stat "finish.flag") -eq "true") "finish() 幂等标志" $report

    return $script:failures.Count
}

# ---------------------------------------------------------------- 源码守卫（只在原版跑）

function Check-SourceGuards {
    $overlay = Get-Content $overlaySrc -Raw -Encoding UTF8
    $early = Get-Content $earlySrc -Raw -Encoding UTF8
    $layout = Get-Content $layoutSrc -Raw -Encoding UTF8
    $stages = Get-ChildItem (Join-Path $stageDir "*.java") | ForEach-Object {
        Get-Content $_.FullName -Raw -Encoding UTF8
    }
    $stageText = ($stages -join "`n")
    Assert-True ($overlay -match "LoadProgress\.view\(") "主窗口从 LoadProgress 取数据" $true
    Assert-True ($early -match "LoadProgress\.view\(") "早期窗口从同一份 LoadProgress 取数据" $true
    Assert-True ($overlay -match "OLD_STEPPER") "主窗口保留旧形态回滚开关" $true
    Assert-True ($early -match "OLD_STEPPER") "早期窗口保留旧形态回滚开关" $true
    Assert-True ($overlay -match "cursorLeft\(" -and $early -match "cursorLeft\(") "两窗口都画不定游标" $true
    Assert-True ($layout -match "EVENT_VISIBLE_LINES = 4") "事件流 4 行（5 行会出血到 496）" $true
    Assert-True ($stageText -match "LoadProgress\.begin\(") "阶段埋点接了真实 meter" $true
    Assert-True ($overlay -match "v\.segments\(\)") "分段总条取 LoadProgress.segments() 而非手写常量" $true
    Assert-True ($stageText -match "bootStrap") "S4 黑盒区间仍在（不定条覆盖它）" $true
}

# ---------------------------------------------------------------- 原版 + 变异

Write-Host "== 原版 LoadProgress：编译 + 断言 ==" -ForegroundColor Cyan
$baseFails = Run-Suite $src "base" $true
Write-Host "== 源码守卫 ==" -ForegroundColor Cyan
Check-SourceGuards
$baseFails = $script:failures.Count
Write-Host "   原版断言：$($script:checks) 条，失败 $baseFails 条" -ForegroundColor $(if ($baseFails -eq 0) { "Green" } else { "Red" })

$mutations = @(
    [pscustomobject]@{
        Name = "M1-indeterminate"
        Old  = "boolean indeterminate = m.steps == 0;"
        New  = "boolean indeterminate = m.steps == -1;"
    },
    [pscustomobject]@{
        Name = "M2-snapshot-max"
        Old  = "int limit = Math.min(max, size);"
        New  = "int limit = size;"
    },
    [pscustomobject]@{
        Name = "M3-event-capacity"
        Old  = "public static final int EVENT_CAPACITY = 32;"
        New  = "public static final int EVENT_CAPACITY = 33;"
    },
    [pscustomobject]@{
        Name = "M4-cursor-width"
        Old  = "public static final float CURSOR_FRACTION = 0.2f;"
        New  = "public static final float CURSOR_FRACTION = 1.2f;"
    }
)

Write-Host "== 变异检验（每种都必须被断言抓红） ==" -ForegroundColor Cyan
$script:mutationFails = New-Object System.Collections.Generic.List[string]
$original = [System.IO.File]::ReadAllText($src)
foreach ($m in $mutations) {
    if (-not $original.Contains($m.Old)) {
        $script:mutationFails.Add("$($m.Name)：源码里找不到变异锚点，用例已失效")
        Write-Host "   [FAIL] $($m.Name)：变异锚点不存在" -ForegroundColor Red
        continue
    }
    $variantDir = Join-Path $work ("variant-" + $m.Name)
    New-Item -ItemType Directory -Force -Path $variantDir | Out-Null
    $variantSrc = Join-Path $variantDir "LoadProgress.java"
    [System.IO.File]::WriteAllText($variantSrc, $original.Replace($m.Old, $m.New),
            (New-Object System.Text.UTF8Encoding($false)))
    $red = Run-Suite $variantSrc $m.Name $false
    if ($red -ge 1) {
        Write-Host "   [OK]   $($m.Name) 被抓到（$red 条断言变红）" -ForegroundColor DarkGreen
    } else {
        $script:mutationFails.Add("$($m.Name) 改坏后断言仍全绿 = 用例空转")
        Write-Host "   [FAIL] $($m.Name) 改坏后断言仍全绿" -ForegroundColor Red
    }
}

if (-not $Keep) {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
} else {
    Write-Host "临时目录保留在: $work" -ForegroundColor DarkGray
}

Write-Host ""
if ($baseFails -eq 0 -and $script:mutationFails.Count -eq 0) {
    Write-Host "全部通过：原版断言全绿 + $($mutations.Count) 种变异全红" -ForegroundColor Green
    exit 0
}
Write-Host "失败：原版 $baseFails 条 / 变异 $($script:mutationFails.Count) 项" -ForegroundColor Red
$script:mutationFails | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
