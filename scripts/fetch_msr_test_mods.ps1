# scripts/fetch_msr_test_mods.ps1
# MSR P3 · 真实 mod 测试素材拉取（幂等，缓存到 %TEMP%\msr-mods）
#
# 为什么要有这个脚本：
#   P3 原先只在**合成探针**（scripts/fixtures/msr-probe-mod）上验过，而探针只用
#   `modBus.addListener(...)` 显式注册 —— 这条路测不出「@SubscribeEvent 自动注册」的缺口。
#   2026-11-08 首次喂真实 mod 才暴露：AppleSkin 的 4 条注解订阅全被吞掉
#   （agent 侧去重键只按事件名）。真实 mod 才是判据来源，所以把素材固定下来。
#
#   清单锁版本 + sha512：同样的 jar 才能复现同一份 diff，避免「昨天对今天不对」。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\fetch_msr_test_mods.ps1
#   ... -Refresh                 # 忽略缓存强制重下
#   ... -Run                     # 拉完直接跑录制（等价于手动调 run_msr_p3.ps1 -ModJar ...）
#   ... -Run -TimeoutSec 300
#
# 退出码：0 全部就绪；1 下载失败 / 校验不符。

[CmdletBinding()]
param(
    [string]$ModDir = (Join-Path $env:TEMP "msr-mods"),
    [switch]$Refresh,
    [switch]$Run,
    [int]$TimeoutSec = 300,
    [string]$ReportFile = (Join-Path $env:TEMP "fetch_msr_test_mods.report.txt")
)

$ErrorActionPreference = "Stop"
$report = New-Object System.Collections.Generic.List[string]
function Log($m) { Write-Host $m; $report.Add([string]$m) }
function Fail($m) {
    Write-Host "[FAIL] $m" -ForegroundColor Red
    $report.Add("FAIL: $m")
    [System.IO.File]::WriteAllLines($ReportFile, $report, [System.Text.UTF8Encoding]::new($false))
    exit 1
}

# 全部是 26.2 / neoforge 的当前版本。sha512 取自 Modrinth 版本 API。
# 覆盖面：大注册量（JEI）+ 客户端 UI（Jade）+ 事件密集（AppleSkin）+ 纯库（Cloth / MezzConfig）+ 纯 mixin（FerriteCore）。
$mods = @(
    @{ file = "jei-26.2-neoforge-30.48.0.244.jar"
       url  = "https://cdn.modrinth.com/data/u6dRKJwZ/versions/lU7Sz0zI/jei-26.2-neoforge-30.48.0.244.jar"
       sha512 = "eb9c66808518b5f78e624dfd38c8a541daa19948f0269e42c3d9e6c24f72a8f401124572c1f6be8c711c491301df7a441c7d9db12da1943b8717971e73ede5a9" },
    @{ file = "mezz_config-26.2-neoforge-0.6.11-standalone.jar"
       url  = "https://cdn.modrinth.com/data/7tEfOcA7/versions/JbFLLNcY/mezz_config-26.2-neoforge-0.6.11-standalone.jar"
       sha512 = "081535c89d3cfe00a60439522a2a8cd9895fa7aec2c40cde6c3f519d815fc18ce44f7f4021697d1287d9b0ccff2b9072ddfbcf1565ce147d1921aa57d500ab0b" },
    @{ file = "cloth-config-26.2.155.jar"
       url  = "https://cdn.modrinth.com/data/9s6osm5g/versions/zErG1kOw/cloth-config-26.2.155.jar"
       sha512 = "3f7ea8108d8c0463764e419f3fe7d4d7f586b6fb7d2578de85f709b70d59587060a07c0fe02d5b79ed37e74850dd088caace40f8baefb4402df8139d076dc662" },
    @{ file = "Jade-mc26.2-NeoForge-26.2.10.jar"
       url  = "https://cdn.modrinth.com/data/nvQzSEkH/versions/GBES6etT/Jade-mc26.2-NeoForge-26.2.10.jar"
       sha512 = "23c4ce0e0aec0d70b38ba5c8a346e5233aaa3923a94f54dc1b412e48ef98d394d3a1ac3ceaa9fe674dc1653bbef489a228be167f011d163db7ae6e4a947051b9" },
    @{ file = "ferritecore-9.0.0-neoforge.jar"
       url  = "https://cdn.modrinth.com/data/uXXizFIs/versions/LtVvw4uS/ferritecore-9.0.0-neoforge.jar"
       sha512 = "e96a99ac5539f56a1f4cd109d62b668ebd5283f0068491ede956f52e67023beba7abe2e40021499352ffc41ead950bebcabce7792352249a1b45c5dccb3cf99c" },
    @{ file = "appleskin-neoforge-mc26.2-3.0.10.jar"
       url  = "https://cdn.modrinth.com/data/EsAfCjCV/versions/slnk1Qah/appleskin-neoforge-mc26.2-3.0.10.jar"
       sha512 = "cf38368f611779a493a8281c5d1cfaf0360832978ab7b25f8cc0ddca88ceefea1a39e5e7ff454c194b3d0e1c08632c3e6e701d51b4245d62acfbc0f705adf1b2" }
)

if (-not (Test-Path -LiteralPath $ModDir)) {
    New-Item -ItemType Directory -Path $ModDir -Force | Out-Null
    Log "创建缓存目录：$ModDir"
}

$paths = New-Object System.Collections.Generic.List[string]
foreach ($m in $mods) {
    $dest = Join-Path $ModDir $m.file
    $need = $true
    if ((-not $Refresh) -and (Test-Path -LiteralPath $dest)) {
        $h = (Get-FileHash -LiteralPath $dest -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($h -eq $m.sha512) {
            $need = $false
            Log ("[SKIP] {0}（sha512 匹配）" -f $m.file)
        } else {
            Log ("[STALE] {0} 校验不符，重新下载" -f $m.file)
        }
    }
    if ($need) {
        $tmp = "$dest.part"
        if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Force }
        Log ("[GET ] {0}" -f $m.file)
        & curl.exe -sS -L --fail -m 300 -A "mirage-msr-test/1.0" -o $tmp $m.url
        if ($LASTEXITCODE -ne 0) { Fail "下载失败（curl exit $LASTEXITCODE）：$($m.url)" }
        $h = (Get-FileHash -LiteralPath $tmp -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($h -ne $m.sha512) {
            Remove-Item -LiteralPath $tmp -Force
            Fail "sha512 不符：$($m.file)（拿到 $h）"
        }
        Move-Item -LiteralPath $tmp -Destination $dest -Force
        Log ("[ OK ] {0}（$((Get-Item -LiteralPath $dest).Length) B）" -f $m.file)
    }
    $paths.Add($dest)
}

Log ""
Log ("就绪：{0}/{1} 个 jar，目录 {2}" -f $paths.Count, $mods.Count, $ModDir)
Log "手动跑录制："
Log ("  .\scripts\run_msr_p3.ps1 -ModJar " + ($mods[0].file) + " -ModJar ...")
[System.IO.File]::WriteAllLines($ReportFile, $report, [System.Text.UTF8Encoding]::new($false))
Log "报告：$ReportFile"

if ($Run) {
    Log ""
    Log "== 启动 P3 录制（真实 mod）=="
    & (Join-Path $PSScriptRoot "run_msr_p3.ps1") -ModJar $paths.ToArray() -TimeoutSec $TimeoutSec
    exit $LASTEXITCODE
}

exit 0
