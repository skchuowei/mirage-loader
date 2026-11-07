# scripts/verify_neo_api_surface.ps1
# NeoForge 链接期 API 表面扫描（N0 度量工具，纯离线，不启动游戏）
#
# 作用：
#   把真实 NeoForge mod jar 里对 net/neoforged/* 的类引用（读 .class 常量池）与
#   Mirage compat 模块实际提供的类做差集，输出「引用数 / 缺失数 / 覆盖率 / 缺失清单」。
#
# 为什么这么做：
#   `docs/NeoForge兼容层缺口清单.md` 曾写「P0+P1 全绿」，但从未有真实 mod 通过验收；
#   自建夹具（neop0test/ExpOre/testtab）只碰最小子集。常量池差集能把「文档说齐了」
#   变成可数缺口，并且可以直接当回归基线（新增/删除 stub 都跑一遍）。
#
# 边界：
#   常量池引用 ≠ 全部立即解析（JVM 懒解析），只有执行到的路径才炸；
#   主类构造、@SubscribeEvent 方法签名扫描会立刻触发解析，所以主类/事件类/client 包要优先。
#   本脚本只管链接期，行为兼容率仍要实机矩阵。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_api_surface.ps1
#   powershell ... -ModJars ..\mods-library\neoforge            # 扫目录
#   powershell ... -ModJars a.jar,b.jar                        # 扫指定 jar
#   powershell ... -Reference <build\classes 目录或 fat jar>
#   powershell ... -Baseline ..\docs\neo-api-surface-baseline.json -UpdateBaseline
#   powershell ... -SelfTest                                   # 内置自检（合成 jar）
#   powershell ... -SelfTest -Mutation empty-refs              # 变异检验（应退出 1）
#
# 变异检验（本脚本内置 4 个变异开关，SelfTest 下应全部变红）：
#   empty-refs           Get-NeoRefsFromJar 恒返回空 → 引用数断言红
#   drop-nested          丢掉含 '$' 的嵌套类 → 嵌套类断言红
#   zero-missing         Get-Missing 恒返回空 → 缺失数断言红
#   ignore-new-missing   基线比较恒无新缺失 → 回归检测断言红
#
# 退出码：0 通过；1 自检失败 / 扫描失败 / 基线出现新缺失。

[CmdletBinding()]
param(
    [string[]]$ModJars = @(),
    [string]$Reference = "",
    [string]$Baseline = "",
    [switch]$UpdateBaseline,
    [switch]$SelfTest,
    [switch]$Keep,
    [ValidateSet("none", "empty-refs", "drop-nested", "zero-missing", "ignore-new-missing")]
    [string]$Mutation = "none",
    [string]$ReportFile = (Join-Path $env:TEMP "verify_neo_api_surface.report.txt")
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }
Add-Type -AssemblyName System.IO.Compression | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

$root = Split-Path $PSScriptRoot -Parent
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

# --- 类名集合：目录 ---
function Get-ClassNamesFromDir {
    param([string]$Dir)
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    if (-not (Test-Path $Dir)) { return ,$set }
    $base = (Resolve-Path $Dir).Path.TrimEnd('\', '/')
    Get-ChildItem -Path $Dir -Recurse -Filter *.class -File | ForEach-Object {
        $rel = $_.FullName.Substring($base.Length).TrimStart('\', '/')
        $name = $rel.Replace('\', '/')
        if ($name.EndsWith('.class')) { $name = $name.Substring(0, $name.Length - 6) }
        [void]$set.Add($name)
    }
    return ,$set
}

# --- 类名集合：jar ---
function Get-ClassNamesFromJar {
    param([string]$JarPath)
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.EndsWith('.class')) { continue }
            $name = $e.FullName.Substring(0, $e.FullName.Length - 6)
            [void]$set.Add($name)
        }
    } finally { $zip.Dispose() }
    return ,$set
}

# --- 从一个 mod jar 提取 net/neoforged/* 引用 ---
function Get-NeoRefsFromJar {
    param([string]$JarPath)
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    if ($Mutation -eq 'empty-refs') { return ,$set }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.EndsWith('.class')) { continue }
            $ms = New-Object System.IO.MemoryStream
            $stream = $e.Open()
            try { $stream.CopyTo($ms) } finally { $stream.Dispose() }
            $text = [System.Text.Encoding]::ASCII.GetString($ms.ToArray())
            $ms.Dispose()
            foreach ($m in [regex]::Matches($text, 'net/neoforged/[A-Za-z0-9_/$]+')) {
                $name = $m.Value
                if ($Mutation -eq 'drop-nested' -and $name.Contains('$')) { continue }
                [void]$set.Add($name)
            }
        }
    } finally { $zip.Dispose() }
    return ,$set
}

# --- 缺失 = 引用 - 已提供 ---
function Get-Missing {
    param($Refs, $Provided)
    if ($Mutation -eq 'zero-missing') { return @() }
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($r in $Refs) {
        if (-not $Provided.Contains($r)) { [void]$missing.Add($r) }
    }
    return ,$missing.ToArray()
}

function Get-Coverage {
    param($Refs, $Missing)
    $n = @($Refs).Count
    if ($n -eq 0) { return "n/a" }
    $ok = $n - @($Missing).Count
    return ([math]::Round(100.0 * $ok / $n, 1).ToString() + "%")
}

function Resolve-ReferenceSet {
    param([string]$RefPath)
    if (-not (Test-Path $RefPath)) { throw ("参考路径不存在: " + $RefPath) }
    if (Test-Path -PathType Container $RefPath) {
        return (Get-ClassNamesFromDir $RefPath)
    }
    return (Get-ClassNamesFromJar $RefPath)
}

function Resolve-ModJarList {
    param([string[]]$Inputs)
    $list = New-Object System.Collections.Generic.List[string]
    $paths = @($Inputs)
    if ($paths.Count -eq 0) {
        $defaultDir = Join-Path $root "..\mods-library\neoforge"
        if (Test-Path $defaultDir) { $paths = @($defaultDir) }
    }
    foreach ($p in $paths) {
        if (-not $p) { continue }
        if ([System.IO.Path]::IsPathRooted($p)) {
            $full = [System.IO.Path]::GetFullPath($p)
        } else {
            $full = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $p))
        }
        if (Test-Path -PathType Container $full) {
            Get-ChildItem -Path $full -Filter *.jar -File | ForEach-Object { [void]$list.Add($_.FullName) }
        } elseif (Test-Path -PathType Leaf $full) {
            [void]$list.Add($full)
        } else {
            throw ("找不到 mod jar / 目录: " + $p)
        }
    }
    return ,$list.ToArray()
}

function Invoke-Scan {
    param([string[]]$Jars, $Provided)
    $results = @()
    foreach ($jar in $Jars) {
        $refs = Get-NeoRefsFromJar $jar
        $missing = Get-Missing $refs $Provided
        $results += [pscustomobject]@{
            jar      = $jar
            jarName  = [System.IO.Path]::GetFileName($jar)
            sizeKB   = [math]::Round((Get-Item $jar).Length / 1024)
            refs     = @($refs).Count
            missing  = $missing
            coverage = (Get-Coverage $refs $missing)
        }
    }
    return ,$results
}

function Read-BaselineFile {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $null }
    $raw = Get-Content -Path $Path -Raw -Encoding UTF8
    if (-not $raw) { return $null }
    try {
        return ($raw | ConvertFrom-Json)
    } catch {
        Say ("[WARN] 基线不是合法 JSON，按不存在处理: " + $Path)
        return $null
    }
}

function Write-BaselineFile {
    param([string]$Path, $Results, [string]$RefPath)
    $mods = [ordered]@{}
    foreach ($r in $Results) {
        $mods[$r.jarName] = [ordered]@{
            refs    = @($r.refs)
            missing = @($r.missing)
        }
    }
    $obj = [ordered]@{
        generatedAt = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
        reference   = $RefPath
        mods        = $mods
    }
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($Path, ($obj | ConvertTo-Json -Depth 8),
        (New-Object System.Text.UTF8Encoding($false)))
}

function Compare-Baseline {
    param($Results, $BaselineObj)
    $newMissing = @()
    $resolved = @()
    if ($null -eq $BaselineObj) { return [pscustomobject]@{ newMissing = $newMissing; resolved = $resolved } }
    foreach ($r in $Results) {
        $prop = $BaselineObj.mods.PSObject.Properties[$r.jarName]
        if ($null -eq $prop) { continue }
        $oldMissing = @($prop.Value.missing)
        $curMissing = @($r.missing)
        foreach ($m in $curMissing) {
            if ($oldMissing -notcontains $m) { $newMissing += ($r.jarName + " -> " + $m) }
        }
        foreach ($m in $oldMissing) {
            if ($curMissing -notcontains $m) { $resolved += ($r.jarName + " -> " + $m) }
        }
    }
    if ($Mutation -eq 'ignore-new-missing') { $newMissing = @() }
    return [pscustomobject]@{ newMissing = $newMissing; resolved = $resolved }
}

# --- 造一个只含假 class 的 jar（自检用） ---
function New-FakeJar {
    param([string]$Path, [hashtable]$Entries)
    $fs = [System.IO.File]::Create($Path)
    $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Entries.Keys) {
            $entry = $zip.CreateEntry($name)
            $sw = New-Object System.IO.StreamWriter($entry.Open(), [System.Text.Encoding]::ASCII)
            try { $sw.Write([string]$Entries[$name]) } finally { $sw.Dispose() }
        }
    } finally {
        $zip.Dispose()
        $fs.Dispose()
    }
}

function Invoke-SelfTest {
    Say "== verify_neo_api_surface 自检 =="
    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-neoapi-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $refDir = Join-Path $work "ref"
    New-Item -ItemType Directory -Force -Path (Join-Path $refDir "net\neoforged\fml\common") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $refDir "net\neoforged\bus\api") | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $refDir "net\neoforged\neoforge\event") | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $refDir "net\neoforged\fml\common\Mod.class"), "x")
    [System.IO.File]::WriteAllText((Join-Path $refDir "net\neoforged\bus\api\IEventBus.class"), "x")
    [System.IO.File]::WriteAllText((Join-Path $refDir "net\neoforged\neoforge\event\EntityEvent`$EnteringSection.class"), "x")

    $provided = Get-ClassNamesFromDir $refDir
    Assert-True (@($provided).Count -eq 3) ("自检1 参考目录解析出 3 个类，实际=" + @($provided).Count)
    Assert-True ($provided.Contains("net/neoforged/neoforge/event/EntityEvent`$EnteringSection")) "自检2 参考目录保留嵌套类名（含 `$）"

    $modA = Join-Path $work "modA.jar"
    New-FakeJar $modA ([ordered]@{
        "a/A.class" = "Lnet/neoforged/fml/common/Mod; Lnet/neoforged/bus/api/IEventBus; net/neoforged/api/distmarker/Dist;"
        "a/B.class" = "Lnet/neoforged/neoforge/event/EntityEvent`$EnteringSection;"
    })
    $refsA = Get-NeoRefsFromJar $modA
    Assert-True (@($refsA).Count -eq 4) ("自检3 modA 提取 4 个引用，实际=" + @($refsA).Count)
    Assert-True ($refsA.Contains("net/neoforged/neoforge/event/EntityEvent`$EnteringSection")) "自检4 提取保留嵌套类名"

    $missA = Get-Missing $refsA $provided
    Assert-True (@($missA).Count -eq 1) ("自检5 modA 缺 1 个，实际=" + @($missA).Count)
    Assert-True ($missA -contains "net/neoforged/api/distmarker/Dist") "自检6 缺失项命中 Dist"
    Assert-True ((Get-Coverage $refsA $missA) -eq "75%") ("自检7 覆盖率 75%，实际=" + (Get-Coverage $refsA $missA))

    $modB = Join-Path $work "modB.jar"
    New-FakeJar $modB ([ordered]@{ "b/B.class" = "hello world, no neo refs" })
    $refsB = Get-NeoRefsFromJar $modB
    Assert-True (@($refsB).Count -eq 0) "自检8 无 neo 引用的 jar 返回 0"
    Assert-True ((Get-Coverage $refsB @()) -eq "n/a") "自检9 0 引用时覆盖率显示 n/a"

    # 参考 jar 模式应与目录模式等价
    $refJar = Join-Path $work "ref.jar"
    New-FakeJar $refJar ([ordered]@{
        "net/neoforged/fml/common/Mod.class" = "x"
        "net/neoforged/bus/api/IEventBus.class" = "x"
        "net/neoforged/neoforge/event/EntityEvent`$EnteringSection.class" = "x"
    })
    $providedJar = Get-ClassNamesFromJar $refJar
    Assert-True (@($providedJar).Count -eq 3) "自检10 参考 jar 模式解析出 3 个类"
    Assert-True ((Get-ClassNamesFromDir $refDir).SetEquals($providedJar)) "自检11 目录与 jar 两种参考模式结果一致"

    # 基线：同结果 0 新缺失
    $basePath = Join-Path $work "baseline.json"
    $resultsA = @([pscustomobject]@{ jar="$modA"; jarName="modA.jar"; sizeKB=1; refs=@($refsA).Count; missing=$missA; coverage="75%" })
    Write-BaselineFile $basePath $resultsA $refDir
    $baseObj = Read-BaselineFile $basePath
    Assert-True ($null -ne $baseObj -and $baseObj.mods.PSObject.Properties["modA.jar"] -ne $null) "自检12 基线落盘并可读回"
    $cmpSame = Compare-Baseline $resultsA $baseObj
    Assert-True (@($cmpSame.newMissing).Count -eq 0) "自检13 与基线一致时无新缺失"

    # 回归：多一个缺类 → newMissing 必须命中
    $modA2 = Join-Path $work "modA2.jar"
    New-FakeJar $modA2 ([ordered]@{
        "a/A.class" = "Lnet/neoforged/fml/common/Mod; net/neoforged/api/distmarker/Dist; net/neoforged/fml/ModList;"
    })
    $refsA2 = Get-NeoRefsFromJar $modA2
    $missA2 = Get-Missing $refsA2 $provided
    $resultsA2 = @([pscustomobject]@{ jar="$modA2"; jarName="modA2.jar"; sizeKB=1; refs=@($refsA2).Count; missing=$missA2; coverage="n/a" })
    $cmpNew = Compare-Baseline $resultsA2 ([pscustomobject]@{ mods=[pscustomobject]@{ "modA2.jar"=[pscustomobject]@{ missing=@("net/neoforged/api/distmarker/Dist") } } })
    Assert-True (@($cmpNew.newMissing).Count -eq 1) ("自检14 新缺失被检出，实际=" + @($cmpNew.newMissing).Count)
    Assert-True (@(@($cmpNew.newMissing) | Where-Object { $_ -like "*ModList*" }).Count -eq 1) ("自检15 新缺失指名 ModList，实际=" + (@($cmpNew.newMissing) -join "; "))

    # 修复：少一个缺类 → resolved 必须命中
    $modA3 = Join-Path $work "modA3.jar"
    New-FakeJar $modA3 ([ordered]@{ "a/A.class" = "Lnet/neoforged/fml/common/Mod;" })
    $refsA3 = Get-NeoRefsFromJar $modA3
    $missA3 = Get-Missing $refsA3 $provided
    $resultsA3 = @([pscustomobject]@{ jar="$modA3"; jarName="modA3.jar"; sizeKB=1; refs=@($refsA3).Count; missing=$missA3; coverage="100%" })
    $cmpFix = Compare-Baseline $resultsA3 ([pscustomobject]@{ mods=[pscustomobject]@{ "modA3.jar"=[pscustomobject]@{ missing=@("net/neoforged/api/distmarker/Dist") } } })
    Assert-True (@($cmpFix.resolved).Count -eq 1) ("自检16 已修复缺失被标记 resolved，实际=" + @($cmpFix.resolved).Count)

    if (-not $Keep) {
        try { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue } catch { }
    } else {
        Say ("临时目录保留: " + $work)
    }

    Say ""
    Say ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==")
    if ($script:fail -gt 0) {
        Say "失败项："
        foreach ($f in $script:failures) { Say ("  - " + $f) }
    }
    Save-Report
    Say ("报告: " + $ReportFile)
    if ($script:fail -gt 0) { exit 1 } else { exit 0 }
}

# ============================ 主流程 ============================

if ($SelfTest) { Invoke-SelfTest }

if (-not $Reference) {
    $buildDir = Join-Path $root "modules\mirage-neoforge-compat\build\classes\java\main"
    $fatJar = Join-Path $root "..\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar"
    if (Test-Path $buildDir) {
        $Reference = $buildDir
    } elseif (Test-Path $fatJar) {
        $Reference = $fatJar
    } else {
        Say "[FAIL] 找不到参考类路径（先跑 gradle :modules:mirage-neoforge-compat:build，或用 -Reference 指定）"
        Save-Report
        exit 1
    }
}

if (-not $Baseline) {
    $Baseline = Join-Path $root "docs\neo-api-surface-baseline.json"
}

Say "== NeoForge 链接期 API 表面扫描 =="
Say ("参考类: " + $Reference)

try {
    $provided = Resolve-ReferenceSet $Reference
    $jars = Resolve-ModJarList $ModJars
} catch {
    Say ("[FAIL] " + $_.Exception.Message)
    Save-Report
    exit 1
}

$neoProvided = @($provided | Where-Object { $_ -like 'net/neoforged/*' })
Say ("参考类总数: " + @($provided).Count + "（其中 net/neoforged 前缀 " + $neoProvided.Count + " 个）")
if ($jars.Count -eq 0) {
    Say "[FAIL] 没有找到要扫描的 mod jar（默认目录 ..\mods-library\neoforge，或用 -ModJars 指定）"
    Save-Report
    exit 1
}

Say ""
$results = Invoke-Scan $jars $provided
foreach ($r in $results) {
    Say ("-- " + $r.jarName + "  (" + $r.sizeKB + " KB)")
    Say ("   引用 net/neoforged 类 " + $r.refs + " 个，缺失 " + @($r.missing).Count + " 个，覆盖率 " + $r.coverage)
    if (@($r.missing).Count -gt 0) {
        $sorted = @($r.missing) | Sort-Object
        foreach ($m in $sorted) { Say ("   [缺] " + $m) }
    }
    Say ""
}

if ($UpdateBaseline) {
    Write-BaselineFile $Baseline $results $Reference
    Say ("基线已写入: " + $Baseline)
} else {
    $baselineObj = Read-BaselineFile $Baseline
    if ($null -eq $baselineObj) {
        Say ("[提示] 基线不存在，本次只报告、不判定回归；首次运行请加 -UpdateBaseline 生成: " + $Baseline)
    } else {
        $cmp = Compare-Baseline $results $baselineObj
        if (@($cmp.resolved).Count -gt 0) {
            Say ("[信息] 相对基线已修复 " + @($cmp.resolved).Count + " 个缺失：")
            foreach ($x in $cmp.resolved) { Say ("   [修复] " + $x) }
        }
        if (@($cmp.newMissing).Count -gt 0) {
            Say ("[FAIL] 相对基线出现 " + @($cmp.newMissing).Count + " 个新缺失（链接期回归）：")
            foreach ($x in $cmp.newMissing) { Say ("   [新缺] " + $x) }
            $script:fail += @($cmp.newMissing).Count
            $script:failures += @($cmp.newMissing)
        } else {
            Say "[PASS] 相对基线无新增缺失"
            $script:pass++
        }
    }
}

Say ""
Say ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==")
if ($script:fail -gt 0) {
    Say "失败项："
    foreach ($f in $script:failures) { Say ("  - " + $f) }
}
Save-Report
Say ("报告: " + $ReportFile)

if ($script:fail -gt 0) { exit 1 } else { exit 0 }
