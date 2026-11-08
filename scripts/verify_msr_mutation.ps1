# scripts/verify_msr_mutation.ps1
# MSR 行为层变异检验（真实落盘 + 重新编译 + 字节级还原）
#
# 为什么要有这个脚本：
#   verify_msr_real_mods.ps1 -SelfTest 只改**内存副本**，只能验证「源码守卫层」。
#   行为层（分类逻辑算得对不对）必须真跑一遍分类代码才算数 —— 那要落盘改源码 +
#   重新编译 + 让探针跑到改坏的类。所以这一层单独一个脚本。
#
# 分层约定（早先踩过的坑）：
#   · 「调用点接线」归**源码守卫**验 —— 把 classifyFailure(err[0]) 换成常量，守卫必须红；
#   · 「分类逻辑本身」归**行为断言**验 —— 把 classifyFailure 方法体改坏，行为断言必须红。
#   拿行为断言去验调用点（或反过来）都会得出「断言无效」的错误结论。
#
# 铁律：
#   1. 备份/还原走**字节级**（ReadAllBytes/WriteAllBytes），绝不 Get-Content/Set-Content。
#      —— Get-Content -Raw 不带 -Encoding 会走系统 ANSI 代码页（GBK），
#         UTF-8 源码会被读成乱码再写回，直接毁掉源码（2026-11-07 真踩过）。
#   2. 写回时保留原文件的 BOM 状态与行尾风格。
#   3. 还原后必须校验 byte-identical。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_mutation.ps1
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_msr_mutation.ps1 -Mutation drop-constant
#
# 退出码：0 全部符合预期；1 有变异没按预期变红，或还原不干净。

[CmdletBinding()]
param(
    [string]$Mutation = "all",
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$recSrc = Join-Path $repo "core\mirage-loader\src\main\java\io\mirage\loader\sandbox\SandboxRecorder.java"
$verifyScript = Join-Path $repo "scripts\verify_msr_real_mods.ps1"

if (-not (Test-Path $recSrc)) { Write-Host "[FAIL] 找不到 $recSrc" -ForegroundColor Red; exit 1 }

# ---- 找 gradle（全局 9.1.0）与 JDK ----
$gradleBat = $null
$distRoot = Join-Path $env:USERPROFILE ".gradle\wrapper\dists\gradle-9.1.0-bin"
if (Test-Path $distRoot) {
    $g = Get-ChildItem -Path $distRoot -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($g) { $gradleBat = $g.FullName }
}
if (-not $gradleBat) { Write-Host "[FAIL] 找不到 gradle.bat（全局 9.1.0）" -ForegroundColor Red; exit 1 }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Java\jdk-25.0.4" }

function Build-Core {
    if ($SkipBuild) { return $true }
    # 必须 -p $repo：cwd 不在仓库根时 gradle 会「目录里没有 build」直接失败，
    # 而失败会被后面误读成「变异没变红 = 断言无效」（C82）。另：PS 5.1 里对原生 exe 用
    # 2>&1 + $ErrorActionPreference=Stop 会抛 NativeCommandError，这里局部降级。
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $out = & $gradleBat -p $repo ":core:mirage-loader:compileJava" "--no-daemon" "-q" 2>&1 | Out-String
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) {
        Write-Host "      编译失败：" -ForegroundColor Red
        ($out -split "`r?`n") | Select-Object -First 8 | ForEach-Object { Write-Host "        $_" -ForegroundColor DarkGray }
        return $false
    }
    return $true
}

function Run-Verify {
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $verifyScript 2>&1 | Out-String
    return @{ Code = $LASTEXITCODE; Fails = ([regex]::Matches($out, "\[FAIL\]")).Count; Out = $out }
}

# ---- 变异定义：Find / Replace 都是**字面量**（不是正则），避免转义地狱 ----
$mutations = @(
    @{ Name = "drop-classify-call"
       Desc = "调用点换成常量（守卫层）"
       Find = 'String kind = classifyFailure(err[0]);'
       Repl = 'String kind = "ctor_failed";'
       Layer = "guard" },
    @{ Name = "drop-classify-impl"
       Desc = "分类方法体短路（行为层）"
       Find = 'public static String classifyFailure(Throwable t) {'
       Repl = "public static String classifyFailure(Throwable t) {`n        if (t != null) return KIND_CTOR_FAILED;"
       Layer = "behavior" },
    @{ Name = "drop-constant"
       Desc = "分类常量改名（行为层）"
       Find = 'KIND_MISSING_FAKE = "missing_fake"'
       Repl = 'KIND_MISSING_FAKE = "nope"'
       Layer = "behavior" },
    @{ Name = "drop-domain"
       Desc = "fake 域判定去掉 net.neoforged.（行为层）"
       Find = 'className.startsWith("net.neoforged.")'
       Repl = 'false'
       Layer = "behavior" }
)

if ($Mutation -ne "all") {
    $known = @($mutations | ForEach-Object { $_.Name })
    $picked = @($mutations | Where-Object { $_.Name -eq $Mutation })
    if ($picked.Count -eq 0) {
        Write-Host "[FAIL] 未知变异名：$Mutation" -ForegroundColor Red
        Write-Host ("       可用：all / " + ($known -join " / ")) -ForegroundColor DarkGray
        exit 1
    }
    $mutations = $picked
}

$backup = [System.IO.File]::ReadAllBytes($recSrc)
$hasBom = ($backup.Length -ge 3 -and $backup[0] -eq 0xEF -and $backup[1] -eq 0xBB -and $backup[2] -eq 0xBF)
$origText = [System.IO.File]::ReadAllText($recSrc, [System.Text.Encoding]::UTF8)

function Restore-Bytes {
    [System.IO.File]::WriteAllBytes($recSrc, $backup)
}

# 0) 预检：全部锚点必须先在基线里 —— 否则说明工作区源码不是干净基线
#    （上一次变异中途失败没还原就会这样），此时继续跑只会把「变异态」当备份，
#    结果既是假红也把源码留在变异态。必须先出头再动手。
$missingAnchors = @($mutations | Where-Object { $origText.IndexOf($_.Find) -lt 0 })
if ($missingAnchors.Count -gt 0) {
    Write-Host "== MSR 行为层变异检验 ==" -ForegroundColor Cyan
    foreach ($m in $missingAnchors) {
        Write-Host ("   [FAIL] 基线里找不到锚点（{0}）：{1}" -f $m.Name, $m.Find) -ForegroundColor Red
    }
    Write-Host "   工作区源码可能还留着上一次变异 —— 先 'git checkout -- <该文件>' 再跑。" -ForegroundColor Red
    exit 1
}

$allOk = $true
Write-Host "== MSR 行为层变异检验（落盘 + 编译 + 字节级还原）==" -ForegroundColor Cyan

foreach ($m in $mutations) {
    Write-Host "-- 变异 $($m.Name)：$($m.Desc)" -ForegroundColor Yellow

    # 1) 变异落盘：读文本 → 字面量替换 → 按原 BOM 状态写回
    if ($origText.IndexOf($m.Find) -lt 0) {
        Write-Host "   [FAIL] 锚点没找到：$($m.Find)" -ForegroundColor Red
        $allOk = $false
        Restore-Bytes
        continue
    }
    $mutated = $origText.Replace($m.Find, $m.Repl)
    $enc = New-Object System.Text.UTF8Encoding $hasBom
    [System.IO.File]::WriteAllText($recSrc, $mutated, $enc)

    # try/finally 是硬要求：中途任何异常（编译超时、gradle 报错、Ctrl-C）都不能把
    # 变异态留在工作区 —— 2026-11-08 真踩过：脚本崩在 Build-Core，源码带着两处变异过夜，
    # 下一轮把「变异态」当基准备份，锚点全找不到、还差点把假结论写进台账。
    try {
        # 2) 重新编译（探针跑的是 build/classes 里的 class，不重编译就看不到变异）
        if (-not (Build-Core)) {
            Write-Host "   [RED-OK] 编译失败 —— 变异被编译器抓住了" -ForegroundColor DarkGreen
            continue
        }

        # 3) 跑回归，必须变红
        $r = Run-Verify
        if ($r.Fails -gt 0 -and $r.Code -ne 0) {
            Write-Host "   [RED-OK] 变红（FAIL=$($r.Fails), exit=$($r.Code)）" -ForegroundColor DarkGreen
        } else {
            Write-Host "   [RED-FAIL] 没变红（FAIL=$($r.Fails), exit=$($r.Code)）—— 断言无效！" -ForegroundColor Red
            $allOk = $false
        }
    } finally {
        # 4) 字节级还原（无论上面发生什么都执行）
        Restore-Bytes
    }

    $now = [System.IO.File]::ReadAllBytes($recSrc)
    if ([System.Linq.Enumerable]::SequenceEqual([byte[]]$backup, [byte[]]$now)) {
        Write-Host "   [OK] 还原 byte-identical" -ForegroundColor DarkGreen
    } else {
        Write-Host "   [FAIL] 还原后不是 byte-identical！" -ForegroundColor Red
        $allOk = $false
    }
}

# 5) 基线：还原 + 重编译 + 必须全绿
[void](Build-Core)
$r = Run-Verify
if ($r.Code -eq 0 -and $r.Fails -eq 0) {
    Write-Host "   [GREEN-OK] 还原后基线全绿" -ForegroundColor DarkGreen
} else {
    Write-Host "   [GREEN-FAIL] 还原后仍红（FAIL=$($r.Fails), exit=$($r.Code)）" -ForegroundColor Red
    $allOk = $false
}

# 6) 最终一致性
$final = [System.IO.File]::ReadAllBytes($recSrc)
if ([System.Linq.Enumerable]::SequenceEqual([byte[]]$backup, [byte[]]$final)) {
    Write-Host "   [OK] 源码最终与备份一致" -ForegroundColor DarkGreen
} else {
    Write-Host "   [FAIL] 源码最终与备份不一致！" -ForegroundColor Red
    $allOk = $false
}

if ($allOk) { Write-Host "`n== 结果：全部符合预期 ==" -ForegroundColor Green; exit 0 }
else { Write-Host "`n== 结果：有变异未按预期变红，或还原不干净 ==" -ForegroundColor Red; exit 1 }
