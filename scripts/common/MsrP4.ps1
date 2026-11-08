# MSR P4 共用辅助：只做路径解析，不启动任何东西。
# 由 msr_p4_generate_fakes.ps1 / verify_msr_p4.ps1 dot-source 引入。
#
# 坑（C79）：本文件里的局部变量**_不要_**与调用方的 [switch] 参数同名
# （PowerShell 变量名大小写不敏感，[switch] 是强类型，赋值会抛类型转换异常）。

function Get-MsrRepoRoot {
    # 本文件位于 <repo>\scripts\common\ ⇒ 上两级
    return (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
}

function Find-MsrJdk([string]$Preferred) {
    $cands = New-Object System.Collections.Generic.List[string]
    if ($Preferred) { [void]$cands.Add($Preferred) }
    if ($env:JAVA_HOME) { [void]$cands.Add($env:JAVA_HOME) }
    [void]$cands.Add("C:\Program Files\Java\jdk-25.0.4")
    [void]$cands.Add("C:\Program Files\Java\jdk-21.0.11")
    foreach ($c in $cands) {
        if ($c -and (Test-Path (Join-Path $c "bin\java.exe"))) { return $c }
    }
    return $null
}

function Find-MsrGradleBat {
    $root = Join-Path $env:USERPROFILE ".gradle\wrapper\dists"
    if (Test-Path $root) {
        $hit = Get-ChildItem -Path $root -Recurse -Filter "gradle.bat" -ErrorAction SilentlyContinue |
               Sort-Object FullName -Descending | Select-Object -First 1
        if ($hit) { return $hit.FullName }
    }
    $cmd = Get-Command gradle -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Find-MsrAsmJar {
    $root = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\org.ow2.asm\asm"
    if (-not (Test-Path $root)) { return $null }
    $hit = Get-ChildItem -Path $root -Recurse -Filter "asm-*.jar" -ErrorAction SilentlyContinue |
           Sort-Object Name -Descending | Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

# 形状源：真实 NeoForge 的三个 jar（universal / bus / fancymodloader loader）。
# 只用来读签名（SKIP_CODE），不参与运行。
function Find-MsrShapeJars([string]$Libraries) {
    $out = New-Object System.Collections.Generic.List[string]
    $patterns = @(
        "net\neoforged\neoforge\*\neoforge-*-universal.jar",
        "net\neoforged\bus\*\bus-*.jar",
        "net\neoforged\fancymodloader\loader\*\loader-*.jar"
    )
    foreach ($pat in $patterns) {
        $hit = Get-ChildItem -Path (Join-Path $Libraries $pat) -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending | Select-Object -First 1
        if ($hit) { [void]$out.Add($hit.FullName) }
    }
    return $out
}

# 生成器 / 探针用的 classpath：loader 产物 + api 产物 + compat 产物 + ASM。
function Get-MsrP4Classpath([string]$Repo) {
    $parts = New-Object System.Collections.Generic.List[string]
    [void]$parts.Add((Join-Path $Repo "core\mirage-loader\build\classes\java\main"))
    [void]$parts.Add((Join-Path $Repo "api\mirage-api\build\classes\java\main"))
    [void]$parts.Add((Join-Path $Repo "modules\mirage-neoforge-compat\build\classes\java\main"))
    $asm = Find-MsrAsmJar
    if ($asm) { [void]$parts.Add($asm) }
    return ($parts -join ";")
}

function Get-MsrCompatClasses([string]$Repo) {
    return (Join-Path $Repo "modules\mirage-neoforge-compat\build\classes\java\main")
}
