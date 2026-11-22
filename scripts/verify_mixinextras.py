#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MixinExtras 集成门禁：守卫层 + 行为层 + 变异检验（C104 全套）。

判据（项目铁律 C77/C82/C90/C102/C104）：
  - 守卫层：接线锚点（toml/build/init 时序/后处理正则），内存副本即可做断言；
  - 行为层：逻辑对不对，走真实 MirageClassLoader + MirageMixinBootstrap.init 生产链路，
    JVM 探针真跑到织入结果（标记字符串 + KEY=VALUE）；
  - 变异层：落盘改源码 -> 单文件 javac 重编译 -> jar uf 打进 fat jar 临时副本 ->
    探针必须变红 -> try/finally 字节级还原 + 编译路径自检 + 基线回绿；
  - 守卫正则一律行首锚定 (?m)^\\s*；绝对值不作阈值。

纯 headless：只起 javac / java / jar（无窗口、不写 mc-shili/），产物只写 %TEMP%。
用法：python scripts/verify_mixinextras.py
"""

import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

REPO = pathlib.Path(__file__).resolve().parents[1]
TOML = REPO / "gradle/libs.versions.toml"
MIXIN_BUILD = REPO / "core/mirage-mixin/build.gradle.kts"
BOOT_SRC = REPO / "core/mirage-mixin/src/main/java/io/mirage/mixin/MirageMixinBootstrap.java"
SVC_SRC = REPO / "core/mirage-mixin/src/main/java/io/mirage/mixin/MirageMixinService.java"
FIXTURES = REPO / "scripts/fixtures/mixinextras"
FATJAR = REPO / "launcher/mirage-launch/build/libs/mirage-launch-0.1.0.jar"
MC_DIR = REPO.parent / "mc-shili"
GUAVA = MC_DIR / "libraries/com/google/guava/guava/33.6.0-jre/guava-33.6.0-jre.jar"
FAILACC = MC_DIR / "libraries/com/google/guava/failureaccess/1.0.3/failureaccess-1.0.3.jar"
JDK = pathlib.Path(r"C:\Program Files\Java\jdk-25.0.4\bin")
JAVAC, JAVA, JAR = JDK / "javac.exe", JDK / "java.exe", JDK / "jar.exe"
WORK = pathlib.Path(tempfile.gettempdir()) / "mex-gate"

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


# ---------------------------------------------------------------- 守卫层

def guard_layer():
    toml = TOML.read_text(encoding="utf-8")
    check(anchored(toml, re.escape('mixinextras = "0.5.5"')), "toml: mixinextras = 0.5.5 版本声明")
    check(anchored(toml, re.escape("mixinextras-common = { module = \"io.github.llamalad7:mixinextras-common\"")),
          "toml: mixinextras-common 坐标（不 relocate）")

    build = MIXIN_BUILD.read_text(encoding="utf-8")
    check(anchored(build, re.escape("implementation(libs.mixinextras.common)")), "build: mirage-mixin implementation 依赖")

    boot = BOOT_SRC.read_text(encoding="utf-8")
    m_init = re.search(r"(?m)^\s*com\.llamalad7\.mixinextras\.MixinExtrasBootstrap\.init\(\);", boot)
    m_active = re.search(r"(?m)^\s*env\.setActiveTransformer\(transformer\);", boot)
    check(m_init is not None, "bootstrap: MixinExtrasBootstrap.init() 调用行存在")
    check(m_active is not None, "bootstrap: setActiveTransformer 行存在")
    if m_init and m_active:
        check(m_init.start() > m_active.start(),
              "bootstrap: init 调用在 setActiveTransformer 之后（时序硬约束，早了会 NPE）")
    check(re.search(r'(?m)^\s*.*MixinExtrasBootstrap\.getVersion\(\).*已初始化', boot) is not None,
          "bootstrap: 成功日志带版本号锚点")
    check("catch (Throwable t)" in boot and "初始化失败" in boot, "bootstrap: init 失败 warn 不阻断")

    svc = SVC_SRC.read_text(encoding="utf-8")
    check(not re.search(r'replaceAll\(",.*mixinextras', svc), "service: 不再剥离 mixinextras 键")
    check(re.search(r'(?m)^\s*if \(\(name\.endsWith\("\.mixin\.json"\) \|\| name\.endsWith\("\.mixins\.json"\)\)', svc) is not None,
          "service: 后缀匹配单数+复数双覆盖")
    check('"mirage.mixins.json"' in svc, "service: mirage.mixins.json 豁免仍在")
    check(anchored(svc, r'private static void checkMixinExtrasMinVersion'), "service: minVersion 校验方法存在")
    check("要求 mixinextras >= " in svc, "service: 致命报错含要求版本文案")
    check(anchored(svc, re.escape("json = json.replaceAll(\"\\\"compatibilityLevel\\\"")), "service: compatibilityLevel 剥离保留")

    check(FATJAR.exists(), "fat jar 存在（先跑 :launcher:mirage-launch:fatJar）")
    if FATJAR.exists():
        import zipfile
        with zipfile.ZipFile(FATJAR) as z:
            names = set(z.namelist())
        check("com/llamalad7/mixinextras/MixinExtrasBootstrap.class" in names, "fat jar: MixinExtrasBootstrap 在包内")
        check("com/llamalad7/mixinextras/injector/wrapoperation/WrapOperation.class" in names, "fat jar: WrapOperation 在包内")
        check("com/llamalad7/mixinextras/injector/ModifyReturnValue.class" in names, "fat jar: ModifyReturnValue 在包内")

    for f in ("MexTarget.java", "MexReturnMixin.java", "MexWrapMixin.java", "MexProbeMain.java",
              "mexfixture.mixins.json", "mexfixture-badmin.mixins.json"):
        check((FIXTURES / f).exists(), f"夹具存在: {f}")
    ret = (FIXTURES / "MexReturnMixin.java").read_text(encoding="utf-8")
    check("ModifyReturnValue fired original=" in ret, "夹具: @ModifyReturnValue 标记字符串")
    wrap = (FIXTURES / "MexWrapMixin.java").read_text(encoding="utf-8")
    check("WrapOperation fired x=" in wrap, "夹具: @WrapOperation 标记字符串")
    cfg = (FIXTURES / "mexfixture.mixins.json").read_text(encoding="utf-8")
    check('"mixinextras"' in cfg and '"minVersion": "0.5.0"' in cfg, "夹具: 正向 config 带 mixinextras.minVersion")
    check('"compatibilityLevel": "JAVA_21"' in cfg and "mexfixture.mixins.json".endswith(".mixins.json"),
          "夹具: 复数后缀 + compatibilityLevel（剥离回归锚）")
    bad = (FIXTURES / "mexfixture-badmin.mixins.json").read_text(encoding="utf-8")
    check('"9.9.9"' in bad, "夹具: 负向 config minVersion 9.9.9")


# ---------------------------------------------------------------- 行为层

def compile_fixtures():
    out = WORK / "fixtures"
    out.mkdir(parents=True, exist_ok=True)
    srcs = [FIXTURES / f for f in ("MexTarget.java", "MexReturnMixin.java", "MexWrapMixin.java", "MexProbeMain.java")]
    r = run([JAVAC, "--release", "21", "-encoding", "UTF-8", "-cp", FATJAR, "-d", out] + srcs)
    # mixin config json 必须和编译产物同目录（探针按资源名从 fixtures URL 读取）
    for j in ("mexfixture.mixins.json", "mexfixture-badmin.mixins.json"):
        shutil.copy2(FIXTURES / j, out / j)
    return r.returncode == 0, out


def probe(config, jar, fixtures_out):
    cp = ";".join([str(jar), str(fixtures_out), str(GUAVA), str(FAILACC)])
    r = run([JAVA, "-cp", cp, "mexfixture.MexProbeMain", config, str(jar), str(fixtures_out)])
    return dec(r.stdout) + "\n" + dec(r.stderr)


def behaviour_layer(fixtures_out):
    out = probe("mexfixture.mixins.json", FATJAR, fixtures_out)
    check("[MEX-PROBE] INIT_OK" in out, "行为: INIT_OK（含 MixinExtras 初始化）")
    check("ModifyReturnValue fired original=1" in out, "行为: @ModifyReturnValue 真实织入触发")
    check("WrapOperation fired x=-7" in out, "行为: @WrapOperation 真实织入触发")
    check("value()=42 calc(-7)=107" in out, "行为: 织入后返回值被改写 42/107")
    check("RESULT PASS" in out, "行为: RESULT PASS")
    check("要求 mixinextras >= 0.5.0" in out and "满足" in out, "行为: minVersion 满足时 INFO 留痕")
    check("MixinInitialisationError" not in out, "行为: 复数后缀 config 的 compatibilityLevel 已被剥离（否则炸）")

    out2 = probe("mexfixture-badmin.mixins.json", FATJAR, fixtures_out)
    check("MINVERSION_FATAL=yes" in out2, "行为: minVersion 9.9.9 不满足 → 启动致命被探针捕获")
    check("9.9.9" in out2 and "mixinextras" in out2, "行为: 致命信息含要求版本与 mixinextras 字样")
    return out, out2


# ---------------------------------------------------------------- 变异层

def recompile_one(src):
    tmp = WORK / "recomp"
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True)
    r = run([JAVAC, "--release", "21", "-encoding", "UTF-8", "-cp", FATJAR, "-d", tmp, src])
    if r.returncode != 0:
        return None, dec(r.stderr)
    return tmp, ""


def patch_jar(class_rel, classes_dir, jar_out):
    if jar_out.exists():
        jar_out.unlink()
    shutil.copy2(FATJAR, jar_out)
    r = run([JAR, "uf", jar_out, "-C", classes_dir, class_rel])
    return r.returncode == 0


def mutation_layer(fixtures_out):
    mutations = [
        ("M1-去掉 init 调用（行为红：织入消失）", BOOT_SRC,
         "com.llamalad7.mixinextras.MixinExtrasBootstrap.init();",
         "if (false) com.llamalad7.mixinextras.MixinExtrasBootstrap.init();",
         "io/mirage/mixin/MirageMixinBootstrap.class",
         "mexfixture.mixins.json", "RESULT PASS"),
        ("M2-关掉 minVersion 校验（负向红：不再致命）", SVC_SRC,
         "if (versionLess(bundled, required)) {",
         "if (false) {",
         "io/mirage/mixin/MirageMixinService.class",
         "mexfixture-badmin.mixins.json", "MINVERSION_FATAL=yes"),
        ("M3-后缀退回仅单数（复数 config 红：compatibilityLevel 炸）", SVC_SRC,
         'name.endsWith(".mixins.json")',
         'name.endsWith(".mutant-none")',
         "io/mirage/mixin/MirageMixinService.class",
         "mexfixture.mixins.json", "RESULT PASS"),
    ]
    for label, src, old, new, cls_rel, cfg, must_disappear in mutations:
        text = src.read_text(encoding="utf-8")
        if old not in text:
            check(False, f"{label} :: 变异锚点缺失（源码已变？）")
            continue
        bak = src.read_bytes()
        try:
            src.write_text(text.replace(old, new, 1), encoding="utf-8")
            tmp, err = recompile_one(src)
            if tmp is None:
                check(False, f"{label} :: 变异重编译失败 {err[:150]}")
                continue
            # 编译路径自检：产物存在且确实是本类
            cls = tmp / cls_rel
            check(cls.exists(), f"{label} :: 重编译产物存在")
            jar_m = WORK / "mutant.jar"
            if not patch_jar(cls_rel, tmp, jar_m):
                check(False, f"{label} :: jar uf 打包失败")
                continue
            out = probe(cfg, jar_m, fixtures_out)
            check(must_disappear not in out,
                  f"{label} :: 变异后「{must_disappear}」消失（判红）" )
            # 判红原因对应：变异场景必须还能跑出探针框架输出（不是环境性崩溃）
            check("[MEX-PROBE]" in out, f"{label} :: 探针仍输出框架标记（排除环境假红）")
        finally:
            src.write_bytes(bak)
            check(src.read_bytes() == bak, f"{label} :: 源码字节级还原")
        # 编译路径自检 + 基线回绿：未变异源码走同一条重编译/打包流程仍绿
        tmp, err = recompile_one(src)
        if tmp is None:
            check(False, f"{label} :: 基线重编译失败 {err[:150]}")
            continue
        jar_b = WORK / "baseline.jar"
        patch_jar(cls_rel, tmp, jar_b)
        out = probe(cfg, jar_b, fixtures_out)
        check(must_disappear in out, f"{label} :: 基线回绿（「{must_disappear}」回来）")


def main():
    if not FATJAR.exists():
        print("[FATAL] fat jar 不存在：先跑 gradle :launcher:mirage-launch:fatJar")
        return 2
    if not (GUAVA.exists() and FAILACC.exists()):
        print("[FATAL] 找不到 guava/failureaccess（mc-shili/libraries）")
        return 2
    WORK.mkdir(parents=True, exist_ok=True)
    guard_layer()
    ok, fixtures_out = compile_fixtures()
    check(ok, "夹具 javac 编译通过")
    if ok:
        behaviour_layer(fixtures_out)
        mutation_layer(fixtures_out)
    print(f"\n== verify_mixinextras: PASS {len(PASS)} / FAIL {len(FAIL)} ==")
    for f in FAIL:
        print("  FAIL:", f)
    return 0 if not FAIL else 1


if __name__ == "__main__":
    sys.exit(main())
