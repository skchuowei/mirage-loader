#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W3-C 诊断启动器门禁：锁死 .bat 的字节格式与跳板接线。

背景（C105）：cmd.exe 按 OEM 代码页(CP936)逐字节解析 .bat。若文件是
UTF-8 中文 + 裸 LF + 无 BOM，GBK 双字节切分会吞掉相邻的 " / % / ( 等
ASCII 字符、或解出 & / AT / ( 等命令元字符，把提示文本当成命令执行；
chcp 65001 写在第 2 行也救不了，因为 cmd 已完成词法解析。裸 LF 还会
破坏 if (...) 多行块与 goto 标签。

因此不变量：
  *.bat     纯 ASCII + CRLF + 无 BOM（中文界面一律不进 .bat）
  *.ps1     UTF-8 WITH BOM + CRLF（PS 5.1 读无 BOM 的 .ps1 会按 GBK 解码）
  .bat      只做跳板，菜单/逻辑全在 run_w3c_diag.ps1
  pause     只在 .bat（否则双击会停两次）

验证分层（C77）：
  守卫层  静态字节断言 + 行首锚定的接线守卫
  行为层  --behaviour 时真的用 PowerShell 跑启动器的退出档与探针档
  变异层  在 %TEMP% 副本上落盘改坏，逐个判红 + 编译路径自检 + 基线回绿
副本策略：变异不碰仓库源文件，天然满足 C82 的「字节级还原」要求。
"""
import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BAT_REL = Path("scripts/run_w3c_diag.bat")
PS1_REL = Path("scripts/run_w3c_diag.ps1")
ATTR_REL = Path(".gitattributes")
MDK_BAT_REL = Path("mdk/mdk-example/build.bat")

# 行首锚定：防止注释里的示例行骗过守卫（C77 / C82）。
LAUNCH_ANCHOR = (
    r'(?m)^\s*powershell\.exe -NoProfile -ExecutionPolicy Bypass '
    r'-File "%~dp0run_w3c_diag\.ps1" %\*\s*$'
)
ATTR_ANCHOR = r'(?m)^\s*\*\.bat text eol=crlf\s*$'
PAUSE_ANCHOR = r'(?m)^\s*pause >nul\s*$'


class Gate:
    def __init__(self, root: Path):
        self.root = root
        self.checks = 0
        self.fails = []

    def ok(self, cond: bool, label: str) -> None:
        self.checks += 1
        if not cond:
            self.fails.append(label)

    def read(self, rel: Path):
        p = self.root / rel
        return p.read_bytes() if p.exists() else None


def bat_rules(g: Gate, rel: Path, raw, strict: bool = False) -> None:
    """strict=True 额外要求零 cmd 元字符——只对本次重写的跳板启用。

    mdk/mdk-example/build.bat 原本就有括号，属另一个模块的历史写法，
    本轮只统一它的编码/换行，不擅自改它的命令逻辑。
    """
    tag = rel.as_posix()
    g.ok(raw is not None, f"{tag}: 文件存在")
    if raw is None:
        return
    g.ok(not raw.startswith(b"\xef\xbb\xbf"), f"{tag}: 无 UTF-8 BOM")
    g.ok(all(x < 128 for x in raw), f"{tag}: 纯 ASCII（无中文字节）")
    crlf = raw.count(b"\r\n")
    bare_lf = raw.count(b"\n") - crlf
    bare_cr = raw.count(b"\r") - crlf
    g.ok(raw.endswith(b"\r\n") and crlf > 0, f"{tag}: 以 CRLF 结尾且有换行")
    g.ok(bare_lf == 0, f"{tag}: 零裸 LF（实得 {bare_lf}）")
    g.ok(bare_cr == 0, f"{tag}: 零裸 CR（实得 {bare_cr}）")
    text = raw.decode("ascii", errors="replace")
    g.ok(not text.lstrip("\ufeff").startswith("chcp"), f"{tag}: 首行不是 chcp")
    g.ok("chcp 65001" not in text, f"{tag}: 不含 chcp 65001（对词法解析无效）")
    if strict:
        # cmd 元字符：& | ^ ( ) < 一律禁止；> 只允许 pause >nul 这一处合法重定向。
        for c in b"&|^()<":
            g.ok(c not in raw, f"{tag}: 不含命令元字符 {chr(c)!r}")
        g.ok(raw.count(b">") == 1 and b"pause >nul" in raw,
             f"{tag}: 唯一重定向是 pause >nul")


KNOWN_BATS = [BAT_REL, MDK_BAT_REL]


def guard_layer(root: Path, bat_files=None, check_git: bool = True) -> Gate:
    """bat_files 显式传入时用于 %TEMP% 副本（副本不是 git 仓库，C77 守卫层）。"""
    import re

    g = Gate(root)

    # 1. 版本控制内的所有 .bat 都要满足字节格式
    if bat_files is None:
        try:
            out = subprocess.run(
                ["git", "ls-files", "--", "*.bat"],
                cwd=str(root), capture_output=True, text=True, check=True,
            ).stdout
            tracked = [Path(x.strip()) for x in out.splitlines() if x.strip()]
        except Exception:
            tracked = [p for p in KNOWN_BATS if (root / p).exists()]
        if check_git:
            g.ok(len(tracked) >= 2, f"git 列出至少 2 个 .bat（实得 {len(tracked)}）")
            g.ok(BAT_REL in tracked, "run_w3c_diag.bat 已纳入版本控制")
            g.ok(MDK_BAT_REL in tracked, "mdk build.bat 已纳入版本控制")
    else:
        tracked = bat_files
    for rel in tracked:
        bat_rules(g, rel, g.read(rel), strict=(rel == BAT_REL))

    # 2. .gitattributes 锁 CRLF
    attr = g.read(ATTR_REL)
    g.ok(attr is not None, ".gitattributes 存在")
    if attr is not None:
        g.ok(bool(re.search(ATTR_ANCHOR, attr.decode("utf-8", "replace"))),
             ".gitattributes 含 *.bat text eol=crlf（行首锚定）")

    # 3. .bat 跳板接线
    bat = g.read(BAT_REL)
    if bat is not None:
        bt = bat.decode("ascii", errors="replace")
        g.ok(bool(re.search(LAUNCH_ANCHOR, bt)), ".bat 含 PowerShell 跳板行（行首锚定）")
        g.ok(bool(re.search(PAUSE_ANCHOR, bt)), ".bat 含 pause >nul（窗口不自动关）")
        g.ok(bt.startswith("@echo off\r\n"), ".bat 首行 @echo off")
        g.ok("set /p" not in bt, ".bat 不含 set /p（交互菜单已迁到 .ps1）")
        g.ok("goto MODE_" not in bt, ".bat 不含 goto 菜单标签（逻辑已迁到 .ps1）")
        g.ok("if not exist" not in bt, ".bat 不含 if (...) 块（逻辑已迁到 .ps1）")

    # 4. .ps1 编码与内容
    ps1 = g.read(PS1_REL)
    g.ok(ps1 is not None, "run_w3c_diag.ps1 存在")
    if ps1 is not None:
        g.ok(ps1.startswith(b"\xef\xbb\xbf"), "run_w3c_diag.ps1 有 UTF-8 BOM")
        body = ps1[3:] if ps1.startswith(b"\xef\xbb\xbf") else ps1
        g.ok(body.count(b"\n") - body.count(b"\r\n") == 0,
             "run_w3c_diag.ps1 零裸 LF")
        try:
            st = body.decode("utf-8")
            decoded = True
        except UnicodeDecodeError:
            st, decoded = "", False
        g.ok(decoded, "run_w3c_diag.ps1 可按 UTF-8 解码")
        for anchor in [
            r'(?m)^\s*function Show-Menu',
            r'(?m)^\s*function Invoke-Gate',
            r'(?m)^\s*function Invoke-BuiltinProbe',
            r'(?m)^\s*\$raw = Read-Host "请选择 \[2\]"',
            r'(?m)^\s*if \(\$SkipBuild\) \{ \$gateArgs \+= "-SkipBuild" \}',
            r'(?m)^\s*& \$javaExe "-Dmirage\.msr\.fakepack\.runtime=on"',
            r'(?m)^\s*& \$javacExe -cp "\$fatJar"',
            r'(?m)^\s*\$exitCode = Invoke-BuiltinProbe',
        ]:
            g.ok(bool(re.search(anchor, st)), f".ps1 守卫锚点 {anchor[:46]}")
        # 行首锚定：只禁真正的属性行，注释里提到 ValidateSet 不算（C77）。
        g.ok(not re.search(r'(?m)^\s*\[ValidateSet', st),
             ".ps1 不用 ValidateSet 属性（空默认串要留给交互菜单）")
        g.ok(not re.search(r'(?m)^\s*pause\s*$', st),
             ".ps1 不含独立 pause（避免双击停两次）")
        g.ok("1) 完整门禁" in st and "4) 退出" in st,
             ".ps1 保留中文四档菜单")
        g.ok("诊断结束" in st, ".ps1 保留中文结束横幅")

    # 5. 门禁脚本里那两个 .bat 与 .ps1 的中文界面必须真的存在
    g.ok((root / "scripts/fixtures/fakepack-w3c/W3CBuiltinProbe.java").exists(),
         "探针源码 W3CBuiltinProbe.java 存在")
    g.ok((root / "scripts/verify_fakepack_w3_c.ps1").exists(),
         "W3-C 门禁脚本存在")
    return g


def behaviour_layer(root: Path) -> Gate:
    """真的用 PowerShell 跑启动器：退出档（交互无关）+ 探针档（headless JVM）。"""
    import re

    g = Gate(root)
    ps1 = root / PS1_REL
    exe = shutil.which("powershell.exe") or "powershell.exe"

    def run(mode: str):
        return subprocess.run(
            [exe, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(ps1),
             "-Mode", mode],
            cwd=str(root), capture_output=True, timeout=300,
        )

    def decode(raw: bytes) -> str:
        """PS 5.1 管道输出走系统 ANSI(GBK)；按 GBK 优先、UTF-8 兜底解码。"""
        for enc in ("gbk", "utf-8"):
            try:
                return raw.decode(enc)
            except UnicodeDecodeError:
                continue
        return raw.decode("gbk", "replace")

    # 退出档：不碰 fat jar / 不启动任何 JVM，纯验证 PS 脚本可解析、中文可显示。
    r4 = run("4")
    out4 = decode(r4.stdout + r4.stderr)
    g.ok(r4.returncode == 0, f"-Mode 4 退出码 0（实得 {r4.returncode}）")
    for key in ["已退出", "诊断结束", "RESULT PASS"]:
        g.ok(key in out4, f"-Mode 4 输出含 {key}")
    g.ok("不是内部或外部命令" not in out4, "-Mode 4 无 cmd 命令未找到报错")
    g.ok("At line:" not in out4 and "ParserError" not in out4,
         "-Mode 4 无 PowerShell 解析错误")

    # 探针档：编译并跑 W3CBuiltinProbe，验证 -D 整体引号（C89）与 fallback 接线。
    r3 = run("3")
    out3 = decode(r3.stdout + r3.stderr)
    g.ok(r3.returncode == 0, f"-Mode 3 退出码 0（实得 {r3.returncode}）")
    g.ok("BUILTIN_ON" in out3, "-Mode 3 输出 BUILTIN_ON 场景")
    g.ok("DEFAULT" in out3, "-Mode 3 输出 DEFAULT 场景")
    g.ok("[编译并运行]" in out3, "-Mode 3 真的编译并运行了探针")
    g.ok(re.search(r'(?m)^NEO_LOAD=', out3) is not None,
         "-Mode 3 探针输出 NEO_LOAD= 行")
    g.ok("找不到或无法加载主类" not in out3 and "ClassNotFoundException" not in out3,
         "-Mode 3 无主类/类加载失败")

    # 端到端：真的让 cmd.exe 解析 .bat 一次，验证 C105 的报错形态不再出现。
    # CREATE_NO_WINDOW 保证不弹控制台、不抢焦点（AGENTS 第 2 节 (B) 类）；
    # stdin=DEVNULL 让 pause 读到 EOF 立即返回，不会挂住；-Mode 4 跳过交互菜单。
    bat = root / BAT_REL
    cmd = shutil.which("cmd.exe") or "cmd.exe"
    kwargs = {}
    if hasattr(subprocess, "CREATE_NO_WINDOW"):
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
    try:
        rb = subprocess.run(
            [cmd, "/d", "/c", str(bat), "-Mode", "4"],
            cwd=str(root), stdin=subprocess.DEVNULL,
            capture_output=True, timeout=180, **kwargs,
        )
        outb = decode(rb.stdout + rb.stderr)
        g.ok(rb.returncode == 0, f"cmd 解析 .bat 退出码 0（实得 {rb.returncode}）")
        g.ok("已退出" in outb and "诊断结束" in outb,
             "cmd 解析 .bat 后中文界面正常显示")
        for bad in ["不是内部或外部命令", "AT 命令已弃用", "绑定句柄无效",
                    "系统找不到指定的路径", "命令语法不正确"]:
            g.ok(bad not in outb, f"cmd 解析 .bat 无报错形态 {bad}")
    except FileNotFoundError:
        g.ok(False, "cmd.exe 可用（端到端验证必需）")
    return g


def make_copy(root: Path, dst: Path) -> None:
    """把门禁需要的文件复制到沙盒，保持相对路径。"""
    rels = [BAT_REL, PS1_REL, ATTR_REL, MDK_BAT_REL,
            Path("scripts/fixtures/fakepack-w3c/W3CBuiltinProbe.java"),
            Path("scripts/verify_fakepack_w3_c.ps1")]
    for rel in rels:
        src = root / rel
        if src.exists():
            target = dst / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(src.read_bytes())


def mutation_layer(root: Path, tmp: Path) -> Gate:
    """守卫层变异：落盘改坏副本，逐个判红 + 编译路径自检 + 基线回绿。"""
    import re

    g = Gate(tmp)
    base = tmp / "baseline"
    base.mkdir(parents=True, exist_ok=True)
    make_copy(root, base)
    sandbox_gate = lambda d: guard_layer(d, bat_files=KNOWN_BATS, check_git=False)
    g.ok(len(sandbox_gate(base).fails) == 0,
         "编译路径自检：未变异副本走同流程仍全绿")

    # 动手前预检全部锚点（C82）
    bat_text = (base / BAT_REL).read_bytes().decode("ascii")
    ps1_bytes = (base / PS1_REL).read_bytes()
    attr_text = (base / ATTR_REL).read_bytes().decode("utf-8")
    assert re.search(LAUNCH_ANCHOR, bat_text), "LAUNCH_ANCHOR 未命中基线"
    assert re.search(ATTR_ANCHOR, attr_text), "ATTR_ANCHOR 未命中基线"
    assert ps1_bytes.startswith(b"\xef\xbb\xbf"), "PS1 BOM 未命中基线"
    assert "@echo off\r\n" in bat_text, "首行锚点未命中基线"

    def case(name: str, mutate) -> None:
        d = tmp / ("mut_" + name)
        if d.exists():
            shutil.rmtree(d)
        make_copy(root, d)
        mutate(d)
        fails = sandbox_gate(d).fails
        g.ok(len(fails) > 0, f"变异 {name} 必须判红")
        print(f"  MUT {name}: RED={len(fails)} first={fails[0] if fails else '-'}")

    def m_bom(d):
        p = d / BAT_REL
        p.write_bytes(b"\xef\xbb\xbf" + p.read_bytes())

    def m_lf(d):
        p = d / BAT_REL
        p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n"))

    def m_chinese(d):
        p = d / BAT_REL
        raw = p.read_bytes()
        p.write_bytes(raw.replace(b"@echo off\r\n",
                                 b"@echo off\r\nrem \xe8\xaf\x8a\xe6\x96\xad\r\n", 1))

    def m_no_launch(d):
        # 精确替换整行且保留 CRLF，避免变异同时破坏换行导致「假红原因错位」。
        p = d / BAT_REL
        raw = p.read_bytes()
        line = (b'powershell.exe -NoProfile -ExecutionPolicy Bypass '
                b'-File "%~dp0run_w3c_diag.ps1" %*\r\n')
        assert line in raw, "LAUNCH line not found byte-exact"
        p.write_bytes(raw.replace(line, b"rem launcher removed\r\n", 1))

    def m_no_pause(d):
        p = d / BAT_REL
        p.write_bytes(p.read_bytes().replace(b"pause >nul", b"rem no pause"))

    def m_no_attr(d):
        p = d / ATTR_REL
        p.write_bytes(p.read_bytes().replace(b"*.bat text eol=crlf",
                                             b"# removed"))

    def m_ps1_no_bom(d):
        p = d / PS1_REL
        raw = p.read_bytes()
        p.write_bytes(raw[3:] if raw.startswith(b"\xef\xbb\xbf") else raw)

    def m_ps1_lf(d):
        p = d / PS1_REL
        p.write_bytes(p.read_bytes().replace(b"\r\n", b"\n"))

    def m_ps1_no_menu(d):
        p = d / PS1_REL
        p.write_bytes(p.read_bytes().replace("请选择 [2]".encode("utf-8"),
                                             b"Select [2]"))

    for name, fn in [
        ("bat_bom", m_bom), ("bat_lf", m_lf), ("bat_chinese", m_chinese),
        ("bat_no_launch", m_no_launch), ("bat_no_pause", m_no_pause),
        ("no_gitattributes", m_no_attr), ("ps1_no_bom", m_ps1_no_bom),
        ("ps1_lf", m_ps1_lf), ("ps1_no_menu", m_ps1_no_menu),
    ]:
        case(name, fn)

    # 字节级还原校验：源文件在整轮变异中未被触碰
    for rel in [BAT_REL, PS1_REL, ATTR_REL, MDK_BAT_REL]:
        g.ok((root / rel).read_bytes() == (base / rel).read_bytes(),
             f"源文件 {rel.as_posix()} 字节级未变")
    return g


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--behaviour", action="store_true",
                    help="额外跑 PowerShell 行为层（退出档 + headless 探针档）")
    ap.add_argument("--skip-mutation", action="store_true")
    args = ap.parse_args()

    total, failed = 0, []
    for name, fn in [
        ("守卫层", lambda: guard_layer(ROOT)),
        ("行为层", lambda: behaviour_layer(ROOT)),
        ("变异层", lambda: mutation_layer(ROOT, Path(tempfile.mkdtemp(prefix="mirage-bat-gate-")))),
    ]:
        if name == "行为层" and not args.behaviour:
            continue
        if name == "变异层" and args.skip_mutation:
            continue
        g = fn()
        total += g.checks
        failed += [f"{name}: {x}" for x in g.fails]
        print(f"[{name}] {g.checks - len(g.fails)}/{g.checks}")

    if failed:
        for f in failed:
            print("FAIL " + f)
        print(f"RESULT FAIL {total - len(failed)}/{total}")
        return 1
    print(f"RESULT PASS {total}/{total}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
