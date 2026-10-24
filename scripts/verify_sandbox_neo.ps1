# scripts/verify_sandbox_neo.ps1
# Verify MSR-neo sandbox recording produced recorded field in MID cache.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\verify_sandbox_neo.ps1

$gameDir = "D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2-mirage"
$cacheDir = Join-Path $gameDir ".mid-cache\26.2"

if (-not (Test-Path $cacheDir)) {
    Write-Host "[FAIL] cache dir missing: $cacheDir" -ForegroundColor Red
    Write-Host "       run game first via bat to trigger recording" -ForegroundColor Yellow
    exit 1
}

$files = Get-ChildItem $cacheDir -Filter *.json -ErrorAction SilentlyContinue
if (-not $files -or $files.Count -eq 0) {
    Write-Host "[FAIL] no json in cache dir: $cacheDir" -ForegroundColor Red
    exit 1
}

$ok = 0
$fail = 0
foreach ($f in $files) {
    try {
        $j = Get-Content $f.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        $id = $j.detected.id
        if ($null -eq $j.recorded) {
            Write-Host ("[SKIP] {0}: no recorded field" -f $id) -ForegroundColor Yellow
            continue
        }
        $r = $j.recorded
        $lv = $r.recorded_loader_version
        $apis = $r.touched_apis
        $evts = $r.registered_events
        if ($null -eq $apis -or $apis -isnot [array]) {
            Write-Host ("[FAIL] {0}: touched_apis not array" -f $id) -ForegroundColor Red
            $fail++
            continue
        }
        $status = if ($r.failed) { "RECORDED-FAILED" } else { "OK" }
        $color = if ($r.failed) { "Yellow" } else { "Green" }
        Write-Host ("[{0}] {1}: loader={2} mc={3} apis={4} events={5} dr={6} failed={7}" -f `
            $status, $id, $lv, $r.recorded_mc_version, $apis.Count, $evts.Count, `
            $r.deferred_register_count, $r.failed) -ForegroundColor $color
        # recorded 字段存在即视为沙盒已运行（failed=true 也是合法的录制结果，表示构造崩溃但数据已收集）
        $ok++
    } catch {
        Write-Host "  $($f.Name) [JSON error: $_]" -ForegroundColor Red
        $fail++
    }
}

Write-Host ""
Write-Host "[RESULT] OK=$ok FAIL=$fail" -ForegroundColor Cyan
if ($ok -gt 0) {
    Write-Host "[PASS] sandbox recording produced recorded field" -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAIL] no successful recorded field" -ForegroundColor Red
    exit 1
}
