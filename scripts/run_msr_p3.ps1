# scripts/run_msr_p3.ps1
# MSR P3 · 注入器深录运行器（一次性实例，仓库外，跑完即弃）
#
# 作用：
#   在**真实 NeoForge 客户端**里挂 mirage-msr-agent，录出行为级 MID（IR），
#   再用基线（无 mod）与录制（有 mod）求差 ⇒ 得到「这个 mod 引入的注册项与事件订阅」。
#   与 P2 的 fake pass 根本不同：P2 只能看到 mod 碰 stub 的调用；P3 插桩游戏侧
#   （MappedRegistry.register / EventBus.addListener / EventListenerFactory.create），
#   能看到 mod 直接调 MC 原版方法的后果。
#
# 关键约束（务必先读）：
#   1. **不碰 mc-shili/**（AGENTS 规则 8）。实例建在仓库外的 %TEMP%；
#      mc-shili 的 libraries / assets 只**读**；natives **复制**到临时实例
#      （LWJGL 会往 SharedLibraryExtractPath 解压 dll，直接指过去等于改 mc-shili）。
#   2. agent 是**构建期工具**，不进 MG fat jar（AGENTS 规则 7.5）。
#   3. 本脚本会**真的启动 Minecraft 客户端窗口**（NeoForge 客户端无法 headless）。
#      加载期（mod 注册 / 事件订阅）结束后脚本自动终止进程。
#   4. 断网运行：--accessToken 0 走离线身份，不连账号服务器。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -SelfTest
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -ListDeps
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -ModJar <mod jar>
#   powershell -ExecutionPolicy Bypass -File .\scripts\run_msr_p3.ps1 -ModJar a.jar -ModJar b.jar
#
# 退出码：0 通过；1 自检失败 / 依赖缺失 / 实例构建失败 / 未产出 IR。

[CmdletBinding()]
param(
    [string[]]$ModJar = @(),
    [switch]$Baseline,
    [switch]$Recording,
    [switch]$SelfTest,
    [switch]$ListDeps,
    [switch]$Keep,
    [int]$TimeoutSec = 150,
    [string]$JavaHome = "",
    [string]$OutDir = "",
    [string]$ReportFile = (Join-Path $env:TEMP "run_msr_p3.report.txt")
)

$ErrorActionPreference = "Stop"
trap {
    # 未捕获异常也要留下证据：MSR 的「跑了但没产出」必须能定位到具体原因
    $msg = "ERROR: " + $_.Exception.Message + " @ " + $_.InvocationInfo.PositionMessage
    Write-Host "[ERROR] $msg" -ForegroundColor Red
    $report.Add($msg)
    try { WriteTextNoBom $ReportFile ($report -join "`n") } catch { }
    exit 3
}
$report = New-Object System.Collections.Generic.List[string]
function Log($msg) {
    $report.Add($msg)
    Write-Host $msg
}

$repo = Split-Path -Parent $PSScriptRoot
$mcRoot = Split-Path -Parent $repo                    # D:\bing\MC\mcmoderr-my

# JDK 解析顺序：-JavaHome 参数 → 项目约定的 jdk-25.0.4 → 环境变量 JAVA_HOME。
# 注意系统默认 JAVA_HOME 是 jdk-21，而 MC 26.2 + NeoForge 工具链在 25 上验证过。
if (-not $JavaHome) {
    $jdk25 = "C:\Program Files\Java\jdk-25.0.4"
    if (Test-Path $jdk25) { $JavaHome = $jdk25 } else { $JavaHome = $env:JAVA_HOME }
}
if (-not (Test-Path (Join-Path $JavaHome "bin\java.exe"))) { Fail "找不到 JDK：$JavaHome" }
$javaExe = Join-Path $JavaHome "bin\java.exe"
$javawExe = Join-Path $JavaHome "bin\javaw.exe"
Log "JDK: $JavaHome"

$agentJar = Join-Path $repo "modules\mirage-msr-agent\build\libs\mirage-msr-agent.jar"
$shili = Join-Path $mcRoot "mc-shili"
$libsDir = Join-Path $shili "libraries"
$assetsDir = Join-Path $shili "assets"
$verDir = Join-Path $shili "versions\26.2-NeoForge_26.2.0.90"
$verJson = Join-Path $verDir "26.2-NeoForge_26.2.0.90.json"
$versionId = "26.2-NeoForge_26.2.0.90"
$nativesSrc = Join-Path $verDir "$versionId-natives"

# ---------------------------------------------------------------- 工具函数

function WriteTextNoBom([string]$path, [string]$text) {
    # 明确用无 BOM 的 UTF-8 写 —— PowerShell 5.1 的 Out-File/Set-Content 会加 BOM，
    # 那是本项目反复踩过的坑（见 docs/经验与踩坑.md C76）。
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding $false))
}

function Fail($msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
    $report.Add("FAIL: $msg")
    WriteTextNoBom $ReportFile ($report -join "`n")
    exit 1
}

# 判断 launcher 的 library rules 是否允许当前平台
function Test-LibraryAllowed($rules) {
    if (-not $rules) { return $true }
    foreach ($r in $rules) {
        $ok = $true
        if ($r.os) {
            if ($r.os.name -and $r.os.name -ne "windows") { $ok = $false }
            if ($r.os.arch -and $r.os.arch -ne "x86_64" -and $r.os.arch -ne "x86") { $ok = $false }
            if ($r.os.version) {
                $cur = [System.Environment]::OSVersion.Version
                if ($r.os.version.min) {
                    try { if ($cur -lt [version]$r.os.version.min) { $ok = $false } } catch { $ok = $false }
                }
                if ($r.os.version.max) {
                    try { if ($cur -gt [version]$r.os.version.max) { $ok = $false } } catch { $ok = $false }
                }
            }
        }
        # features 规则（has_custom_resolution 之类）只影响游戏参数，不影响 classpath
        if ($r.features) { $ok = $false }
        if ($r.action -eq "allow") {
            if ($ok) { return $true }
        } else {
            if ($ok) { return $false }
        }
    }
    return $true
}

# 从版本 json 重建 classpath
function Build-Classpath($vjson) {
    $doc = Get-Content $vjson -Raw -Encoding UTF8 | ConvertFrom-Json
    $parts = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($lib in $doc.libraries) {
        if (-not (Test-LibraryAllowed $lib.rules)) { continue }
        $path = $null
        if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.path) {
            $path = $lib.downloads.artifact.path
        } elseif ($lib.name) {
            # 少数条目只有 name/version，按 maven 约定拼
            $path = ($lib.name -replace "\.", "/") + "/" + $lib.version + "/" + ($lib.name -split "\.")[-1] + "-" + $lib.version + ".jar"
        }
        if (-not $path) { continue }
        $full = Join-Path $libsDir ($path -replace "/", "\")
        if (Test-Path $full) { $parts.Add($full) } else { $missing.Add($path) }
    }
    return @{ cp = ($parts -join ";"); missing = $missing; mainClass = $doc.mainClass; assetIndex = $doc.assetIndex.id }
}

# ---------------------------------------------------------------- 依赖自检
$required = @($agentJar, $verJson, $nativesSrc, $assetsDir, $libsDir)
$missing = @()
foreach ($f in $required) { if (-not (Test-Path $f)) { $missing += $f } }

if ($SelfTest) {
    Log "== MSR P3 运行器自检 =="
    if (Test-Path $agentJar) {
        # 用 .NET 读 zip 条目，**不**用 `jar xf`（那会把 META-INF 解到当前目录，污染工作区）
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($agentJar)
        try {
            $entry = $zip.GetEntry("META-INF/MANIFEST.MF")
            if ($null -eq $entry) { Fail "agent jar 里读不到 META-INF/MANIFEST.MF" }
            $reader = New-Object System.IO.StreamReader($entry.Open())
            $content = $reader.ReadToEnd()
            $reader.Close()
            if ($content -match "Premain-Class:\s*io\.mirage\.msr\.agent\.RecorderAgent") {
                Log "[PASS] agent manifest 带 Premain-Class"
            } else {
                Fail "agent manifest 缺 Premain-Class"
            }
            # ASM 必须打进 agent jar（javaagent 只加载 jar 自身，缺了运行期 NoClassDefFoundError）
            $hasAsm = $false
            foreach ($e in $zip.Entries) {
                if ($e.FullName -eq "org/objectweb/asm/ClassReader.class") { $hasAsm = $true; break }
            }
            if ($hasAsm) { Log "[PASS] agent jar 自包含 ASM" }
            else { Fail "agent jar 里没有 ASM（运行期会 NoClassDefFoundError）" }
        } finally {
            $zip.Dispose()
        }
    } else {
        Log "[WARN] agent jar 还没构建：$agentJar"
    }
    $src = Join-Path $repo "modules\mirage-msr-agent\src\main\java\io\mirage\msr\agent"
    foreach ($n in @("RecorderAgent.java", "RecorderTransformer.java", "MidIrWriter.java", "ModIdResolver.java", "BaselineDiffer.java")) {
        if (Test-Path (Join-Path $src $n)) { Log "[PASS] 源文件存在: $n" }
        else { Fail "缺源文件: $n" }
    }
    # 插桩点常量必须与真实类名一致（踩过的坑：EventBus 在 net.neoforged.bus 而非 api 包）
    $tr = Get-Content (Join-Path $src "RecorderTransformer.java") -Raw -Encoding UTF8
    foreach ($point in @("net/minecraft/core/MappedRegistry", "net/neoforged/bus/EventBus", "net/neoforged/bus/EventListenerFactory")) {
        if ($tr -match [regex]::Escape($point)) { Log "[PASS] 插桩点常量在位: $point" }
        else { Fail "插桩点常量缺失: $point" }
    }
    $ag = Get-Content (Join-Path $src "RecorderAgent.java") -Raw -Encoding UTF8
    if ($ag -match "public static void premain\(") { Log "[PASS] premain 签名在位" }
    else { Fail "premain 签名缺失" }
    $settings = Get-Content (Join-Path $repo "settings.gradle.kts") -Raw -Encoding UTF8
    if ($settings -match "mirage-msr-agent" -and $settings -notmatch "launcher:mirage-launch.*mirage-msr-agent") {
        Log "[PASS] agent 是独立模块，未被 fat jar 依赖"
    } else { Fail "agent 模块接线异常" }
    Log "== 自检完成 =="
    WriteTextNoBom $ReportFile ($report -join "`n")
    exit 0
}

if ($ListDeps) {
    Log "== P3 依赖清单 =="
    foreach ($f in $required) {
        $state = if (Test-Path $f) { "有  ($((Get-Item $f).Length) B)" } else { "缺" }
        Log "  [$state] $f"
    }
    if (Test-Path $verJson) {
        $info = Build-Classpath $verJson
        Log "  classpath 条目: $(($info.cp -split ';').Count)"
        Log "  缺失 library: $($info.missing.Count)"
        Log "  mainClass: $($info.mainClass)"
        Log "  assetIndex: $($info.assetIndex)"
    }
    WriteTextNoBom $ReportFile ($report -join "`n")
    exit 0
}

if ($missing.Count -gt 0) {
    Log "== 缺失依赖 =="
    $missing | ForEach-Object { Log "  缺: $_" }
    Log "提示：agent jar 用 `gradle :modules:mirage-msr-agent:jar` 构建；"
    Log "      其余依赖在 mc-shili/ 下（PCL 装的 NeoForge 26.2.0.90）。"
    Fail "依赖不齐，无法跑 P3"
}

$cpInfo = Build-Classpath $verJson
if ([string]::IsNullOrWhiteSpace($cpInfo.cp)) { Fail "classpath 重建失败（版本 json 解析不出 library）" }
Log "classpath 条目: $(($cpInfo.cp -split ';').Count)（缺失 $($cpInfo.missing.Count) 个非本平台 library）"

# ---------------------------------------------------------------- 一次性实例
$inst = Join-Path $env:TEMP ("mirage-msr-" + (Get-Random))
New-Item -ItemType Directory -Force -Path $inst | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $inst "mods") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $inst "config") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $inst "saves") | Out-Null
Log "一次性实例: $inst"

# natives 复制到实例内（LWJGL 会解压 dll 进去，直接指 mc-shili 等于改用户目录）
$nativesInst = Join-Path $inst "natives"
Copy-Item $nativesSrc $nativesInst -Recurse -Force
Log "natives 已复制到实例内（避免写 mc-shili）"

if ($OutDir) {
    $outRoot = $OutDir
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
} else {
    $outRoot = Join-Path $inst "msr-out"
    New-Item -ItemType Directory -Force -Path $outRoot | Out-Null
}

# ---------------------------------------------------------------- 跑一轮
function Invoke-MsrRun {
    param(
        [bool]$IsBaseline,
        [string[]]$Mods,
        [string]$OutPath
    )
    $runOut = Join-Path $OutPath ("msr-" + $(if ($IsBaseline) { "baseline" } else { "recording" }))
    New-Item -ItemType Directory -Force -Path $runOut | Out-Null
    $gameDir = Join-Path $runOut "game"
    $modDir = Join-Path $gameDir "mods"
    New-Item -ItemType Directory -Force -Path $modDir | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $gameDir "config") | Out-Null
    foreach ($m in $Mods) {
        Copy-Item $m $modDir -Force
    }
    Log "  mod 数: $($Mods.Count)"

    $stdoutFile = Join-Path $runOut "stdout.txt"
    $stderrFile = Join-Path $runOut "stderr.txt"

    $jvmArgs = @(
        "-javaagent:$agentJar",
        "-Dmirage.msr.out=$runOut",
        "-Dmirage.msr.baseline=$($IsBaseline.ToString().ToLower())",
        "-Xms512M", "-Xmx2G",
        # 默认编码在 Windows 上是 GBK ⇒ 游戏日志里的中文时间戳变乱码（实测）
        "-Dfile.encoding=UTF-8",
        # FML production 模式靠 libraryDirectory 拼 classpath（日志里是 ~libraries/... 前缀），
        # 缺这个属性会直接报 "Your NeoForge installation is corrupted" —— 见本轮实测。
        "-DlibraryDirectory=$libsDir",
        "-Dminecraft.launcher.brand=mirage-msr",
        "-Dminecraft.launcher.version=1.0",
        "-Djava.net.preferIPv6Addresses=system",
        "-Djava.library.path=$nativesInst",
        "-Djna.tmpdir=$nativesInst\jna",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=$nativesInst\lwjgl",
        "-Dio.netty.native.workdir=$nativesInst\netty",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming",
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
        "-cp", $cpInfo.cp
    )
    $gameArgs = @(
        "--username", "msr",
        "--version", $versionId,
        "--gameDir", $gameDir,
        "--assetsDir", $assetsDir,
        "--assetIndex", $cpInfo.assetIndex,
        "--uuid", "00000000-0000-0000-0000-000000000000",
        "--accessToken", "0",
        "--clientId", "0",
        "--xuid", "0",
        "--versionType", "release",
        "--fml.neoForgeVersion", "26.2.0.90",
        "--fml.mcVersion", "26.2",
        "--fml.neoFormVersion", "2"
    )

    $argList = @()
    $argList += $jvmArgs
    $argList += $cpInfo.mainClass
    $argList += $gameArgs

    # 启动方式说明（两个坑都踩过）：
    #   a) **不用 Start-Process**：Git Bash 环境下进程环境块里同时存在 Path 与 PATH，
    #      Start-Process 构造环境字典时抛「已添加项。字典中的关键字:"Path"所添加的关键字:"PATH"」。
    #   b) **不用 Process 事件回调重定向**：回调跑在 .NET 线程池线程上，
    #      与关闭 StreamWriter 竞态会让宿主进程直接退出（exit 2 且无任何报错，trap 也抓不到）。
    # 改为「不重定向」——子进程继承父进程 stdout/stderr，agent 的输出能实时看到；
    # 游戏的完整日志另在 game/logs/latest.log。
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $javawExe
    $psi.Arguments = ($argList) -join ' '
    $psi.WorkingDirectory = $gameDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $false
    $psi.RedirectStandardError = $false

    $p = New-Object System.Diagnostics.Process
    $p.StartInfo = $psi
    $null = $p.Start()
    Log "  启动真 NeoForge 客户端（pid=$($p.Id)），超时 ${TimeoutSec}s"

    $gameLog = Join-Path $gameDir "logs\latest.log"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $killed = $false
    $loaded = $false
    $crashed = $false
    $tick = 0
    while (-not $p.HasExited) {
        Start-Sleep -Milliseconds 700
        $tick++
        if ($tick % 8 -eq 0) {
            $logLines = 0
            try { if (Test-Path $gameLog) { $logLines = (Get-Content $gameLog -ErrorAction SilentlyContinue | Measure-Object).Count } } catch { }
            Log "    ... 等待中（${tick}0 秒级 tick，游戏日志 $logLines 行）"
        }
        if ((Get-Date) -gt $deadline) { $killed = $true; break }
        try {
            # javaw 没有控制台 ⇒ stdout 往往是空的；真正的进度看游戏自己的 latest.log
            $probe = $null
            if (Test-Path $gameLog) { $probe = Get-Content $gameLog -Tail 60 -ErrorAction SilentlyContinue }
            if (-not $probe -and (Test-Path $stdoutFile)) {
                $probe = Get-Content $stdoutFile -Tail 60 -ErrorAction SilentlyContinue
            }
            # 完成判据：必须等**注册阶段结束**才算，否则基线会在
            # `MappedRegistry#register` 还没走完时被 kill ⇒ 注册数偏少、
            # 两轮不可比（本轮实测：误把 `Backend library: LWJGL`（窗口初始化）
            # 当完成信号，基线只收到 7074 条，完整应为 7153 条）。
            # 可靠信号：`Sound engine started` / `Stopping!`（退出）—— 都在注册之后。
            if ($probe -and ($probe -join "`n") -match "Sound engine started|Stopping!") {
                $loaded = $true
                break
            }
            # 崩溃也要立刻收场：mod 加载失败时游戏会崩，等满超时是纯浪费
            if ($probe -and ($probe -join "`n") -match "Game crashed!|Failed to start FML|Failed to wait for future Mod Construction|ModLoadingException") {
                $crashed = $true
                break
            }
        } catch { }
    }
    Log "  等待循环结束（loaded=$loaded crashed=$crashed killed=$killed）"
    if (-not $p.HasExited) {
        # 注意：Process.Kill(bool) 只在 .NET Core 有，Windows PowerShell 5.1（.NET Framework）
        # 只有无参 Kill() —— 传参会抛 MethodNotFound，必须用无参版。
        try { $p.Kill() } catch { }
    }
    $null = $p.WaitForExit(20000)

    $exitCode = "unknown"
    try { if ($p.HasExited) { $exitCode = "$($p.ExitCode)" } else { $exitCode = "killed" } } catch { }
    $irFile = Join-Path $runOut ($(if ($IsBaseline) { "baseline" } else { "recording" }) + ".mirage.json")
    $ok = Test-Path $irFile
    Log "  退出码: $exitCode（提前终止=$loaded 超时=$killed）"
    Log "  游戏日志: $gameLog"
    $regCount = 0; $subCount = 0
    if ($ok) {
        $irText = Get-Content $irFile -Raw
        $regCount = ([regex]::Matches($irText, '"registry"\s*:')).Count
        $subCount = ([regex]::Matches($irText, '"event"\s*:')).Count
        Log "  [PASS] IR 已产出: $irFile ($((Get-Item $irFile).Length) B) 注册=$regCount 订阅=$subCount"
        if ($regCount -eq 0 -and $subCount -eq 0) {
            # 文件在但一条都没录到 ⇒ 插桩没生效或游戏没跑到注册阶段，不能算成功
            Log "  [FAIL] IR 是空壳（0 注册 0 订阅）—— 插桩未命中或加载未完成"
            $ok = $false
        }
    } else {
        Log "  [FAIL] 没有产出 IR"
    }
    if (Test-Path $gameLog) {
        Log "  --- 游戏日志尾部 ---"
        Get-Content $gameLog -Tail 12 -ErrorAction SilentlyContinue | ForEach-Object { Log "    $_" }
    }
    return @{ ok = $ok; ir = $irFile; out = $runOut; reg = $regCount; sub = $subCount }
}

$baselineResult = $null
$recordResult = $null

if (-not $Recording -or $ModJar.Count -eq 0) {
    Log "== 跑基线（无 mod）=="
    $baselineResult = Invoke-MsrRun -IsBaseline $true -Mods @() -OutPath $outRoot
    if (-not $baselineResult.ok) {
        Log "--- stdout 末尾 ---"
        Get-Content (Join-Path $baselineResult.out "stdout.txt") -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Log "  $_" }
        Fail "基线未产出 IR"
    }
}

if ($ModJar.Count -gt 0) {
    foreach ($m in $ModJar) {
        if (-not (Test-Path $m)) { Fail "mod jar 不存在: $m" }
    }
    Log "== 跑录制（$($ModJar.Count) 个 mod）=="
    $recordResult = Invoke-MsrRun -IsBaseline $false -Mods $ModJar -OutPath $outRoot
    if (-not $recordResult.ok) {
        Log "--- stdout 末尾 ---"
        Get-Content (Join-Path $recordResult.out "stdout.txt") -Tail 30 -ErrorAction SilentlyContinue | ForEach-Object { Log "  $_" }
        Fail "录制未产出 IR"
    }
}

# ---------------------------------------------------------------- 求差
if ($baselineResult -and $recordResult) {
    $diffFile = Join-Path $outRoot "diff.mirage.json"
    $diffText = & $javaExe -cp $agentJar io.mirage.msr.agent.BaselineDiffer `
        $baselineResult.ir $recordResult.ir $diffFile 2>&1
    $diffText | ForEach-Object { Log $_ }
    Log "增量 JSON: $diffFile"
}

if (-not $Keep) {
    Remove-Item $inst -Recurse -Force -ErrorAction SilentlyContinue
    Log "一次性实例已删除（-Keep 可保留）"
} else {
    Log "实例保留在: $inst"
}

WriteTextNoBom $ReportFile ($report -join "`n")
Log "报告: $ReportFile"
exit 0
