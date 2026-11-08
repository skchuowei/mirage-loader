# scripts/common/NeoClasspath.ps1
# 从 NeoForge 版本 json 重建真实启动 classpath（共享给运行器与探针 mod 构建脚本）
#
# 为什么要有这个共享模块：
#   1. NeoForge/MC 的 classpath 有 100+ 个 jar，**手列必错**；
#   2. launcher 的 library rules 决定「哪些 jar 在本平台生效」（43 个 linux/macos natives 要排除）；
#   3. **探针 mod 的编译 classpath 必须与运行 classpath 同源** ——
#      否则会出现「编译过了但运行期 NoClassDefFoundError」这类假象。
#
# 用法：
#   . (Join-Path $repo "scripts\common\NeoClasspath.ps1")
#   $info = Get-NeoClasspath -VersionJson $verJson -LibrariesDir $libsDir
#   $info.cp        # 分号分隔的 classpath
#   $info.missing   # 缺失的 library（应只剩非本平台 natives）
#   $info.mainClass # net.neoforged.fml.startup.Client
#   $info.assetIndex # 32

function Get-NeoClasspath {
    param(
        [Parameter(Mandatory = $true)][string]$VersionJson,
        [Parameter(Mandatory = $true)][string]$LibrariesDir,
        # 额外的 jar（相对 LibrariesDir）——**MC 本体与 NeoForge universal 必须显式补**：
        # 它们不在版本 json 的 libraries 里（NeoForge 安装器额外下载的），
        # 运行时 FML 靠 -DlibraryDirectory 自拼（日志里是 ~libraries/... 前缀），
        # 但编译期（javac）必须显式在 classpath 上，否则「程序包 net.minecraft.* 不存在」。
        [string[]]$ExtraRelative = @()
    )

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

    $doc = Get-Content $VersionJson -Raw -Encoding UTF8 | ConvertFrom-Json
    $parts = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]
    foreach ($lib in $doc.libraries) {
        if (-not (Test-LibraryAllowed $lib.rules)) { continue }
        $path = $null
        if ($lib.downloads -and $lib.downloads.artifact -and $lib.downloads.artifact.path) {
            $path = $lib.downloads.artifact.path
        } elseif ($lib.name) {
            $path = ($lib.name -replace "\.", "/") + "/" + $lib.version + "/" + ($lib.name -split "\.")[-1] + "-" + $lib.version + ".jar"
        }
        if (-not $path) { continue }
        $full = Join-Path $LibrariesDir ($path -replace "/", "\")
        if (Test-Path $full) { $parts.Add($full) } else { $missing.Add($path) }
    }
    # 额外 jar（MC 本体 + NeoForge universal）
    foreach ($rel in $ExtraRelative) {
        $full = Join-Path $LibrariesDir ($rel -replace "/", "\")
        if (Test-Path $full) {
            $parts.Add($full)
        } else {
            $missing.Add($rel)
        }
    }
    return @{
        cp        = ($parts -join ";")
        missing   = $missing
        mainClass = $doc.mainClass
        assetIndex = $doc.assetIndex.id
        libraries = $LibrariesDir
    }
}
