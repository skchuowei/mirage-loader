<#
.SYNOPSIS
    MSR W1 接线门禁的「行为层变异检验」：证明 90 条断言不是永真。

.DESCRIPTION
    按 C77/C82：行为断言不能拿守卫层内存副本糊弄，必须改 .java 源码 →
    javac 单文件重编译 → 覆盖 classes 副本 → 让 WiringProbe 真跑到改坏的类。
    每轮 try{...}finally{字节级还原}，动手前预检全部锚点唯一，还原后 byte-identical。
    含「编译路径自检」：未变异源码走同一套 javac+副本流程仍全绿，排除副本损坏造成假红。
.EXAMPLE
    .\scripts\verify_fakepack_wiring_mutation.ps1
.NOTES
    退出码：0 全部变异做红 + 自检/基线全绿；1 有变异没做红或还原失败。
#>
[CmdletBinding()]
param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.4",
    [string]$Repo = "D:\bing\MC\mcmoderr-my\mirage-loader"
)

$ErrorActionPreference = "Continue"
$gate    = Join-Path $Repo "scripts\verify_fakepack_wiring.ps1"
$javac   = Join-Path $JavaHome "bin\javac.exe"
$loaderMain  = Join-Path $Repo "core\mirage-loader\build\classes\java\main"
$srcRoot = Join-Path $Repo "core\mirage-loader\src\main\java\io\mirage\loader"
$mclFile = Join-Path $srcRoot "MirageClassLoader.java"
$trkFile = Join-Path $srcRoot "MissingNeoTracker.java"
$rfpFile = Join-Path $srcRoot "RuntimeFakePack.java"

foreach ($p in @($gate, $javac, $loaderMain, $mclFile, $trkFile, $rfpFile)) {
    if (-not (Test-Path $p)) { Write-Host "[FATAL] 缺文件: $p" -ForegroundColor Red; exit 1 }
}

$cpParts = @(
    $loaderMain,
    (Join-Path $Repo "api\mirage-api\build\classes\java\main"),
    (Join-Path $Repo "core\mirage-mixin\build\classes\java\main"),
    (Join-Path $Repo "core\mirage-eventbus\build\classes\java\main"),
    (Join-Path $Repo "modules\mirage-neoforge-compat\build\classes\java\main"),
    (Join-Path $Repo "modules\mirage-fabric-compat\build\classes\java\main"),
    (Join-Path $Repo "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar")
) | Where-Object { Test-Path $_ }
$cpCompile = ($cpParts -join ";")

$work = Join-Path $env:TEMP "mirage-w1-wiring-mutation"
Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $work | Out-Null

function Copy-Classes([string]$dest) {
    Remove-Item -Recurse -Force $dest -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item -Path (Join-Path $loaderMain "*") -Destination $dest -Recurse -Force
}

function Invoke-Gate([string]$classesOverride) {
    if ($classesOverride -eq "") {
        return (& $gate -SkipGuard -NoExitOnFail 6>&1 | Out-String)
    }
    return (& $gate -ClassesOverride $classesOverride -SkipGuard -NoExitOnFail 6>&1 | Out-String)
}

function Compile-Into([string]$srcFile, [string]$dest) {
    $log = & $javac "-encoding" "UTF-8" "-nowarn" "-proc:none" "-cp" $cpCompile "-d" $dest $srcFile 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        Write-Host "   [COMPILE-FAIL] $srcFile`n$log" -ForegroundColor Red
        return $false
    }
    return $true
}

# ---------------------------------------------------------------- 变异定义
$mutations = @(
    @{ Name = "A_PREFIX";  File = $trkFile
       Find = '        if (name == null || !name.startsWith(NEO_PREFIX)) return;'
       Repl = '        if (name == null) return;'
       Key  = "A_PREFIX"
       Desc = "A 记账前缀闸放开 ⇒ mc / io.mirage.neoforge 会被误记账" },
    @{ Name = "A_RECORD";  File = $mclFile
       Find = '        MissingNeoTracker.recordIfEnabled(name);'
       Repl = '        // MissingNeoTracker.recordIfEnabled(name);'
       Key  = "A_RECORD"
       Desc = "findClass 里摘掉 A 记账调用 ⇒ 探针收不到任何缺口" },
    @{ Name = "A_FLUSH";   File = $trkFile
       Find = '        lines.addAll(snapshot());'
       Repl = '        // lines.addAll(snapshot());'
       Key  = "A_FLUSH"
       Desc = "A 清单只写头注释 ⇒ --only-file 解析出 0 条" },
    @{ Name = "B_PREFIX";  File = $rfpFile
       Find = '        if (name == null || !name.startsWith(NEO_PREFIX)) return null;'
       Repl = '        if (name == null || !name.startsWith("net.")) return null;'
       Key  = "B_PREFIX"
       Desc = "B 前缀闸放宽成 net. ⇒ pack 里的假 mc 类会被加载" },
    @{ Name = "B_RUNTIME"; File = $rfpFile
       Find = '            if (home.isBlank() || !"on".equalsIgnoreCase(runtime)) return false;'
       Repl = '            if (home.isBlank()) return false;'
       Key  = "B_RUNTIME"
       Desc = "去掉 runtime=on 闸 ⇒ 只给 home 就生效" },
    @{ Name = "B_KILL";    File = $rfpFile
       Find = '            if ("off".equalsIgnoreCase(kill)) return false;'
       Repl = '            if ("__never__".equalsIgnoreCase(kill)) return false;'
       Key  = "B_KILL"
       Desc = "去掉 kill switch ⇒ -Dmirage.msr.fakeprovider=off 关不掉" },
    @{ Name = "B_DEFINE";  File = $mclFile
       Find = '                return defineClass(name, fake, 0, fake.length);'
       Repl = '                return ClassLoader.getSystemClassLoader().loadClass(name);'
       Key  = "B_DEFINE"
       Desc = "把 MCL 自己 defineClass 改成委派 app loader ⇒ 归属破坏 + 类加载失败" }
)

# ---------------------------------------------------------------- 动手前预检全部锚点（C82）
Write-Host "== MSR W1 · 行为层变异检验（落盘 + 单文件重编译 + 字节级还原）==" -ForegroundColor Cyan
Write-Host "-- 预检锚点唯一性 --"
foreach ($mut in $mutations) {
    $t = [System.IO.File]::ReadAllText($mut.File, [System.Text.Encoding]::UTF8)
    $n = ([regex]::Matches($t, [regex]::Escape($mut.Find))).Count
    if ($n -ne 1) {
        Write-Host ("[FATAL] {0} 锚点出现 {1} 次（应为 1）: {2}" -f $mut.Name, $n, $mut.File) -ForegroundColor Red
        exit 1
    }
    Write-Host ("   [OK] {0} 锚点唯一" -f $mut.Name)
}

$allOk = $true
$mutOk = 0

# ---------------------------------------------------------------- 编译路径自检
Write-Host "-- 编译路径自检：未变异源码走同一套 javac + classes 副本流程 --"
$selfClasses = Join-Path $work "classes-selfcheck"
Copy-Classes $selfClasses
if (-not (Compile-Into $mclFile $selfClasses)) {
    Write-Host "[FATAL] 自检编译失败" -ForegroundColor Red; exit 1
}
$selfOut = Invoke-Gate $selfClasses
if ($selfOut -match "RESULT PASS") {
    Write-Host "   [OK] GREEN-OK 未变异源码走同流程仍全绿（排除副本损坏假红）" -ForegroundColor DarkGreen
} else {
    Write-Host "   [FAIL] GREEN-FAIL 自检没通过，后续变异结果不可信" -ForegroundColor Red
    Write-Host $selfOut.Substring(0, [Math]::Min(1200, $selfOut.Length))
    exit 1
}

# ---------------------------------------------------------------- 逐变异
foreach ($mut in $mutations) {
    Write-Host ("-- 变异 {0}: {1} --" -f $mut.Name, $mut.Desc)
    $src = $mut.File
    $orig = [System.IO.File]::ReadAllBytes($src)
    $origB64 = [Convert]::ToBase64String($orig)
    $text = [System.IO.File]::ReadAllText($src, [System.Text.Encoding]::UTF8)
    $newText = $text.Replace($mut.Find, $mut.Repl)
    if ($newText -eq $text) {
        Write-Host ("[FATAL] {0} 替换未生效" -f $mut.Name) -ForegroundColor Red; exit 1
    }
    $red = $false
    $keyHit = $false
    try {
        [System.IO.File]::WriteAllBytes($src, [System.Text.Encoding]::UTF8.GetBytes($newText))
        $classes = Join-Path $work ("classes-" + $mut.Name)
        Copy-Classes $classes
        if (-not (Compile-Into $src $classes)) {
            $allOk = $false
        } else {
            $o = Invoke-Gate $classes
            $m = [regex]::Match($o, "RESULT FAIL (\d+)/(\d+)")
            $red = $m.Success
            $keyHit = ($o -match ("\[FAIL\].*" + [regex]::Escape($mut.Key)))
            if ($red -and $keyHit) {
                $mutOk++
                Write-Host ("   [OK] 变红：RESULT FAIL {0}/{1}，关键断言 {2} 出现在失败列表" -f $m.Groups[1].Value, $m.Groups[2].Value, $mut.Key) -ForegroundColor DarkGreen
            } elseif ($red) {
                $allOk = $false
                Write-Host ("   [FAIL] 虽变红（{0}/{1}）但期望的关键断言 {2} 没红 ⇒ 变异没打到目标能力" -f $m.Groups[1].Value, $m.Groups[2].Value, $mut.Key) -ForegroundColor Red
            } else {
                $allOk = $false
                Write-Host "   [FAIL] 变异后门禁仍全绿 ⇒ 断言永真 / 探针没跑到改坏的类" -ForegroundColor Red
                Write-Host ("   " + $o.Substring(0, [Math]::Min(800, $o.Length)))
            }
        }
    } finally {
        # 字节级还原（C82）：绝不能把变异态当基准备份
        [System.IO.File]::WriteAllBytes($src, $orig)
        $after = [System.IO.File]::ReadAllBytes($src)
        if ([Convert]::ToBase64String($after) -ne $origB64) {
            Write-Host ("[FATAL] {0} 源码还原不是 byte-identical" -f $mut.Name) -ForegroundColor Red
            $allOk = $false
        }
    }
}

# ---------------------------------------------------------------- 基线回绿（含守卫层）
Write-Host "-- 还原后基线回绿（完整门禁，含守卫层）--"
$baseOut = & $gate -NoExitOnFail 6>&1 | Out-String
if ($baseOut -match "RESULT PASS") {
    Write-Host "   [OK] GREEN-OK 基线回绿" -ForegroundColor DarkGreen
} else {
    Write-Host "   [FAIL] GREEN-FAIL 基线没回绿（可能残留变异态）" -ForegroundColor Red
    $allOk = $false
}

# 最终逐文件 byte-identical 复核
foreach ($f in @($mclFile, $trkFile, $rfpFile)) {
    $b = [System.IO.File]::ReadAllBytes($f)
    Write-Host ("   {0}: {1} bytes" -f [System.IO.Path]::GetFileName($f), $b.Length)
}

Write-Host ""
if ($allOk -and $mutOk -eq $mutations.Count) {
    Write-Host ("MUTATION RESULT PASS {0}/{0} | SELFCHECK GREEN-OK | BASELINE GREEN-OK" -f $mutations.Count) -ForegroundColor Green
    exit 0
}
Write-Host ("MUTATION RESULT FAIL ({0}/{1} 变异真正做红)" -f $mutOk, $mutations.Count) -ForegroundColor Red
exit 1
