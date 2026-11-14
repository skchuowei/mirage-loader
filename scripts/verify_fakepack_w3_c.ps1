<#
.SYNOPSIS
    MSR W3 路线 C 门禁：把 P4b pack 构建期固化进 fat jar（资源 mirage/fakepack/），
    并在真启动类加载器的 fallback 分支里当内置 pack 服务。

.DESCRIPTION
    守卫层：源码行首锚定 (?m)^\s* 核 BUILTIN_PREFIX 常量、findBytesIfEnabled 在 HOME 为空时
            走 readFromBuiltin、readFromBuiltin 拼 mirage/fakepack/ 资源前缀、initEnabled 不再
            硬性要求 home 目录、前缀闸只认 net.neoforged.、kill switch 保留。
    行为层：构造真实 MirageClassLoader（URL 只挂 fat jar），跑两个 headless 场景：
            DEFAULT（runtime 不给）→ 缺 neo 类仍 CNFE、B 零状态（默认零变化）；
            BUILTIN_ON（-Dmirage.msr.fakepack.runtime=on，无外部 home）→ 内置 pack 由 MCL
            自己 defineClass 成功（OK:mcl）、超类回落 Object（沙盒形状）、前缀闸
            net.minecraft.* / io.mirage.neoforge.* 不被服务、FAKE MODE 横幅打出。
    变异层（行为）：把 readFromBuiltin 分支置空再编一份 RuntimeFakePack 顶在 fat jar 之前，
            重新跑 BUILTIN_ON 必须退回 CNFE —— 证明「内置 pack 真被消费」不是空转。
    全程不创建窗口、不启动 MC、不写 mc-shili/（AGENTS 第 2 节 (B) 类）。

.EXAMPLE
    .\scripts\verify_fakepack_w3_c.ps1
    .\scripts\verify_fakepack_w3_c.ps1 -SkipBuild      # 复用已有 fat jar
    .\scripts\verify_fakepack_w3_c.ps1 -SkipMutation  # 跳过变异层

.NOTES
    退出码：0 全绿；1 有断言失败 / 编译失败 / 构建失败 / 环境缺失。
    末行固定 "RESULT PASS n/n" 或 "RESULT FAIL x/y"。
#>
[CmdletBinding()]
param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.4",
    [string]$Repo = "D:\bing\MC\mcmoderr-my\mirage-loader",
    [switch]$SkipBuild,
    [switch]$SkipMutation
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
    $m = [regex]::Match($text, "(?m)^\s*" + [regex]::Escape($key) + "=([^\r\n]*)")
    if ($m.Success) { return $m.Groups[1].Value.Trim() }
    return "<MISSING>"
}
function Assert-Val([string]$text, [string]$key, [string]$expected, [string]$desc) {
    $v = Get-ProbeValue $text $key
    Assert-True ($v -eq $expected) ("$desc (期望 $key=$expected，实得 $v)")
}

function Find-Gradle {
    $base = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.1.0-bin"
    if (-not (Test-Path $base)) { return $null }
    $cand = Get-ChildItem -Path $base -Recurse -Filter "gradle" -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "gradle-9.1.0[\\/]bin[\\/]gradle$" -and -not $_.PSIsContainer } |
            Select-Object -First 1
    if ($cand) { return $cand.FullName }
    return $null
}

# ---------------------------------------------------------------- 环境
$java  = Join-Path $JavaHome "bin\java.exe"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar   = Join-Path $JavaHome "bin\jar.exe"
$javap = Join-Path $JavaHome "bin\javap.exe"
if (-not (Test-Path $java)) { Write-Host "[FATAL] 找不到 java.exe: $java"; exit 1 }
if (-not (Test-Path $javac)) { Write-Host "[FATAL] 找不到 javac.exe: $javac"; exit 1 }

$loaderMain  = Join-Path $Repo "core\mirage-loader\build\classes\java\main"
$apiMain     = Join-Path $Repo "api\mirage-api\build\classes\java\main"
$mixinMain   = Join-Path $Repo "core\mirage-mixin\build\classes\java\main"
$eventbusMain= Join-Path $Repo "core\mirage-eventbus\build\classes\java\main"
$compatMain  = Join-Path $Repo "modules\mirage-neoforge-compat\build\classes\java\main"
$fabricMain  = Join-Path $Repo "modules\mirage-fabric-compat\build\classes\java\main"
$fatJar      = Join-Path $Repo "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
$rfpSrc      = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader\RuntimeFakePack.java"
$probeSrc    = Join-Path $Repo "scripts\fixtures\fakepack-w3c\W3CBuiltinProbe.java"

$cpParts = New-Object System.Collections.ArrayList
foreach ($p in @($loaderMain, $apiMain, $mixinMain, $eventbusMain, $compatMain, $fabricMain, $fatJar)) {
    if (Test-Path $p) { [void]$cpParts.Add($p) }
}
$cpCompile = ($cpParts -join ";")

$work = Join-Path $env:TEMP "mirage-w3c"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null
$probeOut = Join-Path $work "probe"
New-Item -ItemType Directory -Force $probeOut | Out-Null

Write-Host "== MSR W3 路线 C 门禁（headless，不拉 GUI / 不写 mc-shili）==" -ForegroundColor Cyan
Write-Host "   fatJar = $fatJar"

# ---------------------------------------------------------------- 构建 fat jar
if (-not $SkipBuild) {
    Write-Host "-- 构建 fat jar（:launcher:mirage-launch:fatJar）--" -ForegroundColor Yellow
    $gradle = Find-Gradle
    if (-not $gradle) { Write-Host "[FATAL] 找不到 gradle 9.1.0（~/.gradle/wrapper/dists）"; exit 1 }
    $env:JAVA_HOME = $JavaHome
    $env:MSYS2_ARG_CONV_EXCL = "*"
    $buildOut = & $gradle --no-daemon -p $Repo :launcher:mirage-launch:fatJar 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FATAL] fat jar 构建失败:`n$buildOut"; exit 1
    }
    Assert-True (Test-Path $fatJar) "fat jar 构建产物存在"
} else {
    Write-Host "-- 跳过构建，复用已有 fat jar --" -ForegroundColor DarkGray
    Assert-True (Test-Path $fatJar) "fat jar 存在（SkipBuild）"
}

# ---------------------------------------------------------------- 核包内容
Write-Host "-- 核包内容：内置 pack 必须进 fat jar --" -ForegroundColor Yellow
$tmpUnzip = Join-Path $work "jarlist.txt"
& $jar tf $fatJar 2>&1 | Out-File -FilePath $tmpUnzip -Encoding ascii
$packNeo = ([regex]::Matches((Get-Content $tmpUnzip -Raw), '(?m)mirage/fakepack/net/neoforged/[^\r\n]*\.class\r?$')).Count
$packMc  = ([regex]::Matches((Get-Content $tmpUnzip -Raw), '(?m)mirage/fakepack/net/minecraft/[^\r\n]*\.class\r?$')).Count
Assert-True ($packNeo -eq 114) ("fat jar 内置 pack 含 114 个 neo 类（80 mod 缺口 + 34 real-shape 超类闭包，实得 $packNeo）")
Assert-True ($packMc  -eq 0)  ("内置 pack 不含任何 net.minecraft.* 类（防假覆盖，实得 $packMc）")
$jarSize = (Get-Item $fatJar).Length
Assert-True ($jarSize -gt 2996660) ("fat jar 体积较部署基线 2 996 660 字节增大（实得 $jarSize）")

# fat jar 里的 RuntimeFakePack 必须含内置逻辑（不是旧版只认 home 的）
$javapOut = & $javap -p -c -classpath $fatJar io.mirage.loader.RuntimeFakePack 2>&1 | Out-String
Assert-True ($javapOut -match 'mirage/fakepack') ("fat jar 的 RuntimeFakePack 含 mirage/fakepack 读取逻辑（字节码常量）")

# ---------------------------------------------------------------- 守卫层
Write-Host "-- 守卫层（源码行首锚定）--" -ForegroundColor Yellow
$rfp = Read-Utf8 $rfpSrc
Assert-True ($rfp -match '(?m)^\s*public static final String BUILTIN_PREFIX = "mirage/fakepack/";') "GUARD 内置资源前缀常量 mirage/fakepack/"
Assert-True ($rfp -match '(?m)^\s*return readFromBuiltin\(name\);') "GUARD findBytesIfEnabled 在 HOME 为空时走 readFromBuiltin"
Assert-True ($rfp -match '(?m)^\s*String res = BUILTIN_PREFIX \+ name\.replace\(''\.'', ''/''\) \+ "\.class";') "GUARD readFromBuiltin 拼 mirage/fakepack/<path>.class"
Assert-True ($rfp -match '(?m)^\s*if \(!"on"\.equalsIgnoreCase\(runtime\)\) return false;') "GUARD initEnabled 不再硬性要求 home 目录"
Assert-True ($rfp -notmatch '(?m)^\s*if \(home\.isBlank\(\) \|\| !"on"\.equalsIgnoreCase\(runtime\)\) return false;') "GUARD 旧 home 硬性要求已移除"
Assert-True ($rfp -match '(?m)^\s*if \(name == null \|\| !name\.startsWith\(NEO_PREFIX\)\) return null;') "GUARD 仍只认 net.neoforged. 前缀闸"
Assert-True ($rfp -match '(?m)^\s*if \("off"\.equalsIgnoreCase\(kill\)\) return false;') "GUARD kill switch 保留"
Assert-True ($rfp -match '(?m)^\s*private static byte\[\] readFromBuiltin\(String name\)') "GUARD 存在 readFromBuiltin 方法"

# ---------------------------------------------------------------- 编译探针
Write-Host "-- 编译 W3CBuiltinProbe --" -ForegroundColor Yellow
$probeCompile = & $javac "-encoding" "UTF-8" "-nowarn" "-proc:none" "-cp" $cpCompile "-d" $probeOut $probeSrc 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FATAL] W3CBuiltinProbe 编译失败:`n$probeCompile"; exit 1
}
$cpRun = "$probeOut;$cpCompile"

function Run-Probe([string[]]$jvmArgs) {
    $runArgs = @() + $jvmArgs + @("-cp", $cpRun, "probe.W3CBuiltinProbe", $fatJar, (Join-Path $work "probe-out.txt"))
    $env:MSYS2_ARG_CONV_EXCL = "*"
    return (& $java @runArgs 2>&1 | ForEach-Object { $_.ToString() } | Out-String)
}

# ---- DEFAULT（默认零变化）----
Write-Host "-- DEFAULT（runtime 不给，默认零变化）--" -ForegroundColor Yellow
$s0 = Run-Probe @()
Assert-Val $s0 "BUILTIN_RESOURCE_COUNT" "114" "内置 pack 资源在 fat jar 里（114 个）"
Assert-Val $s0 "NEO_LOAD" "CNFE" "DEFAULT 缺 neo 类仍抛 CNFE（默认零变化）"
Assert-Val $s0 "B_ENABLED" "false" "DEFAULT B 默认关闭"
Assert-Val $s0 "B_HITS" "0" "DEFAULT B 零命中"
Assert-Val $s0 "MC_LOAD" "CNFE" "DEFAULT 缺 mc 类仍 CNFE"
Assert-Val $s0 "ION_LOAD" "CNFE" "DEFAULT 缺 io.mirage.neoforge 类仍 CNFE"
Assert-True (-not ($s0 -match "FAKE MODE 已启用")) "DEFAULT 没有 FAKE MODE 横幅（零日志）"

# ---- BUILTIN_ON（内置 pack 服务）----
Write-Host "-- BUILTIN_ON（runtime=on，无外部 home）--" -ForegroundColor Yellow
$s1 = Run-Probe @("-Dmirage.msr.fakepack.runtime=on")
Assert-Val $s1 "BUILTIN_RESOURCE_COUNT" "114" "BUILTIN_ON 内置 pack 资源仍在"
Assert-Val $s1 "NEO_LOAD" "OK:mcl" "BUILTIN_ON 内置 pack 由 MCL 自己 defineClass 成功"
# real-shape（2026-11-14）：首个候选按字典序是接口 IExtensionPoint，其 getSuperclass() 为 null；
# 沙盒形状时代这里断言 java.lang.Object，real-shape 后必须放宽为「不是 Object」。
# 真实形状是否生效由独立门禁 verify_fakepack_realshape.ps1 的 REALSUPER / 全量加载断言锁死。
Assert-True ((Get-ProbeValue $s1 "NEO_SUPER") -ne "java.lang.Object") "BUILTIN_ON 首个命中类不再回落 Object（real-shape，超类=null 或真实 neo 超类）"
Assert-Val $s1 "B_ENABLED" "true" "BUILTIN_ON 开关生效"
Assert-Val $s1 "B_HITS" "1" "BUILTIN_ON 恰好命中 1 个类"
Assert-Val $s1 "B_HAS_TARGET" "true" "BUILTIN_ON 命中集合含被加载的目标类"
Assert-Val $s1 "MC_LOAD" "CNFE" "BUILTIN_ON 前缀闸：net.minecraft 不被服务"
Assert-Val $s1 "ION_LOAD" "CNFE" "BUILTIN_ON 前缀闸：io.mirage.neoforge 不被服务"
Assert-True ($s1 -match "FAKE MODE 已启用") "BUILTIN_ON 打出 FAKE MODE 横幅"

$unmutatedOk = (Get-ProbeValue $s1 "NEO_LOAD") -eq "OK:mcl"

# ---------------------------------------------------------------- 变异层（行为）
if (-not $SkipMutation) {
    Write-Host "-- 变异层：置空 readFromBuiltin 再跑 BUILTIN_ON 必须退回 CNFE --" -ForegroundColor Yellow
    $mutDir = Join-Path $work "mutated"
    New-Item -ItemType Directory -Force $mutDir | Out-Null
    $mutSrc = Join-Path $work "RuntimeFakePack.java"
    $srcTxt = Read-Utf8 $rfpSrc
    # 把内置分支置空：只改行为层，不改编译期签名，确保变异后仍编得过
    $mutated = $srcTxt -replace 'return readFromBuiltin\(name\);', 'return null; // MUTATED: builtin classpath path disabled'
    if ($mutated -eq $srcTxt) {
        Write-Host "[FATAL] 变异锚点未命中（readFromBuiltin 调用缺失？）"; exit 1
    }
    [System.IO.File]::WriteAllText($mutSrc, $mutated, (New-Object System.Text.UTF8Encoding($false)))
    $mutCompile = & $javac "-encoding" "UTF-8" "-nowarn" "-proc:none" "-cp" $cpCompile "-d" $mutDir $mutSrc 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[FATAL] 变异版 RuntimeFakePack 编译失败:`n$mutCompile"; exit 1
    }
    Assert-True (Test-Path (Join-Path $mutDir "io\mirage\loader\RuntimeFakePack.class")) "变异版 RuntimeFakePack 编译产物存在"
    # 变异版顶在 fat jar 之前：探针里的 MCL 引用 RuntimeFakePack 会解析到变异版
    $cpRunMut = "$mutDir;$cpRun"
    $sM = & $java -cp $cpRunMut probe.W3CBuiltinProbe $fatJar (Join-Path $work "probe-out-mut.txt") 2>&1 | ForEach-Object { $_.ToString() } | Out-String
    Assert-Val $sM "NEO_LOAD" "CNFE" "变异（内置分支置空）后 BUILTIN_ON 退回 CNFE"
    Assert-Val $sM "B_HITS" "0" "变异后零命中"
    # 负向自检：变异能抓到，前提是未变异版本确实 OK——否则变异测试空转
    Assert-True ($unmutatedOk) "变异层负向自检：未变异 BUILTIN_ON 曾 OK:mcl（否则变异断言空转）"
} else {
    Write-Host "-- 跳过变异层（SkipMutation）--" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 结果
$pass = $script:checks - $script:fails.Count
Write-Host ""
Write-Host "== 门禁结果：PASS $pass / $($script:checks) ==" -ForegroundColor $(if ($script:fails.Count -eq 0) { "Green" } else { "Red" })
if ($script:fails.Count -gt 0) {
    Write-Host "失败项：" -ForegroundColor Red
    foreach ($f in $script:fails) { Write-Host "  - $f" -ForegroundColor Red }
    Write-Output ("RESULT FAIL {0}/{1}" -f $script:fails.Count, $script:checks)
    exit 1
}
Write-Output ("RESULT PASS {0}/{0}" -f $script:checks)
exit 0
