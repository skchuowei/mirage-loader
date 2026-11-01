# ModMixinConfigs 纯 JVM 回归（不启动 Minecraft）—— P1-13 修正版
#
# 用法：powershell -ExecutionPolicy Bypass -File .\scripts\verify_mod_mixin_config.ps1
#
# 做的事：
#   1. javac 现场编译 ModMixinConfigs.java + ModMixinConfigCheck.java 到临时目录并运行，
#      统计 [MMC-CHECK] PASS/FAIL（收集/规范化/去重/缺失检测 15 断言）；
#   2. 源码守卫：TransformStage 必须走收集器、注册名禁止回到"去后缀"老写法、
#      FabricModManifest/ModContainer/ModDiscoverer 的 mixins 透传链在位；
#   3. 变异检验 4 种（M1 缺失检测 / M2 后缀补全 / M3 根目录兜底 / M4 合并去重），
#      每种临时改坏 ModMixinConfigs.java 重编译重跑，断言对应组必须变红，然后还原。
#
# 全绿退出码 0，任何一条红退出码 1。明细报告：%TEMP%\verify_mod_mixin_config.report.txt
# （Write-Host 走信息流，工具/父进程捕获不到，所以明细必须落盘。）

$ErrorActionPreference = "Stop"
$scriptName = "verify_mod_mixin_config"
$reportPath = Join-Path $env:TEMP ($scriptName + ".report.txt")
$report = New-Object System.Collections.Generic.List[string]

function Log($msg) { $report.Add($msg) }

$repo = Split-Path -Parent $PSScriptRoot
$prodSrc   = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\mixin\ModMixinConfigs.java"
$checkSrc  = Join-Path $PSScriptRoot "mixincheck\ModMixinConfigCheck.java"
$stageSrc  = Join-Path $repo "lifecycle-starter\src\main\java\io\mirage\ls\stage\TransformStage.java"
$manifestSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\FabricModManifest.java"
$containerSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\ModContainer.java"
$discovererSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\ModDiscoverer.java"

foreach ($f in @($prodSrc, $checkSrc, $stageSrc, $manifestSrc, $containerSrc, $discovererSrc)) {
    if (-not (Test-Path $f)) { Write-Host "[MMC-VERIFY] 找不到文件: $f" -ForegroundColor Red; exit 1 }
}

# ---- 定位 JDK ----
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\javac.exe"))) {
    foreach ($c in @("C:\Program Files\Java\jdk-25.0.4", "C:\Program Files\Java\jdk-21.0.11")) {
        if (Test-Path (Join-Path $c "bin\javac.exe")) { $javaHome = $c; break }
    }
}
if (-not $javaHome) { Write-Host "[MMC-VERIFY] 找不到 JDK" -ForegroundColor Red; exit 1 }
$javac = Join-Path $javaHome "bin\javac.exe"
$java  = Join-Path $javaHome "bin\java.exe"

$tmp = Join-Path $env:TEMP ("mmc-verify-" + $PID + "-" + (Get-Random))
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

# 编译 + 运行，返回 @{ Ok = bool; Pass = n; Fail = n; Output = ... }
function Invoke-Check {
    $compileOut = & $javac -encoding UTF-8 -nowarn -d $tmp $prodSrc $checkSrc 2>&1
    if ($LASTEXITCODE -ne 0) {
        return @{ Ok = $false; Pass = -1; Fail = -1; Output = ($compileOut | Out-String) }
    }
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java
    $psi.Arguments = "-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$tmp`" ModMixinConfigCheck"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = New-Object System.Text.UTF8Encoding $false
    $psi.StandardErrorEncoding = New-Object System.Text.UTF8Encoding $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()   # 只能读一次，先读流再 WaitForExit
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $passCount = ([regex]::Matches($stdout, "\[MMC-CHECK\] PASS")).Count
    $failCount = ([regex]::Matches($stdout, "\[MMC-CHECK\] FAIL")).Count
    $failedNames = ([regex]::Matches($stdout, "\[MMC-CHECK\] FAIL (\S+)") | ForEach-Object { $_.Groups[1].Value })
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
    $stageText = [IO.File]::ReadAllText($stageSrc)
    $guards = @(
        @{ name = "guard.stage.collector";  ok = $stageText -match "ModMixinConfigs\.collectOne"; why = "TransformStage 必须走收集器" },
        @{ name = "guard.stage.merge";      ok = $stageText -match "ModMixinConfigs\.collectAll"; why = "TransformStage 必须全局合并" },
        @{ name = "guard.stage.no-suffix-strip"; ok = -not ($stageText -match '\.substring\(0, name\.length\(\) - "\.mixin\.json"\.length\(\)\)'); why = "禁止回到'去掉 .json 后缀'的老写法（Mixin 原样查资源，缺后缀必解析失败）" },
        @{ name = "guard.stage.fullname";   ok = $stageText -match '""?\.mixin\.json""?\s*\)\s*\{|\!name\.contains\("/"\)'; why = "根目录盲扫识别必须保留" },
        @{ name = "guard.manifest.mixins";  ok = ([IO.File]::ReadAllText($manifestSrc)) -match "List<JsonElement> mixins"; why = "FabricModManifest 必须有防御式 mixins 字段" },
        @{ name = "guard.manifest.helper";  ok = ([IO.File]::ReadAllText($manifestSrc)) -match "mixinConfigPaths"; why = "FabricModManifest 必须有 mixinConfigPaths()" },
        @{ name = "guard.container.field";  ok = ([IO.File]::ReadAllText($containerSrc)) -match "fabricMixinConfigs"; why = "ModContainer 必须透传 fabricMixinConfigs" },
        @{ name = "guard.discoverer.wire";  ok = ([IO.File]::ReadAllText($discovererSrc)) -match "fabricMixinConfigs\s*=\s*fm\.mixinConfigPaths\(\)"; why = "ModDiscoverer 必须接线 mixins 声明" }
    )
    foreach ($g in $guards) {
        if ($g.ok) { Log ("[2] PASS " + $g.name) } else { Log ("[2] FAIL " + $g.name + " -- " + $g.why); $overallOk = $false }
    }

    # ---- 3. 变异检验（临时改坏 → 期望红 → 还原）----
    $origBytes = [IO.File]::ReadAllBytes($prodSrc)
    $origText = [IO.File]::ReadAllText($prodSrc)

    $mutations = @(
        @{ name = "M1.missing-detection"; patch = @(@{ from = "missing.add(path);"; to = "found.add(path);" }); expect = "declared-missing.reported" },
        @{ name = "M2.json-suffix";       patch = @(@{ from = "p = p + `".json`";"; to = "p = p;" }); expect = "normalize.add-suffix" },
        @{ name = "M3.root-fallback";     patch = @(@{ from = "!declaredNormalized.contains(entry)"; to = "false" }); expect = "root-fallback.collect" },
        @{ name = "M4.merge-dedup";       patch = @(@{ from = "merged.addAll(r.configs);"; to = "if (merged.isEmpty()) merged.addAll(r.configs);" }); expect = "collectAll.dedup-order" }
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
Write-Host ("[MMC-VERIFY] " + $(if ($overallOk) { "ALL GREEN" } else { "RED" }) + " —— 报告: " + $reportPath)
if (-not $overallOk) { exit 1 } else { exit 0 }
