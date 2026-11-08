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
$javacExe = Join-Path $JavaHome "bin\javac.exe"
if (-not (Test-Path $javacExe)) { throw "找不到 javac：$javacExe（用 -JavaHome 指定 JDK 25）" }

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
# 2026-11-08 真实 mod 实测：bus 8.0.5 的 addListener(Consumer) 单参重载**不带 Class**，
# 真正覆盖全部重载的汇聚点是私有 addToListeners(Object owner, Class eventType, ...) ——
# addListener 各重载与 @SubscribeEvent 自动注册最终都汇到它，且 owner 以参数直接可得。
Assert-Match $tr 'ADD_TO_LISTENERS_DESC' '插桩点：addToListeners 汇聚点（覆盖全部 addListener 重载 + @SubscribeEvent）'
Assert-Match $tr 'ADD_TO_LISTENERS_DESC\.equals\(desc\)' 'addToListeners 按完整描述符匹配（不靠猜槽位）'

$ag = Read-Utf8 $recorderSrc
Assert-Match $ag '(?m)^\s*public static void onRegisterKey' '注册回调入口在位'
Assert-Match $ag 'resolveByRegistrationId' '注册归属用 namespace（栈回溯在注册点上取不到 mod 帧）'
Assert-Match $ag 'extractRegistry' '注册表名从 ResourceKey 取（不是硬编码 root）'
# 2026-11-08 真实 mod 实测：AppleSkin 有 4 条 @SubscribeEvent 订阅被 jade/mezz_config/基线
# 的同名事件先占位而整条丢失。去重键必须带 owner —— 同一事件被多个 mod 订阅是常态。
Assert-Match $ag 'SEEN_SUBSCRIPTIONS\.add\(owner \+ "\|" \+ eventType\)' '订阅去重键按 owner|event（只按事件名会吞掉后注册的 mod）'
Assert-Match $ag 'resolveForInstance\(listener\)' '订阅归属优先取监听器实例的 CodeSource（订阅点栈上全是平台帧）'

# ---- 断言自检（守卫层变异，纯内存、不落盘）----
# 防止「断言写了但永远为真」：把锚点换回历史 bug 写法，同一断言必须判红。
# 守卫层（能力有没有接线）用内存副本即可；行为层（算得对不对）才必须落盘重编译。
$mutSub = $ag.Replace('SEEN_SUBSCRIPTIONS.add(owner + "|" + eventType)', 'SEEN_SUBSCRIPTIONS.add(eventType)')
Assert-True ($mutSub -ne $ag) '自检：订阅去重键锚点可替换（断言不是空转）'
Assert-True (-not [regex]::IsMatch($mutSub, 'SEEN_SUBSCRIPTIONS\.add\(owner \+ "\|" \+ eventType\)')) '自检：退回「只看事件名」时该断言判红'

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

# ---- 录制期不显示窗口（2026-11-08 加）----
# 背景：P3 必须真跑客户端，但录制时窗口会占屏。窗口「出现」只有一条路：
# GLFW.glfwShowWindow。agent 把它摘成 POP2；运行器另有一层 OS 兜底并记账。
Write-Host "-- 静态守卫（录制期不显示窗口）--" -ForegroundColor Yellow
$hideSrc = Join-Path $agentDir "src\main\java\io\mirage\msr\agent\WindowHideTransformer.java"
$hd = Read-Utf8 $hideSrc
Assert-True ($hd.Length -gt 0) '窗口隐藏插桩器存在：WindowHideTransformer.java'
Assert-Match $hd 'org/lwjgl/glfw/GLFW' '插桩目标只认 GLFW 门面类'
Assert-Match $hd '"glfwShowWindow"' '摘除目标：glfwShowWindow（窗口出现的唯一路径）'
Assert-Match $hd 'Opcodes\.POP2' '摘除方式：POP2 吃掉 long 句柄（栈深度不变 ⇒ 不用重算 maxStack）'
Assert-Match $hd 'mentionsShowWindow' '先做字节级 needle 筛（不为每个类构造 ClassReader）'
Assert-Match $ag 'new WindowHideTransformer\(\)' 'premain 真的注册了窗口插桩器'
Assert-Match $ag 'mirage\.msr\.hidewindow' '开关：-Dmirage.msr.hidewindow（默认开）'
Assert-Match $rnCode '\-Dmirage\.msr\.hidewindow=' '运行器把 hidewindow 开关传给 JVM'
Assert-Match $rnCode '可见峰值' '运行器对「窗口是否真的出现过」记账（峰值>0 即 agent 失效）'
Assert-Match $rnCode 'ShowWindow' '运行器带 -ShowWindow 排查开关（要看画面时用）'

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

# ================================================================= 窗口隐藏行为断言
# 不开游戏也能验：造一个「真会调 glfwShowWindow」的类（假 GLFW 门面里让它打印
# SHOW_CALLED），把同一个类的字节码喂给插桩器后再跑一遍：
#   原始 → 必须打印 SHOW_CALLED（证明探针本身有效，不是空跑）
#   改写 → 必须不打印，且能跑到 RUN_DONE（证明摘除生效、字节码仍合法可执行）
# 这是**行为层**断言：只看「调用到底有没有发生」，与源码长什么样无关。
Write-Host "-- 窗口隐藏行为断言（合成类，秒级）--" -ForegroundColor Yellow

$hideWork = Join-Path $env:TEMP ("msr-p3-hide-" + [guid]::NewGuid().ToString("N"))
$hideSrcDir = Join-Path $hideWork "src"
$hideClasses = Join-Path $hideWork "classes"
$hidePatched = Join-Path $hideWork "patched"
foreach ($d in @($hideSrcDir, (Join-Path $hideSrcDir "org\lwjgl\glfw"), $hideClasses, $hidePatched)) {
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}

# 探针源码刻意全 ASCII：脚本里生成的 .java 不带 BOM，写中文反而容易踩编码坑（C76）
$glfwStub = @'
package org.lwjgl.glfw;

public final class GLFW {
    public static void glfwShowWindow(long handle) {
        System.out.println("SHOW_CALLED:" + handle);
    }

    public static void glfwWindowHint(int hint, int value) {
    }
}
'@

$showCase = @'
public final class ShowCase {
    public static void show(long handle) {
        org.lwjgl.glfw.GLFW.glfwShowWindow(handle);
    }
}
'@

$runShow = @'
public final class RunShow {
    public static void main(String[] args) {
        ShowCase.show(7L);
        System.out.println("RUN_DONE");
    }
}
'@

$hideProbe = @'
import io.mirage.msr.agent.WindowHideTransformer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class HideProbe {
    public static void main(String[] args) throws Exception {
        byte[] orig = Files.readAllBytes(Paths.get(args[0]));
        byte[] other = Files.readAllBytes(Paths.get(args[1]));
        Path outDir = Paths.get(args[2]);
        Files.createDirectories(outDir);
        System.out.println("probe.needle.orig=" + WindowHideTransformer.mentionsShowWindow(orig));
        System.out.println("probe.needle.other=" + WindowHideTransformer.mentionsShowWindow(other));
        System.out.println("probe.other.untouched=" + (WindowHideTransformer.stripShowWindow(other) == null));
        byte[] patched = WindowHideTransformer.stripShowWindow(orig);
        System.out.println("probe.orig.changed=" + (patched != null));
        if (patched != null) {
            Files.write(outDir.resolve("ShowCase.class"), patched);
        }
    }
}
'@

$utf8NoBomSrc = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText((Join-Path $hideSrcDir "org\lwjgl\glfw\GLFW.java"), $glfwStub, $utf8NoBomSrc)
[System.IO.File]::WriteAllText((Join-Path $hideSrcDir "ShowCase.java"), $showCase, $utf8NoBomSrc)
[System.IO.File]::WriteAllText((Join-Path $hideSrcDir "RunShow.java"), $runShow, $utf8NoBomSrc)
[System.IO.File]::WriteAllText((Join-Path $hideSrcDir "HideProbe.java"), $hideProbe, $utf8NoBomSrc)

$sources = @(
    (Join-Path $hideSrcDir "org\lwjgl\glfw\GLFW.java"),
    (Join-Path $hideSrcDir "ShowCase.java"),
    (Join-Path $hideSrcDir "RunShow.java"),
    (Join-Path $hideSrcDir "HideProbe.java")
)
$javacOut = & $javacExe -encoding UTF-8 -nowarn -d $hideClasses -cp $agentJar $sources
$compiled = Test-Path (Join-Path $hideClasses "ShowCase.class")
if (-not $compiled) { $javacOut | ForEach-Object { Write-Host "        javac: $_" -ForegroundColor DarkGray } }
Assert-True $compiled "探针类编译通过（假 GLFW 门面 + ShowCase + RunShow + HideProbe）"

if ($compiled) {
    $cpOrig = $hideClasses + ";" + $agentJar
    $beforeOut = (& $javaExe -cp $cpOrig RunShow) -join "`n"
    # 先证明探针有效：没这一步，「改写后不打印」可能只是因为压根没调用过
    Assert-True ($beforeOut -match 'SHOW_CALLED:7') "探针有效：原始类确实调用了 glfwShowWindow"

    $probeOut = (& $javaExe -cp $cpOrig HideProbe `
        (Join-Path $hideClasses "ShowCase.class") (Join-Path $hideClasses "RunShow.class") $hidePatched) -join "`n"
    Assert-True ($probeOut -match 'probe\.needle\.orig=true') "插桩器识别出含 glfwShowWindow 的类（needle 命中）"
    Assert-True ($probeOut -match 'probe\.orig\.changed=true') "插桩器真的改写了该类"
    Assert-True ($probeOut -match 'probe\.needle\.other=false') "无关类不被 needle 误判"
    Assert-True ($probeOut -match 'probe\.other\.untouched=true') "无关类原样放行（返回 null，不改写）"

    $cpPatched = $hidePatched + ";" + $hideClasses + ";" + $agentJar
    $afterOut = (& $javaExe -cp $cpPatched RunShow) -join "`n"
    Assert-True ($afterOut -notmatch 'SHOW_CALLED') "改写后不再调用 glfwShowWindow（窗口不会出现）"
    Assert-True ($afterOut -match 'RUN_DONE') "改写后的字节码仍可执行（POP2 没弄坏栈/校验）"
}

Remove-Item $hideWork -Recurse -Force -ErrorAction SilentlyContinue

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
    $runTxt = ""
    if (Test-Path $runReport) {
        $txt = [System.IO.File]::ReadAllText($runReport, [System.Text.UTF8Encoding]::new($false))
        $runTxt = $txt
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

    # ---- 录制期间到底有没有「显示过」窗口（2026-11-08）----
    # 两层证据，缺一不可：
    #   1) 运行器采样：可见峰值必须为 0（>0 = 窗口真的出现过，靠 OS 兜底按下，画面会闪）；
    #   2) IR stats：agent 摘掉的 glfwShowWindow 调用数必须 ≥1（=0 说明插桩压根没接上）。
    Assert-True ($runTxt.Length -gt 0) "实机：运行器报告可读（窗口证据来自它）"
    Assert-True ($runTxt -notmatch '可见峰值=[1-9]') "实机：录制全程没有可见窗口（可见峰值 0）"
    Assert-True ($runTxt -match '可见峰值=0') "实机：两轮都做了窗口采样（不是没采就判 0）"
    Assert-True ($runTxt -notmatch '\[WARN\] 出现过可见窗口') "实机：没有触发 OS 兜底隐藏（agent 已从源头摘掉）"

    $baseIrFile = Join-Path $instDir "msr-out\msr-baseline\baseline.mirage.json"
    $stripHit = -1
    if (Test-Path $baseIrFile) {
        $bi = [System.IO.File]::ReadAllText($baseIrFile, [System.Text.UTF8Encoding]::new($false))
        $mm = [regex]::Match($bi, '"showWindowStripped"\s*:\s*(\d+)')
        if ($mm.Success) { $stripHit = [int]$mm.Groups[1].Value }
    }
    Assert-True ($stripHit -ge 1) "实机：agent 摘掉至少 1 处 glfwShowWindow（实际 $stripHit；0 = 插桩没接上）"

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
