# scripts/verify_fml_loader_dist.ps1
# FMLLoader.getDist() 语义门禁（纯 JVM，不启动游戏、不写 mc-shili/）
#
# 背景：11-22 真机 latest.log:98 的 NoSuchMethodError 来自
#   mezz_config.ConfigNeoForgePhysicalSideProvider.isPhysicalClient()
#   -> FMLLoader.getCurrent().getDist() -> JEI 初始化 ServiceConfigurationError。
# 真值来源：NeoCompatBootstrap.init 在 S6 mod 构造前把 ctx.dist() 写进 FMLEnvironment；
#   客户端运行即 Dist.CLIENT。getDist() 必须委托这个真值源，不能写死常量。
#
# 三层：
#   守卫层：源码锚点（实例方法形态 / 真值委托 / 最小边界 / 禁止硬编码）
#   行为层：生产源码真实 javac + headless JVM 探针（反射核对签名、CLIENT 真值、跟随注入）
#   变异层：3 个落盘变异（去方法 / 错返回类型 / 硬编码 CLIENT），各自判红原因一一对应；
#           每次 try/finally 字节级还原 + 还原后编译路径自检回绿
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_fml_loader_dist.ps1
#   powershell ... -SkipMutation                  # 只跑守卫 + 行为（快速档）
#   powershell ... -Keep                          # 保留 %TEMP% 工作目录
#
# 退出码：0 全绿；1 任一层失败 / 变异未判红 / 还原失败。

[CmdletBinding()]
param(
    [switch]$SkipMutation,
    [switch]$Keep,
    [string]$ReportFile = (Join-Path $env:TEMP "verify_fml_loader_dist.report.txt")
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
$compatSrc = Join-Path $root "modules\mirage-neoforge-compat\src\main\java"
$apiSrc = Join-Path $root "api\mirage-api\src\main\java"
$fmlPath = Join-Path $compatSrc "net\neoforged\fml\loading\FMLLoader.java"
$envPath = Join-Path $compatSrc "net\neoforged\fml\loading\FMLEnvironment.java"
$bootstrapPath = Join-Path $compatSrc "io\mirage\neoforge\runtime\NeoCompatBootstrap.java"

$script:pass = 0
$script:fail = 0
$script:failures = @()
$script:lines = New-Object System.Collections.ArrayList
$utf8 = New-Object System.Text.UTF8Encoding($false)

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
        [System.IO.File]::WriteAllBytes($ReportFile,
            $utf8.GetBytes(($script:lines -join [Environment]::NewLine) + [Environment]::NewLine))
    } catch {
        Write-Host ("[WARN] 报告落盘失败: " + $_.Exception.Message) -ForegroundColor Yellow
    }
}

function Find-JavaTool {
    param([string]$Name)
    $candidates = @()
    # 项目铁律 JDK 25：先认本机 jdk-25.0.4，再退回 JAVA_HOME/PATH（后面还有版本断言）
    $candidates += "C:\Program Files\Java\jdk-25.0.4\bin\$Name.exe"
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$Name.exe") }
    $cmd = Get-Command $Name -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($v in @("jdk-21.0.11", "jdk-17")) {
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
    Say "[FAIL] 没找到 javac/java（设置 JAVA_HOME 或把 JDK bin 加进 PATH）"
    Save-Report
    exit 1
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("mirage-fmldist-" + [Guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null

Say "== FMLLoader.getDist() 语义门禁 =="
Say ("javac = " + $javac)
Say ("work  = " + $work)

# ---------------------------------------------------------------- 源码存在性
foreach ($p in @($fmlPath, $envPath, $bootstrapPath)) {
    if (-not (Test-Path $p)) {
        Say ("[FAIL] 找不到生产源码: " + $p)
        Save-Report
        exit 1
    }
}

# ---------------------------------------------------------------- 守卫层（内存读字节，不改盘）
Say "-- 守卫层"
$fmlBytes = [System.IO.File]::ReadAllBytes($fmlPath)
$fmlSrc = $utf8.GetString($fmlBytes)
$envSrc = $utf8.GetString([System.IO.File]::ReadAllBytes($envPath))
$bootSrc = $utf8.GetString([System.IO.File]::ReadAllBytes($bootstrapPath))
$selfSrc = $utf8.GetString([System.IO.File]::ReadAllBytes($PSCommandPath))

Assert-True ($fmlSrc -match '(?m)^\s*public Dist getDist\(\)\s*\{') `
    "G1 FMLLoader 有实例方法 getDist()（真实签名，invokevirtual 形态）"
Assert-True ($fmlSrc -match '(?m)^\s*return FMLEnvironment\.getDist\(\);') `
    "G2 getDist() 委托 FMLEnvironment 真值源，不写死常量"
Assert-True ($fmlSrc -match '(?m)^\s*import net\.neoforged\.api\.distmarker\.Dist;') `
    "G3 显式 import Dist（返回类型不是同名假类）"
Assert-True ($fmlSrc -match '(?m)^\s*public static FMLLoader getCurrent\(\)') `
    "G4 getCurrent() 仍在（调用链 getCurrent -> getDist）"
Assert-True (-not ($fmlSrc -match '(?m)^\s*public static Dist getDist\(')) `
    "G5 禁止把 getDist() 改成静态（真实签名为实例方法）"
Assert-True (-not ($fmlSrc -match '(?m)^\s*return Dist\.CLIENT;')) `
    "G6 FMLLoader 内不得硬编码 Dist.CLIENT"
Assert-True (-not ($fmlSrc -match '(?m)^\s*public .*\b(getGamePath|getModsPath)\s*\(')) `
    "G7 本轮不扩大方法集（getGamePath/getModsPath 无真机证据）"
Assert-True ($envSrc -match '(?m)^\s*public static Dist getDist\(\)') `
    "G8 FMLEnvironment.getDist() 真值源存在"
Assert-True ($bootSrc -match 'FMLEnvironment\.init\(') `
    "G9 加载器在 S6 之前注入端侧（NeoCompatBootstrap）"
Assert-True ($selfSrc -match 'Invoke-Mutation') "G10 门禁自身含变异层入口"
Assert-True ($selfSrc -match 'ReadAllBytes' -and $selfSrc -match 'WriteAllBytes') `
    "G11 门禁自身按字节读写源码（不用 Get-Content -Raw / Set-Content）"

# ---------------------------------------------------------------- 行为层（真跑 JVM）
$checkSrc = Join-Path $work "FmlDistCheck.java"
[System.IO.File]::WriteAllBytes($checkSrc, $utf8.GetBytes(@'
package io.mirage.check;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** 只输出 ASCII KEY=VALUE / PASS 汇总，避免 PS 5.1 管道编码坑。 */
public final class FmlDistCheck {
    private static int pass = 0;
    private static int fail = 0;

    private static void check(boolean cond, String name) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("JAVA_VERSION=" + System.getProperty("java.version"));
        Class<?> cls = Class.forName("net.neoforged.fml.loading.FMLLoader");
        Method m;
        try {
            m = cls.getMethod("getDist");
            check(true, "METHOD_PRESENT");
        } catch (NoSuchMethodException e) {
            check(false, "METHOD_PRESENT");
            System.out.println("PROBE PASS " + pass + " / FAIL " + (fail + 1));
            System.exit(1);
            return;
        }
        check(!Modifier.isStatic(m.getModifiers()), "INSTANCE_METHOD");
        check(m.getReturnType() == Dist.class, "RETURN_TYPE_DIST");

        FMLEnvironment.init(Dist.CLIENT);
        Object client = m.invoke(FMLLoader.getCurrent());
        check(client == Dist.CLIENT, "CLIENT_TRUE_VALUE");
        check(client instanceof Dist && ((Dist) client).isClient(), "CLIENT_IS_CLIENT");

        FMLEnvironment.init(Dist.DEDICATED_SERVER);
        Object server = m.invoke(FMLLoader.getCurrent());
        check(server == Dist.DEDICATED_SERVER, "DELEGATES_TO_ENVIRONMENT");

        FMLEnvironment.init(Dist.CLIENT);
        check(FMLLoader.versionInfo() != null, "VERSION_INFO_UNCHANGED");
        check(!FMLLoader.getCurrent().isProduction(), "IS_PRODUCTION_UNCHANGED");

        System.out.println("PROBE PASS " + pass + " / FAIL " + fail);
        if (fail > 0) { System.exit(1); }
    }
}
'@))

$sourceFiles = @(
    "$compatSrc\net\neoforged\api\distmarker\Dist.java",
    "$compatSrc\net\neoforged\fml\loading\FMLLoader.java",
    "$compatSrc\net\neoforged\fml\loading\FMLEnvironment.java",
    "$compatSrc\net\neoforged\fml\loading\VersionInfo.java",
    "$compatSrc\net\neoforged\fml\loading\LoadingModList.java",
    "$compatSrc\net\neoforged\fml\loading\moddiscovery\ModInfo.java",
    "$apiSrc\io\mirage\api\version\VersionMasks.java",
    "$apiSrc\io\mirage\api\log\MirageLog.java"
)
foreach ($s in $sourceFiles) {
    if (-not (Test-Path $s)) {
        Say ("[FAIL] 找不到编译输入: " + $s)
        Save-Report
        exit 1
    }
}

function Invoke-Jvm {
    param([string]$Exe, [string]$Arguments)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = $Arguments
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    [void]$p.Start()
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    $p.WaitForExit()
    return [pscustomobject]@{ Code = $p.ExitCode; Out = $out; Err = $err }
}

$compileSeq = 0
function Invoke-CompileAndProbe {
    $script:compileSeq++
    $dir = Join-Path $work ("run" + $script:compileSeq)
    $classes = Join-Path $dir "classes"
    New-Item -ItemType Directory -Force -Path $classes | Out-Null

    $javacArgs = "-encoding UTF-8 -d `"$classes`""
    foreach ($s in $sourceFiles) { $javacArgs += " `"$s`"" }
    $javacArgs += " `"$checkSrc`""
    $cr = Invoke-Jvm -Exe $javac -Arguments $javacArgs
    if ($cr.Code -ne 0) {
        return [pscustomobject]@{ Ok = $false; Stage = "javac"; Out = ($cr.Out + $cr.Err) }
    }
    $pr = Invoke-Jvm -Exe $java -Arguments ("-Dfile.encoding=UTF-8 -cp `"$classes`" io.mirage.check.FmlDistCheck")
    return [pscustomobject]@{ Ok = ($pr.Code -eq 0); Stage = "probe"; Out = ($pr.Out + $pr.Err) }
}

Say "-- 行为层（编译路径自检）"
$baseline = Invoke-CompileAndProbe
if (-not $baseline.Ok) {
    Say ("[FAIL] 基线编译/探针未通过（stage=" + $baseline.Stage + "）")
    Say $baseline.Out
    $script:fail++
    $script:failures += "基线编译/探针未通过"
} else {
    Assert-True ($baseline.Out -match 'JAVA_VERSION=25(\.|$)') "B-1 探针实际跑在 JDK 25（项目铁律）"
    Assert-True ($baseline.Out -match 'PROBE PASS (\d+) / FAIL 0') "B0 基线 JVM 探针全绿（同一编译路径）"
    $probePass = 0
    if ($baseline.Out -match 'PROBE PASS (\d+) / FAIL 0') { $probePass = [int]$Matches[1] }
    $script:pass += $probePass
    Say ("  （探针内部断言 " + $probePass + " 条已计入 PASS）")
}

# ---------------------------------------------------------------- 变异层（落盘重编译 + 字节级还原）
function Invoke-Mutation {
    param([string]$Name, [string]$Find, [string]$Replace, [string]$Expected, [string]$ExpectedStage)
    $original = [System.IO.File]::ReadAllBytes($fmlPath)
    $text = $utf8.GetString($original)
    $mutated = $text.Replace($Find, $Replace)
    if ($mutated -eq $text) {
        Assert-True $false ("变异 " + $Name + " 未应用（替换锚点漂移）")
        return
    }
    try {
        [System.IO.File]::WriteAllBytes($fmlPath, $utf8.GetBytes($mutated))
        $r = Invoke-CompileAndProbe
        if ($r.Ok) {
            Assert-True $false ("变异 " + $Name + " 未判红")
        } else {
            $stageOk = ($r.Stage -eq $ExpectedStage)
            $reasonOk = ($r.Out -match $Expected)
            Assert-True ($stageOk -and $reasonOk) `
                ("变异 " + $Name + " 判红且原因对应（stage=" + $r.Stage + "）")
            if (-not ($stageOk -and $reasonOk)) { Say ("  实际输出: " + ($r.Out -replace "`r?`n", " | ")) }
        }
    } finally {
        [System.IO.File]::WriteAllBytes($fmlPath, $original)
    }
    $after = [System.IO.File]::ReadAllBytes($fmlPath)
    $same = ($after.Length -eq $original.Length)
    if ($same) {
        for ($i = 0; $i -lt $after.Length; $i++) {
            if ($after[$i] -ne $original[$i]) { $same = $false; break }
        }
    }
    Assert-True $same ("变异 " + $Name + " 源文件字节级还原")
    $recheck = Invoke-CompileAndProbe
    Assert-True $recheck.Ok ("变异 " + $Name + " 还原后编译路径自检回绿")
}

if (-not $SkipMutation) {
    Say "-- 变异层"
    Invoke-Mutation -Name "no-method" `
        -Find 'public Dist getDist() {' -Replace 'public Dist getDistX() {' `
        -Expected 'METHOD_PRESENT' -ExpectedStage 'probe'
    Invoke-Mutation -Name "wrong-return" `
        -Find 'public Dist getDist() {' -Replace 'public String getDist() {' `
        -Expected 'incompatible types|String cannot be converted|Dist' -ExpectedStage 'javac'
    Invoke-Mutation -Name "hardcode" `
        -Find 'return FMLEnvironment.getDist();' -Replace 'return Dist.CLIENT;' `
        -Expected 'DELEGATES_TO_ENVIRONMENT' -ExpectedStage 'probe'
} else {
    Say "-- 变异层已跳过 (-SkipMutation)"
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
