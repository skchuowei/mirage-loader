# FabricDepends 纯 JVM 回归（不启动 Minecraft）—— P1-14
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\verify_mod_depends.ps1
#
# 做的事：
#   1. javac 现场编译 FabricDepends.java + FabricDependsCheck.java 到临时目录并运行，
#      统计 [MDC-CHECK] PASS/FAIL（内置键过滤/可解析/缺失/value 规范化/边界 15 断言）；
#   2. 源码守卫：ModDiscoverer 暂存+split 接线在位、缺失走 WARN 不抛、
#      ModContainer 透传字段在位、DependencySorter 未被改动（硬抛语义保持）；
#   3. 变异检验 4 种（M1 内置键过滤 / M2 缺失收集 / M3 可解析赋值 / M4 value 规范化），
#      每种临时改坏 FabricDepends.java 重编译重跑，断言对应组必须变红，然后还原。
#
# 全绿退出码 0，任何一条红退出码 1。明细报告：%TEMP%\verify_mod_depends.report.txt
# （Write-Host 走信息流，工具/父进程捕获不到，所以明细必须落盘。）

$ErrorActionPreference = "Stop"
$scriptName = "verify_mod_depends"
$reportPath = Join-Path $env:TEMP ($scriptName + ".report.txt")
$report = New-Object System.Collections.Generic.List[string]

function Log($msg) { $report.Add($msg) }

$repo = Split-Path -Parent $PSScriptRoot
$prodSrc  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\depends\FabricDepends.java"
$checkSrc = Join-Path $PSScriptRoot "dependscheck\FabricDependsCheck.java"
$containerSrc  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\ModContainer.java"
$discovererSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\ModDiscoverer.java"
$sorterSrc     = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\DependencySorter.java"

foreach ($f in @($prodSrc, $checkSrc, $containerSrc, $discovererSrc, $sorterSrc)) {
    if (-not (Test-Path $f)) { Write-Host "[MDC-VERIFY] 找不到文件: $f" -ForegroundColor Red; exit 1 }
}

# ---- 定位 JDK ----
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) {
    foreach ($c in @("C:\Program Files\Java\jdk-25.0.4", "C:\Program Files\Java\jdk-21.0.11")) {
        if (Test-Path (Join-Path $c "bin\javac.exe")) { $javaHome = $c; break }
    }
}
if (-not $javaHome) { Write-Host "[MDC-VERIFY] 找不到 JDK" -ForegroundColor Red; exit 1 }
$javac = Join-Path $javaHome "bin\javac.exe"
$java  = Join-Path $javaHome "bin\java.exe"

$tmp = Join-Path $env:TEMP ("mdc-verify-" + $PID + "-" + (Get-Random))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

# 编译 + 运行，返回 @{ Ok = bool; Pass = n; Fail = n; ... }
function Invoke-Check {
    $compileOut = & $javac -encoding UTF-8 -nowarn -d $tmp $prodSrc $checkSrc 2>&1
    if ($LASTEXITCODE -ne 0) {
        return @{ Ok = $false; Pass = -1; Fail = -1; Output = ($compileOut | Out-String); FailedNames = @() }
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$tmp`" io.mirage.loader.depends.FabricDependsCheck"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding $false
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()   # 只能读一次，先读流再 WaitForExit
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $passCount = ([regex]::Matches($stdout, "\[MDC-CHECK\] PASS")).Count
    $failCount = ([regex]::Matches($stdout, "\[MDC-CHECK\] FAIL")).Count
    $failedNames = ([regex]::Matches($stdout, "\[MDC-CHECK\] FAIL (\S+)") | ForEach-Object { $_.Groups[1].Value })
    return @{ Ok = ($proc.ExitCode -eq 0 -and $failCount -eq 0); Pass = $passCount; Fail = $failCount;
               Output = ($stdout + $stderr); FailedNames = $failedNames }
}

$overallOk = $true
try {
    # ---- 1. 正常断言 ----
    Log "=== $scriptName $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') ==="
    $r = Invoke-Check
    if (-not $r.Ok) { $overallOk = $false }
    Log ("[1] 断言: PASS={0} FAIL={1} => {2}" -f $r.Pass, $r.Fail, $(if ($r.Ok) { "绿" } else { "红" }))
    if (-not $r.Ok) { $r.Output -split "`n" | ForEach-Object { Log ("    " + $_.TrimEnd()) } }
    if ($r.Pass -lt 15) { $overallOk = $false; Log "    ! 断言数不足 15，检查程序或被改坏" }

    # ---- 2. 源码守卫 ----
    $discText = [IO.File]::ReadAllText($discovererSrc)
    $guards = @(
        @{ name = "guard.container.field";  ok = ([IO.File]::ReadAllText($containerSrc)) -match "fabricDependsRaw"; why = "ModContainer 必须透传 fabricDependsRaw" },
        @{ name = "guard.discoverer.stash"; ok = $discText -match "fabricDependsRaw\s*=\s*fm\.depends"; why = "ModDiscoverer fabric 分支必须暂存原始 depends" },
        @{ name = "guard.discoverer.split"; ok = $discText -match "FabricDepends\.split"; why = "ModDiscoverer 收尾必须走 FabricDepends.split" },
        @{ name = "guard.discoverer.warn";  ok = $discText -match "声明的依赖未找到"; why = "缺失依赖必须 WARN 指名" },
        @{ name = "guard.discoverer.soft";  ok = -not ($discText -match "depends 检查[\s\S]{0,400}throw new IllegalStateException"); why = "depends 检查必须软策略（WARN 不抛）" },
        @{ name = "guard.depends.builtin";  ok = ([IO.File]::ReadAllText($prodSrc)) -match "BUILTIN_KEYS"; why = "FabricDepends 必须有内置键表" },
        @{ name = "guard.sorter.untouched"; ok = ([IO.File]::ReadAllText($sorterSrc)) -match "缺少依赖"; why = "DependencySorter 语义必须保持不动（NeoForge 硬抛路径）" }
    )
    foreach ($g in $guards) {
        if ($g.ok) { Log ("[2] PASS " + $g.name) } else { Log ("[2] FAIL " + $g.name + " -- " + $g.why); $overallOk = $false }
    }

    # ---- 3. 变异检验（临时改坏 → 期望红 → 还原）----
    $origBytes = [IO.File]::ReadAllBytes($prodSrc)
    $origText = [IO.File]::ReadAllText($prodSrc)

    $mutations = @(
        @{ name = "M1.builtin-filter";  patch = @(@{ from = "if (BUILTIN_KEYS.contains(lower))"; to = "if (false)" }); expect = "builtin.all-ignored" },
        @{ name = "M2.missing-collect"; patch = @(@{ from = "out.missing.add(key);"; to = ";" }); expect = "missing.reported" },
        @{ name = "M3.resolvable-put";  patch = @(@{ from = "out.resolvable.put(key, normalizeValue(rawDepends.get(key)));"; to = "out.missing.add(key);" }); expect = "resolvable.simple" },
        @{ name = "M4.value-normalize"; patch = @(@{ from = 'if (v instanceof String s) return s;'; to = 'if (v instanceof String s) return "";' }); expect = "value.string-preserved" }
    )

    foreach ($m in $mutations) {
        $text = $origText
        foreach ($p in $m.patch) {
            if (-not $text.Contains($p.from)) {
                Log ("[3] FAIL " + $m.name + " -- 变异源串未命中: " + $p.from)
                $overallOk = $false; continue
            }
            $text = $text.Replace($p.from, $p.to)
        }
        # 落盘变异版（.java 不带 BOM）
        [IO.File]::WriteAllText($prodSrc, $text, (New-Object System.Text.UTF8Encoding $false))
        $mr = Invoke-Check
        $red = ($mr.Fail -gt 0) -and ($mr.FailedNames -contains $m.expect)
        if ($red) { Log ("[3] PASS " + $m.name + " （变异后 '" + $m.expect + "' 正确变红）") }
        else {
            $overallOk = $false
            Log ("[3] FAIL " + $m.name + " -- 期望 '" + $m.expect + "' 变红，实际 FAIL 组=[" + ($mr.FailedNames -join ", ") + "]")
        }
        # 还原
        [IO.File]::WriteAllBytes($prodSrc, $origBytes)
    }

    # 还原后终验：必须回到绿
    $fr = Invoke-Check
    if (-not $fr.Ok) { $overallOk = $false; Log ("[4] FAIL 还原后终验仍红: PASS=" + $fr.Pass + " FAIL=" + $fr.Fail) }
    else { Log ("[4] PASS 还原后终验回绿 (PASS=" + $fr.Pass + ")") }
}
finally {
    Remove-Item -Recurse -Force -Path $tmp -ErrorAction SilentlyContinue
}

Log ("=== 结果: " + $(if ($overallOk) { "全绿" } else { "有红" }) + " ===")
[IO.File]::WriteAllLines($reportPath, $report, (New-Object System.Text.UTF8Encoding $false))
Write-Host ("[MDC-VERIFY] " + $(if ($overallOk) { "ALL GREEN" } else { "RED" }) + " —— 报告: " + $reportPath)
if (-not $overallOk) { exit 1 } else { exit 0 }
