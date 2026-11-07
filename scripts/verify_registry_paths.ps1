# scripts/verify_registry_paths.ps1
# verify_registry_paths.ps1 —— 已知 bug #3（creative_mode_tab 缺 intrusive holder）的回归用例（纯 JVM）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_registry_paths.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_registry_paths.ps1 -Keep
#
# 为什么这样测：
#   实机日志 `expore:creative_mode_tab 桥接 1 个条目` 证明 DeferredRegister.registryPath()
#   给的是**蛇形** key 路径；而 RegistryWindow 旧代码写的是
#   `"creativemodetab".equals(path)`（按"元素类型简单名小写"的形态比较）⇒ 恒 false
#   ⇒ intrusive holder 从未创建 ⇒ MappedRegistry.register 抛
#   `AssertionError: Missing intrusive holder for ResourceKey[.../expore:expore]`。
#
#   RegistryWindow 引了整条反射/游戏依赖链，纯 JVM 加载不了；所以把归一逻辑抽成
#   RegistryPaths（纯 JDK），本脚本编译**生产源码本身**跑断言，而不是测副本。
#
#   源码守卫盯的是"接线不能用回字面量硬比较"——归一对但调用点没换，行为断言全绿也验不出。
#
# 变异检验（实际做过，全部正确变红）：
#   ① RegistryPaths.normalize 去掉 `if (c == '_') continue;`     → B1/B2/B10 红
#   ② isCreativeModeTab 改成直接 compare 不归一                  → B2/B4 红
#   ③ RegistryWindow 改回 `"creativemodetab".equals(path)`       → 守卫1/2 红
#   ④ normalize 去掉 Character.toLowerCase（不转小写）            → B4 红

[CmdletBinding()]
param(
    [switch]$Keep,
    [string]$ReportFile = (Join-Path $env:TEMP "verify_registry_paths.report.txt")
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
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

Say "== 已知 bug #3 回归：注册表路径归一 =="

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-regpaths-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$pathsSrc = Join-Path $stageSrc "RegistryPaths.java"
if (-not (Test-Path $pathsSrc)) {
    Say ("[FAIL] 找不到生产源码: " + $pathsSrc)
    Save-Report
    exit 1
}

$checkDir = Join-Path $work "check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "RegistryPathsCheck.java"
$checkCode = @'
package io.mirage.ls.stage.check;

import io.mirage.ls.stage.RegistryPaths;

import java.util.ArrayList;
import java.util.List;

/**
 * 检查程序：测**生产代码本身**（RegistryPaths），不在测试里重写归一算法。
 */
public final class RegistryPathsCheck {

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

    public static void main(String[] args) {
        // B1~B4：核心回归——实机给的是蛇形，必须命中；简单名形态也要兼容
        check("creativemodetab".equals(RegistryPaths.normalize("creative_mode_tab")),
                "B1 normalize(creative_mode_tab) == creativemodetab，实际=" + RegistryPaths.normalize("creative_mode_tab"));
        check(RegistryPaths.isCreativeModeTab("creative_mode_tab"),
                "B2 蛇形 creative_mode_tab 命中创造标签页（修复前恒 false，就是 bug #3）");
        check(RegistryPaths.isCreativeModeTab("creativemodetab"),
                "B3 简单名小写 creativemodetab 同样命中（registryPath() 的退化形态）");
        check(RegistryPaths.isCreativeModeTab("CREATIVE_MODE_TAB"),
                "B4 全大写也命中（归一含转小写）");
        check(RegistryPaths.normalize("creative_mode_tab").equals(RegistryPaths.normalize("creativemodetab")),
                "B5 两种形态归一后完全相等");

        // B6~B7：不能归一过宽
        check(!RegistryPaths.isCreativeModeTab("item") && !RegistryPaths.isCreativeModeTab("creative_mode_tabs"),
                "B6 item / creative_mode_tabs 不得误判成创造标签页");
        check(RegistryPaths.isItem("item") && !RegistryPaths.isItem("items") && !RegistryPaths.isItem("block"),
                "B7 item 精确命中，items / block 不命中");
        check(!RegistryPaths.isCreativeModeTab("point_of_interest_type")
                        && !RegistryPaths.isItem("point_of_interest_type")
                        && "pointofinteresttype".equals(RegistryPaths.normalize("point_of_interest_type")),
                "B8 其它蛇形注册表（point_of_interest_type）正常归一但两个判定都为 false");

        // B9~B11：边界输入不炸
        check("".equals(RegistryPaths.normalize(null)) && "".equals(RegistryPaths.normalize("")),
                "B9 null / 空串归一到空串");
        check(!RegistryPaths.isCreativeModeTab(null) && !RegistryPaths.isItem(null),
                "B10 null 输入返回 false 而不是 NPE");
        check("".equals(RegistryPaths.normalize("___")),
                "B11 纯下划线归一到空串");

        System.out.println();
        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) {
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkCode, (New-Object System.Text.UTF8Encoding($false)))

Say "-- 编译生产类 RegistryPaths + 检查程序"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javac
$psi.Arguments = "-encoding UTF-8 -d `"$classes`" `"$pathsSrc`" `"$checkSrc`""
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$c = New-Object System.Diagnostics.Process
$c.StartInfo = $psi
[void]$c.Start()
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
$psi2.Arguments = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$classes`" io.mirage.ls.stage.check.RegistryPathsCheck"
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError = $true
$psi2.UseShellExecute = $false
$psi2.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi2.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$r = New-Object System.Diagnostics.Process
$r.StartInfo = $psi2
[void]$r.Start()
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

if ($checkOut -match 'PASS (\d+) / FAIL (\d+)') {
    $script:pass += [int]$Matches[1]
    $script:fail += [int]$Matches[2]
} else {
    Say "[FAIL] 检查程序输出里没有 'PASS n / FAIL m' 汇总行（可能进程没跑起来）"
    $script:fail++
    $script:failures += "检查程序未输出汇总行"
}

Say "-- 源码守卫"

$windowPath = Join-Path $stageSrc "RegistryWindow.java"
foreach ($p in @($pathsSrc, $windowPath)) {
    if (-not (Test-Path $p)) {
        Say ("[FAIL] 找不到源码: " + $p)
        Save-Report
        exit 1
    }
}

$rw = Get-Content $windowPath -Raw -Encoding UTF8
# 行首锚定：注释里出现调用不算接线还在
Assert-True ([regex]::IsMatch($rw, '(?m)^\s*if \(RegistryPaths\.isCreativeModeTab\(path\)\) \{')) `
    "守卫1 RegistryWindow 用 RegistryPaths.isCreativeModeTab(path) 判定（行首锚定，注释不算）"
Assert-True (-not ($rw -match '"creativemodetab"\s*\.equals')) `
    "守卫2 不得退回字面量 `"creativemodetab`".equals(...)（bug #3 的原形态）"
Assert-True ([regex]::IsMatch($rw, '(?m)^\s*if \(RegistryPaths\.isItem\(path\)\) \{')) `
    "守卫3 item 分支同样走 RegistryPaths.isItem(path)"
Assert-True (-not ($rw -match '"item"\s*\.equals')) `
    "守卫4 不得退回字面量 `"item`".equals(...)"

$rp = Get-Content $pathsSrc -Raw -Encoding UTF8
Assert-True ([regex]::IsMatch($rp, "c == '_'")) "守卫5 normalize 必须跳过下划线（否则蛇形与简单名永远不相等）"
Assert-True ($rp -match 'public static final String CREATIVE_MODE_TAB = "creativemodetab"') `
    "守卫6 归一目标常量是 creativemodetab"

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
