# scripts/verify_config_dir.ps1
# verify_config_dir.ps1 —— 已知 bug #4（S6 缺 <gameDir>/config）的回归用例（纯 JVM，不启动游戏）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_config_dir.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_config_dir.ps1 -Keep   # 保留临时目录
#
# 为什么这样测：
#   症状在实机日志里是「java.io.FileNotFoundException: config\expore-common.toml」——
#   ExpOre 在构造函数里直接写相对路径 config/xxx.toml，而 NeoForge 的语义是
#   「<gameDir>/config 在 mod 构造之前就已存在」。Mirage 原先没建这个目录。
#
#   ModInitStage 本身在纯 JVM 里加载不了（引了整条游戏 / NeoForge 依赖链），
#   所以把「建目录」这一步纯路径逻辑抽成了 GameDirs（纯 JDK），本脚本编译
#   **生产源码本身**跑断言，而不是在测试里重写一份副本。
#
#   行为断言之外还有源码守卫：接线错了（建目录排在 mod 循环之后、失败路径写成 error、
#   S2 与 S6 各写一份 "config" 字面量）时，行为断言全绿也验不出问题。
#
# 变异检验（实际做过，全部正确变红）：
#   ① GameDirs.ensureConfigDir 改回 Files.createDirectory      → A1 多级创建断言红
#   ② GameDirs.configDir 里顺手加 Files.createDirectories       → A10「只拼路径」断言红
#   ③ ModInitStage 把 ensureConfigDir(ctx) 挪到 mod 循环之后    → 守卫6 顺序断言红
#   ④ ModInitStage helper 的 catch 改成 MIRAGE.error(...)       → 守卫9 降级断言红
#   ⑤ ParseStage 改回 ctx.gameDir().resolve("config")           → 守卫10/11 单一来源断言红

[CmdletBinding()]
param(
    [switch]$Keep,
    # 报告落盘路径。Write-Host 走信息流、管道里捕获不到，这份文件是拿到完整明细的稳定通道。
    [string]$ReportFile = (Join-Path $env:TEMP "verify_config_dir.report.txt")
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
$coreSrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader"
$stageSrc = Join-Path $root "lifecycle-starter\src\main\java\io\mirage\ls\stage"

$script:pass = 0
$script:fail = 0
$script:failures = @()
$script:lines = New-Object System.Collections.ArrayList

function Say {
    param([string]$Text)
    [void]$script:lines.Add($Text)
    Write-Host $Text
}

function Assert-True {
    param([bool]$Cond, [string]$Msg)
    if ($Cond) {
        $script:pass++
        Say ("  [PASS] " + $Msg)
    } else {
        $script:fail++
        $script:failures += $Msg
        Say ("  [FAIL] " + $Msg)
    }
}

function Save-Report {
    try {
        $dir = Split-Path $ReportFile -Parent
        if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
        [System.IO.File]::WriteAllText($ReportFile,
            ($script:lines -join [Environment]::NewLine) + [Environment]::NewLine,
            (New-Object System.Text.UTF8Encoding($false)))
    } catch {
        Write-Host ("[WARN] 报告落盘失败: " + $_.Exception.Message) -ForegroundColor Yellow
    }
}

# --- 定位 JDK ---
function Find-JavaTool {
    param([string]$Name)
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$Name.exe") }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($v in @("jdk-25.0.4", "jdk-21.0.11", "jdk-17")) {
        $candidates += "C:\Program Files\Java\$v\bin\$Name.exe"
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

$javac = Find-JavaTool "javac"
$java = Find-JavaTool "java"
if (-not $javac -or -not $java) {
    Say "[FAIL] 没找到 javac/java（请设置 JAVA_HOME，或把 JDK bin 加进 PATH）"
    Save-Report
    exit 1
}

Say "== 已知 bug #4 回归：<gameDir>/config 预建 =="

# --- 编译被测类 + 检查程序 ---
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-configdir-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$gameDirsSrc = Join-Path $coreSrc "GameDirs.java"
if (-not (Test-Path $gameDirsSrc)) {
    Say ("[FAIL] 找不到生产源码: " + $gameDirsSrc)
    Save-Report
    exit 1
}

$checkDir = Join-Path $work "check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "ConfigDirCheck.java"
$checkCode = @'
package io.mirage.loader.check;

import io.mirage.loader.GameDirs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 检查程序：测**生产代码本身**（GameDirs），不在测试里重写建目录逻辑。
 */
public final class ConfigDirCheck {

    private static int pass = 0;
    private static int fail = 0;
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + msg);
        } else {
            fail++;
            failures.add(msg);
            System.out.println("  [FAIL] " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("mirage-configdir-data-");
        try {
            // A1~A3：gameDir 多级都不存在时也要能建出 config（createDirectories 而非 createDirectory）
            Path gameDir = base.resolve("a").resolve("b");
            Path config = GameDirs.ensureConfigDir(gameDir);
            check(Files.isDirectory(config),
                    "A1 gameDir 多级不存在时也能建出 config（Files.createDirectories 而非 createDirectory）");
            check(config.equals(gameDir.resolve("config")),
                    "A2 返回 <gameDir>/config，实际=" + config);
            check(GameDirs.CONFIG_DIR_NAME.equals(config.getFileName().toString()),
                    "A3 目录名取自 CONFIG_DIR_NAME 常量，实际=" + config.getFileName());

            // A4：幂等（S6 每次启动都会调，重复调用不能炸）
            Path again = GameDirs.ensureConfigDir(gameDir);
            check(again.equals(config) && Files.isDirectory(again),
                    "A4 重复调用幂等、返回同一路径");

            // A5：不得破坏已有配置
            Path toml = config.resolve("expore-common.toml");
            Files.writeString(toml, "keep=me", StandardCharsets.UTF_8);
            GameDirs.ensureConfigDir(gameDir);
            check("keep=me".equals(Files.readString(toml, StandardCharsets.UTF_8)),
                    "A5 已有 config 里的文件不被删除/覆盖");

            // A6：只在 gameDir 下新增 config 一个条目（不顺手建别的目录）
            long entries;
            try (Stream<Path> s = Files.list(gameDir)) {
                entries = s.count();
            }
            check(entries == 1, "A6 gameDir 下只新增 config 一个条目，实际=" + entries);

            // A7~A9：config 被同名普通文件占用 → IOException（调用方可降级），且不破坏该文件
            Path taken = base.resolve("taken");
            Files.createDirectories(taken);
            Files.writeString(taken.resolve("config"), "x", StandardCharsets.UTF_8);
            Throwable caught = null;
            try {
                GameDirs.ensureConfigDir(taken);
            } catch (Throwable t) {
                caught = t;
            }
            check(caught instanceof IOException,
                    "A7 config 被同名文件占用时抛 IOException，实际=" + describe(caught));
            check(caught != null && !(caught instanceof RuntimeException) && !(caught instanceof Error),
                    "A8 失败类型是受检 IOException（不是 RuntimeException/Error）");
            check("x".equals(Files.readString(taken.resolve("config"), StandardCharsets.UTF_8)),
                    "A9 失败路径不破坏同名文件内容");

            // A10：configDir() 只拼路径，副作用只允许出现在 ensureConfigDir
            Path pure = base.resolve("pure").resolve("game");
            Path onlyPath = GameDirs.configDir(pure);
            check(onlyPath.equals(pure.resolve("config")) && !Files.exists(onlyPath),
                    "A10 configDir() 只拼路径、不建目录");

            // A11：中文 + 空格路径（实机 gameDir 里常见）
            Path cjk = base.resolve("中文 目录");
            check(Files.isDirectory(GameDirs.ensureConfigDir(cjk)),
                    "A11 含中文与空格的 gameDir 可正常创建");

            // A12：尾随分隔符不能拼出 config/config
            Path withSep = Path.of(base.toString() + java.io.File.separator);
            check(GameDirs.configDir(withSep).normalize().equals(GameDirs.configDir(base).normalize()),
                    "A12 尾随分隔符不改变结果路径");
        } finally {
            deleteTree(base);
        }

        System.out.println();
        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) {
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    private static String describe(Throwable t) {
        return (t == null) ? "null（没抛异常）" : (t.getClass().getName() + ": " + t.getMessage());
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响结论
                }
            });
        } catch (IOException ignored) {
        }
    }
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkCode, (New-Object System.Text.UTF8Encoding($false)))

Say "-- 编译生产类 GameDirs + 检查程序"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javac
$psi.Arguments = "-encoding UTF-8 -d `"$classes`" `"$gameDirsSrc`" `"$checkSrc`""
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$c = New-Object System.Diagnostics.Process
$c.StartInfo = $psi
[void]$c.Start()
# 先读流再等退出，避免管道缓冲写满时死锁
$javacOut = $c.StandardOutput.ReadToEnd()
$javacErr = $c.StandardError.ReadToEnd()
$c.WaitForExit()
if ($c.ExitCode -ne 0) {
    Say "[FAIL] javac 失败："
    Say $javacOut
    Say $javacErr
    Save-Report
    exit 1
}

Say "-- 运行检查程序"
$psi2 = New-Object System.Diagnostics.ProcessStartInfo
$psi2.FileName = $java
$psi2.Arguments = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$classes`" io.mirage.loader.check.ConfigDirCheck"
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError = $true
$psi2.UseShellExecute = $false
$psi2.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi2.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$r = New-Object System.Diagnostics.Process
$r.StartInfo = $psi2
[void]$r.Start()
# StandardOutput 只能读一次；且必须先读完流再 WaitForExit
$checkOut = $r.StandardOutput.ReadToEnd()
$checkErr = $r.StandardError.ReadToEnd()
$r.WaitForExit()
Say $checkOut
if ($r.ExitCode -ne 0) {
    Say ("[FAIL] 检查程序退出码 " + $r.ExitCode)
    Say $checkErr
    $script:fail++
    $script:failures += "检查程序退出码 " + $r.ExitCode
}

$outText = $checkOut
if ($outText -match 'PASS (\d+) / FAIL (\d+)') {
    $script:pass += [int]$Matches[1]
    $script:fail += [int]$Matches[2]
} else {
    Say "[FAIL] 检查程序输出里没有 'PASS n / FAIL m' 汇总行（可能进程没跑起来）"
    $script:fail++
    $script:failures += "检查程序未输出汇总行"
}

Say "-- 源码守卫"

$gameDirsPath = Join-Path $coreSrc "GameDirs.java"
$modInitPath = Join-Path $stageSrc "ModInitStage.java"
$parsePath = Join-Path $stageSrc "ParseStage.java"
foreach ($p in @($gameDirsPath, $modInitPath, $parsePath)) {
    if (-not (Test-Path $p)) {
        Say ("[FAIL] 找不到源码: " + $p)
        Save-Report
        exit 1
    }
}

$gd = Get-Content $gameDirsPath -Raw -Encoding UTF8
Assert-True ($gd -match 'Files\.createDirectories\(') "守卫1 GameDirs 用 Files.createDirectories（父级未建也能落地）"
Assert-True (-not ($gd -match 'Files\.createDirectory\(')) "守卫2 不得退回 Files.createDirectory（只能建一层）"
Assert-True ($gd -match 'public static final String CONFIG_DIR_NAME = "config"') "守卫3 目录名常量是 config"

$mi = Get-Content $modInitPath -Raw -Encoding UTF8
# 行首锚定：注释里出现 ensureConfigDir(ctx); 不能算接线还在
$callMatch = [regex]::Match($mi, '(?m)^\s*ensureConfigDir\(ctx\);')
$loopMatch = [regex]::Match($mi, '(?m)^\s*for \(ModContainer mod : mods\)')
Assert-True ($callMatch.Success) "守卫4 S6 真的调用了 ensureConfigDir(ctx)（行首锚定，注释不算）"
Assert-True ($loopMatch.Success) "守卫5 找得到 mod 构造循环"
if ($callMatch.Success -and $loopMatch.Success) {
    Assert-True ($callMatch.Index -lt $loopMatch.Index) `
        ("守卫6 建目录发生在 mod 构造循环之前（偏移 " + $callMatch.Index + " < " + $loopMatch.Index + "）")
}

$helperStart = $mi.IndexOf("private static void ensureConfigDir")
$helperEnd = $mi.IndexOf("private static void archivePassive")
Assert-True ($helperStart -ge 0 -and $helperEnd -gt $helperStart) "守卫7 找得到 ensureConfigDir 助手方法边界"
if ($helperStart -ge 0 -and $helperEnd -gt $helperStart) {
    $helperBody = $mi.Substring($helperStart, $helperEnd - $helperStart)
    Assert-True ($helperBody -match 'MIRAGE\.warn\(') "守卫8 建目录失败走 WARN（保持「mod 退化继续跑」的现状）"
    Assert-True (-not ($helperBody -match 'MIRAGE\.error\(')) "守卫9 建目录失败不得走 ERROR（否则升级成启动 FATAL）"
}

$ps = Get-Content $parsePath -Raw -Encoding UTF8
Assert-True ($ps -match 'setConfigDirectory\(GameDirs\.configDir\(ctx\.gameDir\(\)\)\)') `
    "守卫10 S2 的 config 目录走 GameDirs（与 S6 单一来源，防两处字面量漂移）"
Assert-True (-not ($ps -match 'resolve\("config"\)')) "守卫11 S2 不再出现字面量 resolve(`"config`")"

Say ""
Say ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==")
if ($script:fail -gt 0) {
    Say "失败项："
    foreach ($f in $script:failures) { Say ("  - " + $f) }
}
Save-Report
Say ("报告: " + $ReportFile)

if (-not $Keep) {
    try { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue } catch { }
} else {
    Say ("临时目录保留: " + $work)
}

if ($script:fail -gt 0) { exit 1 } else { exit 0 }
