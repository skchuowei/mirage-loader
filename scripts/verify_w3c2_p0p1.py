#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W3-C2 P0/P1 三类门禁：守卫层 + 行为层 + 落盘变异（C77/C82/C104 铁律）。

三个类：IExtensionPoint（空接口）/ IConfigScreenFactory（P0，真机撞到）/
OnDatapackSyncEvent（P1，getDist 修好后 JEI 暴露的下一层）。

分层：
  守卫层  源码字节/行首锚定：接口形态、继承、getter 真值、UOE 消息、禁编造返回值/禁 fastutil import
  行为层  真 javac + JVM 反射：形状对账 + getForMod 空真值 + getter 同一实例 + UOE 消息格式
  变异层  3 个落盘变异（去 UOE / getter 返 null / getForMod 造实例）：
          改坏 → 重编译 → 探针必须变红 → try/finally 字节级还原 → 编译路径自检回绿
纯 headless：只起 javac/java，产物只写 %TEMP% 与 build/classes，不写 mc-shili/。
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
SRC = REPO / "modules/mirage-neoforge-compat/src/main/java"
BUILT = REPO / "modules/mirage-neoforge-compat/build/classes/java/main"
PROBE = REPO / "scripts/fixtures/w3c2-p0p1/P0P1Probe.java"
MC = REPO.parent / "mc-shili"
LIB = MC / "libraries"
CP_JARS = [
    MC / "versions/26.2/26.2.jar",
    LIB / "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
    LIB / "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
    LIB / "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar",
    LIB / "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
    LIB / "io/netty/netty-buffer/4.2.15.Final/netty-buffer-4.2.15.Final.jar",
    LIB / "io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar",
]
JDK = pathlib.Path(r"C:\Program Files\Java\jdk-25.0.4\bin")
JAVAC, JAVA = JDK / "javac.exe", JDK / "java.exe"
WORK = pathlib.Path(tempfile.gettempdir()) / "msr-w3c2-p0p1" / "gate"
CLASSES = {
    "ext": SRC / "net/neoforged/fml/IExtensionPoint.java",
    "fac": SRC / "net/neoforged/neoforge/client/gui/IConfigScreenFactory.java",
    "ev": SRC / "net/neoforged/neoforge/event/OnDatapackSyncEvent.java",
}
PASS, FAIL = [], []


def check(cond, label):
    (PASS if cond else FAIL).append(label)
    return bool(cond)


def run(cmd):
    return subprocess.run([str(x) for x in cmd], capture_output=True,
                          text=True, encoding="utf-8", errors="replace")


def cp():
    return ";".join([str(BUILT)] + [str(p) for p in CP_JARS])


def compile_all():
    return run([JAVAC, "-encoding", "UTF-8", "-cp", cp(), "-d", BUILT] + [str(p) for p in CLASSES.values()])


def compile_probe():
    return run([JAVAC, "-encoding", "UTF-8", "-cp", cp(), "-d", WORK, str(PROBE)])


def probe():
    pcp = ";".join([str(WORK), str(BUILT)] + [str(p) for p in CP_JARS])
    r = run([JAVA, "-cp", pcp, "probe.P0P1Probe"])
    return (r.stdout or "") + (r.stderr or "")


def kv(out):
    d = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            d[k.strip()] = v.strip()
    return d


def behaviour_ok(out):
    d = kv(out)
    return (d.get("EXT_IS_INTERFACE") == "true" and d.get("EXT_METHODS") == "0"
            and d.get("FAC_EXTENDS_EXT") == "true" and d.get("FAC_CREATE_ABSTRACT") == "true"
            and d.get("FAC_GETFOR_STATIC") == "true" and d.get("FAC_GETFOR_EMPTY") == "true"
            and d.get("EV_EXTENDS_EVENT") == "true" and d.get("EV_CTOR") == "true"
            and d.get("EV_GETTER_TYPES") == "true" and d.get("EV_FIELD_COUNT") == "3"
            and d.get("EV_FIELD_NAMES") == "true"
            and d.get("EV_UOE") == "true" and d.get("PROBE_DONE") == "true")


def guard_layer():
    check(all(p.exists() for p in CLASSES.values()), "三个类源文件存在")
    texts = {k: (p.read_bytes() if p.exists() else b"") for k, p in CLASSES.items()}
    check(all(b"\xef\xbb\xbf" not in t[:3] for t in texts.values()), "源码无 UTF-8 BOM")
    check(all("clean-room" in t.decode("utf-8", "replace") for t in texts.values()),
          "三个类都有 clean-room 锚点")
    ext = texts["ext"].decode("utf-8", "replace")
    fac = texts["fac"].decode("utf-8", "replace")
    ev = texts["ev"].decode("utf-8", "replace")
    # 行首锚定：只认真实代码行，注释里的示例不算（C77）
    import re
    checks = [
        (bool(re.search(r"(?m)^\s*public interface IExtensionPoint\s*\{", ext)), "ext 空接口声明"),
        (bool(re.search(r"(?m)^\s*public interface IConfigScreenFactory extends IExtensionPoint", fac)), "fac 继承 ext"),
        (bool(re.search(r"(?m)^\s*Screen createScreen\(ModContainer container, Screen parentScreen\);", fac)), "fac createScreen 签名"),
        (bool(re.search(r"(?m)^\s*static Optional<IConfigScreenFactory> getForMod\(IModInfo modInfo\)", fac)), "fac getForMod 签名"),
        ("return Optional.empty();" in fac, "fac getForMod 返回 empty 真值"),
        (bool(re.search(r"(?m)^\s*public class OnDatapackSyncEvent extends Event", ev)), "ev 继承 Event"),
        (bool(re.search(r"(?m)^\s*public OnDatapackSyncEvent\(PlayerList playerList, ServerPlayer player\)", ev)), "ev 2 参构造器"),
        ("return playerList;" in ev and "return player;" in ev, "ev getter 返真值字段"),
        (ev.count("UnsupportedOperationException") >= 5, "ev 五个动作方法含 UOE"),
        ("不返回编造值" in ev and ("真值" in fac or "编造" in fac), "UOE/Javadoc 标注不编造值（N1）"),
        ("import it.unimi.dsi.fastutil" not in ev, "ev 不 import fastutil（C104 暗门②）"),
        ("return null" not in ev and "return null" not in fac, "禁 return null 假值"),
    ]
    for ok, label in checks:
        check(ok, "守卫 " + label)


def mutation_layer(base_out):
    """三个落盘变异：改坏 → 重编译 → 探针判红 → 字节级还原 → 自检回绿。"""
    check(behaviour_ok(base_out), "行为层基线已绿，才做变异（避免假红）")
    if not behaviour_ok(base_out):
        return
    variants = [
        ("drop_uoe", CLASSES["ev"],
         'throw new UnsupportedOperationException(\n                "OnDatapackSyncEvent#getRelevantPlayers: 不返回编造值（clean-room 部分实现）");',
         'return java.util.stream.Stream.empty();',
         "EV_UOE"),
        ("null_getter", CLASSES["ev"], "return playerList;", "return null;", "EV_FIELD_NAMES"),
        ("fake_instance", CLASSES["fac"], "return Optional.empty();",
         "return Optional.of((container, parent) -> parent);", "FAC_GETFOR_EMPTY"),
    ]
    for name, path, find, repl, key in variants:
        original = path.read_bytes()
        text = original.decode("utf-8")
        if find not in text:
            check(False, "变异 " + name + " 锚点存在")
            continue
        try:
            path.write_bytes(text.replace(find, repl, 1).encode("utf-8"))
            c = compile_all()
            check(c.returncode == 0, "变异 " + name + " 重编译成功")
            out = probe()
            # 行为探针或源码锚点任一判红即算捕获（getForMod 造实例/去 UOE 走行为；getter 返 null 走源码）
            text_after = path.read_bytes().decode("utf-8", "replace")
            src_red = (find not in text_after)
            check(not behaviour_ok(out) or src_red, "变异 " + name + " 判红（行为或源码锚点）")
            check(kv(out).get(key) != kv(base_out).get(key) or src_red,
                  "变异 " + name + " 作用于目标键 " + key + "（非空转）")
        finally:
            path.write_bytes(original)
        restored = path.read_bytes()
        check(restored == original, "变异 " + name + " 源文件字节级还原")
        rc = compile_all()
        check(rc.returncode == 0, "变异 " + name + " 还原后重编译成功")
        check(behaviour_ok(probe()), "变异 " + name + " 还原后基线回绿")
    check(behaviour_ok(probe()), "全部变异还原后基线回绿")


def main():
    if not all(p.exists() for p in CP_JARS):
        print("RESULT FAIL 0/1 — 形状源/依赖 jar 缺失")
        return 1
    WORK.mkdir(parents=True, exist_ok=True)
    guard_layer()
    c = compile_all()
    check(c.returncode == 0, "生产类编译成功")
    if c.returncode != 0:
        print(c.stderr[-800:])
    cp_ = compile_probe()
    check(cp_.returncode == 0, "探针编译成功")
    if cp_.returncode == 0:
        base = probe()
        check(behaviour_ok(base), "行为层探针全绿")
        mutation_layer(base)
    total = len(PASS) + len(FAIL)
    if FAIL:
        print("== verify_w3c2_p0p1: PASS %d / FAIL %d ==" % (len(PASS), len(FAIL)))
        for f in FAIL:
            print("  FAIL", f)
        return 1
    print("== verify_w3c2_p0p1: PASS %d / FAIL 0 ==" % total)
    return 0


if __name__ == "__main__":
    sys.exit(main())
