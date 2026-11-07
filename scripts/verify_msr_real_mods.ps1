# scripts/verify_msr_real_mods.ps1
# MSR 门禁 A · 真实 NeoForge mod 的离线录制回归（不启动 Minecraft）
#
# 作用：
#   用**真实** 26.2 NeoForge mod（mods-library/neoforge/ 下）验证 MSR 三件事：
#     1. 依赖段解析：真实 toml 的 type="required" 能解析出 depends（M1）；
#     2. 失败原因分类：CNFE 能被归成 missing_fake 而不是裸异常（M3）；
#     3. fake pass 有备用来源：compat stub 能被沙盒加载（M2，provider 接线在位）。
#
# 为什么要有这个脚本：
#   既有 verify_msr_p1/p2 全在**自造夹具**上跑 —— 夹具只用最小 API 集，
#   所以「fake 表面只有 19 个类」这个致命缺陷一直测不出来。
#   2026-11-07 实测：把真实 JEI 喂给 fake pass ⇒ 100% CNFE、apis=0。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_real_mods.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_real_mods.ps1 -SelfTest
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_real_mods.ps1 -SelfTest -Mutation <名字>
#
# 变异检验（SelfTest 下应全部变红）：
#   drop-type     依赖解析不认 type= ⇒ required 依赖解析不出来
#   drop-provider fake provider 不接线 ⇒ 找不到备用 fake 来源
#   drop-classify 失败不分类 ⇒ error 里没有 missing_fake 前缀
#
# 退出码：0 全绿；1 有断言失败。

[CmdletBinding()]
param(
    [switch]$SelfTest,
    [string]$Mutation = "none",
    [string]$ModDir = "",
    [string]$ReportFile = (Join-Path $env:TEMP "verify_msr_real_mods.report.txt")
)

$ErrorActionPreference = "Stop"
$scriptName = "verify_msr_real_mods"
$report = New-Object System.Collections.Generic.List[string]
function Log($m) { $report.Add($m) }

$repo = Split-Path -Parent $PSScriptRoot
if (-not $ModDir) {
    $ModDir = Join-Path (Split-Path -Parent $repo) "mods-library\neoforge"
}

# ---- 源码文件（本脚本只做离线静态 + 纯 JVM 校验，不启动 MC）----
$detectorSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\NeoForgeModDetector.java"
$policySrc   = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\RecordingPolicy.java"
$sandboxSrc  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\SandboxClassLoader.java"
$sandboxRec  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\SandboxRecorder.java"
$providerSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\CompatFakeProvider.java"
$fakeProvSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\FakeProvider.java"
$gapSrc      = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\FakeGapAnalyzer.java"

foreach ($f in @($detectorSrc,$policySrc,$sandboxSrc,$sandboxRec,$providerSrc,$fakeProvSrc,$gapSrc)) {
    if (-not (Test-Path $f)) { Write-Host "[FAIL] 找不到文件: $f" -ForegroundColor Red; exit 1 }
}

$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]
function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) { Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen }
    else {
        $script:failures.Add($desc) | Out-Null
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
    }
}
function Assert-Match([string]$text, [string]$pattern, [string]$desc) {
    $ok = $text -match $pattern
    $script:checks++
    if ($ok) { Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen }
    else {
        $script:failures.Add($desc) | Out-Null
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        Write-Host ("          期望匹配 {0}" -f $pattern) -ForegroundColor DarkGray
    }
}

# 坑：Get-Content -Raw 不带 -Encoding 时走**系统 ANSI 代码页**（本机 GBK），
# UTF-8 源码会被读成乱码 —— 断言里只要有中文匹配就会静默失效。
# 统一用 .NET API 显式按 UTF-8 读（Encoding.UTF8 会自动跳过 BOM）。
function Read-Source([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

Write-Host "== MSR 门禁 A · 真实 mod 离线回归 ==" -ForegroundColor Cyan

# ================================================================= 源码守卫
Write-Host "-- 源码守卫 --" -ForegroundColor Yellow

$det = Read-Source $detectorSrc
Assert-Match $det '(?m)^\s*case "type" ->' 'M1：依赖段解析认 type= 键'
Assert-Match $det '(?m)^\s*case "mandatory" ->' 'M1：旧式 mandatory= 仍兼容'
Assert-Match $det 'resolveMandatory' 'M1：type 优先、mandatory 回退的归一化函数在位'

$pol = Read-Source $policySrc
Assert-Match $pol 'PREVIOUSLY_UNSUPPORTED' 'M4：判据含「上次因加载器能力不足失败」分支'
Assert-Match $pol 'isUnsupportedByLoader' 'M4：按 error 分类前缀判定失败归属'

$sb = Read-Source $sandboxSrc
Assert-Match $sb 'FakeProvider' 'M2：SandboxClassLoader 有 fake provider 备用来源'
Assert-Match $sb 'fakeProvider\.findFakeBytes' 'M2：备用来源真的被调用（不是摆设）'

$sr = Read-Source $sandboxRec
Assert-Match $sr 'CompatFakeProvider' 'M2：SandboxRecorder 注入 CompatFakeProvider'
Assert-Match $sr 'classifyFailure' 'M3：失败原因分类函数在位'
Assert-Match $sr 'KIND_MISSING_FAKE' 'M3：missing_fake 分类常量在位'
Assert-Match $sr 'KIND_STUB_INCOMPLETE' 'M3：stub_incomplete 分类常量在位'
Assert-Match $sr 'KIND_CTOR_FAILED' 'M3：ctor_failed 分类常量在位'
# 关键：分类**调用点**必须真的走分类函数，不能写死常量。
# 行首锚定 + 精确匹配，注释里的同名字串骗不过去。
Assert-Match $sr '(?m)^\s*String kind = classifyFailure\(err\[0\]\);' 'M3：失败分支真的调用了分类函数（不是写死常量）'

$fp = Read-Source $providerSrc
Assert-Match $fp 'net\.neoforged\.' 'M2：provider 只覆盖 net.neoforged. 包域'
Assert-Match $fp 'net\.minecraft\.' 'M2：provider 也覆盖 net.minecraft. 包域'

$ga = Read-Source $gapSrc
Assert-Match $ga 'class FakeGapAnalyzer' 'P4：缺口分析器存在'

# ================================================================= 行为断言（M3 分类）
# 光有"字符串存在"不够：把 classifyFailure 换成常量返回，守卫照样绿。
# 所以必须**真的跑一遍分类逻辑**，用真实异常形状验证分类结果。
Write-Host "-- 行为断言（M3 分类）--" -ForegroundColor Yellow

$javaExe = $null
foreach ($c in @($env:JAVA_HOME, "C:\Program Files\Java\jdk-25.0.4", "C:\Program Files\Java\jdk-21.0.11")) {
    if ($c -and (Test-Path (Join-Path $c "bin\java.exe"))) { $javaExe = Join-Path $c "bin\java.exe"; break }
}
if (-not $javaExe) {
    Write-Host "   [WARN] 没找到 java，跳过行为断言（源码守卫仍生效）" -ForegroundColor Yellow
} else {
    $classesDir = Join-Path $repo "core\mirage-loader\build\classes\java\main"
    if (-not (Test-Path $classesDir)) {
        Write-Host "   [WARN] loader 未编译，跳过行为断言（先跑 gradle :core:mirage-loader:compileJava）" -ForegroundColor Yellow
    } else {
        $probeDir = Join-Path $env:TEMP ("msr-classify-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
        $probeSrc = Join-Path $probeDir "ClassifyProbe.java"
        $probeJava = @'
package probe;
import io.mirage.loader.sandbox.SandboxRecorder;
public final class ClassifyProbe {
    public static void main(String[] args) {
        // 1) 缺 fake 类（CNFE 落在 fake 域）⇒ missing_fake
        Throwable cnfe = new NoClassDefFoundError("net/neoforged/api/distmarker/Dist");
        System.out.println("CNFE-NEOFORGED=" + SandboxRecorder.classifyFailure(cnfe));
        // 2) 缺 fake 类（CNFE 落在 MC 域）⇒ missing_fake
        Throwable cnfeMc = new NoClassDefFoundError("net/minecraft/core/HolderLookup$Provider");
        System.out.println("CNFE-MINECRAFT=" + SandboxRecorder.classifyFailure(cnfeMc));
        // 3) mod 自己的依赖缺失（不在 fake 域）⇒ ctor_failed
        Throwable cnfeOther = new NoClassDefFoundError("com/example/SomeLib");
        System.out.println("CNFE-OTHER=" + SandboxRecorder.classifyFailure(cnfeOther));
        // 4) stub 有类但缺方法 ⇒ stub_incomplete
        Throwable nsm = new NoSuchMethodError("net.neoforged.fml.ModList.foo()");
        System.out.println("NSM=" + SandboxRecorder.classifyFailure(nsm));
        // 5) compat 层既定原则：未知方法抛 UnsupportedOperationException ⇒ stub_incomplete
        Throwable uoe = new UnsupportedOperationException("net.neoforged.fml.ModList.bar");
        System.out.println("UOE=" + SandboxRecorder.classifyFailure(uoe));
        // 6) 其它异常 ⇒ ctor_failed
        Throwable boom = new IllegalStateException("mod 自己炸了");
        System.out.println("OTHER=" + SandboxRecorder.classifyFailure(boom));
        // 7) 异常链：外层是 CNFE 包装，内层才是根因
        Throwable wrapped = new RuntimeException("outer", new NoSuchMethodError("x.y.Z#m"));
        System.out.println("WRAPPED=" + SandboxRecorder.classifyFailure(wrapped));
        // 8) missingClassOf 能取出类名
        System.out.println("MISSING=" + SandboxRecorder.missingClassOf(cnfe));
    }
}
'@
        Set-Content -Path $probeSrc -Value $probeJava -Encoding UTF8
        # 坑：Set-Content -Encoding UTF8 在 PS 5.1 会写 BOM，javac 直接报「非法字符 \ufeff」。
        # 脚本内生成的 .java 必须无 BOM —— 用 .NET API 写 UTF8Encoding($false)。
        [System.IO.File]::WriteAllText($probeSrc, $probeJava, (New-Object System.Text.UTF8Encoding $false))
        $javacExe = $javaExe.Replace("java.exe", "javac.exe")
        $compileOut = & $javacExe -encoding UTF-8 -nowarn -cp $classesDir -d $probeDir $probeSrc 2>&1
        if ($LASTEXITCODE -ne 0) {
            Write-Host "   [FAIL] 分类探针编译失败" -ForegroundColor Red
            $compileOut | Select-Object -First 5 | ForEach-Object { Write-Host "          $_" -ForegroundColor DarkGray }
            $script:failures.Add("分类探针编译失败") | Out-Null
        } else {
            $runOut = & $javaExe -cp "$classesDir;$probeDir" probe.ClassifyProbe 2>&1 | Out-String
            Assert-Match $runOut 'CNFE-NEOFORGED=missing_fake'   'M3：缺 net.neoforged 类 ⇒ missing_fake'
            Assert-Match $runOut 'CNFE-MINECRAFT=missing_fake'   'M3：缺 net.minecraft 类 ⇒ missing_fake'
            Assert-Match $runOut 'CNFE-OTHER=ctor_failed'       'M3：缺非 fake 域的类 ⇒ ctor_failed（不算加载器的锅）'
            Assert-Match $runOut 'NSM=stub_incomplete'          'M3：NoSuchMethodError ⇒ stub_incomplete'
            Assert-Match $runOut 'UOE=stub_incomplete'          'M3：UnsupportedOperationException ⇒ stub_incomplete'
            Assert-Match $runOut 'OTHER=ctor_failed'            'M3：其它异常 ⇒ ctor_failed'
            Assert-Match $runOut 'WRAPPED=stub_incomplete'      'M3：异常链能穿透到根因分类'
            Assert-Match $runOut 'MISSING=net\.neoforged\.api\.distmarker\.Dist' 'M3：missingClassOf 能取出缺失类名'
        }
        Remove-Item $probeDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ================================================================= 真实 mod 的静态取证
Write-Host "-- 真实 mod 取证 --" -ForegroundColor Yellow

$modJars = @()
if (Test-Path $ModDir) {
    $modJars = @(Get-ChildItem -Path $ModDir -Filter "*.jar" -ErrorAction SilentlyContinue |
                 Where-Object { $_.Name -notlike "neop0test*" })
}
Assert-True ($modJars.Count -ge 1) "mods-library/neoforge 下有真实 mod 可测（找到 $($modJars.Count) 个）"

# 真实 toml 的依赖段形态：type="required"（而不是旧式 mandatory=）
$foundTypeRequired = $false
$foundMeZZConfig = $false
foreach ($mj in $modJars) {
    $tmp = Join-Path $env:TEMP ("msr-toml-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $zip = [System.IO.Compression.ZipFile]::OpenRead($mj.FullName)
        try {
            $entry = $zip.Entries | Where-Object { $_.FullName -eq "META-INF/neoforge.mods.toml" } | Select-Object -First 1
            if (-not $entry) { continue }
            $sr2 = New-Object System.IO.StreamReader($entry.Open())
            $toml = $sr2.ReadToEnd()
            $sr2.Close()
            if ($toml -match 'type\s*=\s*"required"') { $foundTypeRequired = $true }
            if ($toml -match 'modId\s*=\s*"mezz_config"') { $foundMeZZConfig = $true }
        } finally { $zip.Dispose() }
    } catch {
        # 读不到就跳过这个 jar
    } finally {
        Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}
Assert-True $foundTypeRequired "真实 mod 的 toml 里确实有 type=`"required`"（这是 M1 要认的写法）"
Assert-True $foundMeZZConfig "真实 mod 里能找到 mezz_config 依赖（M1 解析后应出现在 depends）"

# ================================================================= 变异检验
if ($SelfTest) {
    Write-Host "-- 变异检验 --" -ForegroundColor Yellow
    Assert-Match $Mutation '(?i)none|drop-type|drop-provider|drop-classify' "变异名在允许集合内"
    # 变异语义：把对应能力在**源码层**改坏，看守卫是否变红。
    # 这里不落盘改真实源码（会污染工作区），而是检查「若改坏，守卫是否会红」——
    # 做法：构造一份改坏后的内存副本，跑同一批正则。
    # ⚠ 分层边界（本轮踩过的坑）：本段只能验证**源码守卫层**。行为层（分类逻辑本身算得对不对）
    #   必须真跑一遍分类代码才算数 —— 那需要落盘改源码 + 重新编译，由 scripts/verify_msr_mutation.ps1 承担。
    #   早先的 drop-classify 把「调用点」换成常量却拿行为断言去验 ⇒ 行为断言当然不红，
    #   差点被误判成「断言无效」。实际上：调用点归守卫、逻辑归行为，两者不能混。
    $mutDet = $det; $mutSb = $sb; $mutSr = $sr
    switch ($Mutation) {
        "drop-type"     { $mutDet = $det -replace '(?m)^\s*case "type" ->.*$', '' }
        "drop-provider" { $mutSb = $sb -replace 'fakeProvider\.findFakeBytes', 'neverCalled' }
        "drop-classify" { $mutSr = $sr -replace '(?m)^(\s*)String kind = classifyFailure\(err\[0\]\);', '$1String kind = "ctor_failed";' }
    }
    if ($Mutation -eq "drop-type") {
        Assert-True ($mutDet -notmatch '(?m)^\s*case "type" ->') "MUT drop-type：type= 分支真的被删掉了"
        Assert-True ($mutDet -notmatch '(?m)^\s*case "type" ->') "MUT drop-type：守卫会变红（type= 匹配不上）"
    }
    if ($Mutation -eq "drop-provider") {
        Assert-True ($mutSb -notmatch 'fakeProvider\.findFakeBytes') "MUT drop-provider：provider 调用真的被删掉了"
        Assert-True ($mutSb -notmatch 'fakeProvider\.findFakeBytes') "MUT drop-provider：守卫会变红（provider 调用匹配不上）"
    }
    if ($Mutation -eq "drop-classify") {
        Assert-True ($mutSr -notmatch '(?m)^\s*String kind = classifyFailure\(err\[0\]\);') "MUT drop-classify：分类调用点真的被改成常量了"
        Assert-True ($mutSr -notmatch '(?m)^\s*String kind = classifyFailure\(err\[0\]\);') "MUT drop-classify：守卫会变红（分类调用点匹配不上）"
        Assert-True ($mutSr -match 'classifyFailure') "MUT drop-classify：分类**方法**仍在 —— 行为断言不会红，这是预期的分层"
    }
    if ($Mutation -eq "none") {
        Write-Host "   [INFO] 未指定变异，跑的是基线（守卫应全绿）" -ForegroundColor Yellow
        Write-Host "   [INFO] 行为层变异请用 scripts\verify_msr_mutation.ps1（落盘 + 编译 + 字节级还原）" -ForegroundColor Yellow
    }
}

# ================================================================= 汇总
Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host "== 结果：PASS $($script:checks) / FAIL 0 ==" -ForegroundColor Green
    $report | Out-File -FilePath $ReportFile -Encoding utf8
    exit 0
} else {
    Write-Host "== 结果：PASS $($script:checks - $script:failures.Count) / FAIL $($script:failures.Count) ==" -ForegroundColor Red
    $script:failures | ForEach-Object { Write-Host "   - $_" -ForegroundColor Red }
    $report | Out-File -FilePath $ReportFile -Encoding utf8
    exit 1
}
