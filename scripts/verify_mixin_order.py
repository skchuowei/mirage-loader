#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""织入优先级门禁：守卫层 + 行为层 + 变异检验（C104 全套）。

判据（项目铁律 C77/C82/C90/C102/C104）：
  - 守卫层：接线锚点（findClass 里 Mixin 行在 MG ASM 行之前、让路分支的 WARN 在位、
    MG 目标名与 MirageTransformer 双向一致、bootstrap 收集调用在位），内存文本即可断言；
  - 行为层：逻辑对不对，走编译好的探针 —— WeavePriority 判定表 + ModMixinTargets 收集
    + 真 MirageClassLoader/MirageMixinBootstrap.init 加载 net.minecraft.world.item.CreativeModeTab，
    用反射看 MG 自研 ASM 追加的无参 builder() 还在不在（在=织入了，不在=让路了）；
  - 变异层：落盘改源码 -> 单文件 javac 重编译 -> jar uf 打进 fat jar 临时副本 ->
    探针必须变红 -> try/finally 字节级还原 + 编译路径自检 + 基线回绿；
  - 守卫正则一律行首锚定 (?m)^\\s*；绝对值不作阈值。

纯 headless：只起 javac / java / jar（无窗口、不写 mc-shili/），产物只写 %TEMP%。
用法：python scripts/verify_mixin_order.py
"""

import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
LOADER_SRC = REPO / "core/mirage-loader/src/main/java/io/mirage/loader/MirageClassLoader.java"
TRANSFORMER_SRC = REPO / "core/mirage-loader/src/main/java/io/mirage/loader/MirageTransformer.java"
PRIORITY_SRC = REPO / "core/mirage-mixin/src/main/java/io/mirage/mixin/WeavePriority.java"
TARGETS_SRC = REPO / "core/mirage-mixin/src/main/java/io/mirage/mixin/ModMixinTargets.java"
BOOT_SRC = REPO / "core/mirage-mixin/src/main/java/io/mirage/mixin/MirageMixinBootstrap.java"
MIXINS_JSON = REPO / "core/mirage-mixin/src/main/resources/mirage.mixins.json"
FIXTURES = REPO / "scripts/fixtures/mixin-order"
FATJAR = REPO / "launcher/mirage-launch/build/libs/mirage-launch-0.1.0.jar"
MC_DIR = REPO.parent / "mc-shili"
MC_JAR = MC_DIR / "versions/26.2/26.2.jar"
BRIGADIER = MC_DIR / "libraries/com/mojang/brigadier/1.3.10/brigadier-1.3.10.jar"
FASTUTIL = MC_DIR / "libraries/it/unimi/dsi/fastutil/8.5.18/fastutil-8.5.18.jar"
GUAVA = MC_DIR / "libraries/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar"
FAILACC = MC_DIR / "libraries/com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar"
JDK = pathlib.Path(r"C:\Program Files\Java\jdk-25.0.4\bin")
JAVAC, JAVA, JAR = JDK / "javac.exe", JDK / "java.exe", JDK / "jar.exe"
WORK = pathlib.Path(tempfile.gettempdir()) / "mo-gate"

PASS, FAIL = [], []


def check(cond, label):
    (PASS if cond else FAIL).append(label)
    return bool(cond)


def run(cmd, **kw):
    return subprocess.run([str(x) for x in cmd], capture_output=True, **kw)


def dec(raw):
    """Java 18+ 管道默认 UTF-8；兜底 GBK（与 C105 口径互补）。"""
    for enc in ("utf-8", "gbk", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", errors="replace")


def anchored(text, pattern):
    return re.search(r"(?m)^\s*" + pattern, text) is not None


def first_line_index(text, pattern):
    """行首锚定 + 行序：返回命中行号（0 基），未命中返回 -1。"""
    m = re.search(r"(?m)^\s*" + pattern, text)
    if not m:
        return -1
    return text[: m.start()].count("\n")


# ---------------------------------------------------------------- 守卫层

def guard_layer():
    loader = LOADER_SRC.read_text(encoding="utf-8")
    prio = PRIORITY_SRC.read_text(encoding="utf-8")
    targets = TARGETS_SRC.read_text(encoding="utf-8")
    boot = BOOT_SRC.read_text(encoding="utf-8")
    transformer = TRANSFORMER_SRC.read_text(encoding="utf-8")

    # ---- findClass 的织入顺序：Mixin 先、MG 自研 ASM 后 ----
    mixin_line = first_line_index(loader, r"byte\[\] transformed = io\.mirage\.mixin\.MirageMixinBootstrap\.transform\(name, original\);")
    mg_lines = [m.start() for m in re.finditer(r"(?m)^\s*transformed = transformer\.transform\(", loader)]
    check(mixin_line >= 0, "loader: findClass 首次调用 mod Mixin 管线（原版字节入参）")
    check(len(mg_lines) == 1, f"loader: MG 自研 ASM 只在让路判定之后调用一次（实际 {len(mg_lines)} 处）")
    if mixin_line >= 0 and mg_lines:
        mg_line = loader[: mg_lines[0]].count("\n")
        check(mg_line > mixin_line,
              f"loader: Mixin 行({mixin_line + 1}) 在 MG ASM 行({mg_line + 1}) 之前 —— 织入优先级=mod 先")

    # ---- 让路判定与 WARN（禁止无声放弃）----
    check(anchored(loader, re.escape("io.mirage.mixin.WeavePriority.Decision weaving = io.mirage.mixin.WeavePriority.decide(name);")),
          "loader: findClass 调用 WeavePriority.decide(name)")
    check(anchored(loader, re.escape("if (weaving.applyMgWeaving) {")), "loader: 让路分支以 applyMgWeaving 为闸")
    check(anchored(loader, re.escape("MirageLog.MIRAGE.warn(weaving.describe());")),
          "loader: 让路分支必须打 WARN（禁止无声放弃）")
    m_else = re.search(r"(?ms)^\s*\}\s*else\s*\{(.*?)^\s*\}", loader)
    check(m_else is not None and "transformer.transform" not in m_else.group(1),
          "loader: else（让路）分支里不得再调 MG 自研 ASM")

    # ---- WeavePriority：7 个 MG 目标 + 双向一致 ----
    mg_names = re.findall(r'm\.put\("([^"]+)",', prio)
    check(len(mg_names) == 7, f"WeavePriority: MG 硬编码目标应为 7 个（实际 {len(mg_names)}）")
    tf_names = set(re.findall(r'"(net/minecraft/[^"]+|com/mojang/[^"]+)"\.equals\(cn\)', transformer))
    check(len(tf_names) == 7, f"MirageTransformer: if 链目标应为 7 个（实际 {len(tf_names)}）")
    check({n.replace(".", "/") for n in mg_names} == tf_names,
          "双向一致: WeavePriority.MG_TARGETS 与 MirageTransformer 的 if 链目标类名集合相等"
          f"（weave={sorted(mg_names)} tf={sorted(tf_names)}）")
    check(anchored(prio, re.escape("MG_TARGETS = Collections.unmodifiableMap(m);")),
          "WeavePriority: 目标表不可变")
    check(anchored(prio, re.escape("private static volatile Set<String> modMixinTargets = Set.of();")),
          "WeavePriority: mod 目标快照 volatile + 默认空集（降级=不让路）")
    check(anchored(prio, re.escape("public static Decision decide(String className, Set<String> modTargets)")),
          "WeavePriority: 纯函数重载（可离线单测）")
    check("让路：" in prio and "放弃 MG 自研织入「" in prio and "mgPurpose" in prio,
          "WeavePriority: WARN 文案含「让路」+「放弃…功能」+ 功能名")

    # ---- ModMixinTargets：MG 自有 config 排除 + 注解读取 ----
    check(anchored(targets, re.escape('public static final String MG_OWN_CONFIG = "mirage.mixins.json";')),
          "ModMixinTargets: mirage.mixins.json 常量为 MG 自有 config")
    check(anchored(targets, re.escape("if (config == null || config.isEmpty() || isMgOwnConfig(config)) {")),
          "ModMixinTargets: 收集时排除 MG 自有 config（否则自噬）")
    check('"Lorg/spongepowered/asm/mixin/Mixin;"' in targets, "ModMixinTargets: 按 @Mixin 描述符匹配")
    check('"targets".equals(elementName)' in targets and '"value".equals(elementName)' in targets,
          "ModMixinTargets: value 与 targets 两种声明都读")
    check("JsonParser.parseString" in targets, "ModMixinTargets: 用 gson 解析 config（api 已 api 暴露）")
    check("ClassReader.SKIP_CODE" in targets, "ModMixinTargets: 读注解即可，跳过方法体（省成本）")

    # ---- Bootstrap：收集调用 + 降级 ----
    check(anchored(boot, re.escape("java.util.Set<String> modTargets = ModMixinTargets.collect(mcl, registeredConfigs);")),
          "bootstrap: init 里收集 mod @Mixin 目标（用实际注册清单）")
    check(anchored(boot, re.escape("WeavePriority.setModMixinTargets(modTargets);")),
          "bootstrap: 收集结果灌入 WeavePriority")
    check(anchored(boot, re.escape("MirageLog.MIXIN.warn(\"收集 mod @Mixin 目标类失败（MG 自研织入不做让路判定，保持老行为）\", t);")),
          "bootstrap: 收集失败 WARN 降级（不阻断启动）")
    i_collect = first_line_index(boot, r"java\.util\.Set<String> modTargets =")
    i_init_done = first_line_index(boot, r"initialized = true;")
    check(i_collect >= 0 and i_init_done > i_collect,
          "bootstrap: 收集发生在 init 收尾（initialized=true）之前")
    # MG 自有 mixin 仍要注册（别把 mirage.mixins.json 当 mod config 过滤掉了）
    check(any("MirageTestMixin" in line for line in MIXINS_JSON.read_text(encoding="utf-8").splitlines()),
          "mirage.mixins.json: MG 自有 mixin 注册项仍在（排除的只是「算不算 mod 目标」）")

    # ---- fat jar ----
    check(FATJAR.exists(), "fat jar 存在（先跑 :launcher:mirage-launch:fatJar）")
    if FATJAR.exists():
        import zipfile
        with zipfile.ZipFile(FATJAR) as z:
            names = set(z.namelist())
        check("io/mirage/mixin/WeavePriority.class" in names, "fat jar: WeavePriority 在包内")
        check("io/mirage/mixin/ModMixinTargets.class" in names, "fat jar: ModMixinTargets 在包内")

    # ---- 夹具 ----
    for f in ("OrderTabMixin.java", "OrderItemMixin.java", "OrderPackMixin.java", "OrderProbeMain.java",
              "orderfixture-conflict.mixins.json", "orderfixture-control.mixins.json"):
        check((FIXTURES / f).exists(), f"夹具存在: {f}")
    check("CreativeModeTab.class" in (FIXTURES / "OrderTabMixin.java").read_text(encoding="utf-8"),
          "夹具: 冲突档以类字面量声明 CreativeModeTab")
    check('targets = "net.minecraft.world.item.Item"' in (FIXTURES / "OrderItemMixin.java").read_text(encoding="utf-8"),
          "夹具: 对照档以字符串 targets 声明非 MG 目标")


# ---------------------------------------------------------------- 行为层

def compile_fixtures():
    out = WORK / "fixtures"
    if out.exists():
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)
    srcs = [FIXTURES / f for f in ("OrderTabMixin.java", "OrderItemMixin.java",
                                   "OrderPackMixin.java", "OrderProbeMain.java")]
    cp = ";".join(str(p) for p in (FATJAR, MC_JAR, BRIGADIER, FASTUTIL))
    # -proc:none：夹具是空 mixin，不需要 Mixin 注解处理器；关掉可避免 AP 读 MC 类时的额外依赖
    r = run([JAVAC, "-proc:none", "--release", "21", "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", out] + srcs)
    for j in ("orderfixture-conflict.mixins.json", "orderfixture-control.mixins.json"):
        if r.returncode == 0:
            shutil.copy2(FIXTURES / j, out / j)
    return r.returncode == 0, out, dec(r.stderr)


def probe(args, jar=None, fixtures_out=None):
    """跑一次探针。args 是 OrderProbeMain 之后的参数。"""
    jar = jar or FATJAR
    cp = ";".join(str(p) for p in (jar, fixtures_out, GUAVA, FAILACC))
    r = run([JAVA, "-cp", cp, "orderfixture.OrderProbeMain"] + list(args))
    return dec(r.stdout) + "\n" + dec(r.stderr)


def e2e_args(config, expect_conflict, fixtures_out):
    return ["endtoend", config, "yes" if expect_conflict else "no",
            str(MC_JAR), str(BRIGADIER), str(FASTUTIL), str(fixtures_out)]


def assert_probe_green(out, prefix):
    """探针自己的断言必须全绿，且必须真的跑出框架标记（排除环境性假绿）。"""
    fails = re.findall(r"\[MO-CHECK\] FAIL (\S+)", out)
    check(not fails, f"{prefix}: 探针断言全绿（失败: {fails}）")
    check("RESULT PASS" in out, f"{prefix}: RESULT PASS")


def behaviour_layer(fixtures_out):
    # 1) 判定表（纯函数，不碰 MC）
    out = probe(["logic"], fixtures_out=fixtures_out)
    assert_probe_green(out, "行为[logic]")
    check("PASS logic.all-7-targets-consistent" in out, "行为[logic]: 7 个 MG 目标自洽（空集执行 / 自命中让路）")
    check("PASS logic.snapshot-defensive-copy" in out, "行为[logic]: 目标快照防御性拷贝")

    # 2) 目标收集（内存资源，覆盖三个数组 / 排除 MG 自有 config / 坏输入降级）
    out = probe(["collect"], fixtures_out=fixtures_out)
    assert_probe_green(out, "行为[collect]")
    check("PASS collect.self-config-ignored" in out, "行为[collect]: mirage.mixins.json 不算 mod 目标（防自噬）")
    check("PASS collect.targets-string" in out, "行为[collect]: @Mixin(targets=...) 也能收集")
    check("PASS collect.broken-json-tolerated" in out, "行为[collect]: 坏 JSON / 缺类降级不中断")

    # 3) 端到端：真 MCL + 真 Mixin init，看 CreativeModeTab 的无参 builder() 在不在
    conflict = probe(e2e_args("orderfixture-conflict.mixins.json", True, fixtures_out), fixtures_out=fixtures_out)
    assert_probe_green(conflict, "行为[冲突档]")
    check("BUILDER_0ARG=no" in conflict, "行为[冲突档]: MG 自研 ASM 让路（无参 builder() 不出现）")
    check("WARN_LET_WAY=yes" in conflict, "行为[冲突档]: 让路有 WARN（禁止无声放弃）")
    check("PASS e2e.warn-names-purpose" in conflict, "行为[冲突档]: WARN 说明了放弃的 MG 功能")

    control = probe(e2e_args("orderfixture-control.mixins.json", False, fixtures_out), fixtures_out=fixtures_out)
    assert_probe_green(control, "行为[对照档]")
    check("BUILDER_0ARG=yes" in control, "行为[对照档]: 无冲突时 MG 自研 ASM 照旧织入（零回归）")
    check("WARN_LET_WAY=no" in control, "行为[对照档]: 无冲突不得出现让路 WARN")
    return conflict, control


# ---------------------------------------------------------------- 变异层

def recompile_one(src):
    tmp = WORK / "recomp"
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True)
    cp = ";".join(str(p) for p in (FATJAR, MC_JAR))
    r = run([JAVAC, "--release", "21", "-encoding", "UTF-8", "-nowarn", "-cp", cp, "-d", tmp, src])
    if r.returncode != 0:
        return None, dec(r.stderr)
    return tmp, ""


def patch_jar(class_rel, classes_dir, jar_out):
    if jar_out.exists():
        jar_out.unlink()
    shutil.copy2(FATJAR, jar_out)
    return run([JAR, "uf", jar_out, "-C", classes_dir, class_rel]).returncode == 0


# (说明, 源文件, 原文, 变异文, 变异类, 探针参数构造器, 变异后必须消失的 token, 变异后必须出现的 token)
def mutation_layer(fixtures_out):
    def args_logic():
        return ["logic"]

    def args_collect():
        return ["collect"]

    def args_conflict():
        return e2e_args("orderfixture-conflict.mixins.json", True, fixtures_out)

    mutations = [
        ("M1-把 MG 自研 ASM 挪回 Mixin 之前（顺序锚点 + 让路行为双红）", LOADER_SRC,
         "byte[] transformed = io.mirage.mixin.MirageMixinBootstrap.transform(name, original);",
         "byte[] transformed = transformer.transform(name, original);",
         "io/mirage/loader/MirageClassLoader.class",
         args_conflict, "BUILDER_0ARG=no", "BUILDER_0ARG=yes"),
        ("M2-让路判定失效（冲突档仍织入 MG）", PRIORITY_SRC,
         "boolean hit = modTargets != null && modTargets.contains(className);",
         "boolean hit = false;",
         "io/mirage/mixin/WeavePriority.class",
         args_conflict, "PASS e2e.mg-weaving-skipped", "FAIL e2e.mg-weaving-skipped"),
        ("M3-删掉让路 WARN（无声放弃）", LOADER_SRC,
         "MirageLog.MIRAGE.warn(weaving.describe());",
         "weaving.describe();",
         "io/mirage/loader/MirageClassLoader.class",
         args_conflict, "WARN_LET_WAY=yes", "WARN_LET_WAY=no"),
        ("M4-不排除 MG 自有 config（自己跟自己冲突）", TARGETS_SRC,
         "if (config == null || config.isEmpty() || isMgOwnConfig(config)) {",
         "if (config == null || config.isEmpty()) {",
         "io/mirage/mixin/ModMixinTargets.class",
         args_collect, "PASS collect.self-config-ignored", "FAIL collect.self-config-ignored"),
        ("M5-只读 value 不读 targets（字符串目标漏收）", TARGETS_SRC,
         'if (value == null || (!"value".equals(elementName) && !"targets".equals(elementName))) {',
         'if (value == null || !"value".equals(elementName)) {',
         "io/mirage/mixin/ModMixinTargets.class",
         args_collect, "PASS collect.targets-string", "FAIL collect.targets-string"),
    ]

    for label, src, old, new, cls_rel, args_fn, must_gone, must_appear in mutations:
        text = src.read_text(encoding="utf-8")
        if old not in text:
            check(False, f"{label} :: 变异锚点缺失（源码已变？）")
            continue
        bak = src.read_bytes()
        try:
            src.write_text(text.replace(old, new, 1), encoding="utf-8")
            # 顺序锚点类变异：守卫层也得能判红（不是只有行为层能抓）
            if src == LOADER_SRC:
                mutated = src.read_text(encoding="utf-8")
                mixin_line = first_line_index(mutated, r"byte\[\] transformed = io\.mirage\.mixin\.MirageMixinBootstrap\.transform\(")
                if old.startswith("byte[] transformed") and "MirageMixinBootstrap" in old:
                    check(mixin_line < 0, f"{label} :: 变异后 Mixin 行消失（守卫必红）")
                if old.startswith("MirageLog.MIRAGE.warn"):
                    check(not anchored(mutated, re.escape("MirageLog.MIRAGE.warn(weaving.describe());")),
                          f"{label} :: 变异后 WARN 锚点消失（守卫必红）")
            tmp, err = recompile_one(src)
            if tmp is None:
                check(False, f"{label} :: 变异重编译失败 {err[:200]}")
                continue
            cls = tmp / cls_rel
            check(cls.exists(), f"{label} :: 重编译产物存在（编译路径自检）")
            jar_m = WORK / "mutant.jar"
            if not patch_jar(cls_rel, tmp, jar_m):
                check(False, f"{label} :: jar uf 打包失败")
                continue
            out = probe(args_fn(), jar=jar_m, fixtures_out=fixtures_out)
            check(must_gone not in out, f"{label} :: 变异后「{must_gone}」消失（判红）")
            check(must_appear in out, f"{label} :: 变异后「{must_appear}」出现（判红原因对应）")
            check("[MO-CHECK]" in out, f"{label} :: 探针仍输出框架标记（排除环境假红）")
        finally:
            src.write_bytes(bak)
            check(src.read_bytes() == bak, f"{label} :: 源码字节级还原")
        # 基线回绿：未变异源码走同一条重编译/打包流程必须绿
        tmp, err = recompile_one(src)
        if tmp is None:
            check(False, f"{label} :: 基线重编译失败 {err[:200]}")
            continue
        jar_b = WORK / "baseline.jar"
        patch_jar(cls_rel, tmp, jar_b)
        out = probe(args_fn(), jar=jar_b, fixtures_out=fixtures_out)
        check(must_gone in out, f"{label} :: 基线回绿（「{must_gone}」回来）")


def main():
    if not FATJAR.exists():
        print("[FATAL] fat jar 不存在：先跑 gradle :launcher:mirage-launch:fatJar")
        return 2
    for p in (MC_JAR, BRIGADIER, FASTUTIL, GUAVA, FAILACC):
        if not p.exists():
            print(f"[FATAL] 找不到依赖: {p}")
            return 2
    WORK.mkdir(parents=True, exist_ok=True)
    guard_layer()
    ok, fixtures_out, err = compile_fixtures()
    check(ok, "夹具 javac 编译通过")
    if ok:
        behaviour_layer(fixtures_out)
        mutation_layer(fixtures_out)
    else:
        print(err[:800])
    print(f"\n== verify_mixin_order: PASS {len(PASS)} / FAIL {len(FAIL)} ==")
    for f in FAIL:
        print("  FAIL:", f)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
