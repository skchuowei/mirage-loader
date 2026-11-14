# MSR · W2 真启动缺口重算 · 门禁
# ------------------------------------------------------------------
# 锁死一条关键不变量（W2 结论）：
#   用「真实启动 classpath」（compat + 真实 MC jar）重算的缺口集合 M_real，
#   与 P3 沙盒录到的 M_sandbox（仅 compat）**逐字节一致**，
#   且两个 pack 的字节 sha256 完全相同。
#
# 含义：缺口集合与运行时不相关（neo 类只来自 compat，mc 类在两种口径下都"齐全"），
# 现有 P4b 生成物对真实启动同样有效；无需为真启动另造一套。
# 反向含义：真正的「real-shape」修复需要真实 NeoForge universal jar
# （含 net.neoforged.* 类形状）作 --shape，本环境没有，列为后续项。
#
# 本门禁全部 headless（不拉 GUI、不启动游戏、不动 mc-shili/ 之外的任何东西）。
# -RealJar 缺省指向 mc-shili 内的真实游戏 jar（只读）；若环境没有该 jar，
#   real-startup 这一段会跳过并告警，sandbox 段照常跑。
#
# 用法：
#   .\verify_fakepack_realstartup.ps1 [-Repo <dir>] [-JavaHome <jdk25>] [-RealJar <jar>]
# ------------------------------------------------------------------

param(
    [string]$Repo = "",
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$RealJar = ""
)

# ---------------------------------------------------------------- helpers
function Find-MsrJdk([string]$jh) {
    if ($jh -and (Test-Path (Join-Path $jh "bin\java.exe"))) { return $jh }
    $cands = @("C:\Program Files\Java\jdk-25.0.4",
               "C:\Program Files\Java\jdk-21.0.7",
               "C:\Program Files\Java\jdk-21.0.6")
    foreach ($c in $cands) { if (Test-Path (Join-Path $c "bin\java.exe")) { return $c } }
    return $null
}

function Find-MsrAsmJar {
    $base = Join-Path $env:USERPROFILE ".gradle\caches\modules-2"
    if (-not (Test-Path $base)) { return $null }
    $j = Get-ChildItem -Path $base -Recurse -Filter "asm-*.jar" -ErrorAction SilentlyContinue |
         Where-Object { $_.Name -match "^asm-\d" } | Select-Object -First 1
    if ($j) { return $j.FullName }
    return $null
}

function Get-MsrCompatClasses([string]$repo) {
    return Join-Path $repo "modules\mirage-neoforge-compat\build\classes\java\main"
}
function Get-MsrLoaderClasses([string]$repo) {
    return Join-Path $repo "core\mirage-loader\build\classes\java\main"
}

# 跑一次 FakeGenCli，返回 (stdout, generatedCount)
function Invoke-Gen([string]$javaExe, [string]$cp, [string[]]$refs,
                   [string[]]$shapes, [string]$out, [string[]]$mods) {
    $cli = [System.Collections.Generic.List[string]]::new()
    foreach ($r in $refs)  { $cli.Add("--ref");    $cli.Add($r) }
    foreach ($s in $shapes){ $cli.Add("--shape");  $cli.Add($s) }
    $cli.Add("--out");   $cli.Add($out)
    $cli.Add("--verify")
    foreach ($m in $mods) { $cli.Add($m) }
    $o = & $javaExe -cp $cp io.mirage.loader.FakeGenCli @cli 2>&1 | Out-String
    $gen = -1
    if ($o -match 'generated=(\d+)') { $gen = [int]$Matches[1] }
    return @{ Out = $o; Generated = $gen }
}

# 计算一个 pack 目录里所有 .class 字节（按路径排序拼接）的 sha256
function Get-PackSha([string]$dir) {
    $files = Get-ChildItem -Path $dir -Recurse -Filter "*.class" -ErrorAction SilentlyContinue |
             Sort-Object FullName
    $ms = New-Object System.IO.MemoryStream
    foreach ($f in $files) {
        $b = [System.IO.File]::ReadAllBytes($f.FullName)
        $ms.Write($b, 0, $b.Length)
    }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    $h = $sha.ComputeHash($ms.ToArray())
    return ([System.BitConverter]::ToString($h) -replace '-', '').ToLower()
}

function Count-McClasses([string]$dir) {
    $n = 0
    foreach ($f in (Get-ChildItem -Path $dir -Recurse -Filter "*.class" -ErrorAction SilentlyContinue)) {
        if ($f.FullName.Replace('\', '/') -match '/net/minecraft/') { $n++ }
    }
    return $n
}
function Count-NeoClasses([string]$dir) {
    $n = 0
    foreach ($f in (Get-ChildItem -Path $dir -Recurse -Filter "*.class" -ErrorAction SilentlyContinue)) {
        if ($f.FullName.Replace('\', '/') -match '/net/neoforged/') { $n++ }
    }
    return $n
}

$script:checks = 0
$script:failures = [System.Collections.Generic.List[string]]::new()
function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) {
        Write-Host ("   [OK]   " + $desc) -ForegroundColor DarkGreen
    } else {
        $script:failures.Add($desc) | Out-Null
        Write-Host ("   [FAIL] " + $desc) -ForegroundColor Red
    }
}

# ---------------------------------------------------------------- resolve
if ($Repo -eq "") { $Repo = Resolve-Path (Join-Path $PSScriptRoot "..") }
$Repo = $Repo.TrimEnd('\', '/')

$jdk = Find-MsrJdk $JavaHome
if (-not $jdk) {
    Write-Host "[FAIL] 找不到 JDK（用 -JavaHome 指定 JDK 25）" -ForegroundColor Red
    exit 1
}
$javaExe = Join-Path $jdk "bin\java.exe"
$asmJar  = Find-MsrAsmJar
$loaderMain = Get-MsrLoaderClasses $Repo
$compatClasses = Get-MsrCompatClasses $Repo

if (-not $asmJar) {
    Write-Host "[FAIL] 找不到 asm jar（gradle 缓存里）" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $compatClasses "net\neoforged"))) {
    Write-Host "[FAIL] compat 未编译（缺 $compatClasses\net\neoforged）" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $loaderMain)) {
    Write-Host "[FAIL] loader 未编译（先跑 gradle :core:mirage-loader:compileJava）" -ForegroundColor Red
    exit 1
}
$cp = "$loaderMain;$asmJar"

# 真实 jar 缺省 = mc-shili 里的真实游戏 jar（只读）
if ($RealJar -eq "") {
    $RealJar = Join-Path $Repo "..\mc-shili\versions\26.2-NeoForge_26.2.0.90\26.2-NeoForge_26.2.0.90.jar"
}
$realAvailable = Test-Path $RealJar

# 确保 6 个测试 mod 在 %TEMP%\msr-mods（幂等拉取，只写 %TEMP%）
$modDir = Join-Path $env:TEMP "msr-mods"
if (-not (Test-Path (Join-Path $modDir "jei-26.2-neoforge-30.48.0.244.jar"))) {
    $fetch = Join-Path $Repo "scripts\fetch_msr_test_mods.ps1"
    if (Test-Path $fetch) {
        Write-Host "== 拉取 6 个真实测试 mod（仅 %TEMP%）==" -ForegroundColor Yellow
        & $fetch | Out-Null
    }
}
$mods = @(Get-ChildItem -Path $modDir -Filter "*.jar" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
if ($mods.Count -lt 6) {
    Write-Host ("[FAIL] 测试 mod 不足（{0} 个，需 6）" -f $mods.Count) -ForegroundColor Red
    exit 1
}

$work = Join-Path $env:TEMP ("msr-w2-" + [guid]::NewGuid().ToString("N"))
$sandboxPack = Join-Path $work "sandbox"
$realPack    = Join-Path $work "realstartup"

Write-Host "== MSR W2 · 真启动缺口重算门禁 ==" -ForegroundColor Cyan
Write-Host ("   loader   : " + $loaderMain)
Write-Host ("   asm      : " + $asmJar)
Write-Host ("   real jar : " + $(if ($realAvailable) { $RealJar } else { "(缺省，跳过 real-startup 段)" }))

# ---------------------------------------------------------------- 1) sandbox M
Write-Host "-- (1) 沙盒缺口 M（仅 compat）--" -ForegroundColor Yellow
$r1 = Invoke-Gen $javaExe $cp @($compatClasses) @($compatClasses) $sandboxPack $mods
$sandboxGen = $r1.Generated
$sandboxSha = Get-PackSha $sandboxPack
Write-Host ("   generated=" + $sandboxGen + "  sha=" + $sandboxSha.Substring(0,12) + "...")
Assert-True ($sandboxGen -gt 0) "沙盒缺口 M 非空（generated>0）"
# W3-C1 把 29 个 clean-room 事件类合入 compat 源码后，真实 mod 的 neo 缺口由 80 降为 60。
# 绝对值会随 compat 面继续演进再变，这里改用「只减不增 + 不超出内置 pack 全集」的相对判据；
# W2 的核心结论（M_real 与 M_sandbox 逐字节一致）仍在下面第 (3) 段逐字节锁死。
Assert-True ($sandboxGen -le 114) ("沙盒缺口 M 不超出内置 pack 的 114 类全集（实际 " + $sandboxGen + "）")
Assert-True ($sandboxGen -lt 80) ("W3-C1 后 compat 面扩大，缺口应少于 W2 基线 80（实际 " + $sandboxGen + "）")

# 0 个 net.minecraft 类；全部 net.neoforged.*
$mcCount  = Count-McClasses $sandboxPack
$neoCount = Count-NeoClasses $sandboxPack
Assert-True ($mcCount -eq 0) ("pack 不含任何 net.minecraft.* 类（防假覆盖，实际 " + $mcCount + "）")
Assert-True ($neoCount -eq $sandboxGen) ("pack 内类全部落在 net.neoforged.*（" + $neoCount + "/" + $sandboxGen + "）")

# ---------------------------------------------------------------- 2) 确定性
Write-Host "-- (2) 确定性（同输入再生成一次应逐字节相同）--" -ForegroundColor Yellow
$sandboxPack2 = Join-Path $work "sandbox2"
$r2 = Invoke-Gen $javaExe $cp @($compatClasses) @($compatClasses) $sandboxPack2 $mods
$sandboxSha2 = Get-PackSha $sandboxPack2
Assert-True ($sandboxSha -eq $sandboxSha2) "两次沙盒生成字节级一致（sha 相同）"

# ---------------------------------------------------------------- 3) real-startup M
if ($realAvailable) {
    Write-Host "-- (3) 真实启动缺口 M（compat + 真实 MC jar）--" -ForegroundColor Yellow
    $r3 = Invoke-Gen $javaExe $cp @($compatClasses, $RealJar) @($compatClasses, $RealJar) $realPack $mods
    $realGen = $r3.Generated
    $realSha = Get-PackSha $realPack
    Write-Host ("   generated=" + $realGen + "  sha=" + $realSha.Substring(0,12) + "...")
    Assert-True ($realGen -eq $sandboxGen) ("真实启动缺口 M 与沙盒 M 类数一致（" + $realGen + " vs " + $sandboxGen + "）")
    Assert-True ($realSha -eq $sandboxSha) "真实启动 pack 与沙盒 pack 逐字节一致（sha 相同）"

    # --verify 必须把 mc 报成 0（不再"缺失"）
    $mcZero = ([regex]::Matches($r3.Out, '剩余 mc=0')).Count
    Assert-True ($mcZero -ge 6) ("每个 mod 的 --verify 都报 mc=0（共 " + $mcZero + " 条）")
} else {
    Write-Host "-- (3) 真实启动缺口 M：跳过（未提供 -RealJar / 环境无 mc-shili 真实 jar）--" -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- 4) 负向自检（活性）
Write-Host "-- (4) 负向自检（断言不是永真）--" -ForegroundColor Yellow
# 4a：mc 类扫描能检出 net.minecraft.* —— 否则"0 mc 类"断言是空转
$neg = Join-Path $work "neg-mc"
New-Item -ItemType Directory -Force -Path (Join-Path $neg "net\minecraft") | Out-Null
New-Item -ItemType File -Force -Path (Join-Path $neg "net\minecraft\Phantom.class") | Out-Null
Assert-True ((Count-McClasses $neg) -ge 1) "mc 扫描能检出 net.minecraft.* 类（否则 0-mc 断言空转）"

# 4b：不同 mod 集合应得到不同 sha —— 否则"逐字节一致"断言是空转
# 必须去掉一个"有贡献"的 mod（mezz_config 贡献 0 类，去掉它 sha 不变，不能用来证活性）
$subset = @($mods | Where-Object { $_ -notmatch 'jei-26\.2' })   # 去掉 jei（贡献 55 个 neo 类）
$subsetPack = Join-Path $work "subset"
$r4 = Invoke-Gen $javaExe $cp @($compatClasses) @($compatClasses) $subsetPack $subset
$subsetSha = Get-PackSha $subsetPack
Assert-True ($subsetSha -ne $sandboxSha) "mod 集合变了，pack sha 应随之改变（否则字节比对空转）"

# ---------------------------------------------------------------- 结论
Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host ("RESULT PASS " + $script:checks + "/" + $script:checks) -ForegroundColor Green
    exit 0
} else {
    Write-Host ("RESULT FAIL " + $script:failures.Count + "/" + $script:checks) -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host ("   - " + $f) -ForegroundColor Red }
    exit 1
}
