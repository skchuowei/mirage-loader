# scripts/verify_mixin_registry.ps1
# verify_mixin_registry.ps1 —— 注入点自检（构建期）：Mixin 注册与注入目标离线校验
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File .\scripts\verify_mixin_registry.ps1
#
# 为什么需要它（2026-10-31 的直接教训）：
#   F1 接线时，`core/mirage-mixin` 下 8 个 Fabric*Mixin 类一个都没注册进 mirage.mixins.json，
#   而它们是三个 Fabric bridge 的唯一调用点 —— 桥接是"死代码"，但配置加载成功、字节码没报错、
#   游戏照常能玩，只是功能永远不触发。这类故障在实机上看不出来。
#
#   同一个坑还有第二个形态：FabricClientPlayConnectionMixin 把 onDisconnect 注入到了
#   ClientPacketListener，但该方法实际定义在父类 ClientCommonPacketListenerImpl。
#   require=1 时这一条会在注册瞬间炸；若 require 被调成 0，就又退化成"静默不生效"。
#
#   所以这里离线校验六件事，全部对着 26.2.jar 的真实字节码做：
#     1. 注册完整性（双向）：mirage.mixins.json 里每个名字都有对应源文件；
#        反过来，源目录里每个 *Mixin.java 都必须被注册（这条直接堵住 F1 那类漏注册）
#     2. 目标类存在：@Mixin(targets="...") 的类在 26.2.jar 里真实存在
#     3. 注入方法存在：@Inject(method="X") 的 X 在目标类**或其父类**里真实存在
#        （父类这一层很关键 —— 打错父类/子类正是 F1 踩的那个坑）
#     4. @At(value="FIELD") 的字段真实存在
#     5. @At(value="INVOKE") 的被调方法真实存在
#     6. 启动期守卫：MirageMixinBootstrap 必须把关键织入失败升级为致命错误，
#        而不是降级返回原字节码（否则上面 1~5 全过也可能在运行期被吞）
#
#   不依赖 gradle、不启动游戏，几秒出结果。改完 mixin 注册或新增注入点后必跑。
#
# 退出码：0 = 全过；1 = 有 FAIL。

[CmdletBinding()]
param()

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = "Stop"

# 子控制台默认代码页是 GBK，中文标签会乱码。管道重定向时 chcp 可能无效，
# 但设置后至少在普通控制台与日志重定向场景都能正常显示。
try { chcp 65001 > $null } catch { }

$root = Split-Path $PSScriptRoot -Parent
$mixinDir = Join-Path $root "core\mirage-mixin\src\main\java\io\mirage\mixin"
$cfgPath = Join-Path $root "core\mirage-mixin\src\main\resources\mirage.mixins.json"
$mcJar = Join-Path $root "..\mc-shili\versions\26.2\26.2.jar"

$script:pass = 0
$script:fail = 0
$script:failures = New-Object System.Collections.ArrayList

function Ok([string]$msg) {
    $script:pass++
    Write-Host ("  [PASS] " + $msg) -ForegroundColor DarkGreen
}

function Bad([string]$msg) {
    $script:fail++
    [void]$script:failures.Add($msg)
    Write-Host ("  [FAIL] " + $msg) -ForegroundColor Red
}

# ---------------------------------------------------------------- 工具链定位

function Find-JavaTool([string]$name) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME "bin\$name.exe") }
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { $candidates += $cmd.Source }
    foreach ($v in @("jdk-25.0.4", "jdk-21.0.11", "jdk-17")) {
        $candidates += "C:\Program Files\Java\$v\bin\$name.exe"
    }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    return $null
}

$javap = Find-JavaTool "javap"
if (-not $javap) {
    Write-Host "[FAIL] 没找到 javap（请设置 JAVA_HOME，或把 JDK bin 加进 PATH）" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $cfgPath)) {
    Write-Host "[FAIL] 找不到 mixin 配置: $cfgPath" -ForegroundColor Red
    exit 1
}
if (-not (Test-Path $mcJar)) {
    Write-Host "[FAIL] 找不到原版 jar: $mcJar" -ForegroundColor Red
    exit 1
}

# 不用 Start-Process：本机 http_proxy 与 HTTP_PROXY 并存，复制环境时会抛"已添加项"。
# 也不用 & 调用 + 2>&1：PS 5.1 会把原生 stderr 变成 NativeCommandError 中断脚本。
function Quote-Arg([string]$a) {
    if ($a -match '[\s"]') { return '"' + ($a -replace '"', '\"') + '"' }
    return $a
}

function Invoke-Native([string]$exe, [string[]]$argv) {
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $exe
    $psi.Arguments = ($argv | ForEach-Object { Quote-Arg $_ }) -join ' '
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()
    $proc.WaitForExit()
    return $outTask.Result
}

# ---------------------------------------------------------------- javap 结果缓存

$script:clsCache = @{}

# 解析一个类的 javap 输出：类全名、父类、方法名集合、字段名集合
function Get-ClassInfo([string]$fqn) {
    if ($script:clsCache.ContainsKey($fqn)) { return $script:clsCache[$fqn] }

    $out = Invoke-Native $javap @("-p", "-classpath", $mcJar, $fqn)
    if (-not $out -or $out -notmatch "class\s") {
        $info = $null
    } else {
        $methods = New-Object System.Collections.ArrayList
        $fields = New-Object System.Collections.ArrayList
        $super = $null
        foreach ($line in ($out -split "`r?`n")) {
            $t = $line.Trim()
            if ($t -match "^(?:public |final |abstract |static |strictfp )*class\s+([\w\.\$]+)(?:\s+extends\s+([\w\.\$]+))?") {
                $super = $Matches[2]
                continue
            }
            if ($t -match "^(?:public |private |protected |static |final |abstract |synchronized |native |transient |volatile )*([\w\.\$<>\[\], ]+)\s+(\w+)\(") {
                [void]$methods.Add($Matches[2])
                continue
            }
            # 构造器在 javap 里没有返回类型，形如
            #   public net.minecraft.server.level.ServerLevel(MinecraftServer, ...);
            # 而 @Inject(method = "<init>(...)V") 注入的正是它 —— 记成 <init>。
            if ($t -match "^(?:public|private|protected)\s+([\w\.\$]+)\(.*\);\s*$") {
                [void]$methods.Add("<init>")
                continue
            }
            if ($t -match "^(?:public |private |protected |static |final |transient |volatile )+([\w\.\$<>\[\], ]+)\s+(\w+);\s*$") {
                [void]$fields.Add($Matches[2])
            }
        }
        $info = [pscustomobject]@{
            Exists  = $true
            Super   = $super
            Methods = $methods
            Fields  = $fields
        }
    }
    $script:clsCache[$fqn] = $info
    return $info
}

# 在类及其父类链里找方法
function Find-Method([string]$fqn, [string]$method) {
    $cur = $fqn
    $depth = 0
    while ($cur -and $depth -lt 8) {
        $info = Get-ClassInfo $cur
        if (-not $info) { return $false }
        if ($info.Methods -contains $method) { return $true }
        $cur = $info.Super
        $depth++
    }
    return $false
}

function Find-Field([string]$fqn, [string]$field) {
    $cur = $fqn
    $depth = 0
    while ($cur -and $depth -lt 8) {
        $info = Get-ClassInfo $cur
        if (-not $info) { return $false }
        if ($info.Fields -contains $field) { return $true }
        $cur = $info.Super
        $depth++
    }
    return $false
}

# ---------------------------------------------------------------- 源码解析

# 取出某个注解的完整参数串（按括号配对，避免 @At(...) 的嵌套括号把正则截断）
function Get-AnnotationArgs([string]$src, [string]$annotation) {
    $result = New-Object System.Collections.ArrayList
    $idx = 0
    while ($true) {
        $p = $src.IndexOf($annotation + "(", $idx)
        if ($p -lt 0) { break }
        $open = $p + $annotation.Length
        $depth = 0
        $i = $open
        while ($i -lt $src.Length) {
            $ch = $src[$i]
            if ($ch -eq '(') { $depth++ }
            elseif ($ch -eq ')') {
                $depth--
                if ($depth -eq 0) { break }
            }
            $i++
        }
        if ($i -ge $src.Length) { break }
        [void]$result.Add($src.Substring($open + 1, $i - $open - 1))
        $idx = $i + 1
    }
    return $result
}

# 从 @At 的 target 串里取出被引用类与方法
#   FIELD : Lnet/minecraft/client/Minecraft;gameThread:Ljava/lang/Thread;
#   INVOKE: Lnet/minecraft/server/MinecraftServer;initServer()Z
function Parse-AtTarget([string]$t) {
    if (-not $t) { return $null }
    if ($t -notmatch "^L([^;]+);(.+)$") { return $null }
    $cls = $Matches[1] -replace '/', '.'
    $rest = $Matches[2]
    $name = $null
    if ($rest -match "^(\w+):") { $name = $Matches[1] }
    elseif ($rest -match "^(\w+)\(") { $name = $Matches[1] }
    return [pscustomobject]@{ Class = $cls; Name = $name }
}

# ---------------------------------------------------------------- 读取配置

Write-Host "== 注入点自检（构建期） ==" -ForegroundColor Cyan

$cfg = Get-Content $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
$registered = @()
foreach ($m in $cfg.mixins) { $registered += [pscustomobject]@{ Name = $m; Side = "common" } }
foreach ($m in $cfg.client) { $registered += [pscustomobject]@{ Name = $m; Side = "client" } }

Write-Host ("-- 配置注册 " + $registered.Count + " 条（common " + $cfg.mixins.Count + " / client " + $cfg.client.Count + "）")

# ------------------------------------------------ 检查 1：注册完整性（双向）

$srcFiles = Get-ChildItem -Path $mixinDir -Filter "*Mixin.java" | Select-Object -ExpandProperty Name
$srcNames = @()
foreach ($f in $srcFiles) { $srcNames += ($f -replace '\.java$', '') }

foreach ($r in $registered) {
    if ($srcNames -contains $r.Name) {
        Ok ("注册项有对应源文件: " + $r.Name)
    } else {
        Bad ("注册项在源目录里找不到同名类: " + $r.Name + "（配置指向了一个不存在的 mixin）")
    }
}

foreach ($n in $srcNames) {
    if ($registered.Name -contains $n) {
        Ok ("源文件已注册: " + $n)
    } else {
        Bad ("源文件未注册进 mirage.mixins.json: " + $n + " —— 它的注入点永远不会生效（这正是 F1 的成因）")
    }
}

# ------------------------------------------------ 检查 2~5：逐个 mixin 校验

foreach ($r in $registered) {
    $file = Join-Path $mixinDir ($r.Name + ".java")
    if (-not (Test-Path $file)) { continue }
    $src = Get-Content $file -Raw -Encoding UTF8

    # 目标类
    $target = $null
    $m = [regex]::Match($src, '@Mixin\s*\(\s*targets\s*=\s*"([^"]+)"')
    if ($m.Success) { $target = $m.Groups[1].Value }
    if (-not $target) {
        Bad ($r.Name + "：读不到 @Mixin(targets=...)")
        continue
    }

    $info = Get-ClassInfo $target
    if (-not $info) {
        Bad ($r.Name + "：目标类在 26.2.jar 里不存在 -> " + $target)
        continue
    }
    Ok ($r.Name + " 目标类存在: " + $target)

    # 注入注解：@Inject / @Redirect / @ModifyArg / @ModifyVariable / @ModifyConstant
    $annots = @("Inject", "Redirect", "ModifyArg", "ModifyVariable", "ModifyConstant", "ModifyArgs")
    $checked = 0
    foreach ($a in $annots) {
        foreach ($args in (Get-AnnotationArgs $src $a)) {
            $mm = [regex]::Match($args, 'method\s*=\s*"([^"]+)"')
            if (-not $mm.Success) { continue }
            $method = $mm.Groups[1].Value
            # 两种写法都要支持：
            #   method = "tick"                                  —— 裸方法名
            #   method = "tick(Ljava/util/function/BooleanSup;)V" —— 带描述符（Neo 系常用）
            #   method = "<init>(...)V"                          —— 构造器
            $bare = $method
            if ($bare -match "^([\w$]+|<init>)\(") { $bare = $Matches[1] }
            if ($bare -match "^[\w$]+$" -or $bare -eq "<init>") {
                if (Find-Method $target $bare) {
                    Ok ($r.Name + " @" + $a + " 目标方法存在: " + $target + "." + $bare)
                } else {
                    Bad ($r.Name + " @" + $a + " 目标方法不存在: " + $target + "." + $bare + "（含父类链也没找到 —— 很可能打错了类或方法名）")
                }
                $checked++
            }

            # @At 的 target：FIELD / INVOKE
            $atKind = $null
            if ($args -match 'value\s*=\s*"FIELD"') { $atKind = "FIELD" }
            elseif ($args -match 'value\s*=\s*"INVOKE"') { $atKind = "INVOKE" }
            if ($atKind) {
                $tm = [regex]::Match($args, 'target\s*=\s*"(L[^"]+)"')
                if ($tm.Success) {
                    $parsed = Parse-AtTarget $tm.Groups[1].Value
                    if ($parsed -and $parsed.Name) {
                        if ($atKind -eq "FIELD") {
                            if (Find-Field $parsed.Class $parsed.Name) {
                                Ok ($r.Name + " @At(FIELD) 字段存在: " + $parsed.Class + "." + $parsed.Name)
                            } else {
                                Bad ($r.Name + " @At(FIELD) 字段不存在: " + $parsed.Class + "." + $parsed.Name)
                            }
                        } else {
                            if (Find-Method $parsed.Class $parsed.Name) {
                                Ok ($r.Name + " @At(INVOKE) 被调方法存在: " + $parsed.Class + "." + $parsed.Name)
                            } else {
                                Bad ($r.Name + " @At(INVOKE) 被调方法不存在: " + $parsed.Class + "." + $parsed.Name)
                            }
                        }
                        $checked++
                    }
                }
            }
        }
    }
    if ($checked -eq 0) {
        Write-Host ("  [WARN] " + $r.Name + "：没解析出任何注入点（是否用了本脚本未覆盖的注解形式？）") -ForegroundColor Yellow
    }
}

# ------------------------------------------------ 检查 6：启动期守卫（源码断言）

$bootstrap = Join-Path $mixinDir "MirageMixinBootstrap.java"
$bsrc = Get-Content $bootstrap -Raw -Encoding UTF8
if ($bsrc -match "isFatalMixinFailure" -and $bsrc -match "MixinApplyFatalException") {
    Ok "启动期守卫存在：关键织入失败升级为致命错误（不再降级返回原字节码）"
} else {
    Bad "启动期守卫缺失：MirageMixinBootstrap 未把关键织入失败升级为致命错误 —— 运行期注入失败仍会被静默吞掉"
}
if ($bsrc -match "describeChain") {
    Ok "启动期守卫存在：失败日志带异常链（可读到 目标方法 + mixin 名）"
} else {
    Bad "启动期守卫缺失：失败日志未带异常链，Mixin 的 目标方法/mixin 名 会被丢掉"
}

# ---------------------------------------------------------------- 汇总

Write-Host ""
Write-Host ("== 结果：PASS " + $script:pass + " / FAIL " + $script:fail + " ==") -ForegroundColor Cyan
if ($script:fail -gt 0) {
    Write-Host "失败明细：" -ForegroundColor Red
    foreach ($f in $script:failures) { Write-Host ("  - " + $f) -ForegroundColor Red }
    exit 1
}
Write-Host "全部通过。" -ForegroundColor Green
exit 0
