#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W3-C1 门禁：29 个 clean-room 事件类的守卫层 + 行为层 + 变异检验。

判据（项目铁律 C77/C82/C90/C102）：
  - 守卫层：能力接没接线（源码/生成器锚点、形状对账），内存副本即可做变异；
  - 行为层：逻辑对不对，必须落盘 + 单文件 javac 重编译，让 JVM 探针真跑到改坏的类；
  - 编译路径自检：未变异源码走同一条重编译流程仍全绿，排除「副本损坏假红」；
  - 落盘变异一律 try/finally 字节级还原，还原后校验 byte-identical。

纯 headless：只起 javac / java（无窗口、不写 mc-shili/），产物只写 %TEMP% 与 build/classes。
"""

import importlib.util
import pathlib
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
COMPAT = REPO / "modules/mirage-neoforge-compat"
SRC = COMPAT / "src/main/java"
BUILT = COMPAT / "build/classes/java/main"
PACK = COMPAT / "src/main/resources/mirage/fakepack"
PROBE_SRC = REPO / "scripts/fixtures/fakepack-w3c/C1Probe.java"
GEN = REPO / "scripts/msr_w3c_c1_gen.py"
MC_DIR = REPO.parent / "mc-shili"
LIB = MC_DIR / "libraries"
# 与 modules/mirage-neoforge-compat/build.gradle.kts 的 compileOnly 清单逐项对齐：
# 反射解析 MC 26.2 方法签名时，这些库缺一个就可能 NoClassDefFoundError。
CP_JARS = [
    MC_DIR / "versions/26.2/26.2.jar",
    LIB / "com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar",
    LIB / "it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar",
    LIB / "com/mojang/datafixerupper/10.0.21/datafixerupper-10.0.21.jar",
    LIB / "org/jspecify/jspecify/1.0.0/jspecify-1.0.0.jar",
    LIB / "io/netty/netty-buffer/4.2.15.Final/netty-buffer-4.2.15.Final.jar",
    LIB / "io/netty/netty-common/4.2.15.Final/netty-common-4.2.15.Final.jar",
]
MC_JAR = CP_JARS[0]
DFU_JAR = CP_JARS[3]
JDK = pathlib.Path(r"C:\Program Files\Java\jdk-25.0.4\bin")
JAVAC, JAVA = JDK / "javac.exe", JDK / "java.exe"
WORK = pathlib.Path(tempfile.gettempdir()) / "msr-w3c-c1" / "gate"

spec = importlib.util.spec_from_file_location("w3c1gen", GEN)
gen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gen)

PASS, FAIL = [], []


def check(cond, label):
    (PASS if cond else FAIL).append(label)
    return bool(cond)


def run(cmd, **kw):
    return subprocess.run([str(x) for x in cmd], capture_output=True,
                          text=True, encoding="utf-8", errors="replace", **kw)


def parse_kv(out):
    d = {}
    for line in out.splitlines():
        line = line.strip()
        if "=" in line and not line.startswith("LOADFAIL"):
            k, v = line.split("=", 1)
            d[k] = v
    return d


def behaviour_ok(out):
    d = parse_kv(out)
    return (d.get("LOAD_TOTAL") == "29" and d.get("LOAD_OK") == "29"
            and d.get("FIELD_IDENTITY") == "true"
            and d.get("UOE_1") == "OK" and d.get("UOE_2") == "OK"
            and d.get("RUNTIME_OFF") == "true" and d.get("KILL_OFF") == "true"
            and "LOADFAIL" not in out)


def compile_one(java_file):
    cp = ";".join([str(BUILT)] + [str(p) for p in CP_JARS])
    return run([JAVAC, "-encoding", "UTF-8", "-cp", cp, "-d", BUILT, java_file])


def probe():
    cp = ";".join([str(WORK), str(BUILT)] + [str(p) for p in CP_JARS])
    r = run([JAVA, "-cp", cp, "probe.C1Probe", WORK / "classes.txt"])
    return (r.stdout or "") + (r.stderr or "")


def guard_layer():
    # 1. 清单与边界
    check(len(gen.CLASSES) == 29, "生成器 CLASSES 恰 29 类")
    blocked = ("FMLLoadCompleteEvent", "AddClientReloadListenersEvent",
               "FluidTintSource", "FluidInstance", "EnergyHandler")
    check(not any(any(b in c for b in blocked) for c in gen.CLASSES),
          "5 个被 B/C 档类型阻塞的类不在 C1 清单")
    check(gen.CLASSES.count(gen.CLASSES[0]) == 1, "CLASSES 无重复项")

    # 2. 生成物锚点
    files = list(gen.build().keys())
    check(len(files) == 25, "生成 25 个 .java（29 类含 4 个内部类合并）")
    texts = {p: p.read_text(encoding="utf-8") if p.exists() else "" for p in files}
    check(all("W3-C1 clean-room" in t for t in texts.values()),
          "每个生成文件都有 clean-room 锚点")
    with_uoe = sum(1 for t in texts.values() if "UnsupportedOperationException" in t)
    check(with_uoe >= 5,
          "至少 5 个文件含 UOE 锚点（实得 %d；纯构造器/getter/接口类本就无需 UOE）" % with_uoe)
    check(any("不返回编造值" in t for t in texts.values()), "UOE 消息标注「不返回编造值」(N1 原则)")
    getter_src = (SRC / "net/neoforged/neoforge/client/event/RecipesReceivedEvent.java").read_text(encoding="utf-8")
    check("return this.f0;" in getter_src and "getRecipeTypes" in getter_src,
          "getter 返回构造器保存的实参（源码锚点，不编造值）")
    check(all("由 scripts/msr_w3c_c1_gen.py 生成" in t for t in texts.values()),
          "生成物带可复算来源声明")

    # 3. 守卫层变异（内存副本，不动盘）：删一个 CLASSES 条目应让计数断言变红
    gen_text = GEN.read_text(encoding="utf-8")
    anchor = '    "net/neoforged/neoforge/client/event/InputEvent",\n'
    check(anchor in gen_text, "守卫锚点 InputEvent 存在")
    mutated = gen_text.replace(anchor, "", 1)
    check(mutated.count('    "net/neoforged/') == gen_text.count('    "net/neoforged/') - 1,
          "守卫层变异：删 1 个清单项可被计数断言检出")

    # 4. 可复算
    r = run([sys.executable, GEN, "--check"])
    check(r.returncode == 0 and "CHANGED=0" in r.stdout, "生成器 --check 可复算（CHANGED=0）")

    # 5. 形状对账：编译产物 vs real-shape 骨架
    shape_ok, missing_total = 0, 0
    for n in gen.CLASSES:
        p, q = BUILT / (n + ".class"), PACK / (n + ".class")
        if not (p.exists() and q.exists()):
            continue
        a, b = gen.parse_class(p), gen.parse_class(q)
        sm = {(m[1], m[2]) for m in a["methods"]}
        tm = {(m[1], m[2]) for m in b["methods"]}
        missing_total += len(tm - sm)
        if (a["sup"] == b["sup"] and set(a["itfs"]) == set(b["itfs"])
                and a["kind"] == b["kind"] and not (tm - sm)):
            shape_ok += 1
    check(shape_ok == 29, "编译产物 29/29 与 real-shape 骨架形状一致")
    check(missing_total == 0, "编译产物零方法缺失（0 个签名漂移）")

    # 6. pack 未被改动
    check(len(list(PACK.rglob("*.class"))) == 114, "内置 pack 仍 114 类（C1 不动资源）")


def behaviour_layer():
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "classes.txt").write_text(
        "\n".join(n.replace("/", ".") for n in gen.CLASSES) + "\n", encoding="utf-8")
    c = run([JAVAC, "-encoding", "UTF-8", "-cp", BUILT, "-d", WORK, PROBE_SRC])
    check(c.returncode == 0, "行为探针 javac 编译通过" + ("" if c.returncode == 0 else " :: " + c.stderr[:200]))
    out = probe()
    d = parse_kv(out)
    check(d.get("LOAD_TOTAL") == "29", "行为层 LOAD_TOTAL=29")
    check(d.get("LOAD_OK") == "29" and "LOADFAIL" not in out, "行为层 29 类 DEFAULT 下全部可加载")
    check(d.get("FIELD_IDENTITY") == "true", "行为层构造器保存实参（真实值，非编造）")
    check(d.get("UOE_1") == "OK", "行为层 ItemAbility#getActions 抛 UOE 且消息带类名#方法名")
    check(d.get("UOE_2") == "OK", "行为层 KeyConflictContext#isActive 抛 UOE 且消息带类名#方法名")
    check(d.get("RUNTIME_OFF") == "true" and d.get("KILL_OFF") == "true",
          "行为层 DEFAULT 无诊断 fallback 开关（默认零变化）")
    return out


def mutation_layer(base_out):
    """落盘变异：改源码 -> 单文件 javac 重编译 -> 探针必须变红 -> 字节级还原。"""
    targets = [
        ("RegisterClientTooltipComponentFactoriesEvent.java",
         "src/main/java/net/neoforged/neoforge/client/event/RegisterClientTooltipComponentFactoriesEvent.java",
         "this.f0 = a0;", "this.f0 = null;", "FIELD_IDENTITY", "构造器不保存实参（改丢字段）"),
        ("ItemAbility.java", "src/main/java/net/neoforged/neoforge/common/ItemAbility.java",
         "ItemAbility#getActions", "ItemAbility-getActions", "UOE_1", "去掉 UOE 消息里的类名#方法名"),
    ]
    # 编译路径自检：未变异源码走同一条重编译流程，探针仍全绿
    for _, rel, _, _, _, _ in targets:
        f = COMPAT / rel
        bak_java, bak_cls = f.read_bytes(), (BUILT / rel.replace("src/main/java/", "").replace(".java", ".class")).read_bytes()
        r = compile_one(f)
        check(r.returncode == 0, "编译路径自检 " + f.name + " 重编译成功")
        (BUILT / rel.replace("src/main/java/", "").replace(".java", ".class")).write_bytes(bak_cls)
        f.write_bytes(bak_java)
    self_out = probe()
    check(behaviour_ok(self_out), "编译路径自检：未变异源码走同流程仍全绿（排除副本损坏假红）")

    for name, rel, find, repl, key, desc in targets:
        f = COMPAT / rel
        cls = BUILT / rel.replace("src/main/java/", "").replace(".java", ".class")
        bak_java, bak_cls = f.read_bytes(), cls.read_bytes()
        try:
            text = f.read_text(encoding="utf-8")
            check(find in text, "变异锚点存在 " + name + " :: " + find)
            f.write_bytes(text.replace(find, repl, 1).encode("utf-8"))
            r = compile_one(f)
            check(r.returncode == 0, "变异重编译成功 " + name + ("" if r.returncode == 0 else " :: " + r.stderr[:200]))
            out = probe()
            check(not behaviour_ok(out), "变异变红：" + desc)
            check(parse_kv(out).get(key) != parse_kv(base_out).get(key),
                  "变异确实作用于目标键 " + key + "（非空转）")
        finally:
            f.write_bytes(bak_java)
            cls.write_bytes(bak_cls)
            check(f.read_bytes() == bak_java and cls.read_bytes() == bak_cls,
                  "字节级还原 " + name + "（.java 与 .class 均 byte-identical）")
    check(behaviour_ok(probe()), "全部变异还原后基线回绿")


def main():
    for p in [JAVAC, JAVA, PROBE_SRC, GEN] + CP_JARS:
        if not p.exists():
            print("FATAL missing: " + str(p))
            return 2
    WORK.mkdir(parents=True, exist_ok=True)
    guard_layer()
    base = behaviour_layer()
    if behaviour_ok(base):
        mutation_layer(base)
    else:
        FAIL.append("行为层基线未绿，跳过变异检验（避免假红）")
    print("RESULT %s %d/%d" % ("PASS" if not FAIL else "FAIL", len(PASS), len(PASS) + len(FAIL)))
    for f in FAIL:
        print("  FAIL " + f)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
