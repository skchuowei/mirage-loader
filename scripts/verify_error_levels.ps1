# scripts/verify_error_levels.ps1
# verify_error_levels.ps1 —— MEL 错误分级的回归用例（纯 JVM，不启动游戏、不依赖 gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_error_levels.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_error_levels.ps1 -Keep   # 保留临时目录
#
# 为什么这样测：
#   Phase 1 验收标准 2 要求"三类错误各有一例可 grep"——但"源码里有调用"不等于
#   "运行时会按预期输出"。这里把 MirageLog + MirageErrors + MirageException +
#   ErrorLevel 现场 javac 编译，跑一个检查程序，分流 stdout / stderr 逐条断言：
#     1. 四档前缀字面正确：[MG-FATAL] / [MG-ERROR] / [MG-WARN] / [MG-IGNORE]
#     2. 分流正确：FATAL/ERROR/WARN 走 stderr，IGNORE 走 stdout
#        （IGNORE 表示"已兜住、不该污染错误流"，但仍要留在日志里备查）
#     3. 带异常时异常链完整（根因不能只露出最外层）
#     4. MirageException 带级别，toString() 带前缀
#     5. 级别门控生效：-Dmirage.log.level=error 时 WARN/IGNORE 静音、FATAL/ERROR 保留
#   再加一层源码守卫：四个档位在业务代码里各有真实调用点（不是只在门面里定义）。
#
#   注意：级别门控必须**分进程**跑——MirageLog 的级别在静态初始化时读一次，
#   同进程内改不了（与 verify_logging.ps1 同一个道理）。
#
# 变异检验（做过的）：
#   把 emit 里的 "MG-WARN" 改成 "MG-WARN-X" → 前缀断言红；
#   把 IGNORE 的流向从 stdout 改成 stderr → 分流断言红；
#   把 ErrorLevel.IGNORE 的 logLevel 从 INFO 改成 DEBUG → 门控断言红（默认级别下 IGNORE 不见了）。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
$apiSrc = Join-Path $root "api\mirage-api\src\main\java\io\mirage\api"
$srcs = @(
    (Join-Path $apiSrc "log\MirageLog.java"),
    (Join-Path $apiSrc "error\ErrorLevel.java"),
    (Join-Path $apiSrc "error\MirageException.java"),
    (Join-Path $apiSrc "error\MirageErrors.java")
)
foreach ($s in $srcs) {
    if (-not (Test-Path $s)) {
        Write-Host "[FAIL] 找不到源码: $s" -ForegroundColor Red
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

$work = Join-Path $env:TEMP ("mirage-mel-" + $PID)
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# 注意：生成的 .java **不能带 BOM**（javac 不剥 BOM，会报 illegal character: '\ufeff'）
$checkDir = Join-Path $work "src\io\mirage\api\error\check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "ErrorLevelsCheck.java"
$checkCode = @'
package io.mirage.api.error.check;

import io.mirage.api.error.ErrorLevel;
import io.mirage.api.error.MirageErrors;
import io.mirage.api.error.MirageException;
import io.mirage.api.log.MirageLog;

public class ErrorLevelsCheck {
    public static void main(String[] args) {
        System.out.println("GATE-LEVEL=" + MirageLog.level());
        MirageErrors.fatal("fatal-case");
        MirageErrors.error("error-case");
        MirageErrors.warn("warn-case");
        MirageErrors.ignore("ignore-case");
        MirageErrors.error("error-with-cause",
                new IllegalStateException("boom", new RuntimeException("root-cause")));
        MirageException e = MirageErrors.exception(ErrorLevel.WARN, "ex-msg");
        System.out.println("EX-TOSTRING=" + e);
        System.out.println("EX-LEVEL=" + e.level());
        System.out.println("PREFIX-FATAL=" + ErrorLevel.FATAL.prefix());
        System.out.println("PREFIX-IGNORE=" + ErrorLevel.IGNORE.prefix());
    }
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkCode, (New-Object System.Text.UTF8Encoding($false)))

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 不用 Start-Process：本机 http_proxy 与 HTTP_PROXY 并存，复制环境时会抛"已添加项"。
# 也不用 & 调用 + 2>&1：PS 5.1 会把原生 stderr 变成 NativeCommandError 中断脚本。
function Invoke-Native([string]$exe, [string[]]$argv) {
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
        Code = $proc.ExitCode
        Out  = $outTask.Result
        Err  = $errTask.Result
    }
}

$script:pass = 0
$script:fail = 0
$script:failures = New-Object System.Collections.ArrayList

function Ok([string]$msg) {
    $script:pass++
    Write-Host ("  [PASS] " + $msg) -ForegroundColor DarkGreen
}

function Bad([string]$msg) {
    $script:fail++
    [void]$script:failures.Add($msg)
    Write-Host ("  [FAIL] " + $msg) -ForegroundColor Red
}

function Has([string]$hay, [string]$needle) { return $hay.Contains($needle) }
function Lacks([string]$hay, [string]$needle) { return -not $hay.Contains($needle) }

# ---------------------------------------------------------------- 编译

Write-Host "== MEL 错误分级回归 ==" -ForegroundColor Cyan

$compileArgs = @("-proc:none", "-nowarn", "-d", $classes) + $srcs + @($checkSrc)
$c = Invoke-Native $javac $compileArgs
if ($c.Code -ne 0) {
    Write-Host "[FAIL] javac 失败：" -ForegroundColor Red
    Write-Host $c.Err -ForegroundColor Red
    exit 1
}
Ok "四个源文件 + 检查程序编译通过"

# ---------------------------------------------------------------- 默认级别下的行为

$r = Invoke-Native $java @("-cp", $classes, "io.mirage.api.error.check.ErrorLevelsCheck")
if ($r.Code -ne 0) {
    Write-Host "[FAIL] 检查程序退出码 " + $r.Code -ForegroundColor Red
    Write-Host $r.Err -ForegroundColor Red
    exit 1
}

# 前缀字面
if (Has $r.Err "[MG-FATAL] fatal-case") { Ok "FATAL 前缀字面正确，且走 stderr" } else { Bad "stderr 里没有 [MG-FATAL] fatal-case（实际 stderr: $($r.Err -replace "`r?`n",' | '))" }
if (Has $r.Err "[MG-ERROR] error-case") { Ok "ERROR 前缀字面正确，且走 stderr" } else { Bad "stderr 里没有 [MG-ERROR] error-case" }
if (Has $r.Err "[MG-WARN] warn-case") { Ok "WARN 前缀字面正确，且走 stderr" } else { Bad "stderr 里没有 [MG-WARN] warn-case" }
if (Has $r.Out "[MG-IGNORE] ignore-case") { Ok "IGNORE 前缀字面正确，且走 stdout" } else { Bad "stdout 里没有 [MG-IGNORE] ignore-case" }

# 分流：按**消息内容**判定，不能按前缀判定——
# 检查程序自己会打印 MirageException.toString()（含 "[MG-WARN]"），那是合法输出。
foreach ($p in @("fatal-case", "error-case", "warn-case")) {
    if (Lacks $r.Out $p) { Ok ("分流正确：stdout 不含 " + $p) } else { Bad ("分流错误：stdout 里出现了 " + $p) }
}
if (Lacks $r.Err "ignore-case") { Ok "分流正确：stderr 不含 ignore-case" } else { Bad "分流错误：stderr 里出现了 ignore-case" }

# 异常链完整
if ((Has $r.Err "boom") -and (Has $r.Err "root-cause")) {
    Ok "带异常时异常链完整（根因 root-cause 没被吃掉）"
} else {
    Bad "带异常时异常链不完整（stderr 里看不到 root-cause）"
}

# 异常对象
if (Has $r.Out "EX-TOSTRING=[MG-WARN] ex-msg") { Ok "MirageException.toString() 带级别前缀" } else { Bad "MirageException.toString() 不符合预期" }
if (Has $r.Out "EX-LEVEL=WARN") { Ok "MirageException.level() 返回正确级别" } else { Bad "MirageException.level() 不正确" }
if (Has $r.Out "PREFIX-FATAL=MG-FATAL") { Ok "ErrorLevel.FATAL.prefix() = MG-FATAL" } else { Bad "ErrorLevel.FATAL.prefix() 不正确" }
if (Has $r.Out "PREFIX-IGNORE=MG-IGNORE") { Ok "ErrorLevel.IGNORE.prefix() = MG-IGNORE" } else { Bad "ErrorLevel.IGNORE.prefix() 不正确" }

# ---------------------------------------------------------------- 级别门控（必须分进程）

$g = Invoke-Native $java @("-Dmirage.log.level=error", "-cp", $classes, "io.mirage.api.error.check.ErrorLevelsCheck")
if ($g.Code -ne 0) {
    Bad ("门控模式检查程序退出码 " + $g.Code)
} else {
    if (Has $g.Err "[MG-FATAL] fatal-case") { Ok "门控 error 级：FATAL 仍输出" } else { Bad "门控 error 级：FATAL 被静音了" }
    if (Has $g.Err "[MG-ERROR] error-case") { Ok "门控 error 级：ERROR 仍输出" } else { Bad "门控 error 级：ERROR 被静音了" }
    if (Lacks $g.Err "[MG-WARN]") { Ok "门控 error 级：WARN 被静音" } else { Bad "门控 error 级：WARN 仍输出" }
    if (Lacks $g.Out "[MG-IGNORE]") { Ok "门控 error 级：IGNORE 被静音" } else { Bad "门控 error 级：IGNORE 仍输出" }
}

# ---------------------------------------------------------------- 源码守卫：四档各有真实调用点

Write-Host "-- 源码守卫（业务代码里四档各有实例）"
$repoFiles = @(
    (Join-Path $root "core\mirage-mixin\src\main\java\io\mirage\mixin\MirageMixinBootstrap.java"),
    (Join-Path $root "lifecycle-starter\src\main\java\io\mirage\ls\stage\TransformStage.java"),
    (Join-Path $root "lifecycle-starter\src\main\java\io\mirage\ls\stage\ModInitStage.java"),
    (Join-Path $root "modules\mirage-fabric-compat\src\main\java\io\mirage\fabric\runtime\FabricNetworkingBridge.java")
)
$all = ""
foreach ($f in $repoFiles) {
    if (-not (Test-Path $f)) { Bad ("源码守卫：找不到 " + $f); continue }
    $all += (Get-Content $f -Raw -Encoding UTF8)
}
foreach ($lvl in @("fatal", "error", "warn", "ignore")) {
    $n = ([regex]::Matches($all, "MirageErrors\." + $lvl + "\(")).Count
    if ($n -gt 0) {
        Ok ("业务代码里 MirageErrors." + $lvl + "() 有 " + $n + " 处真实调用")
    } else {
        Bad ("业务代码里没有 MirageErrors." + $lvl + "() 的调用点（只有门面定义，等于没落地）")
    }
}

# ---------------------------------------------------------------- 汇总

Write-Host ""
Write-Host ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==") -ForegroundColor Cyan
if (-not $Keep) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue }
else { Write-Host ("临时目录保留在: " + $work) }

if ($script:fail -gt 0) {
    Write-Host "失败明细：" -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
    exit 1
}
Write-Host "全部通过。" -ForegroundColor Green
exit 0
