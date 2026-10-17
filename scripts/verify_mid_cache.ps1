# scripts/verify_mid_cache.ps1
# 验证 MID 磁盘缓存是否工作。
#
# 用法：
#   1. 先双击 启动 26.2-mirage.bat 启动一次 MC（让 mod 扫描跑起来），进主菜单后退出；
#   2. 再跑这个脚本：powershell -ExecutionPolicy Bypass -File .\scripts\verify_mid_cache.ps1
#
# 退出码 0 = 缓存目录里至少有一个 NeoForge mod 的 json；非 0 = 未生成。

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$gameDir = "D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2-mirage"
$cacheDir = Join-Path $gameDir ".mid-cache\26.2"

if (-not (Test-Path $cacheDir)) {
    Write-Host "[FAIL] 缓存目录不存在: $cacheDir" -ForegroundColor Red
    Write-Host "       请先双击 启动 26.2-mirage.bat 启动一次 MC，让 ModDiscoverer 跑完。" -ForegroundColor Yellow
    exit 1
}

$files = Get-ChildItem $cacheDir -Filter *.json -ErrorAction SilentlyContinue
if (-not $files -or $files.Count -eq 0) {
    Write-Host "[FAIL] 缓存目录里没有 json: $cacheDir" -ForegroundColor Red
    Write-Host "       可能原因：mods/ 下没有 NeoForge mod（neoforge.mods.toml），或首次启动还没跑完。" -ForegroundColor Yellow
    exit 1
}

Write-Host "[OK] 缓存目录: $cacheDir" -ForegroundColor Green
Write-Host "     共 $($files.Count) 个 MID 条目:"
foreach ($f in $files) {
    try {
        $j = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $d = $j.detected
        Write-Host ("       - {0} v{1}  (main={2}, ag={3}, {4:N1} KB)" -f `
            $d.id, $d.version, $d.mainClass, $j.agVersion, ($f.Length/1KB))
    } catch {
        Write-Host "       - $($f.Name) [JSON 损坏: $_]" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "[提示] 二启验证：再双击一次 启动 26.2-mirage.bat，看 logs/latest.log 里应出现"
Write-Host "       'MID 缓存命中: <mod-id>（跳过 ASM 扫 class）'" -ForegroundColor Cyan
exit 0
