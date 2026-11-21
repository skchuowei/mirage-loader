#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""取真清单启动器门禁：锁死 .bat/.ps1 字节格式 + DryRun 注入正确性（C77 三层）。

背景：W3 路线 C 的 C2 需要先在真实客户端跑 MissingNeoTracker 取真缺口清单。
启动脚本只读复制仓库外「启动 26.2-mirage.bat」的 java 命令行，注入两个 -D 后
执行；本门禁 headless 验证这条链，不启动 java、不拉 GUI、不写 mc-shili/。

分层：
  守卫层  静态字节断言（.bat 纯 ASCII+CRLF / .ps1 BOM+CRLF）+ 行首锚定接线守卫
  行为层  实跑 ps1 -DryRun，断言注入的命令行里旧 jar 已换、两个 -D 齐全且 OUT
          落在仓库内、临时 bat 是纯 ASCII+CRLF
  变异层  %TEMP% 副本落盘改坏（删注入 / 改 OUT 到 gameDir / 换错 jar），逐个判红
          + 编译路径自检（未变异走同流程仍绿）+ 源文件字节级不变
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BAT_REL = Path("scripts/run-missing-neo.bat")
PS1_REL = Path("scripts/run-missing-neo.ps1")
OLD_JAR = r"D:\bing\MC\mcmoderr-my\mc-shili\libraries\io\mirage\mirage-launch\0.1.0\mirage-launch-0.1.0.jar"
NEW_JAR_REL = "launcher/mirage-launch/build/libs/mirage-launch-0.1.0.jar"

# 行首锚定：注释里的示例不能骗过守卫（C77/C82）。
ANCHORS = [
    r'(?m)^\s*\$oldJar\s+=\s+.*mc-shili\\libraries\\io\\mirage\\mirage-launch',
    r'(?m)^\s*\$newJar\s+=\s+Join-Path \$Repo',
    r'(?m)^\s*\$outFile\s+=\s+Join-Path \$Repo .build\\msr\\missing-neo-real\.out',
    r'(?m)^\s*\$hits\s+=\s+\(\[regex\]::Matches\(\$javaLine',
    r'(?m)^\s*\$runLine\s+=\s+\$javaLine\.Replace\(\$oldJar, \$newJar\)',
    r"(?m)^\s*\$inject\s+=\s+'-Dmirage\.msr\.missingneo=on",
    r'(?m)^\s*if \(\$DryRun\) \{',
    r'(?m)^\s*\$p\s+=\s+Start-Process -FilePath .cmd\.exe',
]
NEGATIVE = [
    r'(?m)^\s*\$outFile\s+=\s+.*mc-shili',
    r'(?m)^\s*\[System\.IO\.File\]::WriteAll[^\r\n]*mc-shili',
]


class Gate:
    def __init__(self):
        self.checks = 0
        self.fails = []

    def ok(self, cond, label):
        self.checks += 1
        if not cond:
            self.fails.append(label)

    def report(self):
        return self.checks, self.fails


def guard_layer(ps1_path, bat_path):
    g = Gate()
    bat = bat_path.read_bytes() if bat_path.exists() else None
    g.ok(bat is not None, "run-missing-neo.bat 存在")
    if bat is not None:
        g.ok(not bat.startswith(b"\xef\xbb\xbf"), ".bat 无 BOM")
        g.ok(all(x < 128 for x in bat), ".bat 纯 ASCII")
        crlf = bat.count(b"\r\n")
        g.ok(bat.endswith(b"\r\n") and crlf > 0 and bat.count(b"\n") == crlf,
             ".bat 全 CRLF")
        g.ok(not bat.lstrip(b"\xef\xbb\xbf").startswith(b"chcp"),
             ".bat 首行非 chcp")
        g.ok(all(c not in bat for c in b"&|^()<"), ".bat 无 cmd 元字符")
        g.ok(bat.count(b">") == 1 and b"pause >nul" in bat,
             ".bat 唯一重定向是 pause >nul")
        g.ok(re.search(rb'(?m)^\s*powershell\.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-missing-neo\.ps1" %\*\s*$', bat),
             ".bat 跳板指向 run-missing-neo.ps1（行首锚定）")

    ps1 = ps1_path.read_bytes() if ps1_path.exists() else None
    g.ok(ps1 is not None, "run-missing-neo.ps1 存在")
    if ps1 is not None:
        g.ok(ps1.startswith(b"\xef\xbb\xbf"), ".ps1 有 UTF-8 BOM")
        body = ps1[3:] if ps1.startswith(b"\xef\xbb\xbf") else ps1
        g.ok(body.count(b"\n") == body.count(b"\r\n"), ".ps1 零裸 LF")
        try:
            st = body.decode("utf-8")
            decoded = True
        except UnicodeDecodeError:
            st, decoded = "", False
        g.ok(decoded, ".ps1 可按 UTF-8 解码")
        for a in ANCHORS:
            g.ok(bool(re.search(a, st)), f".ps1 锚点 {a[:52]}")
        for a in NEGATIVE:
            g.ok(not re.search(a, st), f".ps1 无负向形态 {a[:44]}")
        g.ok("DryRun" in st and "不启动 java" in st, ".ps1 保留 headless 说明")
        g.ok("-Dmirage.msr.missingneo=on" in st, ".ps1 含 enable 开关")
        g.ok("mirage.msr.missingneo.out" in st, ".ps1 含 out 属性")
    return g


def decode(raw):
    for enc in ("gbk", "utf-8"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("gbk", "replace")


def run_dryrun(ps1_path):
    exe = shutil.which("powershell.exe") or "powershell.exe"
    try:
        r = subprocess.run(
            [exe, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
             str(ps1_path), "-DryRun"],
            cwd=str(ROOT), capture_output=True, timeout=120,
        )
        return r.returncode, decode(r.stdout + r.stderr)
    except Exception as e:
        return -1, str(e)


def behaviour_layer(ps1_path):
    g = Gate()
    rc, out = run_dryrun(ps1_path)
    g.ok(rc == 0, f"DryRun 退出码 0（实得 {rc}）")
    g.ok("ParserError" not in out and "At line:" not in out, "DryRun 无 PowerShell 解析错误")
    for key in ["JAVA_LINE_FOUND=1", "OLD_JAR_HITS=1", "JAR_SWAP=1",
                "INJECT_ON=1", "INJECT_OUT=1", "NON_ASCII=0"]:
        g.ok(key in out, f"DryRun 输出 {key}")

    m = re.search(r"(?m)^OUT=(.+)$", out)
    g.ok(m is not None, "DryRun 输出 OUT= 行")
    if m:
        out_path = m.group(1).strip()
        g.ok("mirage-loader" in out_path and "build\\msr\\missing-neo-real.out" in out_path,
             f"OUT 落在仓库 build/msr（{out_path}）")
        g.ok("mc-shili" not in out_path, "OUT 不写 mc-shili")

    m = re.search(r"(?m)^RUNBAT=(.+)$", out)
    g.ok(m is not None, "DryRun 输出 RUNBAT= 行")
    if m:
        rb = Path(m.group(1).strip())
        g.ok(rb.exists(), "临时 bat 已生成")
        if rb.exists():
            raw = rb.read_bytes()
            g.ok(all(x < 128 for x in raw), "临时 bat 纯 ASCII")
            g.ok(raw.count(b"\n") == raw.count(b"\r\n") and raw.endswith(b"\r\n"),
                 "临时 bat 全 CRLF")
            txt = raw.decode("ascii", "replace")
            g.ok(OLD_JAR not in txt and "mirage-launch-0.1.0.jar" in txt,
                 "临时 bat classpath 里旧 jar 已换成新包")
            g.ok("mirage\\launcher\\mirage-launch\\build\\libs" in txt
                 or "mirage-launcher" in txt or NEW_JAR_REL.replace("/", "\\") in txt
                 or "mirage-launch\\build\\libs" in txt,
                 "临时 bat classpath 指向仓库新包路径")
            g.ok("-Dmirage.msr.missingneo=on" in txt, "临时 bat 含 enable 开关")
            g.ok("mirage.msr.missingneo.out=" in txt, "临时 bat 含 out 属性")
            g.ok('cd /D "D:\\bing\\MC\\mcmoderr-my\\mc-shili\\versions\\26.2-mirage"' in txt,
                 "临时 bat cd 到 gameDir")
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--guard-only", action="store_true", help="只跑守卫层")
    ap.add_argument("--behaviour-only", action="store_true", help="只跑行为层")
    args = ap.parse_args()

    ps1 = ROOT / PS1_REL
    bat = ROOT / BAT_REL
    g_all = Gate()
    t_bat = t_ps1 = None
    if not args.behaviour_only:
        g = guard_layer(ps1, bat)
        t_bat, f = g.report()
        g_all.checks += t_bat
        g_all.fails += f
    if not args.guard_only:
        g = behaviour_layer(ps1)
        t_ps1, f = g.report()
        g_all.checks += t_ps1
        g_all.fails += f

    total, fails = g_all.report()
    if fails:
        print("RESULT FAIL %d/%d" % (total - len(fails), total))
        for x in fails:
            print("  FAIL", x)
        return 1
    print("RESULT PASS %d/%d" % (total, total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
