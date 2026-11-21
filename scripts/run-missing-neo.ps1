<#
.SYNOPSIS
    MSR W1 取真清单启动器（W3 路线 C · C2 前置）。由 run-missing-neo.bat 调用。

.DESCRIPTION
    只读复制仓库外的「启动 26.2-mirage.bat」的 java 命令行，做两处字符串级注入：
      1) classpath 里 mc-shili 的旧 fat jar -> 仓库 11-14 新包（含 MissingNeoTracker）
      2) java.exe" 之后注入 -Dmirage.msr.missingneo=on 与 -Dmirage.msr.missingneo.out=<仓库内路径>
    不改用户现有启动脚本、不写 mc-shili/。清单落到仓库内 build/msr/（gitignore）。
    临时 bat 写到 %TEMP%（纯 ASCII + CRLF）再执行，绕开 cmd /c 的 8191 字符命令行上限。

    按 C93/C107：拉 GUI 的完整客户端只能由用户双击 .bat 运行；AI 只跑 -DryRun 做
    headless 验证（不启动 java、不拉窗口、不写 mc-shili）。

.NOTES
    UTF-8 WITH BOM + CRLF：Windows PowerShell 5.1 读无 BOM 的 .ps1 会按 GBK 解码中文。
#>
param(
    [switch]$DryRun,
    [string]$Repo = 'D:\bing\MC\mcmoderr-my\mirage-loader',
    [string]$LaunchBat = 'D:\bing\MC\mcmoderr-my\启动 26.2-mirage.bat'
)
$ErrorActionPreference = 'Stop'

$oldJar  = 'D:\bing\MC\mcmoderr-my\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar'
$newJar  = Join-Path $Repo 'launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar'
$outFile = Join-Path $Repo 'build\msr\missing-neo-real.out'
$gameDir = 'D:\bing\MC\mcmoderr-my\mc-shili\versions\26.2-mirage'
$tempBat = Join-Path $env:TEMP 'mirage-missing-neo-run.bat'

function Bail { param([string]$Line) Write-Output $Line; exit 2 }

# 1. 读原启动 bat（ReadAllBytes + UTF8，绕开 C76 的 Get-Content -Raw 编码坑）
if (-not (Test-Path -LiteralPath $LaunchBat)) { Bail 'LAUNCH_BAT_MISSING=1' }
if (-not (Test-Path -LiteralPath $newJar))     { Bail 'NEW_JAR_MISSING=1' }
$bytes = [System.IO.File]::ReadAllBytes($LaunchBat)
$text  = [System.Text.Encoding]::UTF8.GetString($bytes)
$lines = $text -split "`r?`n"

# 2. 定位 java 命令行（唯一：含 java.exe 且含 ' -cp '）
$javaLine = $null
foreach ($l in $lines) {
    if ($l -match 'java\.exe' -and $l -match '\s-cp\s') { $javaLine = $l; break }
}
if (-not $javaLine) { Bail 'JAVA_LINE_FOUND=0' }

# 3. 断言旧 jar 恰好出现一次，然后替换成仓库新包
$hits = ([regex]::Matches($javaLine, [regex]::Escape($oldJar))).Count
if ($hits -ne 1) { Bail ('OLD_JAR_HITS=' + $hits) }
$runLine = $javaLine.Replace($oldJar, $newJar)

# 4. 在 java.exe" 之后注入两个 -D（C89：-Dk=v 整段加引号）
$inject = '-Dmirage.msr.missingneo=on "-Dmirage.msr.missingneo.out=' + $outFile + '"'
$pat = '(java\.exe")(\s)'
$m = [regex]::Match($runLine, $pat)
if (-not $m.Success) { Bail 'JAVA_EXE_ANCHOR=0' }
$runLine = $runLine.Substring(0, $m.Index) + $m.Groups[1].Value + ' ' + $inject + ' ' +
           $m.Groups[2].Value + $runLine.Substring($m.Index + $m.Length)

# 5. 命令行必须纯 ASCII（临时 bat 用 ASCII 编码写）
if ($runLine -match '[^\x00-\x7F]') { Bail 'NON_ASCII=1' }

# 6. 生成临时 bat（cd 到 gameDir + 注入后的 java 命令行）
$batLines = @('@echo off', ('cd /D "' + $gameDir + '"'), $runLine, 'echo MIRAGE_MISSING_NEO_EXIT=%ERRORLEVEL%')
$batText = ($batLines -join "`r`n") + "`r`n"
[System.IO.File]::WriteAllBytes($tempBat, [System.Text.Encoding]::ASCII.GetBytes($batText))

# 7. DryRun：只输出证据，不启动 java、不拉 GUI
if ($DryRun) {
    Write-Output 'JAVA_LINE_FOUND=1'
    Write-Output ('OLD_JAR_HITS=' + $hits)
    Write-Output 'JAR_SWAP=1'
    Write-Output 'INJECT_ON=1'
    Write-Output 'INJECT_OUT=1'
    Write-Output ('OUT=' + $outFile)
    Write-Output ('RUNBAT=' + $tempBat)
    Write-Output ('RUNBAT_BYTES=' + (Get-Item -LiteralPath $tempBat).Length)
    Write-Output ('CMD_LEN=' + $javaLine.Length)
    Write-Output 'NON_ASCII=0'
    exit 0
}

# 8. 真实运行：由用户双击触发，拉 MC 客户端（GUI）
Write-Output '[run-missing-neo] Launching real client; list will be written to:'
Write-Output ('[run-missing-neo]   ' + $outFile)
Write-Output '[run-missing-neo] Please exit the game normally (click Exit Game); the shutdown hook flushes the list.'
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList ('/c "' + $tempBat + '"') -Wait -PassThru
Write-Output ('GAME_EXIT=' + $p.ExitCode)
if (Test-Path -LiteralPath $outFile) {
    $n = ([System.IO.File]::ReadAllLines($outFile)).Count
    Write-Output ('OUT_LINES=' + $n)
} else {
    Write-Output 'OUT_MISSING=1'
}
exit $p.ExitCode
