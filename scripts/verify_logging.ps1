# scripts/verify_logging.ps1
# verify_logging.ps1 —— MirageLog 日志门面回归验证（纯 JVM，不启动游戏、不依赖 gradle 产物）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_logging.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_logging.ps1 -Keep   # 保留临时目录便于排查
#
# 为什么这样测：
#   MirageLog 位于 api:mirage-api，只依赖 JDK 标准库，所以可以直接 javac 它本体 + 一个检查程序，
#   在临时目录里跑 —— 不需要构建整个 loader，也不需要开 MC。级别是静态初始化时读的，
#   因此每个级别场景必须独立一个 JVM 进程（这也是脚本要跑 5 次 java 的原因）。
#
# 覆盖的契约（这几条是最容易在后续重构里悄悄回归的点）：
#   1. 异常链展开：InvocationTargetException / ExceptionInInitializerError 必须剥到真实 cause，
#      message 为空的异常只留类名，不能出现裸 ": null"（改造前正是这两条让启动日志没法看）。
#   2. 级别过滤：-Dmirage.log.level=warn 时 INFO/DEBUG 不出现；=debug 时 DEBUG 出现；=off 时全静默。
#   3. raw() 逐字保真：[LS] stage ... / [LS] pipeline finished 这类冻结行不加级别词，且不受级别过滤影响。
#   4. 定宽对齐：[tag] + 级别词补到 5 列（INFO/WARN 补空格），消息正文左对齐。
#   5. 流向分流：WARN 及以上走 stderr，其余走 stdout（启动器靠 2> 分流，这条不能变）。
#   6. 堆栈策略：smart 保留前 2 帧 + 项目帧并折叠超预算帧；-Dmirage.log.stack=none 只留摘要行。
#
# 退出码 0 = 全部通过；1 = 有失败项（会打印失败项 + 各场景实际输出）。

[CmdletBinding()]
param(
    [switch]$Keep
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent
$logSrc = Join-Path $root "api\mirage-api\src\main\java\io\mirage\api\log\MirageLog.java"

if (-not (Test-Path $logSrc)) {
    Write-Host "[FAIL] 找不到 MirageLog 源码: $logSrc" -ForegroundColor Red
    exit 1
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

$work = Join-Path $env:TEMP ("mirage-logcheck-" + $PID)
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 跑一个外部 javac/java，分别取回 stdout / stderr / 退出码。
# 不用 Start-Process：本机环境变量里 http_proxy 与 HTTP_PROXY 并存，
# Windows 上环境字典大小写不敏感，Start-Process 复制环境时会抛 "已添加项"。
# 也不用 & 调用 + 2>&1：PS 5.1 会把原生 stderr 变成 NativeCommandError，配合 Stop 会中断脚本。
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
    # 先起异步读，避免任一管道缓冲区写满导致 WaitForExit 卡死
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
# 注意：放在 io.mirage.* 包里，这样栈帧命中 MirageLog 的 INTERESTING 前缀（io.mirage.），
# 才能真正验证「项目帧即使在预算内第 3 帧之后也保留」这条 smart 策略。

$harnessDir = Join-Path $work "src\io\mirage\api\log\check"
New-Item -ItemType Directory -Force -Path $harnessDir | Out-Null
$harnessSrc = Join-Path $harnessDir "LogFacadeCheck.java"

$harnessText = @'
package io.mirage.api.log.check;

import io.mirage.api.log.MirageLog;

import java.lang.reflect.InvocationTargetException;

/**
 * 日志门面回归用例（由 scripts/verify_logging.ps1 编译运行）。
 * 只输出固定 ASCII 标记，断言在 PowerShell 侧做 —— 避免中文字面量带来的编码不确定性。
 */
public final class LogFacadeCheck {

    public static void main(String[] args) {
        // 级别过滤：默认 INFO 下 DEBUG 该被丢弃，-Dmirage.log.level=debug 时才出现
        MirageLog.MIRAGE.debug("DEBUG-MARK 调试噪声");
        MirageLog.MIRAGE.info("INFO-MARK 普通信息");
        MirageLog.LS.warn("WARN-MARK 警告信息");

        // 1) 反射包装必须展开到真实 cause（改造前只会打类名，message 恒为 null）
        MirageLog.MIRAGE.error("ERR-CHAIN 注册失败",
                new InvocationTargetException(new IllegalStateException("boom-cause")));

        // 2) message 为空的异常只留类名，不能出现 ": null"
        MirageLog.MIRAGE.error("ERR-NULLMSG 事件处理错误", new NullPointerException());

        // 3) 类初始化包装同样要展开
        MirageLog.MIRAGE.error("ERR-INIT 类初始化失败",
                new ExceptionInInitializerError(new RuntimeException("init-cause")));

        // 4) 多级 cause 链要能一路打出来
        MirageLog.MIRAGE.error("ERR-DEEP 深层原因",
                new IllegalStateException("lvl1", new UnsupportedOperationException("lvl2")));

        // 5) 深递归栈：验证 smart 模式的 16 行预算与 "... N more" 折叠
        MirageLog.MIRAGE.error("ERR-STACK 深层堆栈", deep(20));

        // 6) 冻结行：不加级别词、不受级别过滤影响
        MirageLog.LS.raw("[LS] stage S4 RegistryMerge start");
        MirageLog.LS.raw("[LS] pipeline finished");

        // 7) 状态自检，供脚本交叉验证 -D 是否真的生效
        System.out.println("SUMMARY-LEVEL=" + MirageLog.level());
        System.out.println("SUMMARY-DEBUG-ENABLED=" + MirageLog.isDebugEnabled());
    }

    /** 造一条 20 层深的 io.mirage 栈，用于验证栈帧预算。 */
    private static IllegalStateException deep(int n) {
        return n <= 0 ? new IllegalStateException("deep-root") : deep(n - 1);
    }
}
'@

# 写成无 BOM 的 UTF-8：javac 会把 UTF-8 BOM 当成非法字符（error: illegal character: \ufeff）
[System.IO.File]::WriteAllText($harnessSrc, $harnessText, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "== 编译 MirageLog + 检查程序 ==" -ForegroundColor Cyan
$compile = Invoke-Native $javac @(
    "-J-Duser.language=en",
    "-encoding", "UTF-8",
    "-d", $classes,
    $logSrc,
    $harnessSrc
) "compile"

if ($compile.Code -ne 0) {
    Write-Host "[FAIL] javac 编译失败（退出码 $($compile.Code)）" -ForegroundColor Red
    if ($compile.Out.Trim()) { Write-Host $compile.Out }
    if ($compile.Err.Trim()) { Write-Host $compile.Err -ForegroundColor DarkYellow }
    exit 1
}
Write-Host "   [OK] 编译通过" -ForegroundColor DarkGreen

$verProbe = Invoke-Native $java @("-version") "jvmver"
$jvmLine = (($verProbe.Err + "`n" + $verProbe.Out) -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -First 1)
Write-Host "   JVM: $jvmLine" -ForegroundColor DarkGray

# ---------------------------------------------------------------- 断言框架

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]
$script:scenarioLog = [ordered]@{}

function Assert-Match([string]$text, [string]$pattern, [string]$desc) {
    $script:checks++
    if ($text -match $pattern) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
    } else {
        $script:failures.Add("$desc`n        期望匹配: $pattern")
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
    }
}

function Assert-NoMatch([string]$text, [string]$pattern, [string]$desc) {
    $script:checks++
    if ($text -notmatch $pattern) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
    } else {
        $script:failures.Add("$desc`n        不应匹配: $pattern")
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
    }
}

# 截取「以 marker 开头的那一行 + 后续缩进行」，到下一个行首不是空白的行（即下一条日志）为止。
# 用于把异常相关的断言限定在该异常自己打出来的那几行里，避免跨条误匹配。
function Get-Block([string]$text, [string]$marker) {
    $lines = $text -split "`r?`n"
    $buf = New-Object System.Collections.Generic.List[string]
    $in = $false
    foreach ($l in $lines) {
        if (-not $in) {
            if ($l -like "*$marker*") { $in = $true; $buf.Add($l) }
            continue
        }
        if ($l -notmatch '^\s' -or $l -match '^\[') { break }
        $buf.Add($l)
    }
    return ($buf -join "`n")
}

function Invoke-Scenario([string]$tag, [string[]]$props) {
    $argv = @("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8") + $props +
            @("-cp", $classes, "io.mirage.api.log.check.LogFacadeCheck")
    $r = Invoke-Native $java $argv $tag
    if ($r.Code -ne 0) {
        $script:failures.Add("场景 $tag 退出码 $($r.Code)`n        stderr: $($r.Err)")
    }
    $script:scenarioLog[$tag] = $r
    return $r
}

# ================================================================
# 场景 1：默认级别（INFO）—— 主要契约都在这里
# ================================================================
Write-Host ""
Write-Host "== 场景 1：默认（INFO）==" -ForegroundColor Cyan
$s1 = Invoke-Scenario "s1-default" @()

# --- 格式 / 对齐（INFO 与 raw 在 stdout） ---
Assert-Match $s1.Out '(?m)^\[Mirage\] INFO  INFO-MARK' "INFO 行定宽对齐：'[Mirage] INFO␣␣'"
Assert-Match $s1.Err '(?m)^\[Mirage\] ERROR ERR-CHAIN' "ERROR 恰为 5 列，单空格分隔"

# --- 级别过滤 ---
Assert-NoMatch $s1.Out 'DEBUG-MARK' "默认级别下 DEBUG 被丢弃"
Assert-Match $s1.Out '(?m)^SUMMARY-LEVEL=INFO\s*$' "level() 默认 INFO"
Assert-Match $s1.Out '(?m)^SUMMARY-DEBUG-ENABLED=false\s*$' "isDebugEnabled() 默认 false"

# --- raw 逐字保真（stdout） ---
Assert-Match $s1.Out '(?m)^\[LS\] stage S4 RegistryMerge start\s*$' "raw 逐字输出：stage start"
Assert-Match $s1.Out '(?m)^\[LS\] pipeline finished\s*$' "raw 逐字输出：pipeline finished"
Assert-NoMatch $s1.Out '(?m)^\[LS\] (TRACE|DEBUG|INFO|WARN|ERROR|FATAL) +\[LS\]' "raw 行未被塞入级别词"

# --- 流向分流 ---
Assert-Match $s1.Err 'WARN-MARK' "WARN 走 stderr"
Assert-NoMatch $s1.Out 'WARN-MARK' "WARN 不进 stdout"
Assert-Match $s1.Err 'ERR-CHAIN' "ERROR 走 stderr"
Assert-NoMatch $s1.Out 'ERR-CHAIN' "ERROR 不进 stdout"
Assert-Match $s1.Err '(?m)^\[LS\] WARN  WARN-MARK' "WARN 行定宽对齐：'[LS] WARN␣␣'"

# --- 异常链展开（stderr） ---
$chain = Get-Block $s1.Err 'ERR-CHAIN'
Assert-Match $chain '(?m)^\[Mirage\] ERROR ERR-CHAIN .*: java\.lang\.IllegalStateException: boom-cause' `
    "InvocationTargetException 展开到真实 cause 且带 message"
Assert-NoMatch $chain 'InvocationTargetException' "不暴露包装层类名"
Assert-Match $chain '(?m)^\s+at io\.mirage\.' "栈帧缩进对齐且保留项目帧"

$nullMsg = Get-Block $s1.Err 'ERR-NULLMSG'
Assert-Match $nullMsg '(?m)^\[Mirage\] ERROR ERR-NULLMSG .*: java\.lang\.NullPointerException\s*$' `
    "message 为空的异常只打类名"
Assert-NoMatch $nullMsg ':\s*null\s*$' "不出现裸 ': null' 摘要"

$init = Get-Block $s1.Err 'ERR-INIT'
Assert-Match $init '(?m)^\[Mirage\] ERROR ERR-INIT .*: java\.lang\.RuntimeException: init-cause' `
    "ExceptionInInitializerError 展开到真实 cause"
Assert-NoMatch $init 'ExceptionInInitializerError' "不暴露初始化包装层类名"

$deepBlock = Get-Block $s1.Err 'ERR-DEEP'
Assert-Match $deepBlock '(?m)^\s+Caused by: java\.lang\.UnsupportedOperationException: lvl2' `
    "多级 cause 链打出 Caused by"

$stackBlock = Get-Block $s1.Err 'ERR-STACK'
Assert-Match $stackBlock '(?m)^\[Mirage\] ERROR ERR-STACK .*: java\.lang\.IllegalStateException: deep-root' `
    "深栈异常摘要正确"
Assert-Match $stackBlock '(?m)^\s+at io\.mirage\.api\.log\.check\.LogFacadeCheck\.deep' `
    "smart 模式保留项目帧（第 3 帧之后仍保留）"
Assert-Match $stackBlock '\.\.\. \d+ more' "超出 16 行预算后折叠为 '... N more'"
$frameCount = ([regex]::Matches($stackBlock, '(?m)^\s+at ')).Count
Assert-Match ([string]$frameCount) '^([1-9]|1[0-6])$' "smart 模式栈帧数 ≤16（实测 $frameCount 行）"

# ================================================================
# 场景 2：-Dmirage.log.level=warn
# ================================================================
Write-Host ""
Write-Host "== 场景 2：-Dmirage.log.level=warn ==" -ForegroundColor Cyan
$s2 = Invoke-Scenario "s2-warn" @("-Dmirage.log.level=warn")

Assert-Match $s2.Out '(?m)^SUMMARY-LEVEL=WARN\s*$' "系统属性生效（level()==WARN）"
Assert-NoMatch $s2.Out 'INFO-MARK' "INFO 被过滤"
Assert-NoMatch $s2.Err 'INFO-MARK' "INFO 不会跑到 stderr"
Assert-NoMatch $s2.Out 'DEBUG-MARK' "DEBUG 被过滤"
Assert-Match $s2.Err 'WARN-MARK' "WARN 仍输出"
Assert-Match $s2.Out '(?m)^\[LS\] stage S4 RegistryMerge start\s*$' "raw 不受级别过滤影响"

# ================================================================
# 场景 3：-Dmirage.log.level=debug
# ================================================================
Write-Host ""
Write-Host "== 场景 3：-Dmirage.log.level=debug ==" -ForegroundColor Cyan
$s3 = Invoke-Scenario "s3-debug" @("-Dmirage.log.level=debug")

Assert-Match $s3.Out '(?m)^SUMMARY-LEVEL=DEBUG\s*$' "系统属性生效（level()==DEBUG）"
Assert-Match $s3.Out '(?m)^\[Mirage\] DEBUG DEBUG-MARK' "DEBUG 打开后输出"
Assert-Match $s3.Out '(?m)^SUMMARY-DEBUG-ENABLED=true\s*$' "isDebugEnabled() 为 true"

# ================================================================
# 场景 4：-Dmirage.log.level=off —— raw 必须仍然输出
# ================================================================
Write-Host ""
Write-Host "== 场景 4：-Dmirage.log.level=off ==" -ForegroundColor Cyan
$s4 = Invoke-Scenario "s4-off" @("-Dmirage.log.level=off")

Assert-Match $s4.Out '(?m)^SUMMARY-LEVEL=OFF\s*$' "系统属性生效（level()==OFF）"
Assert-NoMatch $s4.Out '\[Mirage\]' "OFF 时通道输出全静默（stdout）"
Assert-NoMatch $s4.Err 'WARN-MARK|ERR-CHAIN' "OFF 时 stderr 也静默"
Assert-Match $s4.Out '(?m)^\[LS\] stage S4 RegistryMerge start\s*$' "OFF 时 raw 行仍逐字输出"

# ================================================================
# 场景 5：-Dmirage.log.stack=none —— 只留摘要
# ================================================================
Write-Host ""
Write-Host "== 场景 5：-Dmirage.log.stack=none ==" -ForegroundColor Cyan
$s5 = Invoke-Scenario "s5-nostack" @("-Dmirage.log.stack=none")

$nsChain = Get-Block $s5.Err 'ERR-CHAIN'
Assert-Match $nsChain '(?m)^\[Mirage\] ERROR ERR-CHAIN .*: java\.lang\.IllegalStateException: boom-cause' `
    "stack=none 仍打摘要行（含真实 cause）"
Assert-NoMatch $s5.Err '(?m)^\s+at ' "stack=none 不打栈帧"
Assert-NoMatch $s5.Err 'Caused by' "stack=none 不打 Caused by"

# ================================================================
# 汇总
# ================================================================
Write-Host ""
Write-Host ("=" * 60) -ForegroundColor DarkGray

if ($script:failures.Count -eq 0) {
    Write-Host " 全部通过：$($script:checks) / $($script:checks) 项" -ForegroundColor Green
    Write-Host ("=" * 60) -ForegroundColor DarkGray
    if (-not $Keep) { Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue }
    exit 0
}

$passed = $script:checks - $script:failures.Count
Write-Host " 失败：$($script:failures.Count) 项（通过 $passed / $($script:checks)）" -ForegroundColor Red
Write-Host ("=" * 60) -ForegroundColor DarkGray
$i = 0
foreach ($f in $script:failures) {
    $i++
    Write-Host "$i) $f" -ForegroundColor Red
    Write-Host ""
}

Write-Host "---- 各场景实际输出（便于对照） ----" -ForegroundColor Yellow
foreach ($tag in $script:scenarioLog.Keys) {
    $r = $script:scenarioLog[$tag]
    Write-Host ""
    Write-Host "### $tag  (exit=$($r.Code))" -ForegroundColor Yellow
    Write-Host "--- stdout ---" -ForegroundColor DarkGray
    Write-Host $r.Out
    if ($r.Err.Trim()) {
        Write-Host "--- stderr ---" -ForegroundColor DarkGray
        Write-Host $r.Err
    }
}

Write-Host ""
Write-Host "[提示] 临时目录已保留：$work" -ForegroundColor Cyan
exit 1
