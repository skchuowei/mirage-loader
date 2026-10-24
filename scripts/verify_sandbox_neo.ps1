# scripts/verify_sandbox_neo.ps1
# 验证 NeoForge mod 沙盒录制（MSR-neo）是否产出 recorded 字段。
#
# 用法：
#   1. 先双击 启动 26.2-mirage.bat 启动一次 MC（让沙盒录制跑起来），进主菜单后退出；
#   2. 再跑这个脚本：powershell -ExecutionPolicy Bypass -File .\scripts\verify_sandbox_neo.ps1
#
# 退出码 0 = 至少一个 NeoForge mod 的 MID json 含 recorded 子对象，且 recorded_loader_version 非空、
#        touched_apis 是数组；非 0 = 未产出（或录制失败）。

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

$gameDir = "D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2-mirage"
$cacheDir = Join-Path $gameDir ".mid-cache\26.2"

if (-not (Test-Path $cacheDir)) {
    Write-Host "[FAIL] 缓存目录不存在: $cacheDir" -ForegroundColor Red
    Write-Host "       请先双击 启动 26.2-mirage.bat 启动一次 MC，让沙盒录制跑完。" -ForegroundColor Yellow
    exit 1
}

$files = Get-ChildItem $cacheDir -Filter *.json -ErrorAction SilentlyContinue
if (-not $files -or $files.Count -eq 0) {
    Write-Host "[FAIL] 缓存目录里没有 json: $cacheDir" -ForegroundColor Red
    exit 1
}

$ok = 0
$fail = 0
foreach ($f in $files) {
    try {
        $j = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $id = $j.detected.id
        if ($null -eq $j.recorded) {
            Write-Host ("[SKIP] {0}: 无 recorded（沙盒关闭或未录制）" -f $id) -ForegroundColor Yellow
            continue
        }
        $r = $j.recorded
        $lv = $r.recorded_loader_version
        $apis = $r.touched_apis
        $evts = $r.registered_events
        if ([string]::IsNullOrEmpty($lv)) {
            Write-Host ("[FAIL] {0}: recorded_loader_version 为空" -f $id) -ForegroundColor Red
            $fail++
            continue
        }
        if ($null -eq $apis -or $apis -isnot [array]) {
            Write-Host ("[FAIL] {0}: touched_apis 不是数组" -f $id) -ForegroundColor Red
            $fail++
            continue
        }
        Write-Host ("[OK] {0}: loader={1} mc={2} apis={3} events={4} dr={5} failed={6}" -f `
            $id, $lv, $r.recorded_mc_version, $apis.Count, $evts.Count,
            $r.deferred_register_count, $r.failed) -ForegroundColor Green
        $ok++
    } catch {
        Write-Host "       - $($f.Name) [JSON 损坏: $_]" -ForegroundColor Red
        $fail++
    }
}

Write-Host ""
Write-Host "[结果] OK=$ok FAIL=$fail" -ForegroundColor Cyan
if ($ok -gt 0 -and $fail -eq 0) {
    Write-Host "[PASS] 沙盒录制已生效（至少一个 NeoForge mod 含 recorded 字段）。" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAIL] 无有效 recorded 字段（或录制失败）。" -ForegroundColor Red
    exit 1
}
