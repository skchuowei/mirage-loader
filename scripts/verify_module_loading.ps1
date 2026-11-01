# scripts/verify_module_loading.ps1
# verify_module_loading.ps1 —— G9 模块懒加载的回归用例（纯 JVM，不启动游戏）
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_module_loading.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_module_loading.ps1 -Keep   # 保留临时目录
#
# 为什么这样测：
#   模块懒加载横跨「描述符解析 → 依赖闭包 → 类加载器加 URL → 入口反射」四段，
#   最后两段要真游戏才能验。但**前两段是纯逻辑**，可以在 JVM 里单独跑：
#     1. 描述符解析：id/version/provides/requires/entrypoint 的解析与合法性
#     2. 依赖闭包：A→B→C 传递展开、循环依赖能终止、缺失模块不炸
#   这两段做错的话，真实启动时表现为「模块没加载」或「启动卡死」，
#   而日志只告诉你"模块没找到"，根因很难倒推——所以必须离线钉死。
#
#   另加两条**源码守卫**（它们是本设计最容易踩、且踩了必然线上炸的坑）：
#     3. 三个模块的 provides 前缀都不得以 io.mirage. 开头
#        （MirageClassLoader 把 io.mirage. 前缀委派给 app loader，
#          而模块 jar 不在 app loader 的 classpath → NoClassDefFoundError）
#     4. ModManifest.requires 字段存在且注释说明它与 depends 的区别
#
# 报告落盘：Write-Host 走信息流，管道里捕获不到，
#   所以本脚本把全部明细写到 $ReportFile（默认 %TEMP%\verify_module_loading.report.txt）。
#
# 变异检验（实际做过，全部正确变红）：
#   ① ModuleDescriptor.parse 里 requires 解析改成永远返回空 → 闭包断言红；
#   ② ModuleGraph.collectClosure 的 visited 检查去掉 → 循环依赖断言红（栈溢出）；
#   ③ ModuleDescriptor.isValid 里的 io.mirage. 前缀检查去掉 → 包名守卫红；
#   ④ ModuleGraph.collectRequests 里 putIfAbsent 换成 put → 「取首个声明者」断言红；
#   ⑤ ModuleRegistry 不委派 ModuleGraph、自己另算一份 → 源码守卫红。

[CmdletBinding()]
param(
    [switch]$Keep,
    # 报告落盘路径。Write-Host 走信息流，管道里捕获不到，
    # 所以这份文件是「拿到完整明细」的唯一稳定通道（CI 也能读）。
    [string]$ReportFile = (Join-Path $env:TEMP "verify_module_loading.report.txt")
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
$coreSrc = Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader"

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

# --- 定位 JDK ---
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

# --- 定位 gson ---
# ModuleDescriptor 用 Gson 解析 mirage-module.json。gson 在工程里是
# api/mirage-api 的 `api(libs.gson)` 依赖（版本目录 gson = 2.11.0），
# 运行时一定在 fat jar 里；但**离线编译**需要显式给出 jar 路径。
# 查找顺序：环境变量覆盖 → gradle 缓存 → MC 自己的 libraries 目录。
function Find-GsonJar {
    if ($env:MIRAGE_GSON_JAR -and (Test-Path $env:MIRAGE_GSON_JAR)) {
        return $env:MIRAGE_GSON_JAR
    }
    $roots = @(
        (Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\com.google.code.gson\gson"),
        (Join-Path $env:USERPROFILE ".minecraft\libraries\com\google\code\gson\gson")
    )
    foreach ($r in $roots) {
        if (Test-Path $r) {
            $hit = Get-ChildItem -Path $r -Recurse -Filter "gson-*.jar" -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    # MC 安装目录（本仓库的约定位置）
    $mcLib = "D:\bing\MC\mcmoderr-my\mc-shili\libraries\com\google\code\gson\gson"
    if (Test-Path $mcLib) {
        $hit = Get-ChildItem -Path $mcLib -Recurse -Filter "gson-*.jar" -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    return $null
}

$gsonJar = Find-GsonJar
if (-not $gsonJar) {
    Say "[FAIL] 没找到 gson jar（ModuleDescriptor 需要它解析描述符）。"
    Say "       修法：先跑一次 gradle build（会拉取依赖进缓存），"
    Say "       或设环境变量 MIRAGE_GSON_JAR 指向 gson-*.jar 的绝对路径。"
    Save-Report
    exit 1
}

Say "== G9 模块懒加载回归 =="

# --- 编译被测类 + 检查程序 ---
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-modchk-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$classes = Join-Path $work "classes"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$sources = @(
    # 只编纯逻辑那三个类：ModuleDescriptor（描述符解析）、ModuleGraph（需求收集 + 依赖闭包）、
    # ModManifest（纯 POJO）。它们不依赖 MirageClassLoader / ASM / 游戏类，
    # 因此这段「纯逻辑」可以完全脱离游戏与 fat jar 验证。
    (Join-Path $coreSrc "module\ModuleDescriptor.java"),
    (Join-Path $coreSrc "module\ModuleGraph.java"),
    (Join-Path $root "core\mirage-loader\src\main\java\io\mirage\loader\ModManifest.java")
)
foreach ($s in $sources) {
    if (-not (Test-Path $s)) {
        Say ("[FAIL] 找不到源码: " + $s)
        Save-Report
        exit 1
    }
}

# 检查程序：放在 io.mirage.loader.module.check 包下，栈帧能命中项目前缀
$checkDir = Join-Path $work "check"
New-Item -ItemType Directory -Force -Path $checkDir | Out-Null
$checkSrc = Join-Path $checkDir "ModuleCheck.java"
$checkCode = @'
package io.mirage.loader.module.check;

import io.mirage.loader.ModManifest;
import io.mirage.loader.module.ModuleDescriptor;
import io.mirage.loader.module.ModuleGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 检查程序：测**生产代码本身**（ModuleDescriptor / ModuleGraph），不测副本。
 *
 * <p>早先版本在检查程序里重写了一遍闭包算法——那样测的是副本，
 * 生产代码改了测试照样全绿。现在直接调 ModuleGraph 的 public 静态方法。
 */
public final class ModuleCheck {

    static int pass = 0;
    static int fail = 0;

    static void check(boolean cond, String msg) {
        if (cond) {
            pass++;
            System.out.println("  [PASS] " + msg);
        } else {
            fail++;
            System.out.println("  [FAIL] " + msg);
        }
    }

    static ModuleDescriptor desc(String id, String... requires) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":\"").append(id).append("\",\"version\":\"0.1.0\"");
        sb.append(",\"requires\":[");
        for (int i = 0; i < requires.length; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(requires[i]).append("\"");
        }
        sb.append("]}");
        return ModuleDescriptor.parse(sb.toString());
    }

    public static void main(String[] args) {
        System.out.println("== 模块描述符解析 ==");

        ModuleDescriptor d = ModuleDescriptor.parse(
                "{\"id\":\"mirage-config\",\"version\":\"0.1.0\","
                        + "\"provides\":[\"dev.mirage.config\"],\"requires\":[\"mirage-core\"],"
                        + "\"entrypoint\":\"dev.mirage.config.ConfigModule\"}");
        check(d != null, "合法描述符解析成功");
        check(d != null && "mirage-config".equals(d.id), "id 解析正确");
        check(d != null && "0.1.0".equals(d.version), "version 解析正确");
        check(d != null && d.provides().size() == 1 && "dev.mirage.config".equals(d.provides().get(0)),
                "provides 解析正确");
        check(d != null && d.requires().size() == 1 && "mirage-core".equals(d.requires().get(0)),
                "requires 解析正确");
        check(d != null && "dev.mirage.config.ConfigModule".equals(d.entrypoint), "entrypoint 解析正确");

        check(ModuleDescriptor.parse(null) == null, "null 文本 → null（不抛）");
        check(ModuleDescriptor.parse("   ") == null, "空白文本 → null");
        check(ModuleDescriptor.parse("{not json") == null, "非法 JSON → null（不抛）");
        check(ModuleDescriptor.parse("{\"version\":\"0.1.0\"}") == null, "缺 id → null");
        check(ModuleDescriptor.parse("{\"id\":\"Mirage_Config\",\"version\":\"0.1.0\"}") == null,
                "id 不合规（大写+下划线）→ null");
        check(ModuleDescriptor.parse("{\"id\":\"mirage-config\"}") == null, "缺 version → null");

        System.out.println("== 包名约定（io.mirage. 前缀禁用） ==");
        ModuleDescriptor reserved = ModuleDescriptor.parse(
                "{\"id\":\"mirage-config\",\"version\":\"0.1.0\",\"provides\":[\"io.mirage.config\"]}");
        check(reserved == null, "provides 用 io.mirage. 前缀 → 判为非法（不静默加载后炸）");
        ModuleDescriptor reservedOk = ModuleDescriptor.parse(
                "{\"id\":\"mirage-config\",\"version\":\"0.1.0\",\"provides\":[\"dev.mirage.config\"]}");
        check(reservedOk != null && !reservedOk.usesReservedPackage(),
                "provides 用 dev.mirage. 前缀 → 合法且不触发保留前缀判定");

        System.out.println("== 依赖闭包（打生产代码 ModuleGraph） ==");
        Map<String, ModuleDescriptor> avail = new LinkedHashMap<>();
        avail.put("ma", desc("ma", "mb"));
        avail.put("mb", desc("mb", "mc"));
        avail.put("mc", desc("mc"));
        avail.put("standalone", desc("standalone"));

        Set<String> out = new LinkedHashSet<>();
        ModuleGraph.collectClosure(avail, "ma", out);
        check(out.size() == 3, "ma→mb→mc 闭包展开为 3 个（实际 " + out.size() + "）");
        check(out.contains("ma") && out.contains("mb") && out.contains("mc"), "闭包含全部三层");

        Set<String> out2 = new LinkedHashSet<>();
        ModuleGraph.collectClosure(avail, "standalone", out2);
        check(out2.size() == 1 && out2.contains("standalone"), "无依赖模块闭包只含自身");

        Set<String> out3 = new LinkedHashSet<>();
        ModuleGraph.collectClosure(avail, "missing", out3);
        check(out3.isEmpty(), "缺失模块闭包为空（不抛）");

        boolean nullStartOk = true;
        try {
            ModuleGraph.collectClosure(avail, null, out3);
        } catch (RuntimeException e) {
            nullStartOk = false;
        }
        check(nullStartOk, "null 起点 → 不抛");

        System.out.println("== 循环依赖必须能终止 ==");
        Map<String, ModuleDescriptor> cyc = new LinkedHashMap<>();
        cyc.put("cx", desc("cx", "cy"));
        cyc.put("cy", desc("cy", "cx"));
        Set<String> out4 = new LinkedHashSet<>();
        boolean finished = true;
        try {
            ModuleGraph.collectClosure(cyc, "cx", out4);
        } catch (StackOverflowError e) {
            finished = false;
        }
        check(finished, "循环依赖 cx→cy→cx 终止（不栈溢出）");
        check(out4.size() == 2, "循环依赖闭包含 2 个（实际 " + out4.size() + "）");

        Map<String, ModuleDescriptor> selfCyc = new LinkedHashMap<>();
        selfCyc.put("self", desc("self", "self"));
        Set<String> out5 = new LinkedHashSet<>();
        boolean finished2 = true;
        try {
            ModuleGraph.collectClosure(selfCyc, "self", out5);
        } catch (StackOverflowError e) {
            finished2 = false;
        }
        check(finished2, "自环依赖 self→self 终止");

        System.out.println("== 需求收集（打生产代码 ModuleGraph） ==");
        ModManifest m1 = new ModManifest();
        m1.id = "mod-a";
        m1.version = "1.0.0";
        m1.requires = List.of("mirage-config");
        ModManifest m2 = new ModManifest();
        m2.id = "mod-b";
        m2.version = "1.0.0";
        m2.requires = List.of("mirage-network", "mirage-config");
        ModManifest m3 = new ModManifest();
        m3.id = "mod-c";
        m3.version = "1.0.0";
        // m3.requires 保持 null —— 未声明 requires 的 mod 必须被安全跳过

        List<ModManifest> mods = new ArrayList<>();
        mods.add(m1);
        mods.add(m2);
        mods.add(m3);
        mods.add(null);   // null 元素也必须被安全跳过

        Map<String, String> req = ModuleGraph.collectRequests(mods);
        check(req.size() == 2, "需求并集 2 个（实际 " + req.size() + "）");
        check(req.containsKey("mirage-config") && req.containsKey("mirage-network"), "并集内容正确");
        check("mod-a".equals(req.get("mirage-config")), "同模块被多个 mod 声明时取首个声明者（实际 " + req.get("mirage-config") + "）");

        check(ModuleGraph.collectRequests(null).isEmpty(), "null 列表 → 空需求（不抛）");
        check(ModuleGraph.collectRequests(List.of()).isEmpty(), "空列表 → 空需求");

        System.out.println("== 需求 → 闭包（生产代码 neededModules） ==");
        Map<String, ModuleDescriptor> avail2 = new LinkedHashMap<>();
        avail2.put("mirage-config", desc("mirage-config"));
        avail2.put("mirage-core", desc("mirage-core"));
        Map<String, String> req2 = new LinkedHashMap<>();
        req2.put("mirage-config", "mod-a");
        Set<String> needed = ModuleGraph.neededModules(avail2, req2);
        check(needed.size() == 1 && needed.contains("mirage-config"), "neededModules 展开需求闭包");

        Map<String, String> req3 = new LinkedHashMap<>();
        req3.put("mirage-config", "mod-a");
        req3.put("mirage-core", "mod-b");
        Set<String> needed2 = ModuleGraph.neededModules(avail2, req3);
        check(needed2.size() == 2, "多个需求 → 闭包含全部（实际 " + needed2.size() + "）");

        check(ModuleGraph.neededModules(avail2, null).isEmpty(), "null 需求 → 空闭包（不抛）");

        System.out.println();
        System.out.println("== 检查程序结果：PASS " + pass + " / FAIL " + fail + " ==");
        System.exit(fail == 0 ? 0 : 1);
    }
}
'@
# .java 文件绝不能带 BOM（javac 不剥 BOM，会报 illegal character: '\ufeff'）
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($checkSrc, $checkCode, $utf8NoBom)

Say "-- 编译被测类与检查程序"
$argList = @("-encoding", "UTF-8", "-nowarn", "-d", $classes)
# 强制 javac 输出英文错误信息：默认跟随平台代码页（Windows 上是 GBK），
# 落盘报告会是乱码，英文则始终可读。
$argList += @("-J-Duser.language=en", "-J-Duser.country=US", "-J-Dfile.encoding=UTF-8")
$argList += $sources
$argList += $checkSrc
$cp = "$classes;$gsonJar"
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $javac
$psi.Arguments = ($argList -join " ") + " -cp `"$cp`""
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$c = New-Object System.Diagnostics.Process
$c.StartInfo = $psi
[void]$c.Start()
# 同上：先读流再等退出，避免管道缓冲写满时死锁
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
$psi2.Arguments = "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp `"$cp`" io.mirage.loader.module.check.ModuleCheck"
$psi2.RedirectStandardOutput = $true
$psi2.RedirectStandardError = $true
$psi2.UseShellExecute = $false
$psi2.StandardOutputEncoding = [System.Text.Encoding]::UTF8
$psi2.StandardErrorEncoding = [System.Text.Encoding]::UTF8
$r = New-Object System.Diagnostics.Process
$r.StartInfo = $psi2
[void]$r.Start()
# 注意：StandardOutput 是 StreamReader，**只能读一次**（第二次读到的是剩余空串）。
# 早先版本在这里读了两次，导致下面合并 PASS/FAIL 计数的正则永远匹配不上。
# 另外必须**先读完流再 WaitForExit**：反过来在输出超过管道缓冲时会死锁。
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

# 把检查程序的计数合并进来
$outText = $checkOut
if ($outText -match 'PASS (\d+) / FAIL (\d+)') {
    $script:pass += [int]$Matches[1]
    $script:fail += [int]$Matches[2]
} else {
    Say "[FAIL] 检查程序输出里没有 'PASS n / FAIL m' 汇总行（可能进程没跑起来）"
    $script:fail++
    $script:failures += "检查程序未输出汇总行"
}

Say "-- 源码守卫"
$descPath = Join-Path $coreSrc "module\ModuleDescriptor.java"
$regPath = Join-Path $coreSrc "module\ModuleRegistry.java"
$manifestPath = Join-Path $coreSrc "ModManifest.java"

# 守卫 1：三个模块的 provides 前缀都不得以 io.mirage. 开头
$moduleDirs = @("mirage-config", "mirage-kotlin", "mirage-network")
foreach ($md in $moduleDirs) {
    $jsonPath = Join-Path $root ("modules\" + $md + "\src\main\resources\META-INF\mirage-module.json")
    if (-not (Test-Path $jsonPath)) {
        Assert-True $false "模块 $md 缺少 META-INF/mirage-module.json"
        continue
    }
    $j = Get-Content $jsonPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ($j.id -eq $md) "模块 $md 的描述符 id 与目录名一致（实际 $($j.id)）"
    Assert-True (-not ([string]$j.version).Equals("")) "模块 $md 的描述符有 version"
    $bad = @($j.provides | Where-Object { $_ -like "io.mirage.*" })
    Assert-True ($bad.Count -eq 0) "模块 $md 的 provides 未使用保留前缀 io.mirage.（实际: $($j.provides -join ', ')）"
    # provides 前缀必须与模块里的实际包名对得上（否则 mod 引用的类根本不在模块 jar 里）
    $srcRoot = Join-Path $root ("modules\" + $md + "\src\main\java")
    $javaFiles = @()
    if (Test-Path $srcRoot) {
        $javaFiles = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java
    }
    foreach ($pf in $j.provides) {
        $pkgDir = Join-Path $srcRoot (($pf -replace '\.', '\'))
        Assert-True (Test-Path $pkgDir) "模块 $md 声明的 provides 前缀 $pf 有对应源码目录"
    }
    # provides 前缀必须有**真实的类**在下面，否则 mod 引用它会 NoClassDefFoundError
    $pkgFiles = @()
    if (Test-Path $srcRoot) {
        $pkgFiles = Get-ChildItem -Path $srcRoot -Recurse -Filter *.java
    }
    foreach ($pf in $j.provides) {
        $pfPath = ($pf -replace '\.', '\')
        $hit = @($pkgFiles | Where-Object { $_.FullName -like "*\$pfPath\*" })
        Assert-True ($hit.Count -gt 0) "模块 $md 的 provides 前缀 $pf 下至少有一个类（实际 $($hit.Count) 个）"
    }
    # 入口类必须真实存在（声明了 entrypoint 却没有类是必然的运行期失败）
    $ep = [string]$j.entrypoint
    if ($ep -ne "") {
        $epPath = Join-Path $srcRoot (($ep -replace '\.', '\') + ".java")
        $epExists = Test-Path $epPath
        $epNote = if ($epExists) { "" } else { "（缺: $epPath）" }
        Assert-True $epExists "模块 $md 的 entrypoint $ep 有对应源码$epNote"
    }
}

# 守卫 2：模块入口类的包名必须在 provides 声明的前缀下
$cfgJson = Get-Content (Join-Path $root "modules\mirage-config\src\main\resources\META-INF\mirage-module.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$cfgSrc = Join-Path $root "modules\mirage-config\src\main\java\dev\mirage\config\ConfigModule.java"
Assert-True (Test-Path $cfgSrc) "mirage-config 有入口类 ConfigModule"
if (Test-Path $cfgSrc) {
    $cfgText = Get-Content $cfgSrc -Raw -Encoding UTF8
    Assert-True ($cfgText -match 'package\s+dev\.mirage\.config\s*;') "ConfigModule 的包名是 dev.mirage.config（非 io.mirage.*）"
    Assert-True ($cfgText -match 'public static void init\(\)') "ConfigModule 有 static init()（ModuleRegistry 的入口约定）"
}

# 守卫 3：ModManifest 有 requires 字段，且注释说清与 depends 的区别
$manifestText = Get-Content $manifestPath -Raw -Encoding UTF8
Assert-True ($manifestText -match 'public\s+java\.util\.List<String>\s+requires') "ModManifest 有 requires 字段"
Assert-True ($manifestText -match 'depends' -and $manifestText -match 'requires') "ModManifest 同时保留 depends 与 requires"
Assert-True ($manifestText -match '模块') "ModManifest.requires 有注释说明它是模块依赖"

# 守卫 4：ModuleRegistry 用了 io.mirage. 前缀检查 + 降级不阻断
$regText = Get-Content $regPath -Raw -Encoding UTF8
Assert-True ($regText -match 'usesReservedPackage') "ModuleRegistry 检查保留包名前缀"
Assert-True ($regText -match 'DIR_PROPERTY') "ModuleRegistry 支持系统属性覆盖仓库目录"
Assert-True ($regText -match 'io\.mirage\.modules\.dir' -or $regText -match 'mirage\.modules\.dir') "仓库目录属性名是 mirage.modules.dir"
Assert-True ($regText -match '游戏继续启动') "ModuleRegistry 的失败路径都是降级不阻断"
# 闭包/需求收集的实现必须**只有一份**（在 ModuleGraph 里）。
# 若 ModuleRegistry 又自己写一遍，两份算法迟早漂移，而离线回归只测得到其中一份。
Assert-True ($regText -match 'ModuleGraph\.collectRequests') "ModuleRegistry 委派需求收集给 ModuleGraph"
Assert-True ($regText -match 'ModuleGraph\.neededModules') "ModuleRegistry 委派闭包计算给 ModuleGraph"

# 守卫 5：TransformStage 接了 ModuleRegistry
$tsPath = Join-Path $root "lifecycle-starter\src\main\java\io\mirage\ls\stage\TransformStage.java"
$tsText = Get-Content $tsPath -Raw -Encoding UTF8
Assert-True ($tsText -match 'ModuleRegistry') "TransformStage 调用了 ModuleRegistry"
Assert-True ($tsText -match 'S3\.modules') "TransformStage 把结果放进 ctx.properties() 的 S3.modules"

Say ""
Say ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==")
if ($script:fail -gt 0) {
    Say "失败明细："
    foreach ($f in $script:failures) { Say ("  - " + $f) }
    if ($Keep) { Say ("临时目录保留在: " + $work) }
    Say ("报告已写入: " + $ReportFile)
    Save-Report
    exit 1
}
if ($Keep) {
    Say ("临时目录保留在: " + $work)
} else {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
Say "全部通过。"
Say ("报告已写入: " + $ReportFile)
Save-Report
exit 0
