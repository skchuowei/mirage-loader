# scripts/verify_msr_p1.ps1
# verify_msr_p1.ps1 —— MSR P1（沙盒录制器进程内加固）回归验证
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p1.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p1.ps1 -Keep            # 保留临时目录
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_p1.ps1 -Jar <fatjar>    # 指定 jar
#
# 前提：先出 fat jar（必须是 fatJar 任务，build 会把同名产物覆盖成 67 KB 瘦 jar）：
#   gradle :launcher:mirage-launch:fatJar
#
# 为什么能脱离游戏测：
#   MSR 的沙盒是纯 JVM 行为（SandboxRecorder + SandboxClassLoader + fake 表面资源都在 fat jar 里），
#   测试 mod 也不需要真 MC —— fake 表面已经把 net.neoforged.* / net.minecraft.* 顶掉了。
#   所以脚本：编译 5 个测试 mod → 用 fat jar 当 classpath 跑一个检查程序，
#   整个过程不启动 MC（符合"虚拟桌面规则：AI 不拉 java 进游戏"）。
#
# 覆盖的契约（P1 五条，回归时最先坏的就是这些）：
#   1. System.exit(7) 被改写拦截 → 录制裁定 failed + 保留退出码，launcher 进程不死；
#   2. 构造死循环 → 约 2s 熔断 + incomplete=true + 半成品数据清空；
#   3. mod 改 Locale/TimeZone/系统属性后抛异常 → 三项全部恢复，既有属性保留；
#   4. 常规 mod（@Mod + DeferredRegister + @SubscribeEvent + 自带 mixin 配置）录制成功；
#   5. fake 表面未覆盖的类 → 诚实降级（error 能看出缺哪个类），不崩不静默。
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
$sandboxSrc = Join-Path $root "core\mirage-loader\src\sandbox\java"

if (-not $Jar) {
    $Jar = Join-Path $root "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
}
if (-not (Test-Path $Jar)) {
    Write-Host "[FAIL] 找不到 fat jar: $Jar" -ForegroundColor Red
    Write-Host "       先跑: gradle :launcher:mirage-launch:fatJar" -ForegroundColor Yellow
    exit 1
}
if (-not (Test-Path $sandboxSrc)) {
    Write-Host "[FAIL] 找不到 sandbox 源码目录: $sandboxSrc" -ForegroundColor Red
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
$jarTool = Find-JavaTool "jar"
if (-not $javac -or -not $java -or -not $jarTool) {
    Write-Host "[FAIL] 没找到 javac/java/jar（请设置 JAVA_HOME，或把 JDK bin 加进 PATH）" -ForegroundColor Red
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

# ---------------------------------------------------------------- 临时工作区

$work = Join-Path $env:TEMP ("mirage-msrp1-" + $PID)
New-Item -ItemType Directory -Force -Path $work | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $work "mods") | Out-Null

# 无 BOM 写入：javac 会把 UTF-8 BOM 当非法字符（error: illegal character: '\ufeff'）
function Write-NoBom([string]$path, [string]$text) {
    $dir = Split-Path $path -Parent
    if ($dir) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

Write-Host "== 检查 fat jar 是否带 fake 表面资源 ==" -ForegroundColor Cyan
$fatList = Invoke-Native $jarTool @("--list", "--file", $Jar) "fatlist"
if ($fatList.Code -ne 0) {
    Write-Host "[FAIL] 读不了 fat jar: $($fatList.Err)" -ForegroundColor Red
    exit 1
}
if ($fatList.Out -notmatch 'mirage/sandbox-fake/net/neoforged/fml/common/Mod\.class') {
    Write-Host "[FAIL] fat jar 里没有 mirage/sandbox-fake/ 资源 —— jar 不是最新构建的？" -ForegroundColor Red
    exit 1
}
Write-Host "   [OK] fake 表面在包内" -ForegroundColor DarkGreen

# ---------------------------------------------------------------- 测试 mod 源码
# 全部用默认包之外的包名，避免与 launcher 侧同名类打架。

$srcExitMod = @'
package t.exit;

import net.neoforged.fml.common.Mod;

@Mod("t_exit")
public class TExitMod {
    public TExitMod() {
        // 故意放到另一个类里：验证改写在 findClass 这一层，对同一 jar 的每个类都生效
        Killer.boom();
    }
}
'@

$srcExitKiller = @'
package t.exit;

public final class Killer {
    public static void boom() {
        System.exit(7);
    }

    private Killer() {}
}
'@

$srcLoop = @'
package t.loop;

import net.neoforged.fml.common.Mod;

@Mod("t_loop")
public class TLoopMod {
    public TLoopMod() {
        // 用 isInterrupted 收尾：让 interrupt 真的能让线程退出，避免测试进程自己残留一个自旋线程
        while (!Thread.currentThread().isInterrupted()) {
            Thread.onSpinWait();
        }
    }
}
'@

$srcWriter = @'
package t.writer;

import net.neoforged.fml.common.Mod;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.TimeZone;

@Mod("t_writer")
public class TWriterMod {
    public TWriterMod() throws Exception {
        // 三样全局态污染 + 一个真实文件副作用，然后抛异常
        Locale.setDefault(Locale.FRANCE);
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        System.setProperty("mirage.test.writer", "1");

        String out = System.getProperty("mirage.test.out");
        if (out != null) {
            File f = new File(out);
            File parent = f.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(f.toPath(), "side-effect".getBytes(StandardCharsets.UTF_8));
        }
        throw new IllegalStateException("writer boom");
    }
}
'@

$srcMixinMod = @'
package t.mixin;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod("t_mixin")
public class TMixinMod {

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("t_mixin");

    public TMixinMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register("foo", Item::new);
        ITEMS.register(bus);
        bus.register(new TMixinListeners());
    }
}
'@

$srcMixinListeners = @'
package t.mixin;

import net.neoforged.bus.api.SubscribeEvent;

public final class TMixinListeners {
    @SubscribeEvent
    public void onAnything(Object event) {
        // 沙盒不派发事件，只登记类型
    }
}
'@

$srcMissing = @'
package t.miss;

import net.neoforged.fml.common.Mod;

@Mod("t_miss")
public class TMissingMod {
    public TMissingMod() throws Exception {
        // fake 表面没有 FluidState：期望走"fake 表面未覆盖"的诚实 CNFE
        Class.forName("net.minecraft.world.level.material.FluidState");
    }
}
'@

# mixin 配置文件（模拟"带 Mixin 的 mod"：录制器不处理 mixin，但不能因此崩）
$mixinsJson = @'
{
  "required": true,
  "package": "t.mixin",
  "mixins": []
}
'@

# case 表：name / 源文件（相对路径 -> 文本）/ 额外资源
$cases = @(
    [pscustomobject]@{
        Name    = "exit"
        Sources = [ordered]@{ "t\exit\TExitMod.java" = $srcExitMod; "t\exit\Killer.java" = $srcExitKiller }
        Extra   = $null
    },
    [pscustomobject]@{
        Name    = "loop"
        Sources = [ordered]@{ "t\loop\TLoopMod.java" = $srcLoop }
        Extra   = $null
    },
    [pscustomobject]@{
        Name    = "writer"
        Sources = [ordered]@{ "t\writer\TWriterMod.java" = $srcWriter }
        Extra   = $null
    },
    [pscustomobject]@{
        Name    = "mixin"
        Sources = [ordered]@{ "t\mixin\TMixinMod.java" = $srcMixinMod; "t\mixin\TMixinListeners.java" = $srcMixinListeners }
        Extra   = $mixinsJson
    },
    [pscustomobject]@{
        Name    = "miss"
        Sources = [ordered]@{ "t\miss\TMissingMod.java" = $srcMissing }
        Extra   = $null
    }
)

Write-Host "== 编译 5 个测试 mod ==" -ForegroundColor Cyan
foreach ($case in $cases) {
    $srcDir = Join-Path $work ("src-" + $case.Name)
    $srcFiles = @()
    foreach ($rel in $case.Sources.Keys) {
        $p = Join-Path $srcDir $rel
        Write-NoBom $p $case.Sources[$rel]
        $srcFiles += $p
    }
    $outDir = Join-Path $work ("classes\" + $case.Name)
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    # -implicit:none 关键：否则 javac 会把 sourcepath 里隐式编译的 fake 类写进 mod jar，
    # 运行时就不是从 fat jar 的 mirage/sandbox-fake/ 取 fake 了，测试会变成自欺欺人。
    $argv = @(
        "-J-Duser.language=en", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
        "-d", $outDir, "-cp", $Jar, "-sourcepath", $sandboxSrc
    ) + $srcFiles
    $res = Invoke-Native $javac $argv ("javac-" + $case.Name)
    if ($res.Code -ne 0) {
        Write-Host "[FAIL] 编译 $($case.Name) 失败" -ForegroundColor Red
        if ($res.Out.Trim()) { Write-Host $res.Out }
        if ($res.Err.Trim()) { Write-Host $res.Err -ForegroundColor DarkYellow }
        exit 1
    }

    if ($case.Extra) {
        Write-NoBom (Join-Path $outDir ("mixins." + $case.Name + ".json")) $case.Extra
    }

    $jarPath = Join-Path $work ("mods\" + $case.Name + ".jar")
    $res = Invoke-Native $jarTool @("--create", "--file", $jarPath, "-C", $outDir, ".") ("jar-" + $case.Name)
    if ($res.Code -ne 0) {
        Write-Host "[FAIL] 打包 $($case.Name) 失败: $($res.Err)" -ForegroundColor Red
        exit 1
    }
    # 断言 mod jar 里只有 mod 自己的类（不该混进 net/ 下的 fake）
    $ls = Invoke-Native $jarTool @("--list", "--file", $jarPath) ("list-" + $case.Name)
    if ($ls.Out -match '(?m)^net/') {
        Write-Host "[FAIL] $($case.Name).jar 里混进了 net/ 类（-implicit:none 没生效？）" -ForegroundColor Red
        exit 1
    }
    Write-Host ("   [OK] {0}.jar" -f $case.Name) -ForegroundColor DarkGreen
}

# ---------------------------------------------------------------- 检查程序

$harnessSrc = Join-Path $work "harness-src\io\mirage\loader\sandbox\check\MsrP1Check.java"
$harnessText = @'
package io.mirage.loader.sandbox.check;

import io.mirage.loader.MidCache;
import io.mirage.loader.NeoForgeModDetector;
import io.mirage.loader.sandbox.SandboxRecorder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.TimeZone;

/**
 * MSR P1 回归用例（由 scripts/verify_msr_p1.ps1 编译运行）。
 * 只输出固定的 ASCII 键值行，断言全部在 PowerShell 侧做。
 */
public final class MsrP1Check {

    public static void main(String[] args) throws Exception {
        Path work = Paths.get(args[0]);
        Locale origLocale = Locale.getDefault();
        TimeZone origTz = TimeZone.getDefault();

        // 1) 构造里 System.exit(7)：进程必须活着，且错误里带原退出码
        MidCache.Recorded r = rec(work, "exit", "t_exit", "t.exit.TExitMod");
        System.out.println("CASE-EXIT alive=true failed=" + r.failed
                + " incomplete=" + r.incomplete + " error=" + oneLine(r.error));

        // 2) 构造死循环：约 2s 熔断 + incomplete + 半成品清空
        long t0 = System.nanoTime();
        r = rec(work, "loop", "t_loop", "t.loop.TLoopMod");
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("CASE-LOOP elapsed_ms=" + ms + " failed=" + r.failed
                + " incomplete=" + r.incomplete + " error=" + oneLine(r.error)
                + " apis=" + size(r.touched_apis) + " dr=" + r.deferred_register_count);

        // 3) 全局态污染 + 文件副作用 + 抛异常
        Path sideEffect = work.resolve("writer-side-effect.txt");
        Files.deleteIfExists(sideEffect);
        System.setProperty("mirage.test.out", sideEffect.toString());
        System.setProperty("mirage.test.pre", "keep");
        r = rec(work, "writer", "t_writer", "t.writer.TWriterMod");
        System.out.println("CASE-WRITER failed=" + r.failed + " error=" + oneLine(r.error)
                + " localeRestored=" + origLocale.equals(Locale.getDefault())
                + " tzRestored=" + origTz.getID().equals(TimeZone.getDefault().getID())
                + " propCleared=" + (System.getProperty("mirage.test.writer") == null)
                + " preKept=" + "keep".equals(System.getProperty("mirage.test.pre"))
                + " sideEffect=" + Files.exists(sideEffect));

        // 4) 常规 mod（@Mod + DeferredRegister + @SubscribeEvent + 自带 mixin 配置）
        r = rec(work, "mixin", "t_mixin", "t.mixin.TMixinMod");
        System.out.println("CASE-MIXIN failed=" + r.failed + " error=" + oneLine(r.error)
                + " dr=" + r.deferred_register_count + " apis=" + size(r.touched_apis)
                + " events=" + size(r.registered_events));

        // 5) fake 表面未覆盖：诚实降级（错误里能看出缺哪个类）
        r = rec(work, "miss", "t_miss", "t.miss.TMissingMod");
        System.out.println("CASE-MISS failed=" + r.failed + " error=" + oneLine(r.error));

        // fake 表面指纹：非空、进程内稳定
        String v1 = SandboxRecorder.fakeVersion();
        String v2 = SandboxRecorder.fakeVersion();
        System.out.println("FAKEVERSION v=" + v1 + " stable=" + v1.equals(v2));

        System.out.println("HARNESS-DONE");
    }

    private static MidCache.Recorded rec(Path work, String caseName, String id, String mainClass) {
        NeoForgeModDetector.Detected d =
                new NeoForgeModDetector.Detected(id, "1.0", id, mainClass);
        return SandboxRecorder.record(work.resolve("mods").resolve(caseName + ".jar"), d);
    }

    private static int size(java.util.List<String> list) {
        return list == null ? -1 : list.size();
    }

    private static String oneLine(String s) {
        return s == null ? "-" : s.replace('\r', ' ').replace('\n', ' ');
    }

    private MsrP1Check() {}
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

# ---------------------------------------------------------------- 跑

Write-Host "== 跑 5 个沙盒用例（纯 JVM，不启动 MC）==" -ForegroundColor Cyan
$cp = $Jar + ";" + $harnessOut
$run = Invoke-Native $java @(
    "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
    "-cp", $cp, "io.mirage.loader.sandbox.check.MsrP1Check", $work
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

# 进程没被 exit 带走 = 能读回任何一行结果
Assert-Match $run.Out 'HARNESS-DONE' "整个用例集跑完（没有任何 exit/halt 把 JVM 带走）"
if ($run.Code -ne 0) {
    Write-Host "   [INFO] java 退出码 $($run.Code)（若上面 [FAIL] 与 exit 有关，这里就是被 System.exit 带走了）" -ForegroundColor Yellow
    if ($run.Err.Trim()) { Write-Host $run.Err -ForegroundColor DarkYellow }
}

# 1) exit
$exitLine = Get-CaseLine $run.Out "CASE-EXIT"
Assert-True ($exitLine -ne "") "exit：录制跑完并返回结果（进程活着）"
Assert-Match $exitLine 'failed=true' "exit：降级为 failed"
Assert-Match $exitLine 'error=.*exit\(7\)' "exit：错误里保留原退出码 exit(7)"
Assert-Match $exitLine 'incomplete=false' "exit：不算 incomplete（不是超时）"

# 2) 死循环
$loopLine = Get-CaseLine $run.Out "CASE-LOOP"
Assert-Match $loopLine 'error=timeout' "死循环：判为 timeout"
Assert-Match $loopLine 'incomplete=true' "死循环：打上 incomplete 标记"
Assert-Match $loopLine 'apis=0' "死循环：半成品 touched_apis 被清空（不入库）"
Assert-Match $loopLine 'dr=0' "死循环：半成品 DeferredRegister 计数被清空"
if ($loopLine -match 'elapsed_ms=(\d+)') {
    $ms = [int]$Matches[1]
    Assert-True ($ms -ge 1800 -and $ms -le 8000) ("死循环：约 2s 熔断，实际 {0}ms" -f $ms)
} else {
    Assert-True $false "死循环：缺 elapsed_ms 字段"
}

# 3) 全局态
$writerLine = Get-CaseLine $run.Out "CASE-WRITER"
Assert-Match $writerLine 'failed=true' "写文件 mod：构造函数异常被降级"
Assert-Match $writerLine 'error=.*writer boom' "写文件 mod：错误保留真实 cause（writer boom）"
Assert-Match $writerLine 'localeRestored=true' "全局态：Locale 已恢复"
Assert-Match $writerLine 'tzRestored=true' "全局态：TimeZone 已恢复"
Assert-Match $writerLine 'propCleared=true' "全局态：mod 新加的系统属性已清除"
Assert-Match $writerLine 'preKept=true' "全局态：录制前已有的系统属性保留"
Assert-Match $writerLine 'sideEffect=true' "文件副作用照常发生（进程内拦不住，已知边界）"

# 4) 常规 mod
$mixinLine = Get-CaseLine $run.Out "CASE-MIXIN"
Assert-Match $mixinLine 'failed=false' "常规 mod：录制成功（不因自带 mixin 配置而崩）"
Assert-Match $mixinLine 'dr=1' "常规 mod：DeferredRegister 计数 = 1"
Assert-Match $mixinLine 'events=1' "常规 mod：@SubscribeEvent 方法进了 registered_events"
if ($mixinLine -match 'apis=(\d+)') {
    Assert-True ([int]$Matches[1] -ge 3) ("常规 mod：touched_apis 有 {0} 条（≥3）" -f $Matches[1])
} else {
    Assert-True $false "常规 mod：缺 apis 字段"
}

# 5) fake 缺口
$missLine = Get-CaseLine $run.Out "CASE-MISS"
Assert-Match $missLine 'failed=true' "fake 缺口：诚实降级为 failed"
Assert-Match $missLine 'ClassNotFoundException' "fake 缺口：错误类型是 CNFE"
Assert-Match $missLine 'FluidState' "fake 缺口：错误里点名缺的类"

# fake 指纹
$fvLine = Get-CaseLine $run.Out "FAKEVERSION"
Assert-Match $fvLine 'stable=true' "fake 指纹：进程内稳定"
if ($fvLine -match 'v=(\w+)') {
    Assert-True ($Matches[1] -ne "none") ("fake 指纹：非退化值（{0}）" -f $Matches[1])
} else {
    Assert-True $false "fake 指纹：缺 v= 字段"
}

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
