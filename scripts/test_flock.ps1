# ============================================================
# test_flock.ps1 - flock.ps1 自动化回归
# 用法: powershell -ExecutionPolicy Bypass -File scripts\test_flock.ps1
# 退出码: 0=全绿  1=有用例失败
# 本脚本会自己创建/销毁 SID 与锁，不影响其他会话（用 .regtest 前缀的假路径）
# ============================================================

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Flock = Join-Path $PSScriptRoot "flock.ps1"

# 用一个不会跟真实文件撞的假路径（变量名避开数字后缀，避免 PS 解析坑）
$ffA = ".regtest/file-a.java"
$ffB = ".regtest/file-b.java"

$script:failures = @()
function Check([string]$name, [int]$actual, [int]$expected) {
    if ($actual -eq $expected) {
        Write-Host "  [PASS] $name (exit=$actual)"
    } else {
        Write-Host "  [FAIL] $name (expected=$expected, actual=$actual)"
        $script:failures += "${name}: expected exit ${expected}, got ${actual}"
    }
}

function Invoke-Flock {
    param([Parameter(Position=0)][string]$Cmd,
          [Parameter(Position=1)][string]$A1 = "",
          [Parameter(Position=2)][string]$A2 = "",
          [Parameter(Position=3)][string]$A3 = "")
    $out = & powershell -ExecutionPolicy Bypass -File $Flock $Cmd $A1 $A2 $A3 2>&1
    $code = $LASTEXITCODE
    return $code
}

Write-Host "== flock.ps1 regression =="

# 清理可能残留的状态
& powershell -ExecutionPolicy Bypass -File $Flock release $ffA | Out-Null
& powershell -ExecutionPolicy Bypass -File $Flock release $ffB | Out-Null

# 1. new 两次，SID 不应相同
$s1 = (& powershell -ExecutionPolicy Bypass -File $Flock new | Select-Object -Last 1).Trim()
$s2 = (& powershell -ExecutionPolicy Bypass -File $Flock new | Select-Object -Last 1).Trim()
if ($s1 -and $s2 -and $s1 -ne $s2 -and $s1 -match '^S\d+$') {
    Write-Host "  [PASS] new returns two distinct SIDs: $s1, $s2"
} else {
    Write-Host "  [FAIL] new returned bad SIDs: '$s1' '$s2'"
    $script:failures += "new: bad SIDs"
}

# 2. lock by s1 -> exit 0
$rc = Invoke-Flock lock $s1 $ffA
Check "lock s1 file-a" $rc 0

# 3. re-lock by same s1 -> idempotent exit 0
$rc = Invoke-Flock lock $s1 $ffA
Check "re-lock same s1 (idempotent)" $rc 0

# 4. lock by s2 on same file -> DENIED exit 1
$rc = Invoke-Flock lock $s2 $ffA
Check "lock s2 file-a (held by s1)" $rc 1

# 5. unlock by s2 -> DENIED exit 1（不是持有者）
$rc = Invoke-Flock unlock $s2 $ffA
Check "unlock s2 file-a (not owner)" $rc 1

# 6. unlock by s1 -> exit 0
$rc = Invoke-Flock unlock $s1 $ffA
Check "unlock s1 file-a" $rc 0

# 7. lock by s2 on fileB, then logout s2 -> should auto-release fileB
$rc = Invoke-Flock lock $s2 $ffB
Check "lock s2 file-b" $rc 0
$rc = Invoke-Flock logout $s2
Check "logout s2 (auto-release file-b)" $rc 0

# 8. after logout, fileB should be acquirable by s1
$rc = Invoke-Flock lock $s1 $ffB
Check "lock s1 file-b after s2 logout" $rc 0

# 9. logout s1 -> cleanup
$rc = Invoke-Flock logout $s1
Check "logout s1" $rc 0

# 10. bad SID lock -> exit 2
$rc = Invoke-Flock lock "S999999" $ffA
Check "lock with invalid SID" $rc 2

# 11. bad args -> exit 3
$rc = Invoke-Flock "bogus_cmd"
Check "bogus command" $rc 3

# 最终 status 应该无活动锁
Write-Host "--- final status ---"
& powershell -ExecutionPolicy Bypass -File $Flock status

if ($script:failures.Count -eq 0) {
    Write-Host "== ALL PASS =="
    exit 0
} else {
    Write-Host "== FAILURES: $($script:failures.Count) =="
    $script:failures | ForEach-Object { Write-Host "  - $_" }
    exit 1
}
