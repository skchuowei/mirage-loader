# scripts/verify_msr_p2_mutation.ps1
# MSR P2 · M4 判据行为层变异检验（落盘 + 单文件重编译 + 字节级还原）
#
# 为什么单独一个脚本：
#   verify_msr_p2.ps1 新增的 17 条 M4 断言全是「行为断言」（读 harness 真实输出）。
#   按 C77 分层约定，行为断言必须用「行为层变异」验 —— 改 RecordingPolicy.java 的
#   判据逻辑、重新编译、让探针真跑到改坏的类，断言必须变红。
#   拿「守卫层变异」（改调用点 / 内存副本）去验行为断言会得出「断言无效」的错误结论。
#
# 为什么用「javac 单文件 + jar uf 副本」而不是全量 fatJar：
#   verify_msr_p2.ps1 的探针跑的是 fat jar（-cp $Jar），不是 build/classes。
#   每个变异都全量 fatJar 太慢；这里只重编译 RecordingPolicy.java（它只依赖
#   fat jar 里已有的类），再用 jar uf 把 3 个 class 覆盖进 fat jar 的临时副本，
#   然后 -Jar <副本> 跑 p2。原 fat jar 不受影响，源码字节级还原。
#
# 铁律（沿用 verify_msr_mutation.ps1）：
#   1. 备份/还原走字节级 ReadAllBytes/WriteAllBytes，绝不 Get-Content/Set-Content（C76）。
#   2. try/finally 保证任何异常都不把变异态留在工作区（C82）。
#   3. 动手前预检全部锚点；还原后校验 byte-identical。
#   4. .ps1 落盘 UTF-8 BOM（PS 5.1 无 BOM 会按 GBK 读中文）。
#   5. 判定用纯 ASCII 片段，不受子进程输出编码影响；双重确认：
#      「harness 真的吐出了坏值」+「至少一条 M4 断言变红」。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p2_mutation.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p2_mutation.ps1 -Mutation drop-prefix
#
# 退出码：0 全部符合预期；1 有变异未按预期变红 / 还原不干净 / 基线未回绿。

[CmdletBinding()]
param(
    [string]$Mutation = "all"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$repo = Split-Path -Parent $PSScriptRoot
$recSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\RecordingPolicy.java"
$verifyScript = Join-Path $repo "scripts\verify_msr_p2.ps1"
$fatJar = Join-Path $repo "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"

$jdk = "C:\Program Files\Java\jdk-25.0.4\bin"
if ($env:JAVA_HOME) { $jdk = Join-Path $env:JAVA_HOME "bin" }
$javac = Join-Path $jdk "javac.exe"
$jarExe = Join-Path $jdk "jar.exe"

foreach ($p in @($recSrc, $verifyScript, $fatJar, $javac, $jarExe)) {
    if (-not (Test-Path $p)) { Write-Host "[FAIL] 找不到：$p" -ForegroundColor Red; exit 1 }
}

$work = Join-Path $env:TEMP ("mirage-p2mut-" + $PID)
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null
$classesDir = Join-Path $work "classes"
$mutJar = Join-Path $work "mut.jar"

# ---- 变异定义：Find / Repl 都是字面量（非正则），避免转义地狱 ----
# ExpectBad = 变异后 harness 应吐出的坏值（纯 ASCII 片段），用于证明「探针真跑到了改坏的类」。
# 注意 Java 拼 boolean 是小写 false/true，别写成 PowerShell 的 False/True：
# 匹配统一用 IgnoreCase 正则，不用 .Contains()（大小写敏感 ⇒ 会假报「断言无效」）。
$mutations = @(
    @{ Name = "drop-prefix"
       Desc = "分类：去掉 missing_fake: 前缀判定"
       Find = 'return err.startsWith("missing_fake:") || err.startsWith("stub_incomplete:");'
       Repl = 'return err.startsWith("stub_incomplete:");'
       ExpectBad = 'missingFake=false' },
    @{ Name = "drop-mcrange"
       Desc = "区间：hasCompleteRanges 不再要求 mcVersionRange"
       Find = 'return d != null && notBlank(d.neoForgeVersionRange) && notBlank(d.mcVersionRange);'
       Repl = 'return d != null && notBlank(d.neoForgeVersionRange);'
       ExpectBad = 'partial=true' },
    @{ Name = "drop-reason"
       Desc = "集成：PREVIOUSLY_UNSUPPORTED 降级成 ALREADY_RECORDED"
       Find = 'return new Decision(false, Reason.PREVIOUSLY_UNSUPPORTED);'
       Repl = 'return new Decision(false, Reason.ALREADY_RECORDED);'
       ExpectBad = 'd3=false:ALREADY_RECORDED' },
    @{ Name = "drop-force"
       Desc = "集成：force 不再无条件录"
       Find = 'if ("force".equalsIgnoreCase(mode)) return new Decision(true, Reason.FORCE);'
       Repl = 'if ("force".equalsIgnoreCase(mode)) return new Decision(false, Reason.FORCE);'
       ExpectBad = 'forced=false:FORCE' },
    @{ Name = "drop-firsttime"
       Desc = "集成：从未录过 + 缺区间不再录"
       Find = 'return new Decision(true, Reason.MISSING_RANGES);'
       Repl = 'return new Decision(false, Reason.MISSING_RANGES);'
       ExpectBad = 'firstTime=false:MISSING_RANGES' }
)

if ($Mutation -ne "all") {
    $picked = @($mutations | Where-Object { $_.Name -eq $Mutation })
    if ($picked.Count -eq 0) {
        Write-Host "[FAIL] 未知变异名：$Mutation" -ForegroundColor Red
        Write-Host ("       可用：all / " + (($mutations | ForEach-Object { $_.Name }) -join " / ")) -ForegroundColor DarkGray
        exit 1
    }
    $mutations = $picked
}

$backup = [System.IO.File]::ReadAllBytes($recSrc)
$hasBom = ($backup.Length -ge 3 -and $backup[0] -eq 0xEF -and $backup[1] -eq 0xBB -and $backup[2] -eq 0xBF)
$origText = [System.IO.File]::ReadAllText($recSrc, [System.Text.Encoding]::UTF8)

function Restore-Bytes {
    [System.IO.File]::WriteAllBytes($recSrc, $backup)
}

# 0) 预检：全部锚点必须先在干净基线里（上一次变异没还原就会全找不到）
$missingAnchors = @($mutations | Where-Object { $origText.IndexOf($_.Find) -lt 0 })
if ($missingAnchors.Count -gt 0) {
    Write-Host "== MSR P2 · M4 行为层变异检验 ==" -ForegroundColor Cyan
    foreach ($a in $missingAnchors) {
        Write-Host ("   [FAIL] 基线里找不到锚点（{0}）：{1}" -f $a.Name, $a.Find) -ForegroundColor Red
    }
    Write-Host "   工作区源码可能还留着上一次变异 —— 先还原 RecordingPolicy.java 再跑。" -ForegroundColor Red
    exit 1
}

# 单文件重编译 + jar uf 覆盖进 fat jar 副本
function Build-Mutant {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    Remove-Item -Recurse -Force $classesDir -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $classesDir | Out-Null
    $c = & $javac -encoding UTF-8 -proc:none -cp $fatJar -d $classesDir $recSrc 2>&1 | Out-String
    $javacCode = $LASTEXITCODE
    $jarCode = -1
    $j = ""
    if ($javacCode -eq 0) {
        Copy-Item $fatJar $mutJar -Force
        $j = & $jarExe uf $mutJar -C $classesDir . 2>&1 | Out-String
        $jarCode = $LASTEXITCODE
    }
    $ErrorActionPreference = $prev
    return @{ Javac = $javacCode; Jar = $jarCode; Log = ($c + $j) }
}

# 跑 p2（-Jar 指定副本），统计总 FAIL 与「M4 断言」FAIL 数
function Run-P2([string]$jarPath) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $verifyScript -Jar $jarPath 2>&1 | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return @{
        Code   = $code
        Fail   = ([regex]::Matches($out, '\[FAIL\]')).Count
        M4Fail = ([regex]::Matches($out, '\[FAIL\] M4 ')).Count
        Out    = $out
    }
}

$allOk = $true
Write-Host "== MSR P2 · M4 行为层变异检验（落盘 + 单文件重编译 + 字节级还原）==" -ForegroundColor Cyan

# 0b) 编译路径自检：未变异源码走同一套 javac + jar uf + 副本 jar 流程，必须全绿。
#     否则「变异变红」有可能只是副本 jar 被这个流程弄坏了，而不是变异真的生效。
Write-Host "-- 编译路径自检（基线源码，副本 jar）" -ForegroundColor Yellow
$selfBuild = Build-Mutant
if ($selfBuild.Javac -ne 0 -or $selfBuild.Jar -ne 0) {
    Write-Host "   [FAIL] 自检编译/打包失败（javac=$($selfBuild.Javac), jar=$($selfBuild.Jar)）" -ForegroundColor Red
    Write-Host $selfBuild.Log -ForegroundColor DarkGray
    exit 1
}
$selfRun = Run-P2 $mutJar
if ($selfRun.Code -eq 0 -and $selfRun.Fail -eq 0 -and $selfRun.M4Fail -eq 0) {
    Write-Host "   [GREEN-OK] 同流程未变异 → 全绿（副本 jar 完好）" -ForegroundColor DarkGreen
} else {
    Write-Host "   [FAIL] 同流程未变异却红（exit=$($selfRun.Code), FAIL=$($selfRun.Fail)）—— 编译路径有问题" -ForegroundColor Red
    exit 1
}

foreach ($mut in $mutations) {
    Write-Host "-- 变异 $($mut.Name)：$($mut.Desc)" -ForegroundColor Yellow

    $mutated = $origText.Replace($mut.Find, $mut.Repl)
    if ($mutated -eq $origText) {
        Write-Host "   [FAIL] 替换没生效（锚点命中但文本未变）" -ForegroundColor Red
        $allOk = $false
        continue
    }
    $enc = New-Object System.Text.UTF8Encoding $hasBom
    [System.IO.File]::WriteAllText($recSrc, $mutated, $enc)

    # try/finally 是硬要求：编译超时 / Ctrl-C 都不能把变异态留在工作区
    try {
        $b = Build-Mutant
        if ($b.Javac -ne 0) {
            Write-Host "   [INFO] 变异被编译器抓住（javac=$($b.Javac)），不算数：" -ForegroundColor DarkYellow
            ($b.Log -split "`r?`n") | Select-Object -First 4 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
            $allOk = $false
            continue
        }
        if ($b.Jar -ne 0) {
            Write-Host "   [FAIL] jar uf 失败（exit=$($b.Jar)）" -ForegroundColor Red
            $allOk = $false
            continue
        }

        $r = Run-P2 $mutJar
        $badSeen = [regex]::IsMatch($r.Out, [regex]::Escape($mut.ExpectBad),
            [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
        if ($r.Code -ne 0 -and $r.M4Fail -gt 0 -and $badSeen) {
            Write-Host ("   [RED-OK] 变红（exit={0}, FAIL={1}, M4FAIL={2}, 坏值 '{3}' 已观测）" -f `
                $r.Code, $r.Fail, $r.M4Fail, $mut.ExpectBad) -ForegroundColor DarkGreen
        } else {
            Write-Host ("   [RED-FAIL] 未按预期变红（exit={0}, FAIL={1}, M4FAIL={2}, 坏值观测={3}）—— 断言无效！" -f `
                $r.Code, $r.Fail, $r.M4Fail, $badSeen) -ForegroundColor Red
            ($r.Out -split "`r?`n") | Where-Object { $_ -match '\[FAIL\]|M4CLASS|M4RANGES|M4DECIDE' } |
                Select-Object -First 8 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
            $allOk = $false
        }
    } finally {
        Restore-Bytes
    }

    $now = [System.IO.File]::ReadAllBytes($recSrc)
    if ([System.Linq.Enumerable]::SequenceEqual([byte[]]$backup, [byte[]]$now)) {
        Write-Host "   [OK] 还原 byte-identical" -ForegroundColor DarkGreen
    } else {
        Write-Host "   [FAIL] 还原后不是 byte-identical！" -ForegroundColor Red
        $allOk = $false
    }
}

# 基线：还原 + 用原 fat jar 跑，必须全绿
Write-Host "-- 基线复核（原 fat jar，应 60/60 全绿）" -ForegroundColor Yellow
$r = Run-P2 $fatJar
if ($r.Code -eq 0 -and $r.Fail -eq 0) {
    Write-Host "   [GREEN-OK] 还原后基线全绿" -ForegroundColor DarkGreen
} else {
    Write-Host "   [GREEN-FAIL] 还原后仍红（exit=$($r.Code), FAIL=$($r.Fail)）" -ForegroundColor Red
    ($r.Out -split "`r?`n") | Where-Object { $_ -match '\[FAIL\]' } |
        Select-Object -First 8 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
    $allOk = $false
}

$final = [System.IO.File]::ReadAllBytes($recSrc)
if ([System.Linq.Enumerable]::SequenceEqual([byte[]]$backup, [byte[]]$final)) {
    Write-Host "   [OK] 源码最终与备份一致" -ForegroundColor DarkGreen
} else {
    Write-Host "   [FAIL] 源码最终与备份不一致！" -ForegroundColor Red
    $allOk = $false
}

Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue

if ($allOk) { Write-Host "`n== 结果：全部符合预期 ==" -ForegroundColor Green; exit 0 }
else { Write-Host "`n== 结果：有变异未按预期变红，或还原不干净 ==" -ForegroundColor Red; exit 1 }
