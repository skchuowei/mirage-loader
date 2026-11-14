<#
.SYNOPSIS
    MSR W3 real-shape 门禁：内置 fakepack 的 neo stub 必须带真实形状（真实超类/接口/
    enum/成员），且整条继承链自洽 —— 全量加载零 NCDFE、零 ClassFormatError。

.DESCRIPTION
    背景：W3-C 的 pack 原本是「沙盒形状」（UOE 骨架，superName 一律 Object）。用真实
    NeoForge universal/bus/loader 三个 jar 作 --shape 后，stub 会 extends 真实 neo 超类；
    但这会引入两类悬空/非法引用，门禁必须锁死：
      ① 间接超类（ScreenEvent / BaseCapability / ParallelDispatchEvent ...）不在 mod
         缺口集合里 → 需白名单闭包 + FakeGenCli 把它们补进 missing 才会递归生成。
      ② 间接 mc 超类（DataComponentHolder ...）一律回落 Object；形状源里 protected
         字段须清 ACC_PROTECTED，否则 0x5 非法修饰符 → ClassFormatError。
    守卫层：源码行首锚定 (?m)^\s*，核 FakeGenCli 白名单补 missing、FakeStubGenerator
            的 mc 回落与 ACC_PROTECTED 清理三处修复。
    行为层：编译固定 fixture AllLoadProbe，构造真实 MirageClassLoader（URL 只挂 fat jar），
            枚举内置 pack 全部类逐个 loadClass，断言 BUILTIN_ON 下 LOAD_OK=114 /
            NCDFE=0 / OTHER=0 / REALSUPER>=1 / REALITF>=1；DEFAULT 下 B 关闭且不全量服务。
    变异层：守卫层（内存副本改锚点，守卫断言必须红）；行为层（落盘 fat jar 副本删掉一个
            间接超类 ScreenEvent.class，全量加载必须出现 NCDFE，证明断言非空转）+ 负向自检
            （未删的副本走同流程仍 114/114，排除副本损坏假红）。
    全程不创建窗口、不启动 MC、不写 mc-shili/。

.EXAMPLE
    .\scripts\verify_fakepack_realshape.ps1
    .\scripts\verify_fakepack_realshape.ps1 -SkipMutation

.NOTES
    退出码：0 全绿；1 有断言失败 / 编译失败 / 环境缺失。
    末行固定 "RESULT PASS n/n" 或 "RESULT FAIL x/y"。
#>
[CmdletBinding()]
param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.4",
    [string]$Repo = "D:\bing\MC\mcmoderr-my\mirage-loader",
    [switch]$SkipMutation
)

$ErrorActionPreference = "Continue"
$script:checks = 0
$script:fails = New-Object System.Collections.ArrayList

function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) { Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen }
    else { [void]$script:fails.Add($desc); Write-Host "   [FAIL] $desc" -ForegroundColor Red }
}

# C76：Get-Content -Raw 不带 -Encoding 会走系统 ANSI(GBK)，UTF-8 源码里的中文断言静默失效
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
function Assert-IntAtLeast([string]$text, [string]$key, [int]$min, [string]$desc) {
    $v = Get-ProbeValue $text $key
    $num = 0
    if (-not [int]::TryParse($v, [ref]$num)) { $num = -1 }
    Assert-True ($num -ge $min) ("$desc (期望 $key>=$min，实得 $v)")
}
function Assert-IntBelow([string]$text, [string]$key, [int]$max, [string]$desc) {
    $v = Get-ProbeValue $text $key
    $num = -1
    if (-not [int]::TryParse($v, [ref]$num)) { $num = [int]::MaxValue }
    Assert-True ($num -lt $max) ("$desc (期望 $key<$max，实得 $v)")
}

# ---------------------------------------------------------------- 环境
$java  = Join-Path $JavaHome "bin\java.exe"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jar   = Join-Path $JavaHome "bin\jar.exe"
if (-not (Test-Path $java))  { Write-Host "[FATAL] 找不到 java.exe: $java"; exit 1 }
if (-not (Test-Path $javac)) { Write-Host "[FATAL] 找不到 javac.exe: $javac"; exit 1 }

$loaderMain = Join-Path $Repo "core\mirage-loader\build\classes\java\main"
$apiMain    = Join-Path $Repo "api\mirage-api\build\classes\java\main"
$mixinMain  = Join-Path $Repo "core\mirage-mixin\build\classes\java\main"
$eventbus   = Join-Path $Repo "core\mirage-eventbus\build\classes\java\main"
$compatMain = Join-Path $Repo "modules\mirage-neoforge-compat\build\classes\java\main"
$fabricMain = Join-Path $Repo "modules\mirage-fabric-compat\build\classes\java\main"
$fatJar     = Join-Path $Repo "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
$genCliSrc  = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader\FakeGenCli.java"
$genStubSrc = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader\FakeStubGenerator.java"
$allProbeSrc= Join-Path $Repo "scripts\fixtures\fakepack-w3c\AllLoadProbe.java"

foreach ($p in @($fatJar, $genCliSrc, $genStubSrc, $allProbeSrc)) {
    if (-not (Test-Path $p)) { Write-Host "[FATAL] 缺少必需文件: $p"; exit 1 }
}

$cpParts = New-Object System.Collections.ArrayList
foreach ($p in @($loaderMain, $apiMain, $mixinMain, $eventbus, $compatMain, $fabricMain, $fatJar)) {
    if (Test-Path $p) { [void]$cpParts.Add($p) }
}
$cpCompile = ($cpParts -join ";")

$work = Join-Path $env:TEMP "mirage-w3c-realshape"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null
$probeOut = Join-Path $work "probe"
New-Item -ItemType Directory -Force $probeOut | Out-Null

Write-Host "== MSR W3 real-shape 门禁（headless，不拉 GUI / 不写 mc-shili）==" -ForegroundColor Cyan
Write-Host "   fatJar = $fatJar"

# ---------------------------------------------------------------- 核包内容
Write-Host "-- 核包内容：real-shape 闭包必须整体进 fat jar --" -ForegroundColor Yellow
$tmpUnzip = Join-Path $work "jarlist.txt"
& $jar tf $fatJar 2>&1 | Out-File -FilePath $tmpUnzip -Encoding ascii
$jarText = Get-Content $tmpUnzip -Raw
# C99：Out-File 写的是 \r\n；(?m) 下 [^\r\n]* 锁单行、\.class\r?$ 容忍行尾 \r
$packNeo = ([regex]::Matches($jarText, '(?m)mirage/fakepack/net/neoforged/[^\r\n]*\.class\r?$')).Count
$packMc  = ([regex]::Matches($jarText, '(?m)mirage/fakepack/net/minecraft/[^\r\n]*\.class\r?$')).Count
Assert-True ($packNeo -eq 114) ("内置 pack 含 114 个 neo 类（80 缺口 + 34 超类闭包，实得 $packNeo）")
Assert-True ($packMc -eq 0) ("内置 pack 不含任何 net.minecraft.* 类（mc 一律回落 Object，实得 $packMc）")

# ---------------------------------------------------------------- 守卫层
Write-Host "-- 守卫层（源码行首锚定）--" -ForegroundColor Yellow
$cliSrc  = Read-Utf8 $genCliSrc
$stubSrc = Read-Utf8 $genStubSrc
$g1 = '(?m)^\s*' + [regex]::Escape('if (w.startsWith(FakeStubGenerator.GENERATED_PREFIX)) missing.add(w);')
$g2 = '(?m)^\s*' + [regex]::Escape('if (dotted.startsWith("net.minecraft.")) return "java/lang/Object";')
$g3 = '(?m)^\s*' + [regex]::Escape('acc = (access & ~(Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED))')
Assert-True ($cliSrc  -match $g1) "GUARD FakeGenCli 按需白名单补进 missing（超类闭包可递归生成）"
Assert-True ($stubSrc -match $g2) "GUARD resolveSuper 对间接 mc 超类一律回落 Object"
Assert-True ($stubSrc -match $g3) "GUARD emitField 清 ACC_PROTECTED（防 0x5 ClassFormatError）"

# ---------------------------------------------------------------- 编译探针
Write-Host "-- 编译 AllLoadProbe --" -ForegroundColor Yellow
$probeCompile = & $javac "-encoding" "UTF-8" "-nowarn" "-proc:none" "-cp" $cpCompile "-d" $probeOut $allProbeSrc 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    Write-Host "[FATAL] AllLoadProbe 编译失败:`n$probeCompile"; exit 1
}
$cpRun = "$probeOut;$cpCompile"

function Run-AllProbe([string[]]$jvmArgs, [string]$jarPath) {
    # C89/C90：& 调 java 时 -Dkey=value 必须作为数组元素整体传入，否则被当主类名。
    # 关键（C100）：RuntimeFakePack.readFromBuiltin 走 app loader（即 -cp 里的 fat jar）
    # 读 mirage/fakepack/ 资源，而不是探针参数那个 jar。跑副本场景时必须把 -cp 里的
    # fat jar 也换成副本，否则变异只影响「枚举列表」、加载仍命中原始 jar ⇒ 变异假红。
    $cpUse = $cpRun
    if ($jarPath -ne $fatJar) { $cpUse = $cpRun.Replace($fatJar, $jarPath) }
    $runArgs = @() + $jvmArgs + @("-cp", $cpUse, "probe.AllLoadProbe", $jarPath)
    $env:MSYS2_ARG_CONV_EXCL = "*"
    return (& $java @runArgs 2>&1 | ForEach-Object { $_.ToString() } | Out-String)
}

# ---- BUILTIN_ON：全量加载必须自洽 + 真实形状生效 ----
Write-Host "-- BUILTIN_ON：全量加载 114 类 --" -ForegroundColor Yellow
$s1 = Run-AllProbe @("-Dmirage.msr.fakepack.runtime=on") $fatJar
Assert-Val $s1 "LOAD_TOTAL" "114" "BUILTIN_ON 枚举到 114 个内置 neo 类"
Assert-Val $s1 "LOAD_OK" "114" "BUILTIN_ON 全部 114 类由 MCL 成功 loadClass（零悬空超类）"
Assert-Val $s1 "LOAD_CNFE" "0" "BUILTIN_ON 零 CNFE"
Assert-Val $s1 "LOAD_NCDFE" "0" "BUILTIN_ON 零 NoClassDefFoundError（超类闭包完整）"
Assert-Val $s1 "LOAD_OTHER" "0" "BUILTIN_ON 零 ClassFormatError/VerifyError 等其它加载失败"
Assert-IntAtLeast $s1 "REALSUPER" 1 "BUILTIN_ON 存在真实 neo 超类（real-shape 生效，非 UOE 骨架）"
Assert-IntAtLeast $s1 "REALITF" 1 "BUILTIN_ON 存在真实 neo 接口实现（real-shape 生效）"
Assert-Val $s1 "B_ENABLED" "true" "BUILTIN_ON 开关生效"
Assert-IntAtLeast $s1 "B_HITS" 1 "BUILTIN_ON 内置 fallback 有命中"
Assert-True ($s1 -match "FAKE MODE 已启用") "BUILTIN_ON 打出 FAKE MODE 横幅"

# ---- DEFAULT：默认零变化（fallback 关闭，只有 compat 原生可提供）----
Write-Host "-- DEFAULT：默认零变化 --" -ForegroundColor Yellow
$s0 = Run-AllProbe @() $fatJar
Assert-Val $s0 "B_ENABLED" "false" "DEFAULT B 默认关闭"
Assert-Val $s0 "B_HITS" "0" "DEFAULT B 零命中"
Assert-IntBelow $s0 "LOAD_OK" 114 "DEFAULT 不全量服务（仅 compat 原生少数，pack 未被消费）"
Assert-True (-not ($s0 -match "FAKE MODE 已启用")) "DEFAULT 没有 FAKE MODE 横幅"

# ---------------------------------------------------------------- 变异层
if (-not $SkipMutation) {
    # -- 守卫层变异（内存副本即可）：改坏锚点后守卫断言必须变红 --
    Write-Host "-- 变异层：守卫层（内存副本改锚点）--" -ForegroundColor Yellow
    $cliMut  = $cliSrc.Replace('missing.add(w);', '// MUTATED: no closure seeding')
    $stubMut = $stubSrc.Replace('if (dotted.startsWith("net.minecraft.")) return "java/lang/Object";',
                                '// MUTATED: mc supertype fallback disabled')
    $fieldMut = $stubSrc.Replace('Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED',
                                 'Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE')
    Assert-True ($cliSrc -ne $cliMut)       "变异锚点 1 命中：白名单补 missing"
    Assert-True ($stubSrc -ne $stubMut)     "变异锚点 2 命中：mc 回落"
    Assert-True ($stubSrc -ne $fieldMut)    "变异锚点 3 命中：ACC_PROTECTED"
    Assert-True (-not ($cliMut  -match $g1)) "守卫变异 1 生效：改坏后 G1 必须红"
    Assert-True (-not ($stubMut -match $g2)) "守卫变异 2 生效：改坏后 G2 必须红"
    Assert-True (-not ($fieldMut -match $g3)) "守卫变异 3 生效：改坏后 G3 必须红"

    # -- 行为层变异（落盘 fat jar 副本）：删一个间接超类，全量加载必须出现 NCDFE --
    Write-Host "-- 变异层：行为层（落盘副本删超类）--" -ForegroundColor Yellow
    $victim = "mirage/fakepack/net/neoforged/neoforge/client/event/ScreenEvent.class"
    $cleanCopy = Join-Path $work "fatjar-clean.jar"
    $mutCopy   = Join-Path $work "fatjar-mutated.jar"
    Copy-Item -LiteralPath $fatJar -Destination $cleanCopy -Force
    Copy-Item -LiteralPath $fatJar -Destination $mutCopy -Force
    # Windows PowerShell 5.1 下 ZipArchiveMode 在 System.IO.Compression 里，
    # ZipFile 在 System.IO.Compression.FileSystem 里，两个程序集都要显式加载（C100）
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($mutCopy, [System.IO.Compression.ZipArchiveMode]::Update)
    $entry = $zip.GetEntry($victim)
    if ($null -eq $entry) {
        $zip.Dispose()
        Write-Host "[FATAL] 变异目标资源不存在: $victim"; exit 1
    }
    $entry.Delete()
    $zip.Dispose()

    # 负向自检：未删的副本走同流程仍须 114/114（排除副本损坏假红，C77 编译路径自检类比）
    $sClean = Run-AllProbe @("-Dmirage.msr.fakepack.runtime=on") $cleanCopy
    Assert-Val $sClean "LOAD_OK" "114" "行为变异负向自检：未变异副本仍 114/114（排除副本损坏假红）"
    Assert-Val $sClean "LOAD_NCDFE" "0" "行为变异负向自检：未变异副本零 NCDFE"

    $sMut = Run-AllProbe @("-Dmirage.msr.fakepack.runtime=on") $mutCopy
    Assert-IntAtLeast $sMut "LOAD_NCDFE" 1 "行为变异生效：删掉 ScreenEvent 超类后出现 NCDFE（证明 LOAD_NCDFE 断言非空转）"
    Assert-IntBelow $sMut "LOAD_OK" 114 "行为变异生效：删掉超类后 LOAD_OK 下降"
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
