# ============================================================
# flock.ps1 - File lock coordinator (filesystem-based, no daemon)
# State dir: .flock/ (project root, gitignored)
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File scripts\flock.ps1 new
#       -> 申请一个新会话 SID（每次开工前调一次，记下它）
#   powershell -ExecutionPolicy Bypass -File scripts\flock.ps1 lock <SID> <file>
#       -> 对 <file> 加锁（相对仓库根的路径，如 core/mirage-loader/src/.../Loader.java）
#   powershell -ExecutionPolicy Bypass -File scripts\flock.ps1 unlock <SID> <file>
#   powershell -ExecutionPolicy Bypass -File scripts\flock.ps1 logout <SID>
#       -> 会话结束：释放该 SID 名下所有锁，并注销 SID
#   powershell -ExecutionPolicy Bypass -File scripts\flock.ps1 status
#   powershell -ExecutionPolicy Bypass -File scripts\flock.ps1 release <file>
#       -> 紧急释放（忘记 logout 时人工用）
#
# Exit codes: 0=ok  1=locked by other  2=invalid session  3=bad args
#
# 在 Mirage Loader 里怎么用（见 AGENTS.md 规则 2.8）：
#   $sid = (powershell -File scripts\flock.ps1 new).Trim()
#   powershell -File scripts\flock.ps1 lock $sid core/mirage-loader/src/main/java/io/mirage/loader/Loader.java
#   ...改代码、gradle build、启动冒烟...
#   powershell -File scripts\flock.ps1 unlock $sid core/mirage-loader/src/main/java/io/mirage/loader/Loader.java
#   powershell -File scripts\flock.ps1 logout $sid
#
# 超时：默认 1800 秒（30 分钟），覆盖 gradle 构建 + MC 启动冒烟的耗时；
#       命令行可加 -TimeoutSec 600 临时缩短。
# ============================================================

param(
    [Parameter(Position=0)] [string]$Cmd = "",
    [Parameter(Position=1)] [string]$Arg1 = "",
    [Parameter(Position=2)] [string]$Arg2 = "",
    [Parameter(Position=3)] [string]$Arg3 = "",
    [int]$TimeoutSec = 1800
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$StateDir = Join-Path $Root ".flock"
$LocksDir = Join-Path $StateDir "locks"

function Ensure-Dirs {
    if (-not (Test-Path $StateDir)) { New-Item -ItemType Directory -Path $StateDir | Out-Null }
    if (-not (Test-Path $LocksDir))  { New-Item -ItemType Directory -Path $LocksDir  | Out-Null }
}

function Lock-Name([string]$RelPath) {
    $name = $RelPath -replace '[\\/:*?"<>|]', '_'
    # Windows 单文件名上限 255 字符，锁文件名保留 .lock 后缀后截断
    if ($name.Length -gt 245) { $name = $name.Substring(0,245) }
    return $name + ".lock"
}

function Read-Lock([string]$LockFile) {
    if (-not (Test-Path $LockFile)) { return $null }
    $lines = Get-Content $LockFile -Encoding UTF8 -ErrorAction SilentlyContinue
    if (-not $lines) { return @{ session=""; ts=0; file="?"; path=$LockFile } }
    $sess = ""; $ts = 0; $f = ""
    foreach ($ln in $lines) {
        if ($ln -match '^session=(.+)$') { $sess = $Matches[1] }
        elseif ($ln -match '^ts=(\d+)$') { $ts = [long]$Matches[1] }
        elseif ($ln -match '^file=(.+)$') { $f = $Matches[1] }
    }
    return @{ session=$sess; ts=$ts; file=$f; path=$LockFile }
}

function Now-Epoch {
    return [int][double]::Parse((Get-Date -UFormat %s))
}

function Reap-Timeouts {
    if (-not (Test-Path $LocksDir)) { return }
    $now = Now-Epoch
    Get-ChildItem $LocksDir -Filter *.lock -ErrorAction SilentlyContinue | ForEach-Object {
        $lk = Read-Lock $_.FullName
        if ($lk -and $lk.ts -gt 0 -and ($now - $lk.ts) -gt $TimeoutSec) {
            Write-Host "[flock] timeout reap: $($lk.file) (holder $($lk.session), $($now - $lk.ts)s > ${TimeoutSec}s)"
            Remove-Item $_.FullName -Force
        }
    }
}

function Test-Session([string]$Sid) {
    $sessionsFile = Join-Path $StateDir "sessions.txt"
    if (-not (Test-Path $sessionsFile)) { return $false }
    foreach ($ln in (Get-Content $sessionsFile -Encoding UTF8)) {
        if ($ln.Trim() -eq $Sid) { return $true }
    }
    return $false
}

switch ($Cmd) {

    "new" {
        Ensure-Dirs; Reap-Timeouts
        $sessionsFile = Join-Path $StateDir "sessions.txt"
        if (-not (Test-Path $sessionsFile)) { New-Item -ItemType File -Path $sessionsFile | Out-Null }
        # 原子读-改-写：先抢到 .new_sid.lock 才能分配下一个 SID，避免两个并发 new 拿到同一 SID
        $allocLock = Join-Path $StateDir ".alloc.lock"
        try {
            $afs = [System.IO.File]::Open($allocLock, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
            $afs.Close()
        } catch {
            Write-Host "DENIED: another session is allocating SID, retry"
            exit 1
        }
        try {
            $existing = @()
            Get-Content $sessionsFile -Encoding UTF8 | ForEach-Object {
                if ($_.Trim() -match '^S(\d+)$') { $existing += [int]$Matches[1] }
            }
            $next = 1
            if ($existing.Count -gt 0) { $next = [int]($existing | Measure-Object -Maximum).Maximum + 1 }
            $sid = "S" + $next.ToString("000")
            Add-Content -Path $sessionsFile -Value $sid -Encoding UTF8
        } finally {
            Remove-Item $allocLock -Force -ErrorAction SilentlyContinue
        }
        Write-Host $sid
        exit 0
    }

    "lock" {
        $sid = $Arg1; $file = $Arg2
        if (-not $sid -or -not $file) { Write-Host "usage: lock <SID> <file>"; exit 3 }
        Ensure-Dirs; Reap-Timeouts
        if (-not (Test-Session $sid)) { Write-Host "session $sid invalid"; exit 2 }

        $lockFile = Join-Path $LocksDir (Lock-Name $file)
        if (Test-Path $lockFile) {
            $existing = Read-Lock $lockFile
            if ($existing.session -eq $sid) {
                Write-Host "OK (idempotent, already held): $file"
                exit 0
            }
            $now = Now-Epoch
            $held = $now - $existing.ts
            Write-Host "DENIED: $file held by $($existing.session) ($held s)"
            exit 1
        }
        $now = Now-Epoch
        $content = "session=$sid`nts=$now`nfile=$file`n"
        try {
            $fs = [System.IO.File]::Open($lockFile, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
            $sw = New-Object System.IO.StreamWriter($fs, (New-Object System.Text.UTF8Encoding $false))
            $sw.Write($content); $sw.Close(); $fs.Close()
        } catch {
            Start-Sleep -Milliseconds 50
            $existing = Read-Lock $lockFile
            $holder = if ($existing.session) { $existing.session } else { "unknown" }
            Write-Host "DENIED: $file held by $holder (race)"
            exit 1
        }
        Write-Host "OK: locked $file (holder $sid)"
        exit 0
    }

    "unlock" {
        $sid = $Arg1; $file = $Arg2
        if (-not $sid -or -not $file) { Write-Host "usage: unlock <SID> <file>"; exit 3 }
        Ensure-Dirs
        $lockFile = Join-Path $LocksDir (Lock-Name $file)
        if (-not (Test-Path $lockFile)) { Write-Host "note: $file not locked"; exit 0 }
        $existing = Read-Lock $lockFile
        if ($existing.session -ne $sid) {
            Write-Host "DENIED: $file held by $($existing.session), not you ($sid)"
            exit 1
        }
        Remove-Item $lockFile -Force
        Write-Host "OK: released $file"
        exit 0
    }

    "logout" {
        $sid = $Arg1
        if (-not $sid) { Write-Host "usage: logout <SID>"; exit 3 }
        Ensure-Dirs; Reap-Timeouts
        $freed = 0
        Get-ChildItem $LocksDir -Filter *.lock -ErrorAction SilentlyContinue | ForEach-Object {
            $lk = Read-Lock $_.FullName
            if ($lk -and $lk.session -eq $sid) {
                Remove-Item $_.FullName -Force
                Write-Host "  freed: $($lk.file)"
                $freed++
            }
        }
        $sessionsFile = Join-Path $StateDir "sessions.txt"
        if (Test-Path $sessionsFile) {
            $remaining = Get-Content $sessionsFile -Encoding UTF8 | Where-Object { $_.Trim() -ne $sid }
            # 原子写：临时文件 + Move-Item -Force，避免写一半崩了 sessions.txt 损坏
            $tmp = "$sessionsFile.tmp"
            Set-Content -Path $tmp -Value $remaining -Encoding UTF8
            Move-Item -Path $tmp -Destination $sessionsFile -Force
        }
        Write-Host "OK: session $sid logged out (freed $freed locks)"
        exit 0
    }

    "status" {
        Ensure-Dirs; Reap-Timeouts
        Write-Host "=== active sessions ==="
        $sessionsFile = Join-Path $StateDir "sessions.txt"
        if (Test-Path $sessionsFile) { Get-Content $sessionsFile -Encoding UTF8 | ForEach-Object { Write-Host "  $_" } }
        Write-Host ""
        Write-Host "=== current file locks ==="
        $any = $false
        Get-ChildItem $LocksDir -Filter *.lock -ErrorAction SilentlyContinue | ForEach-Object {
            $lk = Read-Lock $_.FullName
            $now = Now-Epoch
            $held = $now - $lk.ts
            Write-Host ("  {0,-60} holder={1,-8} held={2}s" -f $lk.file, $lk.session, $held)
            $script:any = $true
        }
        if (-not $any) { Write-Host "  (none)" }
        exit 0
    }

    "release" {
        $file = $Arg1
        if (-not $file) { Write-Host "usage: release <file>"; exit 3 }
        Ensure-Dirs
        $lockFile = Join-Path $LocksDir (Lock-Name $file)
        if (Test-Path $lockFile) {
            $lk = Read-Lock $lockFile
            Remove-Item $lockFile -Force
            Write-Host "OK: emergency release $file (was $($lk.session))"
        } else {
            Write-Host "$file not locked"
        }
        exit 0
    }

    default {
        Write-Host "flock.ps1 - file lock coordinator"
        Write-Host "usage:"
        Write-Host "  flock.ps1 new                  get new session id"
        Write-Host "  flock.ps1 lock <SID> <file>    acquire file lock"
        Write-Host "  flock.ps1 unlock <SID> <file>  release file lock"
        Write-Host "  flock.ps1 logout <SID>          destroy session"
        Write-Host "  flock.ps1 status               list all locks"
        Write-Host "  flock.ps1 release <file>       emergency release"
        exit 3
    }
}
