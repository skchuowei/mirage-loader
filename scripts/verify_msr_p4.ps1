<#
.SYNOPSIS
    MSR P4 门禁：fake 自动生成的静态守卫 + 行为断言（真加载真调用）+ 可选真实 mod 回归。

.DESCRIPTION
    三层：
      1) 静态守卫：只生成 net.neoforged.*、方法体必须是 UOE、enum 带 clinit、
         接口标志/接口字段合法、形状源只读签名、provider 顺序、指纹参与。
      2) 行为断言（合成夹具，秒级、不启动游戏）：现造形状源 + 引用方 class，
         跑「分析 → 生成 → defineClass → 调用」，要求
         接口真是接口、enum 真是枚举且常量非 null、方法/构造抛 UOE（不是 NoSuchMethod）。
      3) -RealMods：用 %TEMP%\msr-mods 缓存的真实 mod 跑生成器 + 批量加载 pack，
         要求 neo 缺口全部归零、net.minecraft 一条不生成、0 个 ClassFormat/VerifyError。

.EXAMPLE
    .\scripts\verify_msr_p4.ps1
    .\scripts\verify_msr_p4.ps1 -RealMods
    .\scripts\verify_msr_p4.ps1 -Mutation

.NOTES
    退出码：0 全绿；1 有断言失败 / 编译失败 / 环境缺失。
#>
[CmdletBinding()]
param(
    [switch]$RealMods,
    [switch]$Mutation,
    [string]$JavaHome = "",
    [string]$ReportFile = (Join-Path $env:TEMP "verify_msr_p4.report.txt")
)

$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "common\MsrP4.ps1")

$repo = Get-MsrRepoRoot
$report = New-Object System.Collections.Generic.List[string]
$script:checks = 0
$script:failures = New-Object System.Collections.Generic.List[string]

function Assert-True([bool]$cond, [string]$desc) {
    $script:checks++
    if ($cond) {
        Write-Host "   [OK]   $desc" -ForegroundColor DarkGreen
        $report.Add("OK   $desc")
    } else {
        $script:failures.Add($desc) | Out-Null
        Write-Host "   [FAIL] $desc" -ForegroundColor Red
        $report.Add("FAIL $desc")
    }
}

function Assert-Match([string]$text, [string]$pattern, [string]$desc) {
    Assert-True ($text -match $pattern) $desc
    if ($text -notmatch $pattern) {
        Write-Host ("          期望匹配 {0}" -f $pattern) -ForegroundColor DarkGray
    }
}

# Get-Content -Raw 不带 -Encoding 会走系统 ANSI(GBK)，UTF-8 源码里的中文断言会静默失效（C76）
function Read-Utf8([string]$path) {
    return [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::UTF8)
}

$jdk = Find-MsrJdk $JavaHome
if (-not $jdk) {
    Write-Host "[FAIL] 找不到 JDK（用 -JavaHome 指定 JDK 25）" -ForegroundColor Red
    exit 1
}
$javaExe = Join-Path $jdk "bin\java.exe"
$javacExe = Join-Path $jdk "bin\javac.exe"
$cp = Get-MsrP4Classpath $repo
$compatClasses = Get-MsrCompatClasses $repo

$sources = @{
    gen  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\FakeStubGenerator.java"
    gap  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\FakeGapAnalyzer.java"
    cli  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\FakeGenCli.java"
    pack = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\FakePackProvider.java"
    rec  = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\SandboxRecorder.java"
    genP = Join-Path $repo "scripts\msr_p4_generate_fakes.ps1"
}
foreach ($k in $sources.Keys) {
    if (-not (Test-Path $sources[$k])) {
        Write-Host ("[FAIL] 找不到文件: {0} ({1})" -f $sources[$k], $k) -ForegroundColor Red
        exit 1
    }
}
if (-not (Test-Path (Join-Path $compatClasses "net\neoforged"))) {
    Write-Host "[FAIL] compat 未编译（缺 $compatClasses\net\neoforged）" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path (Join-Path $repo "core\mirage-loader\build\classes\java\main"))) {
    Write-Host "[FAIL] loader 未编译（先跑 gradle :core:mirage-loader:compileJava）" -ForegroundColor Red
    exit 1
}

Write-Host "== MSR 门禁 P4 · fake 自动生成 ==" -ForegroundColor Cyan

# ================================================================= 静态守卫
Write-Host "-- 静态守卫 --" -ForegroundColor Yellow

$gen = Read-Utf8 $sources.gen
Assert-Match $gen 'class FakeStubGenerator' '生成器存在'
Assert-Match $gen 'GENERATED_PREFIX = "net\.neoforged\."' '只生成 net.neoforged.*（范围写死在一个常量里）'
Assert-Match $gen '不在生成范围' '非该前缀的缺口进 skipped 并写明原因（不是静默丢弃）'
Assert-Match $gen 'UOE_PREFIX = "MSR fake 未实现: "' '方法体消息常量在位'
Assert-Match $gen 'java/lang/UnsupportedOperationException' '方法体一律抛 UOE（不搬真实现）'
Assert-Match $gen 'Opcodes\.ACC_STATIC, "<clinit>"' 'enum 自己合成 <clinit>（常量不能被剥成 null）'
Assert-Match $gen '(?m)^\s*access &= ~Opcodes\.ACC_ABSTRACT;' 'enum 清掉 ACC_ABSTRACT（常量专属实现会让它抽象）'
Assert-Match $gen 'Opcodes\.ACC_PUBLIC \| Opcodes\.ACC_STATIC \| Opcodes\.ACC_FINAL' '接口字段强制 public static final'
Assert-Match $gen '(?m)^\s*access \|= Opcodes\.ACC_INTERFACE \| Opcodes\.ACC_ABSTRACT;' '判出接口后真的把 ACC_INTERFACE 写进 class 头'
Assert-Match $gen 'emitThrowingCtor\(cw, owner, desc, superName\)' '构造调的是**直接超类**（写死 Object 会 VerifyError）'
Assert-Match $gen 'ClassReader\.SKIP_CODE \| ClassReader\.SKIP_DEBUG' '形状源只读签名（SKIP_CODE，不碰方法体）'
Assert-Match $gen '"\$VALUES"' 'enum 生成 $VALUES（values() 才不是空壳）'

$gap = Read-Utf8 $sources.gap
Assert-Match $gap 'visitFieldInsn' '成员级：扫 GETSTATIC/PUTSTATIC 的 owner+name+desc'
Assert-Match $gap 'visitMethodInsn' '成员级：扫方法调用的 owner+name+desc'
Assert-Match $gap 'isStatic' '记录 static 与否（否则会 IncompatibleClassChangeError）'
Assert-Match $gap 'ownerItf' '记录 owner 是不是接口（否则会把接口生成成 class）'
Assert-Match $gap 'visitTypeInsn' 'code 里的 NEW/CHECKCAST 也算类引用（只看描述符会漏）'

$packSrc = Read-Utf8 $sources.pack
Assert-Match $packSrc 'ALLOWED = \{ "net\.neoforged\.", "net\.minecraft\." \}' 'pack 只允许两个 fake 包域'
Assert-Match $packSrc 'indexOf\("\.\."\)' '拒绝 .. 路径逃逸'
Assert-Match $packSrc 'mirage\.msr\.fakepack' 'pack 由系统属性开关控制（默认关）'

$rec = Read-Utf8 $sources.rec
Assert-Match $rec 'chain\(compat, pack\)' 'provider 顺序：compat 在前（它有真桥接，不能被空架子顶掉）'
Assert-Match $rec '"pack:"' 'pack 参与假表面指纹（否则老缓存给出过时结论）'

$cliSrc = Read-Utf8 $sources.cli
Assert-Match $cliSrc '"--verify"' 'CLI 有 --verify：补齐后必须重算缺口'
Assert-Match $cliSrc 'mirage\.msr\.fakepack/1' '报告带 schema 版本'

$genPs = Read-Utf8 $sources.genP
Assert-Match $genPs 'io\.mirage\.loader\.FakeGenCli' '素材脚本调的是同一个 CLI（不另造入口）'

# ================================================================= 行为断言（合成夹具）
Write-Host "-- 行为断言（分析 -> 生成 -> defineClass -> 调用）--" -ForegroundColor Yellow

$work = Join-Path $env:TEMP ("msr-p4-" + [guid]::NewGuid().ToString("N"))
$probeOut = Join-Path $work "probe-classes"
New-Item -ItemType Directory -Force -Path $probeOut | Out-Null
$fixture = Join-Path $repo "scripts\fixtures\p4-gen-probe"

$compileLog = & $javacExe -encoding UTF-8 -nowarn -cp $cp -d $probeOut `
    (Join-Path $fixture "P4GenProbe.java") (Join-Path $fixture "PackLoadProbe.java") 2>&1
if ($LASTEXITCODE -ne 0) {
    $compileLog | Select-Object -First 8 | ForEach-Object { Write-Host "          $_" -ForegroundColor DarkGray }
    Assert-True $false "夹具编译通过"
} else {
    Assert-True $true "夹具编译通过（P4GenProbe + PackLoadProbe）"
    $runOut = & $javaExe -cp "$probeOut;$cp" probe.P4GenProbe $work $compatClasses 2>&1 | Out-String
    Write-Host ($runOut.Trim()) -ForegroundColor DarkGray

    Assert-Match $runOut 'ANALYZE ref=\d+ miss=\d+ neo=4 mc=1' '分析：4 个 neo 缺口 + 1 个 mc 缺口（合成夹具）'
    Assert-Match $runOut 'GEN generated=4 skipped=1 failed=0' '生成：4 个 neo 类，mc 缺口被跳过，无失败'
    Assert-Match $runOut 'BOUNDARY mcGenerated=0' '边界：pack 里一条 net/minecraft 都没有'
    Assert-Match $runOut 'LOAD Api=ok interface=false' '生成物：普通类能加载且不是接口'
    Assert-Match $runOut 'BEHAVIOR Api\.VALUE static=true' '生成物：静态字段存在且是 static'
    Assert-Match $runOut 'BEHAVIOR Api\.bar=UnsupportedOperationException prefixOk=true' '生成物：静态方法是 UOE（不是真实现、不是 NoSuchMethod）'
    Assert-Match $runOut 'BEHAVIOR Api\.ctor=UnsupportedOperationException prefixOk=true' '生成物：构造函数也抛 UOE'
    Assert-Match $runOut 'LOAD IThing=ok interface=true abstractMethod=true' '生成物：接口真是接口，方法保持抽象'
    Assert-Match $runOut 'staticFinalField=true' '生成物：接口字段 public static final'
    Assert-Match $runOut 'LOAD Mode=ok enum=true abstract=false' '生成物：enum 真是枚举且不抽象'
    Assert-Match $runOut 'BEHAVIOR Mode\.CLIENT=ok values=2 name=CLIENT' '生成物：clinit 真跑了（常量非 null、values() 有 2 个）'
}

# ================================================================= 真实 mod（可选）
if ($RealMods) {
    Write-Host "-- 真实 mod 回归 --" -ForegroundColor Yellow

    $cacheDir = Join-Path $env:TEMP "msr-mods"
    $modJars = @()
    if (Test-Path $cacheDir) {
        $modJars = @(Get-ChildItem -Path $cacheDir -Filter "*.jar" | Sort-Object Name | ForEach-Object { $_.FullName })
    }
    Assert-True ($modJars.Count -ge 1) ("有真实 mod 素材（找到 {0} 个；没有就跑 scripts\fetch_msr_test_mods.ps1）" -f $modJars.Count)

    if ($modJars.Count -ge 1) {
        $packOut = Join-Path $work "real-pack"
        $asmJar = Find-MsrAsmJar
        $libraries = Join-Path (Split-Path -Parent $repo) "mc-shili\libraries"
        $shapeJars = Find-MsrShapeJars $libraries
        Assert-True ($shapeJars.Count -ge 1) ("找到 NeoForge 形状源（{0} 个）" -f $shapeJars.Count)

        $cli = New-Object System.Collections.Generic.List[string]
        [void]$cli.Add("io.mirage.loader.FakeGenCli")
        [void]$cli.Add("--ref"); [void]$cli.Add($compatClasses)
        [void]$cli.Add("--out"); [void]$cli.Add($packOut)
        [void]$cli.Add("--verify")
        foreach ($s in $shapeJars) { [void]$cli.Add("--shape"); [void]$cli.Add($s) }
        foreach ($m in $modJars) { [void]$cli.Add($m) }

        $cliOut = & $javaExe -cp "$(Join-Path $repo 'core\mirage-loader\build\classes\java\main');$asmJar" $cli.ToArray() 2>&1 | Out-String
        $cliCode = $LASTEXITCODE
        Write-Host ($cliOut.Trim()) -ForegroundColor DarkGray
        Assert-True ($cliCode -eq 0) "生成器在真实 mod 上退出码 0（实际 $cliCode）"
        Assert-Match $cliOut 'generated=\d+' '真实 mod：生成了类'
        Assert-Match $cliOut 'failed=0' '真实 mod：没有生成失败'

        # 每个 mod 的 neo 缺口都必须归零（"neoMissing N -> 0"）
        $verifyLines = [regex]::Matches($cliOut, 'neoMissing\s+(\d+)\s*->\s*(\d+)')
        $allZero = $verifyLines.Count -ge 1
        $badLines = New-Object System.Collections.Generic.List[string]
        foreach ($m in $verifyLines) {
            if ($m.Groups[2].Value -ne "0") {
                $allZero = $false
                [void]$badLines.Add($m.Value)
            }
        }
        Assert-True $allZero ("真实 mod：每个 mod 的 net.neoforged 缺口都归零（{0} 条校验）" -f $verifyLines.Count)
        foreach ($b in $badLines) { Write-Host "          $b" -ForegroundColor DarkGray }

        # 边界：pack 里不能有 net/minecraft
        $mcFiles = 0
        if (Test-Path $packOut) {
            $mcFiles = @(Get-ChildItem -Path $packOut -Recurse -Filter "*.class" |
                         Where-Object { $_.FullName -replace '\\', '/' -match '/net/minecraft/' }).Count
        }
        Assert-True ($mcFiles -eq 0) "真实 mod：pack 里没有 net/minecraft 类（实际 $mcFiles）"

        # 字节码合法性：ClassFormatError/VerifyError 必须 0（缺 mc 类导致的 CNFE 是预期的）
        $loadOut = & $javaExe -cp $probeOut probe.PackLoadProbe $packOut $compatClasses 2>&1 | Out-String
        Write-Host ($loadOut.Trim()) -ForegroundColor DarkGray
        Assert-Match $loadOut 'formatErr=0' '真实 mod：pack 里 0 个 ClassFormatError'
        Assert-Match $loadOut 'verifyErr=0' '真实 mod：pack 里 0 个 VerifyError'
        Assert-Match $loadOut 'other=0' '真实 mod：没有其它意外加载错误'
        Assert-Match $loadOut 'total=\d+' '真实 mod：批量加载统计跑起来了（不是空转）'
    }
}

# ================================================================= 变异检验
if ($Mutation) {
    Write-Host "-- 变异检验（落盘 + 重编译 + 字节级还原）--" -ForegroundColor Yellow

    $gradle = Find-MsrGradleBat
    if (-not $gradle) {
        Write-Host "   [FAIL] 找不到 gradle，无法做变异检验" -ForegroundColor Red
        exit 1
    }
    $env:JAVA_HOME = $jdk
    $target = $sources.gen
    $orig = [System.IO.File]::ReadAllBytes($target)

    $mutations = @(
        @{ name = "接口字段去掉 FINAL";
           from = 'Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL';
           to   = 'Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC' },
        @{ name = "enum 不清 ACC_ABSTRACT";
           from = 'access &= ~Opcodes.ACC_ABSTRACT;';
           to   = 'access &= ~0;' },
        @{ name = "接口标志不写进 class 头";
           from = 'access |= Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;';
           to   = 'access |= 0;' },
        @{ name = "构造写死调 Object.<init>";
           from = 'emitThrowingCtor(cw, owner, desc, superName);';
           to   = 'emitThrowingCtor(cw, owner, desc, "java/lang/Object");' }
    )

    foreach ($mu in $mutations) {
        $text = [System.Text.Encoding]::UTF8.GetString($orig)
        if (-not $text.Contains($mu.from)) {
            Assert-True $false ("变异锚点存在：{0}" -f $mu.name)
            continue
        }
        $mutated = $text.Replace($mu.from, $mu.to)
        [System.IO.File]::WriteAllBytes($target, (New-Object System.Text.UTF8Encoding($false)).GetBytes($mutated))
        try {
            & $gradle -p $repo ":core:mirage-loader:compileJava" --no-daemon -q | Out-Null
            $gateOut = & powershell -NoProfile -ExecutionPolicy Bypass -File $PSCommandPath 2>&1 | Out-String
            $hasFail = $gateOut -match '\[FAIL\]'
            Assert-True $hasFail ("变异「{0}」让门禁变红" -f $mu.name)
            if (-not $hasFail) {
                $gateOut -split "`r?`n" | Select-Object -Last 6 | ForEach-Object { Write-Host "          $_" -ForegroundColor DarkGray }
            }
        } finally {
            [System.IO.File]::WriteAllBytes($target, $orig)
            $now = [System.IO.File]::ReadAllBytes($target)
            $same = ($now.Length -eq $orig.Length) -and [System.Linq.Enumerable]::SequenceEqual([byte[]]$now, [byte[]]$orig)
            Assert-True $same ("变异「{0}」还原 byte-identical" -f $mu.name)
            & $gradle -p $repo ":core:mirage-loader:compileJava" --no-daemon -q | Out-Null
        }
    }
}

# ================================================================= 收尾
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue

$okCount = $script:checks - $script:failures.Count
Write-Host ""
Write-Host ("断言：{0} 条，通过 {1}，失败 {2}" -f $script:checks, $okCount, $script:failures.Count) -ForegroundColor Cyan
$report.Add(("SUMMARY checks={0} failed={1}" -f $script:checks, $script:failures.Count))
[System.IO.File]::WriteAllText($ReportFile, ($report -join "`r`n"), (New-Object System.Text.UTF8Encoding($false)))

if ($script:failures.Count -gt 0) {
    Write-Host "失败项：" -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}
Write-Host "P4 门禁全绿。" -ForegroundColor Green
exit 0
