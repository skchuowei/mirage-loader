# scripts/verify_neo_ctor.ps1
# N1 回归：NeoForge 链接期 stub 语义 + mod 构造签名选择（纯 JVM，不启动游戏）
#
# 覆盖：
#   A. FMLPaths / FMLEnvironment：路径与端侧确实可注入、非相对路径
#   B. ModList / ModContainer / NeoModInfo：登记、按 id 查找、缺失返回空
#   C. IModInfo → IModFileInfo → IModFile → JarContents：能从 mod jar 读 entry
#   D. ModConstructorPicker：() / (IEventBus) / (IEventBus, Dist) /
#      (IEventBus, ModContainer) / (IEventBus, ModContainer, Dist) 的选择顺序；
#      参数为 null 时跳过该签名；同签名的 no-arg 不得压过注入式构造
#
# 为什么这样测：这些类除了 NeoEventBusImpl 之外都是纯 JDK，能离线编译；
# 构造选择器是本轮最容易"看起来对、实际选错"的纯逻辑，必须直接测生产源码。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_neo_ctor.ps1
#   powershell ... -Mutation no-dist|no-container|no-arg-first   # 变异检验（应退出 1）
#
# 变异检验（实际做过，全部正确变红）：
#   no-dist        把 Dist 类型名常量改错 → (IEventBus, Dist) 夹具断言红
#   no-container   把 ModContainer 类型名常量改错 → (IEventBus, ModContainer) 夹具断言红
#   no-arg-first   让无参构造优先 → 「注入式优先于无参」断言红

[CmdletBinding()]
param(
    [ValidateSet("none", "no-dist", "no-container", "no-arg-first")]
    [string]$Mutation = "none",
    [switch]$Keep,
    [string]$ReportFile = (Join-Path $env:TEMP "verify_neo_ctor.report.txt")
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
$compatSrc = Join-Path $root "modules\mirage-neoforge-compat\src\main\java"
$lsSrc = Join-Path $root "lifecycle-starter\src\main\java\io\mirage\ls\stage"

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

function Find-JavaTool {
    param([string]$Name)
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$Name.exe") }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($v in @("jdk-25.0.4", "jdk-21.0.11", "jdk-17")) {
        $candidates += "C:\Program Files\Java\$v\bin\$Name.exe"
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

$javac = Find-JavaTool "javac"
$java = Find-JavaTool "java"
if (-not $javac -or -not $java) {
    Say "[FAIL] 没找到 javac/java（请设置 JAVA_HOME，或把 JDK bin 加进 PATH）"
    Save-Report
    exit 1
}

Say "== N1 回归：链接期 stub + 构造签名选择 =="
Say ("Mutation = " + $Mutation)

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-neoctor-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$checkDir = Join-Path $work "check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null

# --- 待编译的生产源码（显式列出，避免把依赖 Gson/MirageLog 的重类拖进来） ---
$sources = @(
    "$compatSrc\net\neoforged\api\distmarker\Dist.java",
    "$compatSrc\net\neoforged\bus\api\Event.java",
    "$compatSrc\net\neoforged\bus\api\EventPriority.java",
    "$compatSrc\net\neoforged\bus\api\ICancellableEvent.java",
    "$compatSrc\net\neoforged\bus\api\IEventBus.java",
    "$compatSrc\net\neoforged\fml\event\IModBusEvent.java",
    "$compatSrc\net\neoforged\fml\jarcontents\JarContents.java",
    "$compatSrc\net\neoforged\fml\loading\FMLPaths.java",
    "$compatSrc\net\neoforged\fml\loading\FMLEnvironment.java",
    "$compatSrc\net\neoforged\fml\loading\LoadingModList.java",
    "$compatSrc\net\neoforged\fml\loading\moddiscovery\ModInfo.java",
    "$compatSrc\net\neoforged\fml\ModContainer.java",
    "$compatSrc\net\neoforged\fml\ModList.java",
    "$compatSrc\net\neoforged\neoforgespi\language\IConfigurable.java",
    "$compatSrc\net\neoforged\neoforgespi\language\IModFileInfo.java",
    "$compatSrc\net\neoforged\neoforgespi\language\IModInfo.java",
    "$compatSrc\net\neoforged\neoforgespi\locating\IModFile.java",
    "$compatSrc\io\mirage\neoforge\runtime\NeoModInfo.java",
    "$compatSrc\io\mirage\neoforge\runtime\NeoModFileInfo.java",
    "$compatSrc\io\mirage\neoforge\runtime\NeoModFile.java",
    "$compatSrc\io\mirage\neoforge\runtime\NeoJarContents.java"
)
foreach ($s in $sources) {
    if (-not (Test-Path $s)) {
        Say ("[FAIL] 找不到生产源码: " + $s)
        Save-Report
        exit 1
    }
}

# --- 构造选择器：按变异模式复制到临时目录并改坏 ---
$pickerPath = Join-Path $lsSrc "ModConstructorPicker.java"
if (-not (Test-Path $pickerPath)) {
    Say ("[FAIL] 找不到生产源码: " + $pickerPath)
    Save-Report
    exit 1
}
$pickerCopy = Join-Path $checkDir "ModConstructorPicker.java"
$pickerOriginal = [System.IO.File]::ReadAllText($pickerPath, [System.Text.Encoding]::UTF8)
$pickerText = $pickerOriginal
if ($Mutation -eq 'no-dist') {
    $pickerText = $pickerText.Replace('"net.neoforged.api.distmarker.Dist"', '"net.neoforged.api.distmarker.DistX"')
} elseif ($Mutation -eq 'no-container') {
    $pickerText = $pickerText.Replace('"net.neoforged.fml.ModContainer"', '"net.neoforged.fml.ModContainerX"')
} elseif ($Mutation -eq 'no-arg-first') {
    $pickerText = $pickerText.Replace(
        'Match match = tryPick(type, new Object[]{modBus, container, dist},',
        'Match early = tryPick(type, new Object[0], new String[0]); if (early != null) { return early; } Match match = tryPick(type, new Object[]{modBus, container, dist},')
}
if ($Mutation -ne 'none' -and $pickerText -eq $pickerOriginal) {
    Say ("[FAIL] 变异 " + $Mutation + " 未实际应用（替换锚点漂移）")
    Save-Report
    exit 1
}
[System.IO.File]::WriteAllText($pickerCopy, $pickerText, (New-Object System.Text.UTF8Encoding($false)))
$sources += $pickerCopy

# --- 检查程序 ---
$checkSrc = Join-Path $checkDir "N1Check.java"
$checkCode = @'
package io.mirage.ls.check;

import io.mirage.ls.stage.ModConstructorPicker;
import io.mirage.neoforge.runtime.NeoJarContents;
import io.mirage.neoforge.runtime.NeoModFile;
import io.mirage.neoforge.runtime.NeoModFileInfo;
import io.mirage.neoforge.runtime.NeoModInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** N1 回归检查：只测生产源码，不在测试里重写选择逻辑。 */
public final class N1Check {

    private static int pass = 0;
    private static int fail = 0;
    private static final java.util.ArrayList<String> failures = new java.util.ArrayList<>();

    private static void check(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + msg);
        } else {
            fail++;
            failures.add(msg);
            System.out.println("  [FAIL] " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("mirage-n1-");
        try {
            stubSemantics(temp);
            constructorSelection();
        } finally {
            deleteTree(temp);
        }

        System.out.println();
        System.out.println("PASS " + pass + " / FAIL " + fail);
        if (fail > 0) {
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    // ---- A/B/C：stub 语义 ----
    private static void stubSemantics(Path temp) throws Exception {
        FMLPaths.init(temp);
        check(FMLPaths.GAMEDIR.get().isAbsolute(), "A1 GAMEDIR 是绝对路径（不依赖 CWD）");
        check(FMLPaths.GAMEDIR.get().equals(temp.toAbsolutePath().normalize()), "A2 GAMEDIR = 注入目录");
        check(FMLPaths.CONFIGDIR.get().equals(FMLPaths.GAMEDIR.get().resolve("config")), "A3 CONFIGDIR = <gameDir>/config");
        check(FMLPaths.MODSDIR.get().equals(FMLPaths.GAMEDIR.get().resolve("mods")), "A4 MODSDIR = <gameDir>/mods");
        check(FMLPaths.CONFIGDIR instanceof FMLPaths, "A5 CONFIGDIR 字段类型是 FMLPaths（getstatic 形态一致）");

        FMLEnvironment.init(Dist.DEDICATED_SERVER);
        check(FMLEnvironment.getDist() == Dist.DEDICATED_SERVER, "A6 端侧可注入为 DEDICATED_SERVER");
        check(!FMLEnvironment.getDist().isClient(), "A7 DEDICATED_SERVER.isClient() = false");
        FMLEnvironment.init(Dist.CLIENT);
        check(FMLEnvironment.getDist().isClient(), "A8 端侧可注入为 CLIENT");

        IEventBus bus = dummyBus();
        Path jar = temp.resolve("fake-mod.jar");
        writeJar(jar, "assets/test/icon.png", "icon-bytes".getBytes(StandardCharsets.UTF_8));

        NeoModInfo info = new NeoModInfo("demo", "1.0.0", "Demo Mod", jar);
        check("demo".equals(info.getModId()), "B1 IModInfo.modId");
        check("Demo Mod".equals(info.getDisplayName()), "B2 IModInfo.displayName");
        check(info.getConfig().getConfigElement("a").isEmpty(), "B3 getConfigElement 返回空（不猜自定义 TOML）");
        check(info.getLogoFile().isEmpty(), "B4 logoFile 未配置返回空");

        ModContainer container = new ModContainer(info, bus);
        ModList.clear();
        ModList.register(container);
        check(ModList.get().isLoaded("demo"), "B5 ModList 登记后可查询 isLoaded");
        Optional<ModContainer> got = ModList.get().getModContainerById("demo");
        check(got.isPresent() && got.get().getModInfo().getModId().equals("demo"), "B6 getModContainerById 命中");
        check(ModList.get().getModContainerById("missing").isEmpty(), "B7 未登记 id 返回 Optional.empty");
        check(ModList.get().getMods().size() == 1, "B8 getMods 数量");
        ModList.clear();
        check(!ModList.get().isLoaded("demo"), "B9 clear 后不可见");

        NeoModFileInfo fileInfo = new NeoModFileInfo(jar);
        check(fileInfo.getFile() != null && fileInfo.getFile().getContents() != null,
                "C1 IModFileInfo.getFile().getContents() 非空");
        byte[] bytes = new NeoJarContents(jar).readFile("assets/test/icon.png");
        check(bytes != null && new String(bytes, StandardCharsets.UTF_8).equals("icon-bytes"), "C2 JarContents 能读 jar entry");
        check(new NeoJarContents(jar).readFile("missing.bin") == null, "C3 缺失 entry 返回 null");
    }

    // ---- D：构造签名选择 ----
    private static void constructorSelection() {
        IEventBus bus = dummyBus();
        Dist dist = Dist.CLIENT;
        ModContainer container = new ModContainer(
                new NeoModInfo("demo", "1.0.0", "Demo", Path.of("demo.jar")), bus);

        ModConstructorPicker.Match m0 = ModConstructorPicker.pick(NoArgMod.class, bus, dist, container);
        check(m0 != null && m0.signature().equals("()"), "D1 无参构造可选，实际=" + sig(m0));

        ModConstructorPicker.Match m1 = ModConstructorPicker.pick(BusMod.class, bus, dist, container);
        check(m1 != null && m1.signature().equals("(IEventBus)"), "D2 (IEventBus) 可选，实际=" + sig(m1));

        ModConstructorPicker.Match m2 = ModConstructorPicker.pick(BusDistMod.class, bus, dist, container);
        check(m2 != null && m2.signature().equals("(IEventBus, Dist)"), "D3 (IEventBus, Dist) 可选，实际=" + sig(m2));

        ModConstructorPicker.Match m3 = ModConstructorPicker.pick(BusContainerMod.class, bus, dist, container);
        check(m3 != null && m3.signature().equals("(IEventBus, ModContainer)"), "D4 (IEventBus, ModContainer) 可选，实际=" + sig(m3));

        ModConstructorPicker.Match m4 = ModConstructorPicker.pick(BusContainerDistMod.class, bus, dist, container);
        check(m4 != null && m4.signature().equals("(IEventBus, ModContainer, Dist)"),
                "D5 三参构造优先，实际=" + sig(m4));

        ModConstructorPicker.Match m5 = ModConstructorPicker.pick(BusAndNoArgMod.class, bus, dist, container);
        check(m5 != null && m5.signature().equals("(IEventBus)"),
                "D6 注入式优先于无参（不得先选 no-arg），实际=" + sig(m5));

        check(ModConstructorPicker.pick(BusDistMod.class, bus, null, container) == null,
                "D7 dist=null 时跳过 (IEventBus, Dist)，不把 null 传给构造器");
        check(ModConstructorPicker.pick(BusContainerMod.class, bus, dist, null) == null,
                "D8 container=null 时跳过 (IEventBus, ModContainer)");
        check(ModConstructorPicker.pick(BusContainerDistMod.class, bus, null, null) == null,
                "D9 两个上下文都缺时三参/二参均跳过");
        check(ModConstructorPicker.pick(NoCtorMod.class, bus, dist, container) == null,
                "D10 没有匹配签名返回 null（调用方报错，不静默）");

        ModConstructorPicker.Match m11 = ModConstructorPicker.pick(PrivateNoArgMod.class, bus, dist, container);
        check(m11 != null && m11.signature().equals("()"), "D11 私有但可访问的无参构造保留旧行为，实际=" + sig(m11));
    }

    private static String sig(ModConstructorPicker.Match m) {
        return m == null ? "null" : m.signature();
    }

    private static IEventBus dummyBus() {
        return (IEventBus) Proxy.newProxyInstance(
                N1Check.class.getClassLoader(), new Class<?>[]{IEventBus.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("toString")) return "DummyBus";
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    return null;
                });
    }

    private static void writeJar(Path jar, String entry, byte[] data) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry(entry));
            out.write(data);
            out.closeEntry();
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }

    // ---- 构造签名夹具 ----
    public static final class NoArgMod { public NoArgMod() { } }
    public static final class BusMod { public BusMod(IEventBus bus) { } }
    public static final class BusDistMod { public BusDistMod(IEventBus bus, Dist dist) { } }
    public static final class BusContainerMod { public BusContainerMod(IEventBus bus, ModContainer c) { } }
    public static final class BusContainerDistMod {
        public BusContainerDistMod(IEventBus bus, ModContainer c, Dist d) { }
    }
    public static final class BusAndNoArgMod {
        public BusAndNoArgMod() { }
        public BusAndNoArgMod(IEventBus bus) { }
    }
    public static final class NoCtorMod { private NoCtorMod(int x) { } }
    public static final class PrivateNoArgMod { private PrivateNoArgMod() { } }
}
'@
[System.IO.File]::WriteAllText($checkSrc, $checkCode, (New-Object System.Text.UTF8Encoding($false)))

Say ("-- 编译 " + $sources.Count + " 个生产源 + 检查程序")
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javac
$psi.Arguments = "-encoding UTF-8 -d `"$classes`" " + (($sources | ForEach-Object { "`"$_`"" }) -join " ") + " `"$checkSrc`""
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$c = New-Object System.Diagnostics.Process
$c.StartInfo = $psi
[void]$c.Start()
$javacOut = $c.StandardOutput.ReadToEnd()
$javacErr = $c.StandardError.ReadToEnd()
$c.WaitForExit()
if ($c.ExitCode -ne 0) {
    Say "[FAIL] javac 失败："
    Say $javacOut
    Say $javacErr
    Save-Report
    exit 1
}

Say "-- 运行检查程序"
$psi2 = New-Object System.Diagnostics.ProcessStartInfo
$psi2.FileName = $java
$psi2.Arguments = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$classes`" io.mirage.ls.check.N1Check"
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError = $true
$psi2.UseShellExecute = $false
$psi2.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi2.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$r = New-Object System.Diagnostics.Process
$r.StartInfo = $psi2
[void]$r.Start()
$checkOut = $r.StandardOutput.ReadToEnd()
$checkErr = $r.StandardError.ReadToEnd()
$r.WaitForExit()
Say $checkOut
if ($r.ExitCode -ne 0) {
    Say ("[FAIL] 检查程序退出码 " + $r.ExitCode)
    Say $checkErr
    $script:fail++
    $script:failures += "检查程序退出码 " + $r.ExitCode
}

if ($checkOut -match 'PASS (\d+) / FAIL (\d+)') {
    $script:pass += [int]$Matches[1]
    $script:fail += [int]$Matches[2]
} else {
    Say "[FAIL] 检查程序输出里没有 'PASS n / FAIL m' 汇总行"
    $script:fail++
    $script:failures += "检查程序未输出汇总行"
}

Say "-- 源码守卫"

$pickerSrc = Get-Content $pickerPath -Raw -Encoding UTF8
Assert-True ($pickerSrc -match 'trySetAccessible\(\)') "守卫1 构造器访问用 trySetAccessible（Java 25 下失败可安全跳过）"
Assert-True (-not ($pickerSrc -match '(?m)^\s*import\s+net\.neoforged\.')) "守卫2 选择器不得直接 import neo 类（app/MCL 两份 Class 会永不匹配）"
Assert-True ($pickerSrc -match 'net\.neoforged\.bus\.api\.IEventBus') "守卫3 用类名字符串匹配 IEventBus"
Assert-True ($pickerSrc -match 'net\.neoforged\.api\.distmarker\.Dist') "守卫4 用类名字符串匹配 Dist"
Assert-True ($pickerSrc -match 'net\.neoforged\.fml\.ModContainer') "守卫5 用类名字符串匹配 ModContainer"

$neoLifeSrc = Get-Content (Join-Path $lsSrc "NeoLifecycle.java") -Raw -Encoding UTF8
Assert-True ($neoLifeSrc -match 'ModConstructorPicker\.pick\(') "守卫6 NeoLifecycle 走选择器"
Assert-True (-not ($neoLifeSrc -match 'getConstructor\(busCls\)')) "守卫7 旧的一参注入分支已移除（不得双轨）"

$bridgeSrc = Get-Content (Join-Path $lsSrc "NeoCompatBridge.java") -Raw -Encoding UTF8
Assert-True ($bridgeSrc -match 'Class\.forName\("io\.mirage\.neoforge\.runtime\.NeoCompatBootstrap"') `
    "守卫8 LS 只反射进入 NeoCompatBootstrap"
Assert-True (-not ($bridgeSrc -match '(?m)^\s*import\s+(net\.neoforged|io\.mirage\.neoforge)\.')) `
    "守卫9 桥类不得直接 import neo 类（否则 app loader 又加载一份）"

$modInitSrc = Get-Content (Join-Path $lsSrc "ModInitStage.java") -Raw -Encoding UTF8
$initCall = [regex]::Match($modInitSrc, '(?m)^\s*NeoCompatBridge\.init\(')
$loopStart = [regex]::Match($modInitSrc, '(?m)^\s*for \(ModContainer mod : mods\)')
Assert-True ($initCall.Success) "守卫10 S6 调用 NeoCompatBridge.init"
if ($initCall.Success -and $loopStart.Success) {
    Assert-True ($initCall.Index -lt $loopStart.Index) ("守卫11 初始化早于 mod 循环（偏移 " + $initCall.Index + " < " + $loopStart.Index + "）")
}

Say ""
Say ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==")
if ($script:fail -gt 0) {
    Say "失败项："
    foreach ($f in $script:failures) { Say ("  - " + $f) }
}
Save-Report
Say ("报告: " + $ReportFile)

if (-not $Keep) {
    try { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue } catch { }
} else {
    Say ("临时目录保留: " + $work)
}

if ($script:fail -gt 0) { exit 1 } else { exit 0 }
