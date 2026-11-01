# scripts/measure_startup.ps1
# measure_startup.ps1 —— 启动性能与体积基线测量（Phase 1 验收标准 6「性能与体积基线」）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -LastSession
#   powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -SelfTest
#   powershell -ExecutionPolicy Bypass -File .\scripts\measure_startup.ps1 -OutFile ..\docs\perf-baseline.md
#
# 为什么要有这个脚本：
#   docs/perf-baseline.md 第一版是人肉翻日志手抄的，踩了三个坑——
#     ① 分阶段表漏了 S7（实测 34.9~68.1 s，比 S4 还大），因为"S7 Loaded"被当成终点而不是阶段；
#     ② "端到端"和"到 TitleScreen"混为一谈：S7 done 只是 mod 管线结束，
#        此时 MC 的资源重载还没走完，用户还不能操作游戏；
#     ③ 峰值内存标着"未测"，因为 AI 不自行启动游戏（项目规则），而人肉采样又麻烦。
#   这个脚本把三件事自动化：解析分阶段耗时、算出"到 TitleScreen"的真值、直接报告**还测不了什么**。
#
# 数据来源：
#   <gameDir>/logs/mirage-launch.log —— Launcher 的 tee 旁路（早期输出只进这里，
#   latest.log 里没有 S1–S3，见 docs/经验与踩坑.md C21）。
#   锚点行格式（由 io.mirage.loader.MiragePerf 打出，冻结）：
#       [LS] perf <label> t=<ms> heapUsed=<MB> heapPeak=<MB>
#
# 自检（-SelfTest）：
#   用合成日志跑同一份解析代码，断言分段耗时/端到端/跨天/缺锚点检测都正确。
#   合成样本与真实日志走同一条解析路径——自检测的就是真逻辑，不是"另一份实现"。
#
# 变异检验（做过的）：
#   ① 把段定义里的 "s4.version" 改成 "s4.versionX" → 分段断言红；
#   ② 把端到端计算里的跨天补偿去掉 → 跨天断言红；
#   ③ 把缺锚点检测的 labels 数组清空 → 缺口检测断言红。

[CmdletBinding()]
param(
    [string]$GameDir,
    [string]$LogPath,
    [string]$JarPath,
    [string]$OutFile,
    [switch]$LastSession,
    [switch]$SelfTest,
    [int]$PeakMemoryPid = 0
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
if (-not $GameDir) {
    # 仓库上一级是 mc-shili/（用户的 MC 目录，只读不改）
    $GameDir = Join-Path (Split-Path $root -Parent) "mc-shili\versions\26.2-mirage"
}
if (-not $LogPath) { $LogPath = Join-Path $GameDir "logs\mirage-launch.log" }
if (-not $JarPath) {
    $JarPath = Join-Path (Split-Path $root -Parent) "mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar"
}

# ---------------------------------------------------------------------------
# 段定义：相邻锚点之间的耗时。表驱动，改埋点顺序时只改这里。
# 这里的每一段都对应"谁的开销"，不是"哪个阶段慢"。
# ---------------------------------------------------------------------------
$script:Segments = @(
    @{ from = "s4.begin";         to = "s4.version";         label = "S4 SharedConstants.tryDetectVersion" },
    @{ from = "s4.version";       to = "s4.bootstrap";       label = "S4 Bootstrap.bootStrap (MC 原版)" },
    @{ from = "s4.bootstrap";     to = "s4.unfreeze";        label = "S4 解冻 MappedRegistry" },
    @{ from = "s4.unfreeze";      to = "s4.backend";         label = "S4 装配方" },
    @{ from = "s7.begin";         to = "s7.pre";             label = "S7 Mirage 侧前置开销" },
    @{ from = "s7.pre";           to = "early.handoff.begin"; label = "S7 MC 窗口创建前" },
    @{ from = "early.handoff.begin"; to = "early.handoff.lock"; label = "早期窗口 等 renderLock" },
    @{ from = "early.handoff.lock";  to = "early.handoff.thread"; label = "早期窗口 等渲染线程退出" },
    @{ from = "early.handoff.thread"; to = "early.handoff.done"; label = "早期窗口 GL 交接" },
    @{ from = "early.handoff.done"; to = "titlescreen";       label = "MC 资源重载 → TitleScreen" }
)

# 必须存在的锚点分组：缺了就报"这个维度测不了"，而不是静默少一行
$script:RequiredGroups = @(
    @{ name = "S4 分段";  labels = @("s4.begin", "s4.version", "s4.bootstrap", "s4.unfreeze", "s4.backend") },
    @{ name = "S7 分段";  labels = @("s7.begin", "s7.pre") },
    @{ name = "早期窗口交接"; labels = @("early.handoff.begin", "early.handoff.lock", "early.handoff.thread", "early.handoff.done") },
    @{ name = "TitleScreen 锚点"; labels = @("titlescreen") }
)

# ---------------------------------------------------------------------------
# 解析
# ---------------------------------------------------------------------------

function Get-Sessions {
    <#
      把日志行解析成会话数组。每个会话：
        version / bannerTime(墙钟) / stages(哈希: S1..S7 → 名字+doneMs+doneWall)
        anchors(数组: label/t/heapUsed/heapPeak) / lines
    #>
    param([string[]]$Lines)

    $sessions = @()
    $cur = $null

    foreach ($line in $Lines) {
        # 会话边界：===== Mirage Launch 0.4.0 @ 2026-10-31 17:59:14 =====
        if ($line -match '=====\s*Mirage Launch\s+(\S+)\s+@\s+(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})\s*=====') {
            $cur = @{
                version    = $Matches[1]
                bannerTime = $Matches[2]
                stages     = @{}
                anchors    = @()
                lines      = 0
            }
            $sessions += ,$cur
            continue
        }
        if ($null -eq $cur) { continue }
        $cur.lines++

        # 墙钟时间戳（只有 log4j 接管后的行才有前缀）
        $wall = $null
        if ($line -match '^\[(\d{2}:\d{2}:\d{2})\]') { $wall = $Matches[1] }

        # 阶段骨架行：[LS] stage S4 RegistryMerge done (7433 ms)
        if ($line -match '\[LS\]\s+stage\s+(S\d+)\s+(\S+)\s+(start|done)(?:\s+\((\d+)\s*ms\))?') {
            $sid = $Matches[1]
            $sname = $Matches[2]
            $kind = $Matches[3]
            if (-not $cur.stages.ContainsKey($sid)) {
                $cur.stages[$sid] = @{ id = $sid; name = $sname; doneMs = $null; doneWall = $null }
            }
            if ($kind -eq "done") {
                $cur.stages[$sid].doneMs = [int]$Matches[4]
                $cur.stages[$sid].doneWall = $wall
            }
        }

        # 性能锚点：[LS] perf s4.bootstrap t=7000 heapUsed=200 heapPeak=200
        if ($line -match '\[LS\]\s+perf\s+(\S+)\s+t=(\d+)\s+heapUsed=(\d+)\s+heapPeak=(\d+)') {
            $cur.anchors += @{
                label     = $Matches[1]
                t         = [int]$Matches[2]
                heapUsed  = [int]$Matches[3]
                heapPeak  = [int]$Matches[4]
            }
        }
    }
    return $sessions
}

function Get-SpanMs {
    <# 两个墙钟时间（banner 为 "yyyy-MM-dd HH:mm:ss"，另一端为 "HH:mm:ss"）之间的毫秒数。跨天自动 +24h。 #>
    param([string]$From, [string]$To)
    if (-not $From -or -not $To) { return $null }
    $f = [datetime]::ParseExact($From, "yyyy-MM-dd HH:mm:ss", [System.Globalization.CultureInfo]::InvariantCulture)
    $p = $To.Split(":")
    $t = New-Object System.DateTime($f.Year, $f.Month, $f.Day, [int]$p[0], [int]$p[1], [int]$p[2])
    if ($t -lt $f) { $t = $t.AddDays(1) }
    return [int](($t - $f).TotalMilliseconds)
}

function Get-AnchorMap {
    param($Session)
    $map = @{}
    foreach ($a in $Session.anchors) { if (-not $map.ContainsKey($a.label)) { $map[$a.label] = $a } }
    return $map
}

function Get-SegmentRows {
    <# 按段定义表算出各段耗时。锚点缺失的段返回 null（报告里显示"缺"）。 #>
    param($Session)
    $map = Get-AnchorMap -Session $Session
    $rows = @()
    foreach ($seg in $script:Segments) {
        $ms = $null
        if ($map.ContainsKey($seg.from) -and $map.ContainsKey($seg.to)) {
            $ms = [int]($map[$seg.to].t - $map[$seg.from].t)
        }
        $rows += @{ label = $seg.label; ms = $ms; from = $seg.from; to = $seg.to }
    }
    return $rows
}

function Get-MissingGroups {
    param($Session)
    $map = Get-AnchorMap -Session $Session
    $missing = @()
    foreach ($g in $script:RequiredGroups) {
        $absent = @()
        foreach ($l in $g.labels) { if (-not $map.ContainsKey($l)) { $absent += $l } }
        if ($absent.Count -gt 0) { $missing += @{ group = $g.name; absent = $absent } }
    }
    return $missing
}

function Get-SessionReport {
    param($Session)
    $stages = @()
    foreach ($k in ($Session.stages.Keys | Sort-Object)) {
        $st = $Session.stages[$k]
        $stages += @{ id = $st.id; name = $st.name; ms = $st.doneMs }
    }
    $e2e = $null
    if ($Session.stages.ContainsKey("S7") -and $Session.stages["S7"].doneWall) {
        $e2e = Get-SpanMs -From $Session.bannerTime -To $Session.stages["S7"].doneWall
    }
    $map = Get-AnchorMap -Session $Session
    $toTitle = $null
    if ($map.ContainsKey("titlescreen")) { $toTitle = [int]$map["titlescreen"].t }
    $heapPeak = 0
    foreach ($a in $Session.anchors) { if ($a.heapPeak -gt $heapPeak) { $heapPeak = $a.heapPeak } }
    return @{
        version    = $Session.version
        bannerTime = $Session.bannerTime
        stages     = $stages
        segments   = (Get-SegmentRows -Session $Session)
        e2eMs      = $e2e
        toTitleMs  = $toTitle
        heapPeakMB = $heapPeak
        missing    = (Get-MissingGroups -Session $Session)
        anchorCount = $Session.anchors.Count
    }
}

function Format-SessionReport {
    param($Report, [int]$Index)

    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine("### 会话 $Index（Mirage $($Report.version)，横幅 $($Report.bannerTime)）")
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("| 项 | 值 |")
    [void]$sb.AppendLine("|---|---|")

    $stageTxt = @()
    foreach ($s in $Report.stages) {
        $v = if ($null -eq $s.ms) { "缺" } else { "$($s.ms) ms" }
        $stageTxt += "$($s.id) $($s.name) = $v"
    }
    [void]$sb.AppendLine("| 分阶段（LS 骨架行自带耗时） | " + ($stageTxt -join "；") + " |")

    $e2e = if ($null -eq $Report.e2eMs) { "缺（缺 S7 done 的墙钟时间戳）" } else { "$($Report.e2eMs) ms" }
    [void]$sb.AppendLine("| 端到端（横幅 → S7 done） | $e2e |")

    $tt = if ($null -eq $Report.toTitleMs) { "缺（无 TitleScreen 锚点）" } else { "$($Report.toTitleMs) ms" }
    [void]$sb.AppendLine("| **到 TitleScreen**（用户能操作游戏） | $tt |")

    [void]$sb.AppendLine("| 堆峰值（锚点内最大 heapPeak） | $($Report.heapPeakMB) MB |")
    [void]$sb.AppendLine("| 锚点数 | $($Report.anchorCount) |")
    [void]$sb.AppendLine()
    [void]$sb.AppendLine("| 段 | 耗时 |")
    [void]$sb.AppendLine("|---|---:|")
    foreach ($seg in $Report.segments) {
        $v = if ($null -eq $seg.ms) { "缺锚点" } else { "$($seg.ms) ms" }
        [void]$sb.AppendLine("| $($seg.label) | $v |")
    }
    if ($Report.missing.Count -gt 0) {
        [void]$sb.AppendLine()
        [void]$sb.AppendLine("**本会话测不了的维度**：")
        foreach ($m in $Report.missing) {
            [void]$sb.AppendLine("- $($m.group)：缺锚点 $($m.absent -join ", ")")
        }
    }
    return $sb.ToString()
}

# ---------------------------------------------------------------------------
# 自检
# ---------------------------------------------------------------------------

function Invoke-SelfTest {
    $script:pass = 0
    $script:fail = 0
    $script:failures = @()

    function Assert-True {
        param([bool]$Cond, [string]$Msg)
        if ($Cond) {
            $script:pass++
            Write-Host ("  [PASS] " + $Msg) -ForegroundColor DarkGreen
        } else {
            $script:fail++
            $script:failures += $Msg
            Write-Host ("  [FAIL] " + $Msg) -ForegroundColor Red
        }
    }

    Write-Host "== measure_startup 自检（合成日志，不依赖真实日志） ==" -ForegroundColor Cyan

    # 样本 1：完整会话（含全部锚点）
    $sample1 = @(
        "===== Mirage Launch 0.4.0 @ 2026-10-31 17:59:14 =====",
        "[LS] perf launch t=0 heapUsed=1 heapPeak=1",
        "[LS] stage S1 Discovery start",
        "[LS] stage S1 Discovery done (97 ms)",
        "[LS] stage S4 RegistryMerge start",
        "[LS] perf s4.begin t=200 heapUsed=10 heapPeak=10",
        "[LS] perf s4.version t=250 heapUsed=20 heapPeak=20",
        "[LS] perf s4.bootstrap t=7000 heapUsed=200 heapPeak=200",
        "[LS] perf s4.unfreeze t=7100 heapUsed=210 heapPeak=210",
        "[LS] perf s4.backend t=7200 heapUsed=215 heapPeak=215",
        "[LS] stage S4 RegistryMerge done (7433 ms)",
        "[LS] stage S7 Loaded start",
        "[LS] perf s7.begin t=8000 heapUsed=300 heapPeak=300",
        "[LS] perf s7.pre t=8100 heapUsed=310 heapPeak=310",
        "[LS] perf early.handoff.begin t=8500 heapUsed=320 heapPeak=320",
        "[LS] perf early.handoff.lock t=9000 heapUsed=320 heapPeak=320",
        "[LS] perf early.handoff.thread t=9200 heapUsed=320 heapPeak=320",
        "[LS] perf early.handoff.done t=9300 heapUsed=320 heapPeak=320",
        "[LS] perf titlescreen t=40000 heapUsed=800 heapPeak=1200",
        "[17:59:59] [Render thread/INFO]: [STDOUT]: [LS] stage S7 Loaded done (34972 ms)"
    )

    $s1 = @(Get-Sessions -Lines $sample1)
    Assert-True ($s1.Count -eq 1) "样本1 切出 1 个会话（实际 $($s1.Count)）"

    $r1 = Get-SessionReport -Session $s1[0]
    Assert-True ($r1.stages.Count -eq 3) "样本1 解析出 3 个阶段（S1/S4/S7）"
    Assert-True ($r1.stages[1].ms -eq 7433) "S4 阶段耗时 = 7433 ms（实际 $($r1.stages[1].ms)）"
    Assert-True ($r1.e2eMs -eq 45000) "端到端 = 45000 ms（17:59:14→17:59:59，实际 $($r1.e2eMs)）"
    Assert-True ($r1.toTitleMs -eq 40000) "到 TitleScreen = 40000 ms（实际 $($r1.toTitleMs)）"
    Assert-True ($r1.heapPeakMB -eq 1200) "堆峰值 = 1200 MB（实际 $($r1.heapPeakMB)）"
    Assert-True ($r1.missing.Count -eq 0) "样本1 无缺失锚点组（实际 $($r1.missing.Count)）"

    $segMap = @{}
    foreach ($sg in $r1.segments) { $segMap[$sg.label] = $sg.ms }
    Assert-True ($segMap["S4 SharedConstants.tryDetectVersion"] -eq 50) "tryDetectVersion 段 = 50 ms（实际 $($segMap['S4 SharedConstants.tryDetectVersion'])）"
    Assert-True ($segMap["S4 Bootstrap.bootStrap (MC 原版)"] -eq 6750) "Bootstrap 段 = 6750 ms（实际 $($segMap['S4 Bootstrap.bootStrap (MC 原版)'])）"
    Assert-True ($segMap["S4 解冻 MappedRegistry"] -eq 100) "解冻段 = 100 ms（实际 $($segMap['S4 解冻 MappedRegistry'])）"
    Assert-True ($segMap["早期窗口 等 renderLock"] -eq 500) "等 renderLock 段 = 500 ms（实际 $($segMap['早期窗口 等 renderLock'])）"
    Assert-True ($segMap["早期窗口 等渲染线程退出"] -eq 200) "等渲染线程段 = 200 ms（实际 $($segMap['早期窗口 等渲染线程退出'])）"
    Assert-True ($segMap["MC 资源重载 → TitleScreen"] -eq 30700) "MC→TitleScreen 段 = 30700 ms（实际 $($segMap['MC 资源重载 → TitleScreen'])）"

    # 段定义表自洽：样本1 里每一条段定义的 from/to 锚点都必须存在。
    # 这条断言是"段定义写错锚点名"的护栏——否则改错锚点名只会让某段变 null，
    # 而没有断言盯着它，测试仍然全绿（变异检验实测踩过这个坑）。
    $segMissing = @()
    foreach ($sg in $r1.segments) { if ($null -eq $sg.ms) { $segMissing += $sg.label } }
    Assert-True ($segMissing.Count -eq 0) ("样本1 所有段都能算出耗时（算不出的: " + ($segMissing -join ", ") + "）")

    # 样本 2：旧版 jar（无锚点）——必须报"测不了"，而不是静默给 0
    $sample2 = @(
        "===== Mirage Launch 0.4.0 @ 2026-10-31 16:44:00 =====",
        "[LS] stage S1 Discovery start",
        "[LS] stage S1 Discovery done (111 ms)",
        "[LS] stage S4 RegistryMerge start",
        "[LS] stage S4 RegistryMerge done (6764 ms)",
        "[LS] stage S7 Loaded start",
        "[16:45:10] [Render thread/INFO]: [STDOUT]: [LS] stage S7 Loaded done (26000 ms)"
    )
    $s2 = @(Get-Sessions -Lines $sample2)
    $r2 = Get-SessionReport -Session $s2[0]
    Assert-True ($r2.anchorCount -eq 0) "样本2 锚点数 = 0（实际 $($r2.anchorCount)）"
    Assert-True ($r2.toTitleMs -eq $null) "样本2 无 TitleScreen 锚点时返回 null"
    Assert-True ($r2.missing.Count -eq 4) "样本2 报告 4 个缺失锚点组（实际 $($r2.missing.Count)）"
    Assert-True ($r2.e2eMs -eq 70000) "样本2 端到端 = 70000 ms（实际 $($r2.e2eMs)）"
    # 缺锚点时分段必须是 null，不能默默填 0——0 会被读成"这段不花时间"，是假数据
    $seg2 = @{}
    foreach ($sg in $r2.segments) { $seg2[$sg.label] = $sg.ms }
    $nullSegs = 0
    foreach ($k in $seg2.Keys) { if ($null -eq $seg2[$k]) { $nullSegs++ } }
    Assert-True ($nullSegs -eq $r2.segments.Count) "样本2 全部 $nullSegs/$($r2.segments.Count) 段为 null（缺锚点不填 0）"

    # 渲染层也要守住：缺锚点的段必须显示"缺锚点"，不能显示"0 ms"。
    # "0 ms" 会被读成"这段不花时间"，是假数据——报告给人看时比空值更有欺骗性。
    $rep1Text = Format-SessionReport -Report $r1 -Index 1
    $rep2Text = Format-SessionReport -Report $r2 -Index 1
    Assert-True ($rep1Text.Contains("30700 ms")) "样本1 渲染结果含实际段耗时 30700 ms"
    Assert-True ($rep2Text.Contains("缺锚点")) "样本2 渲染结果里分段显示『缺锚点』"
    Assert-True (-not $rep2Text.Contains("| 0 ms |")) "样本2 渲染结果里没有『0 ms』这类假数据"

    # 样本 3：跨天（23:59:58 → 00:00:12 = 14 秒，不是 -23 小时）
    $sample3 = @(
        "===== Mirage Launch 0.4.0 @ 2026-11-01 23:59:58 =====",
        "[LS] stage S7 Loaded start",
        "[00:00:12] [Render thread/INFO]: [STDOUT]: [LS] stage S7 Loaded done (14000 ms)"
    )
    $s3 = @(Get-Sessions -Lines $sample3)
    $r3 = Get-SessionReport -Session $s3[0]
    Assert-True ($r3.e2eMs -eq 14000) "跨天端到端 = 14000 ms（实际 $($r3.e2eMs)）"

    # 样本 4：多会话 + 尾部无横幅的散行
    $sample4 = @(
        "===== Mirage Launch 0.4.0 @ 2026-11-01 10:00:00 =====",
        "[LS] stage S1 Discovery done (100 ms)",
        "===== Mirage Launch 0.4.0 @ 2026-11-01 11:00:00 =====",
        "[LS] stage S1 Discovery done (110 ms)",
        "孤儿行（不在任何会话里）"
    )
    $s4 = @(Get-Sessions -Lines $sample4)
    Assert-True ($s4.Count -eq 2) "样本4 切出 2 个会话（实际 $($s4.Count)）"
    $r4a = Get-SessionReport -Session $s4[0]
    $r4b = Get-SessionReport -Session $s4[1]
    Assert-True ($r4a.stages[0].ms -eq 100) "样本4 会话1 的 S1 = 100 ms"
    Assert-True ($r4b.stages[0].ms -eq 110) "样本4 会话2 的 S1 = 110 ms"

    Write-Host ""
    Write-Host ("== 自检结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==") -ForegroundColor Cyan
    if ($script:fail -gt 0) {
        Write-Host "失败明细：" -ForegroundColor Red
        foreach ($f in $script:failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
        return 1
    }
    Write-Host "全部通过。" -ForegroundColor Green
    return 0
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

if ($SelfTest) {
    exit (Invoke-SelfTest)
}

Write-Host "== Mirage 启动性能基线 ==" -ForegroundColor Cyan
Write-Host ("日志: " + $LogPath)
Write-Host ("Jar : " + $JarPath)
Write-Host ""

if (-not (Test-Path $LogPath)) {
    Write-Host ("[FAIL] 找不到启动日志: " + $LogPath) -ForegroundColor Red
    Write-Host "       先跑一次游戏（用户双击 ..\启动 26.2-mirage.bat），日志会落在 <gameDir>/logs/mirage-launch.log。" -ForegroundColor Red
    exit 1
}

# 早期输出可能含中文；用 UTF8 读，非法字节按替换处理（ASCII 标记不受影响）
$lines = [System.IO.File]::ReadAllLines($LogPath, [System.Text.Encoding]::UTF8)
$sessions = @(Get-Sessions -Lines $lines)
if ($sessions.Count -eq 0) {
    Write-Host "[FAIL] 日志里没找到任何会话（缺 '===== Mirage Launch ... =====' 行）" -ForegroundColor Red
    exit 1
}
if ($LastSession) {
    $sessions = @($sessions[-1])
}

$reports = @()
foreach ($s in $sessions) { $reports += ,(Get-SessionReport -Session $s) }

$md = New-Object System.Text.StringBuilder
[void]$md.AppendLine("# 启动性能基线（自动生成）")
[void]$md.AppendLine()
[void]$md.AppendLine('> 由 `scripts/measure_startup.ps1` 生成于 ' + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
[void]$md.AppendLine('> 数据来源：`' + $LogPath + '`')
[void]$md.AppendLine()
[void]$md.AppendLine("## 会话总览")
[void]$md.AppendLine()
[void]$md.AppendLine("| # | 横幅时刻 | 端到端 | 到 TitleScreen | 堆峰值 | 锚点 |")
[void]$md.AppendLine("|---:|---|---:|---:|---:|---:|")
$idx = 0
foreach ($r in $reports) {
    $idx++
    $e2e = if ($null -eq $r.e2eMs) { "缺" } else { "$($r.e2eMs) ms" }
    $tt = if ($null -eq $r.toTitleMs) { "缺" } else { "$($r.toTitleMs) ms" }
    [void]$md.AppendLine("| $idx | $($r.bannerTime) | $e2e | $tt | $($r.heapPeakMB) MB | $($r.anchorCount) |")
}
[void]$md.AppendLine()

$idx = 0
foreach ($r in $reports) {
    $idx++
    [void]$md.AppendLine((Format-SessionReport -Report $r -Index $idx))
}

# 体积
if (Test-Path $JarPath) {
    $jar = Get-Item $JarPath
    [void]$md.AppendLine("## 体积")
    [void]$md.AppendLine()
    [void]$md.AppendLine("| 项 | 值 |")
    [void]$md.AppendLine("|---|---|")
    [void]$md.AppendLine("| $JarPath | $([math]::Round($jar.Length / 1MB, 2)) MB（$($jar.Length) 字节） |")
    [void]$md.AppendLine("| 修改时间 | $($jar.LastWriteTime) |")
    [void]$md.AppendLine()
}

# 进程峰值内存（可选）：游戏正在运行时采样
if ($PeakMemoryPid -gt 0) {
    try {
        $p = Get-Process -Id $PeakMemoryPid -ErrorAction Stop
        [void]$md.AppendLine("## 进程峰值内存（采样 pid $($PeakMemoryPid)）")
        [void]$md.AppendLine()
        [void]$md.AppendLine("| 项 | 值 |")
        [void]$md.AppendLine("|---|---|")
        [void]$md.AppendLine("| PeakWorkingSet64 | $([math]::Round($p.PeakWorkingSet64 / 1MB, 1)) MB |")
        [void]$md.AppendLine("| WorkingSet64（当前） | $([math]::Round($p.WorkingSet64 / 1MB, 1)) MB |")
        [void]$md.AppendLine("| PrivateMemorySize64 | $([math]::Round($p.PrivateMemorySize64 / 1MB, 1)) MB |")
        [void]$md.AppendLine()
    } catch {
        Write-Host ("[WARN] 采样 pid " + $PeakMemoryPid + " 失败: " + $_.Exception.Message) -ForegroundColor Yellow
    }
}

# 缺口总表：把"还测不了什么"摆在最显眼的位置
$allMissing = @()
foreach ($r in $reports) {
    foreach ($m in $r.missing) { $allMissing += $m.group + "（缺 " + ($m.absent -join ", ") + "）" }
}
if ($allMissing.Count -gt 0) {
    [void]$md.AppendLine("## 本次测不了的维度")
    [void]$md.AppendLine()
    foreach ($m in ($allMissing | Select-Object -Unique)) {
        [void]$md.AppendLine("- " + $m)
    }
    [void]$md.AppendLine()
    [void]$md.AppendLine('> 说明：缺锚点通常意味着**部署的 jar 比源码旧**——锚点由 `io.mirage.loader.MiragePerf` 打出，')
    [void]$md.AppendLine("> 需要重新构建并部署 fat jar 后再启动一次游戏才有数据。")
    [void]$md.AppendLine()
}

$text = $md.ToString()
Write-Host $text

if ($OutFile) {
    $outFull = if ([System.IO.Path]::IsPathRooted($OutFile)) { $OutFile } else { Join-Path $root $OutFile }
    [System.IO.File]::WriteAllText($outFull, $text, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ("已写出: " + $outFull) -ForegroundColor Green
}

exit 0
