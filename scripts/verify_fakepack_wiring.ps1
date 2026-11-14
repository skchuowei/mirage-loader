<#
.SYNOPSIS
    MSR W1 接线门禁：FakePack 路线 A（缺口探针）+ B（诊断 fallback）的静态守卫 + headless 行为断言。

.DESCRIPTION
    守卫层：行首锚定 (?m)^\s* 核 findClass 三行插入顺序、前缀闸字面量、三道闸、
            defineClass 归属、pack 绝不挂进 MCL 的 URL。
    行为层：构造真实 MirageClassLoader（URL 只挂 compat classes，pack 只经 -D 给 provider），
            起 6 个独立 headless JVM 场景，逐条断言 W0 的 7 条不变量。
            全程不创建窗口、不启动 MC、不写 mc-shili/（AGENTS 第 2 节 (B) 类）。

.EXAMPLE
    .\scripts\verify_fakepack_wiring.ps1
    .\scripts\verify_fakepack_wiring.ps1 -ClassesOverride <变异 classes 副本> -SkipGuard

.NOTES
    退出码：0 全绿；1 有断言失败 / 编译失败 / 环境缺失。
    输出末行固定为 "RESULT PASS n/n" 或 "RESULT FAIL x/y"，供变异脚本解析。
#>
[CmdletBinding()]
param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.4",
    [string]$Repo = "D:\bing\MC\mcmoderr-my\mirage-loader",
    [string]$ClassesOverride = "",
    [switch]$SkipGuard,
    [switch]$NoExitOnFail
)

$ErrorActionPreference = "Continue"
$script:checks = 0
$script:fails = New-Object System.Collections.ArrayList
$report = New-Object System.Collections.ArrayList

function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
        [void]$report.Add("OK   $desc")
    } else {
        [void]$script:fails.Add($desc)
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        [void]$report.Add("FAIL $desc")
    }
}

# Get-Content -Raw 不带 -Encoding 会走系统 ANSI(GBK)，UTF-8 源码里的中文断言会静默失效（C76）
function Read-Utf8([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

function Get-ProbeValue([string]$text, [string]$key) {
    # 不能用 $ 收尾：Out-String 产出的是 \r\n，.NET 多行模式的 $ 落在 \n 前，
    # 中间的 \r 会让 "([^\r\n]*)$" 永远匹配不上（实测 58 条断言全假红）。
    $m = [regex]::Match($text, "(?m)^\s*" + [regex]::Escape($key) + "=([^\r\n]*)")
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    return "<MISSING>"
}
function Get-ProbeInt([string]$text, [string]$key) {
    $v = Get-ProbeValue $text $key
    $n = 0
    if ([int]::TryParse($v, [ref]$n)) { return $n }
    return -999999
}
function Assert-Val([string]$text, [string]$key, [string]$expected, [string]$desc) {
    $v = Get-ProbeValue $text $key
    Assert-True ($v -eq $expected) ("$desc (期望 $key=$expected，实得 $v)")
}
function Assert-Has([string]$text, [string]$needle, [string]$desc) {
    Assert-True ($text.Contains($needle)) $desc
}
function Assert-NotHas([string]$text, [string]$needle, [string]$desc) {
    Assert-True (-not $text.Contains($needle)) $desc
}

# ---------------------------------------------------------------- 环境
$java  = Join-Path $JavaHome "bin\java.exe"
$javac = Join-Path $JavaHome "bin\javac.exe"
if (-not (Test-Path $java)) { Write-Host "[FATAL] 找不到 java.exe: $java"; exit 1 }
if (-not (Test-Path $javac)) { Write-Host "[FATAL] 找不到 javac.exe: $javac"; exit 1 }

$loaderMain  = Join-Path $Repo "core\mirage-loader\build\classes\java\main"
$apiMain     = Join-Path $Repo "api\mirage-api\build\classes\java\main"
$mixinMain   = Join-Path $Repo "core\mirage-mixin\build\classes\java\main"
$eventbusMain= Join-Path $Repo "core\mirage-eventbus\build\classes\java\main"
$compatMain  = Join-Path $Repo "modules\mirage-neoforge-compat\build\classes\java\main"
$fabricMain  = Join-Path $Repo "modules\mirage-fabric-compat\build\classes\java\main"
$fatJar      = Join-Path $Repo "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
$fixture     = Join-Path $Repo "scripts\fixtures\fakepack-wiring"

$effectiveLoader = if ($ClassesOverride -ne "") { $ClassesOverride } else { $loaderMain }

$cpParts = New-Object System.Collections.ArrayList
foreach ($p in @($effectiveLoader, $loaderMain, $apiMain, $mixinMain, $eventbusMain, $compatMain, $fabricMain, $fatJar)) {
    if (Test-Path $p) { [void]$cpParts.Add($p) }
}
$cpCompile = ($cpParts -join ";")

$work = Join-Path $env:TEMP "mirage-w1-wiring"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null
$packDir  = Join-Path $work "pack"
$probeOut = Join-Path $work "probe"
New-Item -ItemType Directory -Force $packDir  | Out-Null
New-Item -ItemType Directory -Force $probeOut | Out-Null

Write-Host "== MSR W1 接线门禁（headless，不拉 GUI / 不写 mc-shili）==" -ForegroundColor Cyan
Write-Host "   effectiveLoader = $effectiveLoader"

# ---------------------------------------------------------------- 编译夹具
Write-Host "-- 编译临时 pack 夹具（4 个类，pack 绝不挂进 MCL URL）--"
$srcFiles = @(Get-ChildItem (Join-Path $fixture "pack-src") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$javacArgs = @("-encoding", "UTF-8", "-nowarn", "-proc:none", "-d", $packDir) + $srcFiles
$packCompile = & $javac @javacArgs 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FATAL] pack 夹具编译失败:`n$packCompile"; exit 1
}
Assert-True (Test-Path (Join-Path $packDir "net\neoforged\wiringprobe\FakeOnly.class")) "夹具：neo FakeOnly.class 已生成"
Assert-True (Test-Path (Join-Path $packDir "net\minecraft\wiringprobe\McFake.class")) "夹具：mc McFake.class 已生成"
Assert-True (Test-Path (Join-Path $packDir "net\neoforged\api\distmarker\Dist.class")) "夹具：pack 版 Dist.class 已生成"

Write-Host "-- 编译 WiringProbe --"
$probeCompile = & $javac "-encoding" "UTF-8" "-nowarn" "-proc:none" "-cp" $cpCompile "-d" $probeOut (Join-Path $fixture "WiringProbe.java") 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FATAL] WiringProbe 编译失败:`n$probeCompile"; exit 1
}
$cpRun = "$probeOut;$cpCompile"

# ---------------------------------------------------------------- 守卫层
$mclSrc    = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader\MirageClassLoader.java"
$trackSrc  = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader\MissingNeoTracker.java"
$packSrc   = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader\RuntimeFakePack.java"
foreach ($p in @($mclSrc, $trackSrc, $packSrc)) {
    if (-not (Test-Path $p)) { Write-Host "[FATAL] 缺源码: $p"; exit 1 }
}
$mcl = Read-Utf8 $mclSrc
$trk = Read-Utf8 $trackSrc
$rfp = Read-Utf8 $packSrc

if (-not $SkipGuard) {
    Write-Host "-- 守卫层（源码行首锚定）--"
    $gRecord = '(?m)^\s*MissingNeoTracker\.recordIfEnabled\(name\);'
    $gFake   = '(?m)^\s*byte\[\] fake = RuntimeFakePack\.findBytesIfEnabled\(name\);'
    $gDefine = '(?m)^\s*return defineClass\(name, fake, 0, fake\.length\);'
    $gSuper  = '(?m)^\s*return super\.findClass\(name\);'
    Assert-True ($mcl -match $gRecord) "GUARD findClass 有路线 A 记账调用"
    Assert-True ($mcl -match $gFake)   "GUARD findClass 有路线 B fallback 调用"
    Assert-True ($mcl -match $gDefine) "GUARD fallback 由 MCL 自己 defineClass"
    Assert-True ($mcl -match $gSuper)  "GUARD 仍保留 super.findClass 抛 CNFE"

    $mR = [regex]::Match($mcl, $gRecord)
    $mF = [regex]::Match($mcl, $gFake)
    $mD = [regex]::Match($mcl, $gDefine)
    $mS = [regex]::Match($mcl, $gSuper)
    Assert-True ($mR.Success -and $mF.Success -and $mD.Success -and $mS.Success -and
                 $mR.Index -lt $mF.Index -and $mF.Index -lt $mD.Index -and $mD.Index -lt $mS.Index) `
                "GUARD 顺序：A 记账 → B 取字节 → defineClass → super.findClass"

    $nullBranch = $mcl.IndexOf("byte[] original = readClassBytes(name);")
    Assert-True ($nullBranch -ge 0 -and $mR.Index -gt $nullBranch) "GUARD 两处调用都插在 readClassBytes 之后的 null 分支"

    Assert-True ($rfp -match '(?m)^\s*public static final String NEO_PREFIX = "net\.neoforged\.";') "GUARD B 前缀常量只认 net.neoforged."
    Assert-True ($rfp -match '(?m)^\s*if \(name == null \|\| !name\.startsWith\(NEO_PREFIX\)\) return null;') "GUARD B findBytes 有窄前缀闸"
    Assert-True ($rfp -match '(?m)^\s*if \(!"on"\.equalsIgnoreCase\(runtime\)\) return false;') "GUARD B 双开关：runtime=on 必给（W3-C 起不再硬性要求 home）"
    Assert-True ($rfp -match '(?m)^\s*if \("off"\.equalsIgnoreCase\(kill\)\) return false;') "GUARD B kill switch 未移除"
    Assert-True ($rfp -match 'f\.startsWith\(ROOT\)') "GUARD B 路径归一化后防 .. 逃逸"
    # 注释里也会出现 "ALLOWED ... net.minecraft."（说明为什么不复用），
    # 所以守卫必须钉「代码定义」而不是关键词，否则注释让守卫误红。
    Assert-True ($rfp -notmatch '(?m)^\s*private static final String\[\] ALLOWED') "GUARD B 没有定义录制端那个含 mc 的 ALLOWED 数组"
    Assert-True ($rfp -notmatch '(?m)^\s*if \(name\.startsWith\("net\.minecraft\.') "GUARD B 前缀闸不含 net.minecraft."

    Assert-True ($trk -match '(?m)^\s*if \(!ENABLED\) return;') "GUARD A 默认关闭：未启用立即返回"
    Assert-True ($trk -match '(?m)^\s*if \(name == null \|\| !name\.startsWith\(NEO_PREFIX\)\) return;') "GUARD A 记账有 neo 前缀闸"
    Assert-True ($trk -match '(?m)^\s*lines\.addAll\(snapshot\(\)\);') "GUARD A 落盘写入完整快照"
    Assert-True ($trk -match 'PROPERTY_OUT') "GUARD A 落盘路径必须显式给"

    $incCount = ([regex]::Matches($mcl, 'LoadProgress\.increment\("mixin\.classes"\)')).Count
    Assert-True ($incCount -eq 1) "GUARD LoadProgress.increment 只在原非 null 分支出现一次"
    Assert-True ($mcl -notmatch 'fakepack\.home') "GUARD MCL 没有把 pack 目录挂进自身 URL"
}

# ---------------------------------------------------------------- 行为层（6 个 headless 场景）
Write-Host "-- 行为层：6 个独立 headless JVM 场景 --"

$scenarioDefs = @(
    @{ Name = "DEFAULT"; Dir = "s0" },
    @{ Name = "A_ON";    Dir = "s1" },
    @{ Name = "B_ON";    Dir = "s2" },
    @{ Name = "B_HALF";  Dir = "s3" },
    @{ Name = "B_KILL";  Dir = "s4" },
    @{ Name = "A_PLUS_B"; Dir = "s5" }
)
$outs = @{}
foreach ($sc in $scenarioDefs) {
    $dir = Join-Path $work $sc.Dir
    New-Item -ItemType Directory -Force $dir | Out-Null
    $outA = Join-Path $dir "missing-neo.txt"
    $outB = Join-Path $dir "fakepack-hits.txt"
    $jvm = New-Object System.Collections.ArrayList
    switch ($sc.Name) {
        "A_ON" {
            [void]$jvm.Add("-Dmirage.msr.missingneo=on")
            [void]$jvm.Add("-Dmirage.msr.missingneo.out=$outA")
        }
        "B_ON" {
            [void]$jvm.Add("-Dmirage.msr.fakepack.home=$packDir")
            [void]$jvm.Add("-Dmirage.msr.fakepack.runtime=on")
            [void]$jvm.Add("-Dmirage.msr.fakepack.out=$outB")
        }
        "B_HALF" {
            [void]$jvm.Add("-Dmirage.msr.fakepack.home=$packDir")
        }
        "B_KILL" {
            [void]$jvm.Add("-Dmirage.msr.fakepack.home=$packDir")
            [void]$jvm.Add("-Dmirage.msr.fakepack.runtime=on")
            [void]$jvm.Add("-Dmirage.msr.fakepack.out=$outB")
            [void]$jvm.Add("-Dmirage.msr.fakeprovider=off")
        }
        "A_PLUS_B" {
            [void]$jvm.Add("-Dmirage.msr.missingneo=on")
            [void]$jvm.Add("-Dmirage.msr.missingneo.out=$outA")
            [void]$jvm.Add("-Dmirage.msr.fakepack.home=$packDir")
            [void]$jvm.Add("-Dmirage.msr.fakepack.runtime=on")
            [void]$jvm.Add("-Dmirage.msr.fakepack.out=$outB")
        }
    }
    # -D 必须整体作为独立参数传给 & ，否则 PowerShell 会把 -D 段当主类参数（C89）
    $runArgs = @() + $jvm.ToArray() + @("-cp", $cpRun, "probe.WiringProbe", $compatMain, $packDir, $outA, $outB)
    # native stderr 经 2>&1 会变成 ErrorRecord，Out-String 会插入「所在位置 行:…」等
    # PowerShell 本地化包装文本，污染输出；先逐个 ToString() 只留消息正文。
    $txt = & $java @runArgs 2>&1 | ForEach-Object { $_.ToString() } | Out-String
    $outs[$sc.Name] = @{ Text = $txt; A = $outA; B = $outB; Exit = $LASTEXITCODE }
    Write-Host ("   [{0}] java exit={1}, {2} 字符输出" -f $sc.Name, $LASTEXITCODE, $txt.Length)
}

$neoFake = "net.neoforged.wiringprobe.FakeOnly"
$mcFake  = "net.minecraft.wiringprobe.McFake"
$ionFake = "io.mirage.neoforge.wiringprobe.CompatFake"
$dist    = "net.neoforged.api.distmarker.Dist"

# ---- S0 默认零变化 ----
$s0 = $outs["DEFAULT"].Text
Write-Host "-- S0 DEFAULT（默认零变化）--"
Assert-Val  $s0 "PACK_DIR_EXISTS" "true" "S0 pack 目录在磁盘上"
Assert-Val  $s0 "COMPAT_DIR_EXISTS" "true" "S0 compat classes 目录在磁盘上"
Assert-Val  $s0 "A_ENABLED" "false" "S0 A 默认关闭"
Assert-Val  $s0 "B_ENABLED" "false" "S0 B 默认关闭"
Assert-Val  $s0 "A_SIZE" "0" "S0 A 零状态"
Assert-Val  $s0 "B_HITS" "0" "S0 B 零状态"
Assert-Val  $s0 "NEO_MISSING" "CNFE" "S0 缺 neo 类仍抛 CNFE（行为零变化）"
Assert-Val  $s0 "MC_MISSING" "CNFE" "S0 缺 mc 类仍抛 CNFE"
Assert-Val  $s0 "ION_MISSING" "CNFE" "S0 缺 io.mirage.neoforge 类仍抛 CNFE"
Assert-Val  $s0 "COMPAT_DIST" "OK:mcl" "S0 compat stub 由 MCL 加载"
Assert-Val  $s0 "COMPAT_PACK_MARKER" "false" "S0 compat 未被 pack 顶掉"
Assert-Val  $s0 "NEO_PING" "ERR:ClassNotFoundException" "S0 pack 类没有偷偷生效"
Assert-Val  $s0 "A_FILE" "false" "S0 A 没写任何文件"
Assert-Val  $s0 "B_FILE" "false" "S0 B 没写任何文件"
Assert-NotHas $s0 "FAKE MODE" "S0 没有 FAKE MODE 横幅（零日志）"
$mb = Get-ProbeInt $s0 "MIXIN_BEFORE"
$md = Get-ProbeInt $s0 "MIXIN_AFTER_DIST"
Assert-True ($md -eq ($mb + 1)) "S0 只有 compat Dist 走 Mixin 管线一次"

# ---- S1 A 开 ----
$s1 = $outs["A_ON"].Text
Write-Host "-- S1 A_ON（缺口探针）--"
Assert-Val  $s1 "A_ENABLED" "true" "S1 A 开关生效"
Assert-Val  $s1 "A_HOOK" "true" "S1 A shutdown hook 已注册"
Assert-Val  $s1 "B_ENABLED" "false" "S1 A 开着不连带打开 B"
Assert-Val  $s1 "NEO_MISSING" "CNFE" "A_RECORD 记账后仍抛 CNFE（不改加载结果）"
Assert-Val  $s1 "A_SIZE" "1" "A_RECORD 恰好记 1 个 neo 缺口"
Assert-Val  $s1 "A_HAS_NEO" "true" "A_RECORD 记账集合含缺的 neo 类"
Assert-Val  $s1 "A_HAS_MC" "false" "A_PREFIX mc 缺失类不记账"
Assert-Val  $s1 "A_HAS_ION" "false" "A_PREFIX io.mirage.neoforge 缺失类不记账"
Assert-Val  $s1 "A_HAS_DIST" "false" "A_RECORD 资源存在的 compat stub 不记账"
Assert-Val  $s1 "A_FLUSH" "1" "A_FLUSH flush 写入 1 条"
Assert-Val  $s1 "A_FILE" "true" "A_FLUSH 清单已落盘"
Assert-Val  $s1 "B_HITS" "0" "S1 B 零命中"
Assert-NotHas $s1 "FAKE MODE" "S1 A 模式没有 FAKE MODE 横幅"

# 清单按 FakeGenCli --only-file 的解析规则复刻（逐行 trim / 跳 # / slash→dot）
$parsed = New-Object System.Collections.ArrayList
if (Test-Path $outs["A_ON"].A) {
    foreach ($l in [System.IO.File]::ReadAllLines($outs["A_ON"].A, [System.Text.Encoding]::UTF8)) {
        $t = $l.Trim()
        if ($t.Length -eq 0 -or $t.StartsWith("#")) { continue }
        [void]$parsed.Add($t.Replace('/', '.'))
    }
}
Assert-True ($parsed.Count -eq 1) "A_FLUSH --only-file 解析得到 1 条类名（实得 $($parsed.Count)）"
Assert-True ($parsed.Contains($neoFake)) "A_FLUSH 清单含真启动实际撞到的 neo 类"
Assert-True (-not $parsed.Contains($mcFake)) "A_PREFIX 清单不含 mc 类"
Assert-True (-not $parsed.Contains($ionFake)) "A_PREFIX 清单不含 io.mirage.neoforge 类"
Assert-True (-not $parsed.Contains($dist)) "A_FLUSH 清单不含资源已存在的 compat stub"

# ---- S2 B 双开关 ----
$s2 = $outs["B_ON"].Text
Write-Host "-- S2 B_ON（诊断 fallback）--"
Assert-Val  $s2 "B_ENABLED" "true" "B_RUNTIME 双开关都给才启用"
Assert-Val  $s2 "B_HOOK" "true" "S2 B shutdown hook 已注册"
Assert-Val  $s2 "NEO_MISSING" "OK:mcl" "B_DEFINE pack 类由 MCL 自己 defineClass"
Assert-Val  $s2 "NEO_PING" "FAKE-OK" "B_DEFINE pack 字节码真能被调用"
Assert-Val  $s2 "B_HITS" "1" "S2 恰好命中 1 个类"
Assert-Val  $s2 "B_HAS_NEO" "true" "S2 命中集合含 neo 类"
Assert-Val  $s2 "B_HAS_MC" "false" "B_PREFIX 命中集合不含 mc 类"
Assert-Val  $s2 "B_HAS_ION" "false" "B_PREFIX 命中集合不含 io.mirage.neoforge 类"
Assert-Val  $s2 "B_HAS_DIST" "false" "S2 compat stub 未走 fallback"
Assert-Val  $s2 "MC_MISSING" "CNFE" "B_PREFIX mc fake 类仍抛 CNFE（窄前缀闸）"
Assert-Val  $s2 "ION_MISSING" "CNFE" "B_PREFIX io.mirage.neoforge fake 类仍抛 CNFE"
Assert-Val  $s2 "COMPAT_DIST" "OK:mcl" "S2 compat stub 加载正常"
Assert-Val  $s2 "COMPAT_PACK_MARKER" "false" "S2 compat 优先：pack 版 Dist 没顶掉真 stub"
Assert-Val  $s2 "B_BANNER" "true" "S2 横幅已打"
Assert-Has $s2 "FAKE MODE 已启用" "S2 FAKE MODE 横幅文本出现在日志"
Assert-Val  $s2 "B_FLUSH" "1" "S2 B 清单写入 1 条"
Assert-Val  $s2 "B_FILE" "true" "S2 B 清单已落盘"
Assert-Val  $s2 "A_ENABLED" "false" "S2 B 开着不连带打开 A"
$mb2 = Get-ProbeInt $s2 "MIXIN_BEFORE"
$ml2 = Get-ProbeInt $s2 "MIXIN_AFTER_LOOKUPS"
$md2 = Get-ProbeInt $s2 "MIXIN_AFTER_DIST"
Assert-True ($ml2 -eq $mb2) "S2 B 提供的类没进 Mixin/LoadProgress 管线"
Assert-True ($md2 -eq ($mb2 + 1)) "S2 只有 compat Dist 让计数 +1"

# ---- S3 单开关 ----
$s3 = $outs["B_HALF"].Text
Write-Host "-- S3 B_HALF（只给 home）--"
Assert-Val  $s3 "B_ENABLED" "false" "B_RUNTIME 只给 home 不生效"
Assert-Val  $s3 "NEO_MISSING" "CNFE" "B_RUNTIME 单开关时 pack 类仍 CNFE"
Assert-Val  $s3 "B_HITS" "0" "B_RUNTIME 单开关零命中"
Assert-NotHas $s3 "FAKE MODE 已启用" "B_RUNTIME 单开关没有横幅"

# ---- S4 kill switch ----
$s4 = $outs["B_KILL"].Text
Write-Host "-- S4 B_KILL（kill switch）--"
Assert-Val  $s4 "B_ENABLED" "false" "B_KILL -Dmirage.msr.fakeprovider=off 能关掉 fallback"
Assert-Val  $s4 "NEO_MISSING" "CNFE" "B_KILL 关闭后 pack 类仍 CNFE"
Assert-Val  $s4 "B_HITS" "0" "B_KILL 关闭后零命中"
Assert-NotHas $s4 "FAKE MODE 已启用" "B_KILL 关闭后没有横幅"

# ---- S5 A+B 同时 ----
$s5 = $outs["A_PLUS_B"].Text
Write-Host "-- S5 A_PLUS_B（A 在 B 之前记账）--"
Assert-Val  $s5 "A_ENABLED" "true" "S5 A 生效"
Assert-Val  $s5 "B_ENABLED" "true" "S5 B 生效"
Assert-Val  $s5 "NEO_MISSING" "OK:mcl" "S5 B 提供了 fake 类"
Assert-Val  $s5 "A_SIZE" "1" "A_RECORD B 提供 fake 时 A 仍如实记账"
Assert-Val  $s5 "A_HAS_NEO" "true" "A_RECORD A 记账顺序在 B 之前"
Assert-Val  $s5 "B_HITS" "1" "S5 B 命中 1 个"

# ---------------------------------------------------------------- 结果
$pass = $script:checks - $script:fails.Count
Write-Host ""
Write-Host "== 门禁结果：PASS $pass / $($script:checks) ==" -ForegroundColor $(if ($script:fails.Count -eq 0) { "Green" } else { "Red" })
if ($script:fails.Count -gt 0) {
    Write-Host "失败项：" -ForegroundColor Red
    foreach ($f in $script:fails) { Write-Host "  - $f" -ForegroundColor Red }
    Write-Output ("RESULT FAIL {0}/{1}" -f $script:fails.Count, $script:checks)
    if ($NoExitOnFail) { return }
    exit 1
}
Write-Output ("RESULT PASS {0}/{0}" -f $script:checks)
if ($NoExitOnFail) { return }
exit 0
