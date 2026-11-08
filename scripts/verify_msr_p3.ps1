# scripts/verify_msr_p3.ps1
# MSR 门禁 C · P3 注入器深录（行为级 MID）回归
#
# 背景（2026-11-07 起）：
#   P3 的 agent 初版只把 IR 骨架搭起来 —— 插桩点的 id / eventType 恒为空串，
#   即使跑起来 IR 也是空壳。本轮逐条用 javap 核实真实字节码签名后重写：
#     · net/minecraft/core/MappedRegistry#register(ResourceKey, Object, RegistrationInfo)
#     · net/neoforged/bus/EventBus#addListener / register        （注意：不是 api.EventBus）
#     · net/neoforged/bus/EventListenerFactory#create(Method, Object)
#
# 为什么有「合成探针 mod」：
#   P3 观测的是**游戏侧状态**的差集，没有真值就无法判断「漏了还是多了」。
#   scripts/fixtures/msr-probe-mod 注册 1 方块 + 1 物品、订阅 1 个 mod bus 事件
#   + 1 个 game bus 事件，全部写在源码里可逐条核对 ⇒ P3 的输出必须**恰好**是这 4 条。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p3.ps1
#       静态守卫 + diff 逻辑行为断言（秒级，不启动游戏）
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p3.ps1 -Full
#       追加实机：真启动 NeoForge 客户端跑基线+录制+diff（约 3-4 分钟）
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p3.ps1 -Mutation
#       落盘变异检验：把 diff 的订阅键改回「只看事件名」→ 重编译 → 行为断言必须变红
#
# 退出码：0 全绿；1 有断言失败。

[CmdletBinding()]
param(
    [switch]$Full,
    [switch]$Mutation,
    [string]$JavaHome = "",
    [int]$TimeoutSec = 200,
    [string]$ReportFile = (Join-Path $env:TEMP "verify_msr_p3.report.txt")
)

$ErrorActionPreference = "Stop"

$repo = Split-Path -Parent $PSScriptRoot
$agentDir = Join-Path $repo "modules\mirage-msr-agent"
$agentJar = Join-Path $agentDir "build\libs\mirage-msr-agent.jar"
$probeJar = Join-Path $repo "scripts\fixtures\msr-probe-mod\build\msr-probe-mod.jar"
$differSrc = Join-Path $agentDir "src\main\java\io\mirage\msr\agent\BaselineDiffer.java"
$resolverSrc = Join-Path $agentDir "src\main\java\io\mirage\msr\agent\ModIdResolver.java"
$recorderSrc = Join-Path $agentDir "src\main\java\io\mirage\msr\agent\RecorderAgent.java"
$transformerSrc = Join-Path $agentDir "src\main\java\io\mirage\msr\agent\RecorderTransformer.java"
$runnerSrc = Join-Path $repo "scripts\run_msr_p3.ps1"

if (-not $JavaHome) {
    $jdk25 = "C:\Program Files\Java\jdk-25.0.4"
    if (Test-Path $jdk25) { $JavaHome = $jdk25 } else { $JavaHome = $env:JAVA_HOME }
}
$javaExe = Join-Path $JavaHome "bin\java.exe"
if (-not (Test-Path $javaExe)) { throw "找不到 java：$javaExe（用 -JavaHome 指定 JDK 25）" }

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]
$report = New-Object System.Collections.Generic.List[string]
function Log($m) { $report.Add($m) }

function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
        Log "[OK] $desc"
    } else {
        $script:failures.Add($desc) | Out-Null
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        Log "[FAIL] $desc"
    }
}

function Assert-Match([string]$text, [string]$pattern, [string]$desc) {
    $ok = $false
    try { $ok = [bool]([regex]::IsMatch($text, $pattern)) } catch { $ok = $false }
    Assert-True $ok $desc
    if (-not $ok) { Write-Host "          期望匹配 $pattern" -ForegroundColor DarkGray }
}

# 源码必须用 UTF-8 显式读取：PS 5.1 的 Get-Content 无 -Encoding 时走系统 ANSI(GBK)，
# 中文注释会静默乱码，导致「中文断言」永远匹配不上（看起来像守卫失效）。
function Read-Utf8([string]$path) {
    if (-not (Test-Path $path)) { return "" }
    return [System.IO.File]::ReadAllText($path, [System.Text.UTF8Encoding]::new($false))
}

Write-Host "== MSR 门禁 C · P3 行为级 MID ==" -ForegroundColor Cyan

# ================================================================= 静态守卫
Write-Host "-- 静态守卫（agent 插桩面）--" -ForegroundColor Yellow

$tr = Read-Utf8 $transformerSrc
Assert-Match $tr 'net/minecraft/core/MappedRegistry' '插桩点：MappedRegistry 在位'
Assert-Match $tr 'net/neoforged/bus/EventBus' '插桩点：net.neoforged.bus.EventBus（真实现类，非 api 包）'
Assert-Match $tr 'net/neoforged/bus/EventListenerFactory' '插桩点：EventListenerFactory（@SubscribeEvent 主路径）'
Assert-True (-not [regex]::IsMatch($tr, 'net/neoforged/bus/api/EventBus')) '不存在的 api.EventBus 引用已清除'
Assert-Match $tr 'classSlotOfAddListener' 'addListener 的 Class 参数槽位是算出来的（不是硬编码 1）'

$ag = Read-Utf8 $recorderSrc
Assert-Match $ag '(?m)^\s*public static void onRegisterKey' '注册回调入口在位'
Assert-Match $ag 'resolveByRegistrationId' '注册归属用 namespace（栈回溯在注册点上取不到 mod 帧）'
Assert-Match $ag 'extractRegistry' '注册表名从 ResourceKey 取（不是硬编码 root）'

$rs = Read-Utf8 $resolverSrc
Assert-Match $rs '(?m)^\s*public static String resolveByRegistrationId' 'ModIdResolver 提供 namespace 归属'
Assert-Match $rs 'namespaceOf' 'namespace 抽取函数在位'

$df = Read-Utf8 $differSrc
Assert-Match $df 'baseEventKeys\.add\(str\(o, "owner"\) \+ "\|" \+ str\(o, "event"\)\)' 'diff 基线集合按 owner|event 建键'
Assert-Match $df 'baseEventKeys\.contains\(owner \+ "\|" \+ event\)' 'diff 命中判定按 owner|event（这处漏了会静默漏订阅）'
Assert-Match $df 'seenEvent\.add\(owner \+ "\|" \+ event\)' 'diff 记录侧按 owner|event 去重'

$rn = Read-Utf8 $runnerSrc
Assert-True ($rn.Length -gt 0) '运行脚本存在'
# 判定「完成判据里没有 Backend library」必须**剔掉注释行** ——
# 脚本里恰好有一段注释在解释「为什么不能用 Backend library」，
# 直接对全文正则会被自己的注释骗红（守卫误报，本轮实测）。
$rnCode = (($rn -split "`r?`n") | Where-Object { -not $_.TrimStart().StartsWith("#") }) -join "`n"
Assert-True (-not [regex]::IsMatch($rnCode, 'Backend library: LWJGL')) '完成判据不含 Backend library（那是窗口初始化，会提前 kill）'
Assert-Match $rnCode 'Sound engine started' '完成判据含 Sound engine started（注册之后）'
Assert-Match $rnCode 'libraryDirectory' '补齐 FML production 模式的 libraryDirectory'
Assert-Match $rnCode '\-javaagent' 'agent 真的挂上了'

# 自检（本轮踩过的坑，留成守卫）：
# PowerShell 变量名**大小写不敏感**，而 `[switch]$X` 声明的是**强类型变量**。
# 所以脚本里若出现 `$x = <字符串>`（与 switch 参数同名），会抛
# 「无法将 String 转换为 SwitchParameter」，且报错行指向赋值语句，极难定位。
# 只检查 [switch] 参数：非 switch 参数（string/int）赋字符串是合法的。
foreach ($sw in @('Full', 'Mutation')) {
    $badPattern = "(?m)^\s*\`$$sw\s*=(?!=)"
    Assert-True (-not [regex]::IsMatch((Read-Utf8 $PSCommandPath), $badPattern)) "本脚本内无与 switch 参数 `$$sw 同名的局部变量赋值"
}

# ================================================================= 产物检查
Write-Host "-- 产物检查 --" -ForegroundColor Yellow

if (Test-Path $agentJar) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($agentJar)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })
        Assert-True ($names -contains "org/objectweb/asm/ClassReader.class") "agent jar 自包含 ASM（否则运行期 NoClassDefFoundError）"
        Assert-True ($names -contains "io/mirage/msr/agent/RecorderAgent.class") "agent 主体已打包"
        $mf = $zip.Entries | Where-Object { $_.FullName -eq "META-INF/MANIFEST.MF" }
        $mfText = ""
        if ($mf) {
            $sr = New-Object System.IO.StreamReader($mf.Open())
            $mfText = $sr.ReadToEnd(); $sr.Close()
        }
        Assert-True ($mfText -match "Premain-Class:\s*io\.mirage\.msr\.agent\.RecorderAgent") "manifest 带 Premain-Class"
    } finally { $zip.Dispose() }
} else {
    Assert-True $false "agent jar 不存在：$agentJar"
}

if (Test-Path $probeJar) {
    Assert-True $true "探针 mod 已构建"
} else {
    Assert-True $false "探针 mod 不存在：$probeJar（先跑 scripts\build_msr_probe_mod.ps1）"
}

# ================================================================= diff 逻辑行为断言
# 这一段**不需要启动游戏**：直接把两份合成 IR 喂给 BaselineDiffer，
# 断言它按「owner|event」判增量。这样变异检验能在秒级跑通，
# 而不是每次都等 3 分钟实机（且实机失败原因往往与 diff 逻辑无关，噪音大）。
Write-Host "-- diff 逻辑行为断言（合成 IR）--" -ForegroundColor Yellow

$tmp = Join-Path $env:TEMP ("msr-p3-diff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

$evtCommon = "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent"

$baseIr = @{
    schema = "mirage.msr.ir/1"; kind = "baseline"
    registrations = @(
        @{ owner = "unknown"; registry = "minecraft:block"; id = "minecraft:stone" }
    )
    subscriptions = @(
        @{ owner = "neoforge"; event = $evtCommon }
    )
    stats = @{}
} | ConvertTo-Json -Depth 8

# 录制 = 基线 + 1 条新 id（namespace msrprobe）+ 1 条**同事件名但不同 owner** 的订阅
$recIr = @{
    schema = "mirage.msr.ir/1"; kind = "recording"
    registrations = @(
        @{ owner = "unknown"; registry = "minecraft:block"; id = "minecraft:stone" },
        @{ owner = "msrprobe"; registry = "minecraft:block"; id = "msrprobe:probe_block" }
    )
    subscriptions = @(
        @{ owner = "neoforge"; event = $evtCommon },
        @{ owner = "msrprobe"; event = $evtCommon }
    )
    stats = @{}
} | ConvertTo-Json -Depth 8

$basePath = Join-Path $tmp "baseline.json"
$recPath = Join-Path $tmp "recording.json"
$outPath = Join-Path $tmp "diff.json"
[System.IO.File]::WriteAllText($basePath, $baseIr, $utf8NoBom)
[System.IO.File]::WriteAllText($recPath, $recIr, $utf8NoBom)

& $javaExe -cp $agentJar io.mirage.msr.agent.BaselineDiffer $basePath $recPath $outPath | Out-Null
$diffText = ""
if (Test-Path $outPath) { $diffText = [System.IO.File]::ReadAllText($outPath, [System.Text.UTF8Encoding]::new($false)) }

Assert-True ($diffText.Length -gt 0) "BaselineDiffer 产出了增量 JSON"
Assert-True ($diffText -match '"id"\s*:\s*"msrprobe:probe_block"') "增量含新注册项 msrprobe:probe_block"
Assert-True ($diffText -match '"owner"\s*:\s*"msrprobe"') "增量的归属解析为 msrprobe（namespace 而非 unknown）"
# 关键断言：同事件名、不同 owner ⇒ 必须算「新增订阅」。只按事件名比对会漏掉这条。
Assert-True ($diffText -match [regex]::Escape('"event": "' + $evtCommon + '"')) "增量含订阅：同事件名但 owner 不同也算新增"
$newEvCount = 0
try {
    $parsed = $diffText | ConvertFrom-Json
    $newEvCount = @($parsed.newEvents).Count
} catch { }
Assert-True ($newEvCount -eq 1) "新增订阅恰好 1 条（实际 $newEvCount）"

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

# ================================================================= 实机（可选）
if ($Full) {
    Write-Host "-- 实机：真启动 NeoForge 客户端 --" -ForegroundColor Yellow
    $runReport = Join-Path $env:TEMP "verify_msr_p3.run.txt"
    $runArgs = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", (Join-Path $repo "scripts\run_msr_p3.ps1"),
        "-ModJar", $probeJar,
        "-TimeoutSec", "$TimeoutSec",
        "-ReportFile", $runReport,
        # 必须 -Keep：否则一次性实例连同 diff.mirage.json 一起被删，断言无从取证。
        # 断言完成后由本脚本负责清理。
        "-Keep"
    )
    if ($JavaHome) { $runArgs += @("-JavaHome", $JavaHome) }
    # 子进程会把大量 JVM/游戏日志写到 stderr；本脚本是 ErrorActionPreference=Stop，
    # 直接 2>&1 会被判成 NativeCommandError 而**中止脚本**（本轮实测）。
    # 所以：局部放宽 + 子进程输出落日志文件（失败时便于取证）。
    $childLog = Join-Path $env:TEMP "verify_msr_p3.child.log"
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        & powershell @runArgs 2>&1 | Out-File -FilePath $childLog -Encoding utf8
    } finally {
        $ErrorActionPreference = $prevEap
    }

    $diffFile = ""
    $instDir = ""
    if (Test-Path $runReport) {
        $txt = [System.IO.File]::ReadAllText($runReport, [System.Text.UTF8Encoding]::new($false))
        $m = [regex]::Match($txt, '实例保留在:\s*(.+)')
        if (-not $m.Success) { $m = [regex]::Match($txt, '实例:\s*(.+)') }
        if ($m.Success) {
            $instDir = $m.Groups[1].Value.Trim()
            $p = Join-Path $instDir "msr-out\diff.mirage.json"
            if (Test-Path $p) { $diffFile = $p }
        }
    }
    if (-not $diffFile) {
        $latest = Get-ChildItem $env:TEMP -Directory -Filter "mirage-msr-*" -ErrorAction SilentlyContinue |
                  Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($latest) {
            $instDir = $latest.FullName
            $p = Join-Path $latest.FullName "msr-out\diff.mirage.json"
            if (Test-Path $p) { $diffFile = $p }
        }
    }

    Assert-True ($diffFile -ne "") "实机产出 diff.mirage.json"
    if ($diffFile) {
        $d = [System.IO.File]::ReadAllText($diffFile, [System.Text.UTF8Encoding]::new($false))
        Assert-True ($d -match '"registry"\s*:\s*"minecraft:block"[\s\S]*?"id"\s*:\s*"msrprobe:probe_block"') "实机：抓到探测方块"
        Assert-True ($d -match '"registry"\s*:\s*"minecraft:item"[\s\S]*?"id"\s*:\s*"msrprobe:probe_item"') "实机：抓到探测物品"
        Assert-True ($d -match '"event"\s*:\s*"net\.neoforged\.fml\.event\.lifecycle\.FMLCommonSetupEvent"') "实机：抓到 mod bus 订阅"
        Assert-True ($d -match '"event"\s*:\s*"net\.neoforged\.neoforge\.event\.tick\.LevelTickEvent') "实机：抓到 game bus 订阅"
        $cnt = ([regex]::Matches($d, '"id"\s*:\s*"msrprobe:')).Count
        Assert-True ($cnt -eq 2) "实机：新增注册项恰好 2 条（实际 $cnt）"
    }

    # 清理一次性实例（本脚本自己建的，位于 %TEMP%\mirage-msr-*）
    #
    # 两个踩过的坑，都写在这里免得下次再踩：
    #   1) 变量名**不能**叫 `$full`：`[switch]$Full` 是强类型变量，而 PowerShell 变量名
    #      大小写不敏感 ⇒ `$full` 与 `$Full` 是**同一个变量**，给它赋字符串会抛
    #      「无法将 String 转换为 SwitchParameter」。报错行还指向赋值语句本身，
    #      看起来跟参数毫无关系（本轮实测绕了 4 轮才定位）。
    #   2) `Split-Path -LiteralPath X -Leaf` 参数集互斥（-Leaf 属 Path 集）
    #      ⇒ 报「无法使用指定的命名参数解析参数集」。故改用 .NET API。
    if ($instDir) {
        try {
            $instPath = [System.IO.Path]::GetFullPath([string]$instDir)
            $tempRoot = [System.IO.Path]::GetFullPath([string]$env:TEMP)
            $isUnderTemp = $instPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)
            $leaf = [System.IO.Path]::GetFileName($instPath)
            if ($isUnderTemp -and ($leaf -like "mirage-msr-*")) {
                if ([System.IO.Directory]::Exists($instPath)) {
                    [System.IO.Directory]::Delete($instPath, $true)
                }
                Write-Host "   已清理一次性实例：$instPath"
            } else {
                Write-Host "   [INFO] 实例不在 %TEMP%（未清理）：$instPath"
            }
        } catch {
            Write-Host ("   [WARN] 实例清理失败：{0} | instDir={1} | type={2}" -f `
                $_.Exception.Message, $instDir, $_.Exception.GetType().Name)
        }
    }
} else {
    Write-Host "-- 跳过实机（加 -Full 才跑，约 3-4 分钟）--" -ForegroundColor DarkGray
}

# ================================================================= 变异检验
if ($Mutation) {
    Write-Host "-- 变异检验：diff 订阅键退回「只看事件名」--" -ForegroundColor Yellow
    $orig = [System.IO.File]::ReadAllBytes($differSrc)
    $backup = Join-Path $env:TEMP ("BaselineDiffer.java." + [guid]::NewGuid().ToString("N") + ".bak")
    [System.IO.File]::WriteAllBytes($backup, $orig)

    # 复现**历史 bug**：三处都只按事件名建键。
    #   · baseEventKeys.add(...)        —— 基线集合建键
    #   · baseEventKeys.contains(...)   —— 命中判定（这处最容易漏！漏了它
    #                                        变异后仍能算对，检不出来 —— 本轮实测）
    #   · seenEvent.add(...)            —— 记录侧去重
    $pairs = @(
        @{ good = 'baseEventKeys.add(str(o, "owner") + "|" + str(o, "event"))'
           bad  = 'baseEventKeys.add(str(o, "event"))' },
        @{ good = 'baseEventKeys.contains(owner + "|" + event)'
           bad  = 'baseEventKeys.contains(event)' },
        @{ good = 'seenEvent.add(owner + "|" + event)'
           bad  = 'seenEvent.add(event)' }
    )
    $text = [System.Text.Encoding]::UTF8.GetString($orig)
    $allFound = $true
    foreach ($p in $pairs) {
        if (-not $text.Contains($p.good)) { $allFound = $false }
    }
    Write-Host ("   变异锚点：三处都在位={0}" -f $allFound)

    if ($allFound) {
        foreach ($p in $pairs) {
            $text = $text.Replace($p.good, $p.bad)
        }
        # 用字节级写入：绝不能用 Set-Content（PS 5.1 会加 BOM + 走 ANSI，毁源码）
        [System.IO.File]::WriteAllBytes($differSrc, [System.Text.UTF8Encoding]::new($false).GetBytes($text))

        $gradle = "C:\Users\chuowei\.gradle\wrapper\dists\gradle-9.1.0-bin\9agqghryom9wkf8r80qlhnts3\gradle-9.1.0\bin\gradle.bat"
        $env:JAVA_HOME = $JavaHome
        & $gradle ":modules:mirage-msr-agent:jar" --no-daemon -q 2>&1 | Out-Null

        # 重新跑行为断言：此时「同事件名不同 owner」应当被漏掉（新增 = 0）
        $mBase = Join-Path $env:TEMP ("mut-base-" + [guid]::NewGuid().ToString("N") + ".json")
        $mRec = Join-Path $env:TEMP ("mut-rec-" + [guid]::NewGuid().ToString("N") + ".json")
        $mOut = Join-Path $env:TEMP ("mut-out-" + [guid]::NewGuid().ToString("N") + ".json")
        [System.IO.File]::WriteAllText($mBase, $baseIr, $utf8NoBom)
        [System.IO.File]::WriteAllText($mRec, $recIr, $utf8NoBom)
        & $javaExe -cp $agentJar io.mirage.msr.agent.BaselineDiffer $mBase $mRec $mOut | Out-Null
        $mDiff = ""
        if (Test-Path $mOut) { $mDiff = [System.IO.File]::ReadAllText($mOut, [System.Text.UTF8Encoding]::new($false)) }
        $mEv = 0
        try { $mEv = @(($mDiff | ConvertFrom-Json).newEvents).Count } catch { }
        # 「检出变异」= 断言通过（脚本整体应当在变异全检出时 exit 0）
        Assert-True ($mEv -ne 1) "变异被检出：新增订阅数应为 1，变异后为 $mEv（0 = 该订阅被漏掉，正是历史 bug）"

        foreach ($f in @($mBase, $mRec, $mOut)) { Remove-Item $f -Force -ErrorAction SilentlyContinue }

        # 字节级还原 + 重建 + 校验 byte-identical
        [System.IO.File]::WriteAllBytes($differSrc, $orig)
        & $gradle ":modules:mirage-msr-agent:jar" --no-daemon -q 2>&1 | Out-Null
        $now = [System.IO.File]::ReadAllBytes($differSrc)
        $same = ($now.Length -eq $orig.Length)
        if ($same) {
            for ($i = 0; $i -lt $orig.Length; $i++) {
                if ($now[$i] -ne $orig[$i]) { $same = $false; break }
            }
        }
        Assert-True $same "还原后源码 byte-identical"

        # 还原后必须重新变绿
        $gBase = Join-Path $env:TEMP ("green-base-" + [guid]::NewGuid().ToString("N") + ".json")
        $gRec = Join-Path $env:TEMP ("green-rec-" + [guid]::NewGuid().ToString("N") + ".json")
        $gOut = Join-Path $env:TEMP ("green-out-" + [guid]::NewGuid().ToString("N") + ".json")
        [System.IO.File]::WriteAllText($gBase, $baseIr, $utf8NoBom)
        [System.IO.File]::WriteAllText($gRec, $recIr, $utf8NoBom)
        & $javaExe -cp $agentJar io.mirage.msr.agent.BaselineDiffer $gBase $gRec $gOut | Out-Null
        $gDiff = ""
        if (Test-Path $gOut) { $gDiff = [System.IO.File]::ReadAllText($gOut, [System.Text.UTF8Encoding]::new($false)) }
        $gEv = 0
        try { $gEv = @(($gDiff | ConvertFrom-Json).newEvents).Count } catch { }
        Assert-True ($gEv -eq 1) "还原后：新增订阅恢复为 1 条（实际 $gEv）"
        foreach ($f in @($gBase, $gRec, $gOut)) { Remove-Item $f -Force -ErrorAction SilentlyContinue }
    } else {
        Assert-True $false "变异锚点未找到，变异未执行（脚本与源码脱节）"
    }

    Remove-Item $backup -Force -ErrorAction SilentlyContinue
}

# ================================================================= 汇总
Write-Host ""
Write-Host ("断言：{0} 项，失败 {1} 项" -f $script:checks, $script:failures.Count) -ForegroundColor Cyan
Log "断言：$($script:checks) 项，失败 $($script:failures.Count) 项"
if ($script:failures.Count -gt 0) {
    Write-Host "失败清单：" -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host "  - $f" -ForegroundColor Red; Log "  - $f" }
    [System.IO.File]::WriteAllLines($ReportFile, $report, $utf8NoBom)
    exit 1
}

Write-Host "报告：$ReportFile" -ForegroundColor DarkGray
[System.IO.File]::WriteAllLines($ReportFile, $report, $utf8NoBom)
exit 0
