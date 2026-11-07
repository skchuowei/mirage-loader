# scripts/verify_earlyfont_stb.ps1
# verify_earlyfont_stb.ps1 —— 早期窗口中文字形打包（STB）契约回归（纯 JVM，不启动游戏、不依赖 gradle）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_earlyfont_stb.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_earlyfont_stb.ps1 -Keep
#
# 为什么有这个脚本（2026-11-01 实机 crash）：
#   EarlyFont 打包中文时把 **字节偏移**（stbtt_GetFontOffsetForIndex 的返回值，msyh.ttc = 32）
#   传给了 stbtt_PackFontRanges 的第三参。而该参数是 **font_index（TTC 集合内索引）**，
#   STB 内部会再调一次 GetFontOffsetForIndex(fontdata, 32)：msyh.ttc 只有 2 个字体 →
#   返回 -1 → STB 拿 data-1 去解析表头 → EXCEPTION_ACCESS_VIOLATION 崩在 lwjgl_stb.dll。
#   这类崩溃**没有 Java 异常、没有 crash-report**，只留 hs_err_pid*.log，
#   表现是启动 4 秒直接闪退，从日志里完全看不出跟字体有关（出事前最后一行还是 OpenGL 版本）。
#
# 两层守卫：
#   静态：从 EarlyFont.java 里把 stbtt_PackFontRanges 的**实际第三参**抽出来，要求它是
#         font_index 语义的常量（名字含 Index、值为 0），且 stbtt_InitFont 仍用字节偏移。
#   行为：用仓库自带的 lwjgl-stb，**以源码里那个参数值**真跑一次全量汉字打包，
#         断言打包成功且字形齐全。参数若被改回偏移，这里会直接原生崩溃（退出码异常）。
#         注意这不是"重写算法"：打包流程是 stb 的，我们只复刻调用参数，且参数取自生产源码。
#
# 变异检验（本轮做过）：
#   把 EarlyFont 里 cjkFontIndex 改回 offset → 静态断言红；把值改成 32 → 行为用例原生崩溃、
#   退出码非 0 → 红。恢复后全绿。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent          # mirage-loader
$repo = Split-Path $root -Parent                  # 仓库根（含 mc-shili）

$fail = 0
function Assert-True([bool]$cond, [string]$msg) {
    if ($cond) {
        Write-Host "  PASS  $msg" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $msg" -ForegroundColor Red
        $script:fail++
    }
}
function Info([string]$msg) { Write-Host "  INFO  $msg" -ForegroundColor DarkGray }

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

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 不用 Start-Process（复制环境变量会抛）；不用 & + 2>&1（PS 5.1 把原生 stderr 变终止错误）。
# 原生崩溃时退出码可能是 0xC0000005 之类的无符号大数，这里原样返回，由调用方判定。
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

# ---------------------------------------------------------------- 静态守卫

Write-Host "`n== 静态守卫：PackFontRanges 的第三参语义" -ForegroundColor Cyan

$earlyFont = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\EarlyFont.java"
$mirageDebug = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\MirageDebug.java"
foreach ($p in @($earlyFont, $mirageDebug)) {
    if (-not (Test-Path $p)) {
        Write-Host "[FAIL] 找不到源码: $p" -ForegroundColor Red
        exit 1
    }
}
$src = Get-Content -Raw -Encoding UTF8 $earlyFont

# 中文打包调用的第三参
$mArg = [regex]::Match($src, 'stbtt_PackFontRanges\s*\(\s*pc\s*,\s*cjkData\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*,')
Assert-True ($mArg.Success) "EarlyFont 里有中文打包调用 stbtt_PackFontRanges(pc, cjkData, <arg>, rngs)"
$argName = ""
if ($mArg.Success) { $argName = $mArg.Groups[1].Value }
Info "第三参实际传入 = $argName"

Assert-True ($argName -ne "offset") "第三参不得是字节偏移 offset（这正是 2026-11-01 闪退的根因）"
Assert-True ($argName -match "Index") "第三参应是 font_index 语义的变量（名字含 Index），实际 '$argName'"

# 该变量的字面量初值
$mInit = [regex]::Match($src, "int\s+" + [regex]::Escape($argName) + "\s*=\s*(\d+)\s*;")
Assert-True ($mInit.Success) "能读到 $argName 的字面量初值（便于行为用例跟随源码）"
$fontIndex = -1
if ($mInit.Success) { $fontIndex = [int]$mInit.Groups[1].Value }
Assert-True ($fontIndex -eq 0) "$argName 必须为 0（msyh.ttc 只有 2 个字体，越界即崩）"
Info "行为用例将使用的 font_index = $fontIndex"

# InitFont 走的是另一套语义：第三参是字节偏移
Assert-True ($src -match 'stbtt_InitFont\s*\(\s*cinfo\s*,\s*cjkData\s*,\s*offset\s*\)') `
    "stbtt_InitFont 仍用字节偏移 offset（两个 API 参数语义不同，别一起改）"
# ASCII 路径（一直正常）显式传 0
$mAscii = [regex]::Match($src, 'stbtt_PackFontRanges\s*\(\s*pc\s*,\s*fontData\s*,\s*([^,]+),')
Assert-True ($mAscii.Success -and $mAscii.Groups[1].Value.Trim() -eq "0") `
    "ASCII 打包显式传 font_index 0（原本正确，作为对照锚点）"
# PackBegin 失败会让后续 STB 调用读到空 pack_info → 同样原生崩溃，必须检查返回值
$mBegin = [regex]::Matches($src, 'if\s*\(\s*!stbtt_PackBegin')
Assert-True ($mBegin.Count -ge 2) "两处 stbtt_PackBegin 都检查返回值（实际 $($mBegin.Count) 处）"
# 失败留痕
Assert-True (-not ($src -match 'catch\s*\(\s*Throwable\s+ignored\s*\)')) `
    "异常不再静默吞掉（中文加载失败必须 warn 留日志，否则中文整片空白无从查）"

# ---------------------------------------------------------------- 行为验证

Write-Host "`n== 行为验证：用源码里的参数真跑一次全量汉字打包" -ForegroundColor Cyan

$lwjgl = Join-Path $repo "mc-shili\libraries\org\lwjgl"
$jars = @{
    Core = Join-Path $lwjgl "lwjgl\3.4.1\lwjgl-3.4.1-unsafe.jar"
    Stb  = Join-Path $lwjgl "lwjgl-stb\3.4.1\lwjgl-stb-3.4.1.jar"
    CoreNat = Join-Path $lwjgl "lwjgl\3.4.1\lwjgl-3.4.1-natives-windows.jar"
    StbNat  = Join-Path $lwjgl "lwjgl-stb\3.4.1\lwjgl-stb-3.4.1-natives-windows.jar"
}
$missingJar = $false
foreach ($k in $jars.Keys) {
    if (-not (Test-Path $jars[$k])) {
        Write-Host "  SKIP  lwjgl 资源缺失: $($jars[$k])" -ForegroundColor Yellow
        $missingJar = $true
    }
}

if ($missingJar) {
    Write-Host "`n[SKIP] 找不到 mc-shili 下的 lwjgl 3.4.1，行为验证跳过（静态守卫已跑）" -ForegroundColor Yellow
} else {
    $work = Join-Path $env:TEMP ("mirage-earlyfont-" + $PID)
    if (Test-Path $work) { Remove-Item -Recurse -Force $work }
    $lib = Join-Path $work "lib"
    $classes = Join-Path $work "classes"
    New-Item -ItemType Directory -Force -Path $lib, $classes | Out-Null

    # 解压 jar 到目录：直接以 jar 作 -cp 时，jar 内的 module-info.class 会让 javac 报
    # 「找不到模块: org.lwjgl」；解压并丢弃 module-info 最省事。
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    function Expand-Jar([string]$jar, [string]$dest) {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
        try {
            foreach ($e in $zip.Entries) {
                if ($e.Name -eq "module-info.class") { continue }
                if ($e.FullName -like "META-INF/*") { continue }
                if ([string]::IsNullOrEmpty($e.Name)) { continue }
                $target = Join-Path $dest $e.FullName
                New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $target, $true)
            }
        } finally {
            $zip.Dispose()
        }
    }
    Expand-Jar $jars.Core $lib
    Expand-Jar $jars.Stb $lib
    Expand-Jar $jars.CoreNat $lib
    Expand-Jar $jars.StbNat $lib

    # 汉字全集：早期窗口实际打包的是「UI 字表 + 日志字表」的并集
    # （MirageEarlyWindow: LOADING_CJK_SOURCE + LOADING_CJK_LOG_SOURCE），
    # 只测其中一份会漏掉 UI 新增词——STB 缺字是静默留白，不验就发现不了。
    $dbgSrc = Get-Content -Raw -Encoding UTF8 $mirageDebug
    $mConst = [regex]::Match($dbgSrc, 'public static final String LOADING_CJK_LOG_SOURCE\s*=\s*(.*?);', 'Singleline')
    $mUi = [regex]::Match($dbgSrc, 'public static final String LOADING_CJK_SOURCE\s*=\s*(.*?);', 'Singleline')
    $cjk = ""
    foreach ($m in @($mUi, $mConst)) {
        if ($m.Success) {
            foreach ($lit in [regex]::Matches($m.Groups[1].Value, '"((?:[^"\\]|\\.)*)"')) {
                $cjk += $lit.Groups[1].Value
            }
        }
    }
    Assert-True ($mConst.Success -and $mUi.Success -and $cjk.Length -gt 400) `
        "能从 MirageDebug 取到 UI+日志汉字全集（拼接长度 $($cjk.Length)，UI 常量存在=$($mUi.Success)）"
    $cjkFile = Join-Path $work "cjk.txt"
    [System.IO.File]::WriteAllText($cjkFile, $cjk, (New-Object System.Text.UTF8Encoding($false)))

    # 字体：跟随生产 findCjkFont 的候选顺序，取第一个存在的
    $earlyWin = Join-Path $root "launcher\mirage-launch\src\main\java\io\mirage\earlydisplay\MirageEarlyWindow.java"
    $winSrc = Get-Content -Raw -Encoding UTF8 $earlyWin
    $fontPath = ""
    foreach ($c in [regex]::Matches($winSrc, '"(C:/Windows/Fonts/[^"]+)"')) {
        if (Test-Path $c.Groups[1].Value) { $fontPath = $c.Groups[1].Value; break }
    }
    Assert-True ($fontPath -ne "") "找到系统中文字体（候选顺序取自 findCjkFont）"
    Info "字体 = $fontPath"

    if ($fontPath -ne "") {
        $harnessSrc = Join-Path $work "StbFontIndexCheck.java"
        $harnessText = @'
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * 用 EarlyFont 的调用参数跑一次 STB 打包（参数由脚本从生产源码提取后传入）。
 * 不创建 GL 上下文：stbtt_PackBegin 只操作内存位图。
 *
 * args: fontPath fontIndex cjkFile
 */
public class StbFontIndexCheck {

    private static final int FONT_SIZE = 24;
    private static final int TEX = 1024;

    public static void main(String[] args) throws Exception {
        String fontPath = args[0];
        int fontIndex = Integer.parseInt(args[1]);
        String cjkFile = args[2];

        String src = new String(Files.readAllBytes(Paths.get(cjkFile)), StandardCharsets.UTF_8);
        List<Integer> cps = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < src.length(); i++) {
            int cp = src.charAt(i);
            if (cp <= 126 || !seen.add(cp)) continue;
            cps.add(cp);
        }
        int n = cps.size();

        byte[] bytes = Files.readAllBytes(Paths.get(fontPath));
        ByteBuffer data = MemoryUtil.memAlloc(bytes.length);
        data.put(bytes).flip();

        // 与生产一致：InitFont 用字节偏移，PackFontRanges 用 font_index
        int offset = stbtt_GetFontOffsetForIndex(data, fontIndex);
        STBTTFontinfo info = STBTTFontinfo.create();
        boolean init = offset >= 0 && stbtt_InitFont(info, data, offset);

        IntBuffer cpBuf = MemoryUtil.memAllocInt(Math.max(1, n));
        for (int k = 0; k < n; k++) cpBuf.put(cps.get(k));
        cpBuf.flip();

        ByteBuffer bitmap = MemoryUtil.memAlloc(TEX * TEX);
        boolean packOk = false;
        int nonEmpty = 0;
        try (STBTTPackedchar.Buffer chars = STBTTPackedchar.malloc(Math.max(1, n));
             STBTTPackRange.Buffer rngs = STBTTPackRange.malloc(1);
             STBTTPackRange rng = STBTTPackRange.malloc();
             STBTTPackContext pc = STBTTPackContext.malloc()) {
            rngs.put(rng.set(FONT_SIZE, 0, cpBuf, n, chars, (byte) 1, (byte) 1));
            rngs.flip();
            if (stbtt_PackBegin(pc, bitmap, TEX, TEX, 0, 1, NULL)) {
                stbtt_PackSetOversampling(pc, 1, 1);
                stbtt_PackSetSkipMissingCodepoints(pc, true);
                packOk = stbtt_PackFontRanges(pc, data, fontIndex, rngs);
                stbtt_PackEnd(pc);
                try (STBTTAlignedQuad q = STBTTAlignedQuad.malloc()) {
                    float[] x = new float[1];
                    float[] y = new float[1];
                    for (int k = 0; k < n; k++) {
                        x[0] = 0f;
                        y[0] = FONT_SIZE;
                        stbtt_GetPackedQuad(chars, TEX, TEX, k, x, y, q, true);
                        if ((int) q.x1() > (int) q.x0() && (int) q.y1() > (int) q.y0()) nonEmpty++;
                    }
                }
            }
        }

        System.out.println("RESULT init=" + init + " offset=" + offset
                + " fontIndex=" + fontIndex + " total=" + n
                + " nonEmpty=" + nonEmpty + " packOk=" + packOk);

        MemoryUtil.memFree(bitmap);
        MemoryUtil.memFree(cpBuf);
        MemoryUtil.memFree(data);
    }
}
'@
        [System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

        $cp = $lib
        $r = Invoke-Native $javac @("-encoding", "UTF-8", "-cp", $cp, "-d", $classes, $harnessSrc) "javac"
        Assert-True ($r.Code -eq 0) "harness 编译通过"
        if ($r.Code -ne 0) { Write-Host $r.Err -ForegroundColor DarkRed }

        if ($r.Code -eq 0) {
            $r = Invoke-Native $java @(
                "-cp", "$lib;$classes",
                "-Djava.library.path=$lib",
                "StbFontIndexCheck", $fontPath, "$fontIndex", $cjkFile
            ) "run"
            Info ("harness 退出码 = " + $r.Code)
            if ($r.Out) { $r.Out.Trim() -split "`r?`n" | Where-Object { $_ -match "RESULT" } | ForEach-Object { Info $_ } }
            Assert-True ($r.Code -eq 0) "harness 正常退出（非 0 尤其 0xC0000005 类大数 = 原生崩溃）"
            if ($r.Code -ne 0) {
                Write-Host "  ---- stderr ----" -ForegroundColor DarkRed
                ($r.Err -split "`r?`n" | Select-Object -First 12) | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkRed }
            }

            $mRes = [regex]::Match($r.Out, 'RESULT init=(\w+) offset=(-?\d+) fontIndex=(-?\d+) total=(\d+) nonEmpty=(\d+) packOk=(\w+)')
            Assert-True ($mRes.Success) "harness 输出了完整结果行"
            if ($mRes.Success) {
                $initOk = $mRes.Groups[1].Value -eq "true"
                $off = [int]$mRes.Groups[2].Value
                $total = [int]$mRes.Groups[4].Value
                $nonEmpty = [int]$mRes.Groups[5].Value
                $packOk = $mRes.Groups[6].Value -eq "true"
                Assert-True $initOk "stbtt_InitFont 成功（offset=$off）"
                Assert-True $packOk "stbtt_PackFontRanges 返回 true"
                Assert-True ($total -gt 400) "汉字码位总数 > 400（实际 $total）"
                Assert-True ($nonEmpty -eq $total) "全部汉字都打进了图集（$nonEmpty/$total）"
            }
        }
    }

    if (-not $Keep) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
    else { Write-Host "  保留临时目录: $work" -ForegroundColor DarkGray }
}

# ---------------------------------------------------------------- 汇总

Write-Host ""
if ($fail -eq 0) {
    Write-Host "全部通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "失败 $fail 项" -ForegroundColor Red
    exit 1
}
