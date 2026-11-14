<#
.SYNOPSIS
    MSR W3 路线 C 一键诊断启动器（由 scripts/run_w3c_diag.bat 调用）。

.DESCRIPTION
    四档菜单：1 完整门禁（含 fatJar 构建）/ 2 快速门禁（-SkipBuild，默认）/
    3 仅内置探针 / 4 退出。全部 headless：不创建窗口、不启动 MC、不写
    mc-shili/（AGENTS.md 第 2 节 (B) 类）。

.NOTES
    本文件必须 UTF-8 WITH BOM + CRLF：Windows PowerShell 5.1 读无 BOM 的 .ps1
    会按 ANSI(GBK) 解码，中文全部乱码（C76 同族坑）。
    .bat 那侧必须纯 ASCII + CRLF + 无 BOM：cmd.exe 按 OEM 代码页逐字节解析，
    UTF-8 中文会吞掉相邻引号/百分号、或解出 & / AT / ( 等命令元字符（C105）。
    因此中文界面只放在这里，不放在 .bat 里。
#>
param(
    [string]$JavaHome = "C:\Program Files\Java\jdk-25.0.4",
    [string]$Repo = "D:\bing\MC\mcmoderr-my\mirage-loader",
    # 不用 ValidateSet：默认空串要留给交互式菜单，菜单输入也可能是任意串。
    [string]$Mode = ""
)

$ErrorActionPreference = "Continue"

$gateScript = Join-Path $PSScriptRoot "verify_fakepack_w3_c.ps1"
$fatJar     = Join-Path $Repo "launcher\mirage-launch\build\libs\mirage-launch-0.1.0.jar"
$probeSrc   = Join-Path $PSScriptRoot "fixtures\fakepack-w3c\W3CBuiltinProbe.java"

function Write-Rule {
    Write-Host "============================================================"
}

function Show-Menu {
    Write-Rule
    Write-Host "  Mirage W3-C 诊断工具  (headless, 不拉 GUI / 不写 mc-shili)"
    Write-Rule
    Write-Host "  1) 完整门禁   - 含 :launcher:mirage-launch:fatJar 重新构建（较慢）"
    Write-Host "  2) 快速门禁   - 复用已有 fat jar（默认，需先构建过一次）"
    Write-Host "  3) 仅内置探针 - 直接编译并运行 W3CBuiltinProbe 看原始输出"
    Write-Host "  4) 退出"
    Write-Rule
}

function Invoke-Gate {
    param([switch]$SkipBuild)

    $gateArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $gateScript,
                  "-JavaHome", $JavaHome, "-Repo", $Repo)
    if ($SkipBuild) { $gateArgs += "-SkipBuild" }
    & powershell.exe @gateArgs | Out-Host
    return $LASTEXITCODE
}

function Invoke-BuiltinProbe {
    if (-not (Test-Path -LiteralPath $fatJar)) {
        Write-Host "[错误] 未发现已构建的 fat jar：$fatJar"
        Write-Host "       请先选 1) 完整门禁 构建一次。"
        return 1
    }
    if (-not (Test-Path -LiteralPath $probeSrc)) {
        Write-Host "[错误] 探针源码不存在：$probeSrc"
        return 1
    }

    $workDir = Join-Path $env:TEMP "mirage-w3c-probe"
    if (Test-Path -LiteralPath $workDir) {
        Remove-Item -LiteralPath $workDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $workDir -Force | Out-Null

    $javacExe = Join-Path $JavaHome "bin\javac.exe"
    $javaExe  = Join-Path $JavaHome "bin\java.exe"
    Write-Host "[编译并运行] W3CBuiltinProbe（BUILTIN_ON 场景，看 NEO_LOAD 等原始输出）..."
    # C89：& 调 java 的 -Dkey=value 必须作为一个整体参数传入（加引号）。
    & $javacExe -cp "$fatJar" -d "$workDir" "$probeSrc" | Out-Host
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[失败] 探针编译失败"
        return 1
    }

    $cp = "$workDir;$fatJar"
    Write-Host "----- BUILTIN_ON (-Dmirage.msr.fakepack.runtime=on) -----"
    & $javaExe "-Dmirage.msr.fakepack.runtime=on" -cp "$cp" probe.W3CBuiltinProbe "$fatJar" | Out-Host
    Write-Host ""
    Write-Host "----- DEFAULT (不给 runtime，应全 CNFE) -----"
    & $javaExe -cp "$cp" probe.W3CBuiltinProbe "$fatJar" | Out-Host
    return 0
}

$exitCode = 0
if ([string]::IsNullOrWhiteSpace($Mode)) {
    Show-Menu
    $raw = Read-Host "请选择 [2]"
    if ([string]::IsNullOrWhiteSpace($raw)) { $Mode = "2" } else { $Mode = $raw.Trim() }
    if ($Mode -notin @("1", "2", "3", "4")) {
        Write-Host "无效选择，使用默认（快速门禁）"
        $Mode = "2"
    }
}

switch ($Mode) {
    "1" {
        Write-Host ""
        Write-Host "[运行] 完整门禁（含 fat jar 构建）..."
        $exitCode = Invoke-Gate
    }
    "2" {
        if (-not (Test-Path -LiteralPath $fatJar)) {
            Write-Host ""
            Write-Host "[提示] 未发现已构建的 fat jar：$fatJar"
            Write-Host "       自动改为「完整门禁」（含构建）..."
            $exitCode = Invoke-Gate
        } else {
            Write-Host ""
            Write-Host "[运行] 快速门禁（SkipBuild，复用已有 fat jar）..."
            $exitCode = Invoke-Gate -SkipBuild
        }
    }
    "3" {
        Write-Host ""
        $exitCode = Invoke-BuiltinProbe
    }
    "4" {
        Write-Host "已退出。"
    }
}

Write-Host ""
Write-Rule
Write-Host "  诊断结束。结果见上方（门禁末行应为 RESULT PASS n/n）。"
Write-Rule
# 暂停统一由 .bat 负责（pause >nul），这里不 pause，否则双击会停两次。
exit $exitCode
