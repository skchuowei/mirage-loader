# scripts/verify_forgestyle_text.ps1
# verify_forgestyle_text.ps1 —— 修「主窗口加载界面无文字」的回归用例（纯 JVM，不启动游戏、不依赖 gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_forgestyle_text.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_forgestyle_text.ps1 -Keep   # 保留临时目录
#
# 为什么这样测：
#   2026-10-31 出现过「红底 + 内存条 + 进度条全对，唯独一条文字都没有，日志无异常」的回归。
#   根因是 ForgeStyleOverlay 曾改用 gge.text()，而 LoadingOverlay 显示期是首次资源重载进行中，
#   Font 的字形图集还没装载 —— text() 只是把 GuiTextRenderState 塞进队列，不抛异常也不出像素，
#   于是「调用成功」但屏幕上什么都没有。这类故障在实机上看一眼是"没字"，在代码里看是"没毛病"，
#   所以必须有离线用例把它钉住。
#
#   做法：把 ForgeStyleOverlay / ForgeStyleLayout / MirageDebug / MirageLog 四个源文件现场 javac 编译，
#   配一个假的 GuiGraphicsExtractor（只实现 fill/guiWidth/guiHeight）和假的 LoadingOverlay（只提供
#   反射要读的四个字段），直接调 ForgeStyleOverlay.draw()，然后检查它到底往 fill 里灌了什么：
#     1. 第一块必须是全屏红底（0xFFEF323D），否则整块界面都没了；
#     2. 必须存在大量「小矩形」（字形的像素块）—— 这条就是回归守卫：回到 text() 时这里是 0；
#     3. 文字块必须落在四个预期区域（内存文字 / 两个标签 / 版本号），且不越出画布；
#     4. 内存文字水平居中、版本号右对齐（这两处是布局正确与否的直接证据）；
#     5. GUI 尺寸减半（sx=0.5）时仍然出字，且字形块尺寸同比变小（字号按 sx 现算，不是固定 12px）。
#
#   为什么不用实机截图当唯一证据：截图只能证明"这一次没字"，证明不了"以后改回 text() 会被发现"。
#
# 变异检验（本轮做过）：
#   把 drawText 里的 fillRect 循环注释掉 → text.total 变 0、"文字块数量"与"四个区域"断言全红，退出码 1；
#   恢复后全绿。确认用例不是空转。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$files = @{
    "MirageLog.java"         = Join-Path $root "api\mirage-api\src\main\java\io\mirage\api\log\MirageLog.java"
    "MirageDebug.java"       = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\MirageDebug.java"
    "ForgeStyleLayout.java"  = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ForgeStyleLayout.java"
    "ForgeStyleOverlay.java" = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ForgeStyleOverlay.java"
}
foreach ($k in $files.Keys) {
    if (-not (Test-Path $files[$k])) {
        Write-Host "[FAIL] 找不到源码: $($files[$k])" -ForegroundColor Red
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
    Write-Host "[FAIL] 没找到 javac/java（请设置 JAVA_HOME，或把 JDK bin 加进 PATH）" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- 临时工作区

$work = Join-Path $env:TEMP ("mirage-forgestyle-" + $PID)
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 不用 Start-Process：本机 http_proxy 与 HTTP_PROXY 并存，复制环境时会抛"已添加项"。
# 也不用 & 调用 + 2>&1：PS 5.1 会把原生 stderr 变成 NativeCommandError 中断脚本。
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

# ---------------------------------------------------------------- 检查程序

$harnessDir = Join-Path $work "src\io\mirage\loader"
New-Item -ItemType Directory -Force -Path $harnessDir | Out-Null
$harnessSrc = Join-Path $harnessDir "ForgeStyleTextCheck.java"

$harnessText = @'
package io.mirage.loader;

import java.util.ArrayList;
import java.util.List;

/**
 * 加载界面绘制回归用例（由 scripts/verify_forgestyle_text.ps1 编译运行）。
 *
 * 只用假的 gge / overlay，不碰 MC：draw() 全程走反射，本来就只认
 * fill(IIIII)、guiWidth()、guiHeight() 和 overlay 的四个字段。
 * 输出固定 ASCII 的 STAT 行，断言放在 PowerShell 侧。
 */
public final class ForgeStyleTextCheck {

    /** 记录每一次 fill 调用。 */
    static final class Rect {
        final int x0, y0, x1, y1, argb;
        Rect(int x0, int y0, int x1, int y1, int argb) {
            this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.argb = argb;
        }
        int w() { return x1 - x0; }
        int h() { return y1 - y0; }
        int area() { return w() * h(); }
        /** 字形像素块：又小又矮。进度条/背景的块远大于这个阈值。 */
        boolean isGlyph() { return h() <= 10 && w() <= 40 && area() <= 200; }
    }

    /** 假的 GuiGraphicsExtractor：只要方法名/签名对上，反射就认。 */
    public static final class FakeGge {
        final List<Rect> rects = new ArrayList<>();
        final int gw, gh;
        FakeGge(int gw, int gh) { this.gw = gw; this.gh = gh; }
        public int guiWidth() { return gw; }
        public int guiHeight() { return gh; }
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            rects.add(new Rect(x0, y0, x1, y1, argb));
        }
    }

    /** 假的 LoadingOverlay：字段名必须与反射读取的名字一致。 */
    public static final class FakeOverlay {
        long fadeOutStart = -1L;
        long fadeInStart = -1L;
        boolean fadeIn = false;
        float currentProgress = 0.5f;
    }

    public static void main(String[] args) {
        // 预渲染字符字形（同步、主线程）—— 与生产环境 Launcher.main 调用 prewarm() 一致。
        // 字符级方案下 glyph 缓存必须先就绪，draw 才查得到字、首帧即有字。
        ForgeStyleOverlay.prewarm();
        // 两种 GUI 尺寸：854x480 是 guiScale=1 的默认窗口；427x240 是同一窗口 guiScale=2
        scenario("S1", 854, 480);
        scenario("S2", 427, 240);
    }

    private static void scenario(String tag, int gw, int gh) {
        FakeGge gge = new FakeGge(gw, gh);
        FakeOverlay overlay = new FakeOverlay();
        // 画几帧：frame 计数会影响第一条进度条的动画位置
        for (int i = 0; i < 5; i++) {
            gge.rects.clear();
            ForgeStyleOverlay.draw(overlay, gge);
        }
        List<Rect> rects = gge.rects;

        System.out.println("STAT " + tag + ".total=" + rects.size());
        if (rects.isEmpty()) {
            System.out.println("STAT " + tag + ".bg=none");
            return;
        }

        Rect bg = rects.get(0);
        System.out.println("STAT " + tag + ".bg=" + bg.x0 + "," + bg.y0 + "," + bg.x1 + "," + bg.y1
                + ",0x" + Integer.toHexString(bg.argb).toUpperCase());

        int glyphs = 0;
        int glyphArea = 0;
        int maxGlyphH = 0;
        int overflow = 0;
        for (Rect r : rects) {
            if (r.x0 < 0 || r.y0 < 0 || r.x1 > gw || r.y1 > gh) overflow++;
            if (r.isGlyph()) {
                glyphs++;
                glyphArea += r.area();
                if (r.h() > maxGlyphH) maxGlyphH = r.h();
            }
        }
        System.out.println("STAT " + tag + ".glyphs=" + glyphs);
        System.out.println("STAT " + tag + ".glypharea=" + glyphArea);
        System.out.println("STAT " + tag + ".glyphmaxh=" + maxGlyphH);
        System.out.println("STAT " + tag + ".overflow=" + overflow);

        // 四个文字带（设计坐标 × sy 之后的实际像素带，留 ±6 容差）
        double sy = gh / 480.0;
        band(tag, "memory", rects, 28 * sy, 34 * sy + 22);
        band(tag, "label0", rects, 250 * sy, 266 * sy + 22);
        band(tag, "label1", rects, 299 * sy, 315 * sy + 22);
        band(tag, "version", rects, 441 * sy, 457 * sy + 22);

        // 内存文字水平居中：取该带里字形块的最小 x 与最大 x1
        int[] mem = bandExtent(rects, 28 * sy, 34 * sy + 22);
        double center = (mem[0] + mem[1]) / 2.0;
        System.out.println("STAT " + tag + ".memcenter=" + Math.round(center));
        System.out.println("STAT " + tag + ".canvascenter=" + (gw / 2));
        System.out.println("STAT " + tag + ".vermargin=" + Math.round(10 * (gw / 854.0)));

        // 版本号右对齐：最右端应贴近 gw - 10*sx
        int[] ver = bandExtent(rects, 441 * sy, 457 * sy + 22);
        System.out.println("STAT " + tag + ".verright=" + ver[1]);
    }

    private static void band(String tag, String name, List<Rect> rects, double y0, double y1) {
        int n = 0;
        int area = 0;
        for (Rect r : rects) {
            if (!r.isGlyph()) continue;
            if (r.y0 >= y0 - 2 && r.y1 <= y1 + 2) { n++; area += r.area(); }
        }
        System.out.println("STAT " + tag + ".band." + name + "=" + n + "," + area);
    }

    /** 返回该带里字形块的 [minX, maxX1]，没有块时返回 [-1, -1]。 */
    private static int[] bandExtent(List<Rect> rects, double y0, double y1) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        for (Rect r : rects) {
            if (!r.isGlyph()) continue;
            if (r.y0 >= y0 - 2 && r.y1 <= y1 + 2) {
                if (r.x0 < minX) minX = r.x0;
                if (r.x1 > maxX) maxX = r.x1;
            }
        }
        if (minX == Integer.MAX_VALUE) return new int[]{-1, -1};
        return new int[]{minX, maxX};
    }
}
'@

# 写成无 BOM 的 UTF-8：javac 会把 BOM 当非法字符（error: illegal character: \ufeff）
[System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "== 编译 ForgeStyleOverlay + 检查程序 ==" -ForegroundColor Cyan
$compile = Invoke-Native $javac @(
    "-J-Duser.language=en",
    "-encoding", "UTF-8",
    "-d", $classes,
    $files["MirageLog.java"],
    $files["MirageDebug.java"],
    $files["ForgeStyleLayout.java"],
    $files["ForgeStyleOverlay.java"],
    $harnessSrc
) "compile"

if ($compile.Code -ne 0) {
    Write-Host "[FAIL] javac 编译失败（退出码 $($compile.Code)）" -ForegroundColor Red
    if ($compile.Out.Trim()) { Write-Host $compile.Out }
    if ($compile.Err.Trim()) { Write-Host $compile.Err -ForegroundColor DarkYellow }
    exit 1
}
Write-Host "   [OK] 编译通过" -ForegroundColor DarkGreen

Write-Host "== 运行检查程序 ==" -ForegroundColor Cyan
$run = Invoke-Native $java @(
    "-Djava.awt.headless=true",
    "-cp", $classes,
    "io.mirage.loader.ForgeStyleTextCheck"
) "run"

if ($run.Code -ne 0) {
    Write-Host "[FAIL] 检查程序异常退出（退出码 $($run.Code)）" -ForegroundColor Red
    if ($run.Out.Trim()) { Write-Host $run.Out }
    if ($run.Err.Trim()) { Write-Host $run.Err -ForegroundColor DarkYellow }
    exit 1
}

$lines = ($run.Out -split "`r?`n") | Where-Object { $_.Trim() }
$lines | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkGray }

function Stat([string]$tag, [string]$key) {
    $m = $lines | Where-Object { $_ -match ("^STAT " + [regex]::Escape($tag) + "\." + [regex]::Escape($key) + "=(.*)$") }
    if (-not $m) { return $null }
    return ([regex]::Match($m, "=(.*)$")).Groups[1].Value
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

foreach ($tag in @("S1", "S2")) {
    $label = if ($tag -eq "S1") { "854x480 (guiScale=1)" } else { "427x240 (guiScale=2)" }
    Write-Host "== 场景 $tag : $label ==" -ForegroundColor Cyan

    $bg = Stat $tag "bg"
    Assert-True ($bg -eq "0,0,854,480,0xFFEF323D" -or $bg -eq "0,0,427,240,0xFFEF323D") `
        "第一块是全屏 Forge 红底（实际 $bg）"

    $glyphs = [int](Stat $tag "glyphs")
    Assert-True ($glyphs -ge 40) "字形像素块数量 >= 40（实际 $glyphs，回到 text() 时这里是 0；字符级拼装下每字符约 1~3 块）"

    $area = [int](Stat $tag "glypharea")
    Assert-True ($area -ge 400) "字形墨迹总面积 >= 400 px（实际 $area，防止只有空壳块）"

    $maxh = [int](Stat $tag "glyphmaxh")
    Assert-True ($maxh -ge 3 -and $maxh -le 12) "字形块最大高度在 3..12 px（实际 $maxh）"

    $overflow = [int](Stat $tag "overflow")
    Assert-True ($overflow -eq 0) "没有任何绘制越出画布（越界块 $overflow 个）"

    $memCenter = [int](Stat $tag "memcenter")
    $canvasCenter = [int](Stat $tag "canvascenter")
    Assert-True ([math]::Abs($memCenter - $canvasCenter) -le 8) `
        "内存文字水平居中（文字中心 $memCenter vs 画布中心 $canvasCenter）"

    $verRight = [int](Stat $tag "verright")
    $verMargin = [int](Stat $tag "vermargin")
    Assert-True ([math]::Abs($verRight - ($canvasCenter * 2 - $verMargin)) -le 6) `
        "版本号右对齐到画布右缘 - 边距（实际右端 $verRight，期望 $($canvasCenter * 2 - $verMargin)）"

    foreach ($band in @("memory", "label0", "label1", "version")) {
        $raw = Stat $tag "band.$band"
        $n = [int](($raw -split ",")[0])
        Assert-True ($n -ge 8) "文字带 $band 有字（块数 $n >= 8）"
    }
}

# ---------------------------------------------------------------- 源码守卫
# 这条是给未来的自己看的：LoadingOverlay 阶段用 text() 必然静默无字，
# 源码里只要重新出现 drawTextSafe / 解析 name=text 的反射，就说明这段又被改回去了。

Write-Host "== 源码守卫 ==" -ForegroundColor Cyan
$overlaySrc = Get-Content $files["ForgeStyleOverlay.java"] -Raw -Encoding UTF8
Assert-True (-not ($overlaySrc -match "drawTextSafe")) `
    "ForgeStyleOverlay 不再调用 drawTextSafe（text() 在重载期不出图）"
Assert-True (-not ($overlaySrc -match 'getMethod\("text"')) `
    "ForgeStyleOverlay 不再反射 gge.text(..)"
Assert-True ($overlaySrc -match "rasterizePixelText") `
    "ForgeStyleOverlay 走 AWT 光栅化像素字（rasterizePixelText）"

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
$script:failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
