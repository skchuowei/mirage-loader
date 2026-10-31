# MGScript 纯 JVM 回归（不启动 Minecraft）
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\verify_mgscript.ps1
#
# 做的事：用本地 JDK 把引擎源码 + 检查程序现场编译到临时目录，跑全部断言，
# 统计 [MGS-CHECK] PASS/FAIL 行。全绿退出码 0，任何一条红退出码 1。
#
# 为什么要现场 javac 而不是走 gradle：这条链要能在「只有 JDK、没有 gradle」的机器上
# 单独跑，才能作为改动后的第一道闸；gradle 留给完整构建。
#
# 变异检验（改完引擎后至少做一次）：
#   把 modules/mirage-mgscript/.../runtime/Values.java 里 RangeValue 的
#   `inclusiveEnd ? to + 1 : to` 改成 `to`，重跑本脚本 → range-inclusive 必须变红。

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$engineSrc = Join-Path $repo "modules\mirage-mgscript\src\main\java"
$checkSrc = Join-Path $PSScriptRoot "mgscript\MgScriptCheck.java"

if (-not (Test-Path $engineSrc)) { Write-Host "[MGS-VERIFY] 找不到引擎源码: $engineSrc" -ForegroundColor Red; exit 1 }
if (-not (Test-Path $checkSrc))  { Write-Host "[MGS-VERIFY] 找不到检查程序: $checkSrc" -ForegroundColor Red; exit 1 }

# ---- 定位 JDK ----
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) {
    $candidates = @(
        "C:\Program Files\Java\jdk-25.0.4",
        "C:\Program Files\Java\jdk-21.0.11",
        "C:\Program Files\Java\latest"
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "bin\javac.exe")) { $javaHome = $c; break }
    }
}
if (-not $javaHome) {
    Write-Host "[MGS-VERIFY] 找不到 JDK（设置 JAVA_HOME 或装 JDK 21+）" -ForegroundColor Red
    exit 1
}
$javac = Join-Path $javaHome "bin\javac.exe"
$java  = Join-Path $javaHome "bin\java.exe"
Write-Host "[MGS-VERIFY] JDK = $javaHome"

$tmp = Join-Path $env:TEMP ("mgs-verify-" + $PID + "-" + (Get-Random))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

try {
    # ---- 编译 ----
    # 只编译纯引擎（lex/parse/ast/runtime + ScriptEngine/ScriptError）。
    # io/mirage/mgscript/mc/ 是 MC 绑定层，import net.minecraft.*，
    # 没有 MC 类路径编译不了，也不属于"纯 JVM 回归"的范畴（那里由 gradle 构建覆盖）。
    $sources = @(Get-ChildItem -Path $engineSrc -Recurse -Filter *.java |
        Where-Object { $_.FullName -notmatch '\\io\\mirage\\mgscript\\mc\\' } |
        ForEach-Object { $_.FullName })
    $sources += $checkSrc
    Write-Host "[MGS-VERIFY] 编译 $($sources.Count) 个源文件 ..."

    $javacArgs = @("-encoding", "UTF-8", "-nowarn", "-d", $tmp) + $sources
    $compileOut = & $javac $javacArgs 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[MGS-VERIFY] javac 失败：" -ForegroundColor Red
        $compileOut | ForEach-Object { Write-Host "  $_" }
        exit 1
    }

    # ---- 运行（ProcessStartInfo + UTF8，避免 Start-Process 复制环境时的 http_proxy 冲突）----
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$tmp`" MgScriptCheck"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding $false
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $exitCode = $proc.ExitCode

    $lines = @($stdout -split "`r?`n" | Where-Object { $_ -ne "" })
    $lines | ForEach-Object { Write-Host "  $_" }
    if ($stderr.Trim() -ne "") { Write-Host "[MGS-VERIFY] stderr:" -ForegroundColor Yellow; Write-Host $stderr }

    # ---- 断言 ----
    # 必须用 -cmatch（区分大小写）：-match 是大小写不敏感的，
    # " failed=0" 会被 " FAIL" 匹配到，导致全绿也报失败（本轮实际踩过）。
    $failLines = @($lines | Where-Object { $_ -cmatch " FAIL" })
    $passLines = @($lines | Where-Object { $_ -cmatch " PASS$" })
    $summary   = $lines | Where-Object { $_ -match "SUMMARY passed=" } | Select-Object -Last 1

    $problems = New-Object System.Collections.Generic.List[string]
    if ($exitCode -ne 0) { $problems.Add("退出码 $exitCode（期望 0）") }
    if ($failLines.Count -gt 0) { $problems.Add("$($failLines.Count) 个用例失败") }
    # PASS 行是「用例」级（14 个用例），SUMMARY 里的 passed 是「断言」级（27+），
    # 两个口径不同：这里只把用例数做下限保险，断言失败由 failed=0 兜住。
    if ($passLines.Count -lt 10) { $problems.Add("通过用例只有 $($passLines.Count) 个（期望 ≥10，检查程序是否跑完）") }
    if (-not $summary) { $problems.Add("没有 SUMMARY 行（进程可能中途崩溃）") }
    elseif ($summary -notmatch "failed=0") { $problems.Add("SUMMARY 显示有失败：$summary") }

    Write-Host ""
    if ($problems.Count -eq 0) {
        Write-Host "[MGS-VERIFY] 通过：$($passLines.Count) 个用例全绿" -ForegroundColor Green
        exit 0
    } else {
        Write-Host "[MGS-VERIFY] 失败：" -ForegroundColor Red
        $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
        exit 1
    }
}
finally {
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue }
}
