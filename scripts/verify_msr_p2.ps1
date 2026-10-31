# scripts/verify_msr_p2.ps1
# verify_msr_p2.ps1 —— MSR P2（单次运行原则）回归验证
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p2.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p2.ps1 -Keep            # 保留临时目录
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p2.ps1 -Jar <fatjar>    # 指定 jar
#
# 前提：先出 fat jar（必须是 fatJar 任务，build 会把同名产物覆盖成 67 KB 瘦 jar）：
#   gradle :launcher:mirage-launch:fatJar
#
# 为什么能脱离游戏测：
#   P2 的三块（fake pass 判据 / 被动捕获 / 版本伪装 + 缓存落盘）全是纯 JVM 行为，
#   挂点都在我们自己的类里（api DeferredRegister / EventBus / compat NeoEventBusImpl / FMLLoader stub），
#   不需要真 MC，也不需要启动游戏。
#
# 覆盖的契约（P2 四条，回归时最先坏的就是这些）：
#   1. fake pass 按需化：版本区间完整→不录；缺区间→录；force→录；已有录制→不录；
#   2. 被动捕获：窗口内记 API/事件/DR 计数，窗口外 no-op；有入口失败→complete=false；
#   3. 缓存：原子写（.tmp 不被当有效条目）、complete 才入库、合并取并集/大值、无变化不重写；
#   4. 消费方：版本伪装按 mod 上下文生效（FMLLoader.versionInfo 收到伪装值）、二启 diff 产出。
#
# 退出码 0 = 全部通过；1 = 有失败项（打印失败项）。

[CmdletBinding()]
param(
    [switch]$Keep,
    [string]$Jar = ""
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$root = Split-Path $PSScriptRoot -Parent

if (-not $Jar) {
    $Jar = Join-Path $root "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
}
if (-not (Test-Path $Jar)) {
    Write-Host "[FAIL] 找不到 fat jar: $Jar" -ForegroundColor Red
    Write-Host "       先跑: gradle :launcher:mirage-launch:fatJar" -ForegroundColor Yellow
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

function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

# 跑外部进程：不用 Start-Process（本机 http_proxy/HTTP_PROXY 并存，会抛"已添加项"），
# 也不用 & + 2>&1（PS5.1 会把原生 stderr 变 NativeCommandError 中断脚本）。
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

function Write-NoBom([string]$path, [string]$text) {
    $dir = Split-Path $path -Parent
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

# ---------------------------------------------------------------- 临时工作区

$work = Join-Path $env:TEMP ("mirage-msrp2-" + $PID)
New-Item -ItemType Directory -Force -Path $work | Out-Null

# ---------------------------------------------------------------- 检查程序

$harnessSrc = Join-Path $work "harness-src\io\mirage\loader\passive\check\MsrP2Check.java"
$harnessText = @'
package io.mirage.loader.passive.check;

import io.mirage.api.hook.PassiveHooks;
import io.mirage.api.registry.DeferredRegister;
import io.mirage.api.version.VersionMasks;
import io.mirage.eventbus.EventBus;
import io.mirage.loader.MidCache;
import io.mirage.loader.NeoForgeModDetector;
import io.mirage.loader.RecordingPolicy;
import io.mirage.loader.masq.LoaderVersionMasquerade;
import io.mirage.loader.passive.PassiveRecorder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

/**
 * MSR P2 回归用例（由 scripts/verify_msr_p2.ps1 编译运行）。
 * 只输出固定 ASCII 键值行，断言全部在 PowerShell 侧做。
 */
public final class MsrP2Check {

    public static void main(String[] args) throws Exception {
        Path work = Paths.get(args[0]);

        policy();
        cache(work);
        passive();
        masq();
        e2e(work);

        System.out.println("HARNESS-DONE");
    }

    // ------------------------------------------------------ 1. fake pass 判据

    private static void policy() {
        NeoForgeModDetector.Detected full = detected("m_full", "[26.1,)", "[26.1,27)");
        NeoForgeModDetector.Detected partial = detected("m_partial", "[26.1,)", null);
        MidCache.Recorded prev = recorded(List.of("a.b.c"), List.of("E"), 1);

        RecordingPolicy.Decision off = RecordingPolicy.decide("off", full, null);
        RecordingPolicy.Decision force = RecordingPolicy.decide("force", full, prev);
        RecordingPolicy.Decision miss = RecordingPolicy.decide("auto", partial, null);
        RecordingPolicy.Decision skip = RecordingPolicy.decide("auto", full, null);
        RecordingPolicy.Decision already = RecordingPolicy.decide("auto", full, prev);
        RecordingPolicy.Decision bad = RecordingPolicy.decide("AUTO", null, null);

        System.out.println("POLICY off=" + off.record() + ":" + off.reason()
                + " force=" + force.record() + ":" + force.reason()
                + " miss=" + miss.record() + ":" + miss.reason()
                + " skip=" + skip.record() + ":" + skip.reason()
                + " already=" + already.record() + ":" + already.reason()
                + " nullDetected=" + bad.record() + ":" + bad.reason());
    }

    // ------------------------------------------------------ 2. 缓存落盘

    private static void cache(Path work) throws Exception {
        Path gameDir = work.resolve("g1");
        Files.createDirectories(gameDir);
        Path jar = work.resolve("g1").resolve("m1.jar");
        Files.write(jar, "jarbytes-v1".getBytes(StandardCharsets.UTF_8));

        NeoForgeModDetector.Detected d = detected("m1", "[26.1,)", "[26.1,27)");
        MidCache cache = new MidCache(gameDir, "26.2");

        // 初始：只有 detected，没有 recorded
        cache.store(jar, d, null);
        MidCache.Entry e0 = cache.lookup(jar, "m1", false);
        System.out.println("CACHE-FRESH lookup=" + (e0 != null)
                + " spec=" + (e0 == null ? "-" : e0.specVersion)
                + " recorded=" + (e0 != null && e0.recorded != null));

        // 不完整的被动结果不入库
        MidCache.Recorded bad = recorded(List.of("x.y"), List.of(), 1);
        bad.complete = false;
        cache.storePassive(jar, d, bad);
        MidCache.Entry e1 = cache.lookup(jar, "m1", false);
        System.out.println("CACHE-INCOMPLETE stored=" + (e1 != null && e1.recorded != null));

        // 完整结果入库
        MidCache.Recorded ok = recorded(List.of("x.y", "x.z"), List.of("net.example.E"), 2);
        ok.complete = true;
        ok.source = "passive";
        MidCache.Recorded stored = cache.storePassive(jar, d, ok);
        MidCache.Entry e2 = cache.lookup(jar, "m1", false);
        System.out.println("CACHE-PASSIVE stored=" + (e2 != null && e2.recorded != null)
                + " source=" + (e2 == null || e2.recorded == null ? "-" : e2.recorded.source)
                + " complete=" + (e2 != null && e2.recorded != null && e2.recorded.complete)
                + " apis=" + (e2 == null || e2.recorded == null ? -1 : e2.recorded.touched_apis.size())
                + " dr=" + (stored == null ? -1 : stored.deferred_register_count));

        // 同样的数据再来一遍：不写盘（mtime 不变）
        Path entryFile = cache.root().resolve("m1+" + sha16(jar) + ".json");
        FileTime before = Files.getLastModifiedTime(entryFile);
        Thread.sleep(60);
        cache.storePassive(jar, d, recorded(List.of("x.y", "x.z"), List.of("net.example.E"), 2));
        FileTime after = Files.getLastModifiedTime(entryFile);
        System.out.println("CACHE-NOCHANGE rewrote=" + (!after.equals(before)));

        // 半成品 .tmp 不能被当有效条目
        Files.writeString(entryFile.resolveSibling(entryFile.getFileName() + ".tmp"),
                "{ this is not json", StandardCharsets.UTF_8);
        MidCache.Entry e3 = cache.lookup(jar, "m1", false);
        System.out.println("CACHE-TMP ignored=" + (e3 != null && e3.recorded != null));

        // 只剩 .tmp（模拟写到一半被 kill -9）：不该有任何有效条目
        Files.deleteIfExists(entryFile);
        MidCache.Entry e4 = cache.lookup(jar, "m1", false);
        System.out.println("CACHE-TMPONLY lookup=" + (e4 != null));

        // jar 变了 → 基线失效
        Files.write(jar, "jarbytes-v2".getBytes(StandardCharsets.UTF_8));
        System.out.println("CACHE-PEEK afterJarChange=" + (cache.peek(jar) != null));
    }

    // ------------------------------------------------------ 3. 被动捕获

    private static void passive() throws Exception {
        PassiveRecorder.resetStats();

        PassiveRecorder.begin("t_passive");
        boolean activeInside = PassiveRecorder.active();

        // (a) MG 原生 DeferredRegister（api 模块挂点）
        DeferredRegister<String> dr = DeferredRegister.create(String.class, "t_passive");
        dr.register("a", () -> "A");
        dr.register("b", () -> "B");
        dr.register();

        // (b) MG 事件总线（eventbus 模块挂点）
        new EventBus().register(new MirageListener());

        // (c) NeoForge 风格总线（compat 模块挂点）
        new io.mirage.neoforge.runtime.NeoEventBusImpl().register(new NeoListener());

        PassiveRecorder.Result r = PassiveRecorder.end();
        System.out.println("PASSIVE activeInside=" + activeInside
                + " endedActive=" + PassiveRecorder.active()
                + " complete=" + r.complete()
                + " dr=" + r.deferredRegisterCount()
                + " apis=" + r.apis().size()
                + " events=" + r.events().size()
                + " hasApiCreate=" + r.apis().contains("io.mirage.api.registry.DeferredRegister.create")
                + " hasApiNeoReg=" + r.apis().contains("net.neoforged.bus.api.IEventBus.register")
                + " hasMirageEvent=" + r.events().contains("io.mirage.api.event.ModInitEvent")
                + " hasNeoEvent=" + r.events().contains("net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent"));

        // 窗口外 no-op：同样的调用不再进任何窗口
        DeferredRegister.create(String.class, "t_outside");
        new EventBus().register(new MirageListener());
        PassiveRecorder.begin("t_empty");
        PassiveRecorder.Result empty = PassiveRecorder.end();
        System.out.println("PASSIVE outsideNoop=" + (PassiveRecorder.end() == null)
                + " emptyApis=" + empty.apis().size()
                + " hooksInstalled=" + (PassiveHooks.apiSink != null));

        // 有入口失败 → complete=false（对应 S6 里 catch 到异常时调 markFailure）
        PassiveRecorder.begin("t_fail");
        DeferredRegister.create(String.class, "t_fail");
        PassiveRecorder.markFailure("t.fail.Mod");
        PassiveRecorder.Result failed = PassiveRecorder.end();
        System.out.println("PASSIVE failedComplete=" + failed.complete()
                + " failedReason=" + failed.failReason()
                + " statsWindows=" + PassiveRecorder.stats()[0]
                + " statsIncomplete=" + PassiveRecorder.stats()[1]);
    }

    // ------------------------------------------------------ 4. 版本伪装

    private static void masq() {
        LoaderVersionMasquerade.reset();
        LoaderVersionMasquerade.installBaseline("26.2");

        String p1 = LoaderVersionMasquerade.pick("[26.1,27)", "26.2");       // 当前在区间内
        String p2 = LoaderVersionMasquerade.pick("[26.1.0.1,26.1.1]", "26.2"); // 超上界 → 上界
        String p3 = LoaderVersionMasquerade.pick("[26.4,)", "26.2");          // 低于下界 → 下界
        String p4 = LoaderVersionMasquerade.pick(null, "26.2");               // 无区间 → 基线
        String p5 = LoaderVersionMasquerade.pick("26.1", "26.2");             // 裸版本
        String p6 = LoaderVersionMasquerade.pick("garbage", "26.2");          // 认不出 → 原样（裸版本分支）

        VersionMasks.Mask mask = LoaderVersionMasquerade.maskFor("[26.1,27)", "[26.1.0.1,26.1.1]", "26.2");
        VersionMasks.put("t_masq", mask);

        VersionMasks.enter("t_masq");
        String neoInside = net.neoforged.fml.loading.FMLLoader.versionInfo().neoForgeVersion();
        String mcInside = net.neoforged.fml.loading.FMLLoader.versionInfo().mcVersion();
        VersionMasks.leave();
        String neoOutside = net.neoforged.fml.loading.FMLLoader.versionInfo().neoForgeVersion();

        VersionMasks.put("t_major", new VersionMasks.Mask("26.2.1", "26.2.1"));
        VersionMasks.enter("t_major");
        String major = net.neoforged.fml.loading.FMLLoader.versionInfo().majorMcVersion();
        VersionMasks.leave();

        System.out.println("MASQ inRange=" + p1 + " aboveUpper=" + p2 + " belowLower=" + p3
                + " noRange=" + p4 + " bare=" + p5 + " garbage=" + p6
                + " mask=" + mask.mcVersion() + "/" + mask.neoForgeVersion()
                + " modNeo=" + neoInside + " modMc=" + mcInside
                + " outside=" + neoOutside + " major=" + major);
    }

    // ------------------------------------------------------ 5. 端到端归档 + diff

    private static void e2e(Path work) throws Exception {
        Path gameDir = work.resolve("g2");
        Files.createDirectories(gameDir);
        Path jar = work.resolve("m2.jar");
        Files.write(jar, "m2-bytes".getBytes(StandardCharsets.UTF_8));

        NeoForgeModDetector.Detected d = detected("m2", "[26.1,)", "[26.1,27)");
        MidCache cache = new MidCache(gameDir, "26.2");
        // 模拟 ModDiscoverer：区间完整 → 跳过 fake pass，只存 detected
        cache.store(jar, d, null);

        // 第一次启动：被动捕获 → 归档
        PassiveRecorder.begin("m2");
        DeferredRegister.create(String.class, "m2").register("x", () -> "X");
        PassiveRecorder.Result r1 = PassiveRecorder.end();
        MidCache.Recorded cur1 = PassiveRecorder.toRecorded(r1);
        MidCache.Recorded prev1 = cache.peek(jar) == null ? null : cache.peek(jar).recorded;
        MidCache.Recorded merged1 = MidCache.merge(prev1, cur1);
        List<String> diff1 = PassiveRecorder.diff(prev1, merged1);
        cache.storePassive(jar, d, cur1);
        MidCache.Entry after1 = cache.lookup(jar, "m2", false);

        // 第二次启动：多了一条 API（模拟 mod 升级后的漂移）
        PassiveRecorder.begin("m2");
        DeferredRegister.create(String.class, "m2").register("x", () -> "X");
        new io.mirage.neoforge.runtime.NeoEventBusImpl().register(new NeoListener());
        PassiveRecorder.Result r2 = PassiveRecorder.end();
        MidCache.Recorded cur2 = PassiveRecorder.toRecorded(r2);
        MidCache.Recorded prev2 = cache.peek(jar).recorded;
        MidCache.Recorded merged2 = MidCache.merge(prev2, cur2);
        List<String> diff2 = PassiveRecorder.diff(prev2, merged2);
        cache.storePassive(jar, d, cur2);
        if (!diff2.isEmpty()) PassiveRecorder.appendDiffReport(cache.root(), "m2", diff2);

        Path diffLog = cache.root().resolve("mid-diff.log");
        String diffText = Files.isRegularFile(diffLog)
                ? Files.readString(diffLog, StandardCharsets.UTF_8) : "";

        System.out.println("E2E firstSource=" + (after1 == null || after1.recorded == null ? "-" : after1.recorded.source)
                + " firstComplete=" + (after1 != null && after1.recorded != null && after1.recorded.complete)
                + " firstApis=" + (after1 == null || after1.recorded == null ? -1 : after1.recorded.touched_apis.size())
                + " diff1Lines=" + diff1.size()
                + " diff2Lines=" + diff2.size()
                + " diff2HasAdded=" + diff2.toString().contains("+apis")
                + " diffLogExists=" + Files.isRegularFile(diffLog)
                + " diffLogHasMod=" + diffText.contains("m2")
                + " mergedApis=" + merged2.touched_apis.size()
                + " mergedEvents=" + merged2.registered_events.size()
                + " source=" + merged2.source);
    }

    // ------------------------------------------------------ 工具

    private static NeoForgeModDetector.Detected detected(String id, String neoRange, String mcRange) {
        NeoForgeModDetector.Detected d = new NeoForgeModDetector.Detected(id, "1.0.0", id, "t." + id + ".Mod");
        d.neoForgeVersionRange = neoRange;
        d.mcVersionRange = mcRange;
        return d;
    }

    private static MidCache.Recorded recorded(List<String> apis, List<String> events, int dr) {
        MidCache.Recorded r = new MidCache.Recorded();
        r.touched_apis = new ArrayList<>(apis);
        r.registered_events = new ArrayList<>(events);
        r.deferred_register_count = dr;
        r.recorded_loader_version = "";
        r.recorded_mc_version = "";
        return r;
    }

    private static String sha16(Path jar) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] dig = md.digest(Files.readAllBytes(jar));
        return java.util.HexFormat.of().formatHex(dig).substring(0, 16);
    }

    /** MG 原生事件总线的监听器。 */
    public static final class MirageListener {
        @io.mirage.api.SubscribeEvent
        public void on(io.mirage.api.event.ModInitEvent e) {}
    }

    /** NeoForge 风格总线的监听器。 */
    public static final class NeoListener {
        @net.neoforged.bus.api.SubscribeEvent
        public void on(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent e) {}
    }

    private MsrP2Check() {}
}
'@

Write-NoBom $harnessSrc $harnessText

Write-Host "== 编译检查程序 ==" -ForegroundColor Cyan
$harnessOut = Join-Path $work "harness-classes"
New-Item -ItemType Directory -Force -Path $harnessOut | Out-Null
$res = Invoke-Native $javac @(
    "-J-Duser.language=en", "-encoding", "UTF-8", "-proc:none",
    "-d", $harnessOut, "-cp", $Jar, $harnessSrc
) "javac-harness"
if ($res.Code -ne 0) {
    Write-Host "[FAIL] 检查程序编译失败" -ForegroundColor Red
    if ($res.Out.Trim()) { Write-Host $res.Out }
    if ($res.Err.Trim()) { Write-Host $res.Err -ForegroundColor DarkYellow }
    exit 1
}
Write-Host "   [OK] 编译通过" -ForegroundColor DarkGreen

Write-Host "== 跑 P2 用例（纯 JVM，不启动 MC）==" -ForegroundColor Cyan
$cp = $Jar + ";" + $harnessOut
$run = Invoke-Native $java @(
    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
    "-cp", $cp, "io.mirage.loader.passive.check.MsrP2Check", $work
) "harness"

if ($Keep) { Write-Host "   (保留临时目录: $work)" -ForegroundColor DarkGray }

# ---------------------------------------------------------------- 断言框架

function Get-CaseLine([string]$text, [string]$prefix) {
    foreach ($l in ($text -split "`r?`n")) {
        if ($l.StartsWith($prefix)) { return $l }
    }
    return ""
}

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]

function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
    } else {
        $script:failures.Add($desc) | Out-Null
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
    }
}

function Assert-Match([string]$text, [string]$pattern, [string]$desc) {
    $ok = $text -match $pattern
    $script:checks++
    if ($ok) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
    } else {
        $script:failures.Add(($desc + " / 期望匹配 " + $pattern + " / 实际: " + $text)) | Out-Null
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        Write-Host ("          期望匹配 {0}" -f $pattern) -ForegroundColor DarkGray
        Write-Host ("          实际: {0}" -f $text) -ForegroundColor DarkGray
    }
}

Assert-Match $run.Out 'HARNESS-DONE' "用例集跑完（进程未被异常带走）"
if ($run.Code -ne 0) {
    Write-Host "   [INFO] java 退出码 $($run.Code)" -ForegroundColor Yellow
    if ($run.Err.Trim()) { Write-Host $run.Err -ForegroundColor DarkYellow }
}

# 1) fake pass 判据
$policy = Get-CaseLine $run.Out "POLICY"
Assert-Match $policy 'off=false:OFF'              "判据：开关 off 一律不录"
Assert-Match $policy 'force=true:FORCE'           "判据：force 无条件录"
Assert-Match $policy 'miss=true:MISSING_RANGES'   "判据：缺版本区间 → 录"
Assert-Match $policy 'skip=false:RANGES_COMPLETE' "判据：区间完整 → 不录（P2 核心）"
Assert-Match $policy 'already=false:ALREADY_RECORDED' "判据：已录过 → 不重录（含上次失败）"
Assert-Match $policy 'nullDetected=true:MISSING_RANGES' "判据：detected 为 null 不炸，按缺区间处理"

# 2) 缓存
$cFresh = Get-CaseLine $run.Out "CACHE-FRESH"
$cInc = Get-CaseLine $run.Out "CACHE-INCOMPLETE"
$cPassive = Get-CaseLine $run.Out "CACHE-PASSIVE"
$cNoChange = Get-CaseLine $run.Out "CACHE-NOCHANGE"
$cTmp = Get-CaseLine $run.Out "CACHE-TMP "
$cTmpOnly = Get-CaseLine $run.Out "CACHE-TMPONLY"
$cPeek = Get-CaseLine $run.Out "CACHE-PEEK"
Assert-Match $cFresh 'lookup=True spec=0.4 recorded=False' "缓存：首写只有 detected（无 recorded），spec=0.4"
Assert-Match $cInc 'stored=False'                 "缓存：不完整的被动结果不入库"
Assert-Match $cPassive 'stored=True source=passive complete=True apis=2 dr=2' "缓存：完整结果入库且字段齐全"
Assert-Match $cNoChange 'rewrote=False'           "缓存：数据无变化不重写文件"
Assert-Match $cTmp 'ignored=True'                 "缓存：残留 .tmp 不影响读取有效条目"
Assert-Match $cTmpOnly 'lookup=False'             "缓存：只有 .tmp（写到一半被 kill）时无有效条目"
Assert-Match $cPeek 'afterJarChange=False'        "缓存：jar 变了则旧基线不再算数"

# 3) 被动捕获
$passive = Get-CaseLine $run.Out "PASSIVE active"
Assert-Match $passive 'activeInside=True endedActive=False' "被动：窗口正确开合"
Assert-Match $passive 'complete=True'             "被动：正常窗口 complete=true"
Assert-Match $passive 'dr=1'                      "被动：DeferredRegister 计数 = 1"
Assert-Match $passive 'hasApiCreate=True'         "被动：记到 api DeferredRegister.create"
Assert-Match $passive 'hasApiNeoReg=True'         "被动：记到 NeoForge 风格 IEventBus.register"
Assert-Match $passive 'hasMirageEvent=True'       "被动：记到 MG 事件总线的 @SubscribeEvent 类型"
Assert-Match $passive 'hasNeoEvent=True'          "被动：记到 NeoForge 总线的 @SubscribeEvent 类型"
if ($passive -match 'apis=(\d+)') {
    Assert-True ([int]$Matches[1] -ge 4) ("被动：api 触达条数 {0}（≥4）" -f $Matches[1])
} else {
    Assert-True $false "被动：缺 apis 字段"
}

$outside = Get-CaseLine $run.Out "PASSIVE outsideNoop"
Assert-Match $outside 'outsideNoop=True'          "被动：窗口外的调用不产生结果（end 返回 null）"
Assert-Match $outside 'emptyApis=0'               "被动：下一个窗口不继承上一窗口的数据"
Assert-Match $outside 'hooksInstalled=True'       "被动：钩子已装到 PassiveHooks"

$failed = Get-CaseLine $run.Out "PASSIVE failedComplete"
Assert-Match $failed 'failedComplete=False'       "被动：有入口失败 → complete=false"
Assert-Match $failed 'failedReason=t.fail.Mod'    "被动：失败原因可追溯"

# 4) 版本伪装
$masq = Get-CaseLine $run.Out "MASQ"
Assert-Match $masq 'inRange=26.2'                 "伪装：当前版本在区间内 → 报当前版本（不伪装）"
Assert-Match $masq 'aboveUpper=26.1.1'            "伪装：超出上界 → 报上界（这才是伪装）"
Assert-Match $masq 'belowLower=26.4'              "伪装：低于下界 → 报下界"
Assert-Match $masq 'noRange=26.2'                 "伪装：无区间 → 基线"
Assert-Match $masq 'bare=26.1'                    "伪装：裸版本号原样返回"
Assert-Match $masq 'modNeo=26.1.1'                "伪装：mod 上下文里 FMLLoader 报伪装后的 NeoForge 版本"
Assert-Match $masq 'modMc=26.2'                   "伪装：MC 版本按该 mod 自己的 MC 区间取值"
Assert-Match $masq 'outside=26.2'                 "伪装：离开上下文后回到基线（不污染整进程）"
Assert-Match $masq 'major=26.2'                   "伪装：majorMcVersion 去掉补丁位"

# 5) 端到端 + diff
$e2e = Get-CaseLine $run.Out "E2E"
Assert-Match $e2e 'firstSource=passive'           "端到端：首启被动捕获入库，source=passive"
Assert-Match $e2e 'firstComplete=True'            "端到端：complete=true"
Assert-Match $e2e 'diff1Lines=0'                  "端到端：首次（无基线）不产生 diff"
Assert-Match $e2e 'diff2HasAdded=True'            "端到端：二启捕获到新增 API → 产出 +apis 行"
Assert-Match $e2e 'diffLogExists=True'            "端到端：mid-diff.log 已落盘"
Assert-Match $e2e 'diffLogHasMod=True'            "端到端：diff 报告里带 mod id"
Assert-Match $e2e 'source=passive'                "端到端：合并后仍标记为被动来源"

# ---------------------------------------------------------------- 收尾

if (-not $Keep) {
    try { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue } catch {}
}

Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host ("全部通过：{0} / {0}" -f $script:checks) -ForegroundColor Green
    exit 0
} else {
    Write-Host ("失败 {0} / {1}" -f $script:failures.Count, $script:checks) -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
    exit 1
}
