#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""W3-C1：把内置 pack 的 real-shape 骨架转成 compat 源码（clean-room 部分实现）。

输入：modules/mirage-neoforge-compat/src/main/resources/mirage/fakepack/**/*.class
输出：modules/mirage-neoforge-compat/src/main/java/net/neoforged/**/*.java

只处理 CLASSES 里列出的「自洽」类：它们的 super / interfaces / 方法描述符所引用的
net.neoforged.* 类型全部落在「本清单自身」或「compat 已有源码」里，不依赖 B/C 档类型。
含 $ 的内部类合并进外部类 .java，保证二进制名与反射语义与真实 API 一致。

生成规则（N1 既有原则）：
  - 继承形状、方法签名、enum 常量一律取自 real-shape 骨架，不手写猜签名；
  - 带参构造器保存实参，0 参 getter 返回真实值（不是编造值）；
  - 其余方法抛 UnsupportedOperationException，消息带「类名#方法名」；
  - 接口方法只声明；enum 自动补齐所实现接口的抽象方法。

用法：
  python scripts/msr_w3c_c1_gen.py            # 生成（已存在且一致则跳过）
  python scripts/msr_w3c_c1_gen.py --check    # 只比对，不写盘；不一致返回 1
"""

import argparse
import pathlib
import struct
import sys

REPO = pathlib.Path(__file__).resolve().parents[1]
PACK = REPO / "modules/mirage-neoforge-compat/src/main/resources/mirage/fakepack"
OUT = REPO / "modules/mirage-neoforge-compat/src/main/java"

# W3-C1 自洽集合（29 类）。来源：docs/plans/2026-11-14_MSR-FakePack-W3-完整路线C立项.md
# 的 A 档 34 类，剔除 5 个被 B/C 档类型阻塞的（FMLLoadCompleteEvent /
# AddClientReloadListenersEvent / FluidTintSource / FluidInstance / EnergyHandler）。
CLASSES = [
    "net/neoforged/neoforge/client/ItemDecoratorHandler",
    "net/neoforged/neoforge/client/event/ClientPlayerNetworkEvent",
    "net/neoforged/neoforge/client/event/ClientPlayerNetworkEvent$LoggingIn",
    "net/neoforged/neoforge/client/event/ClientPlayerNetworkEvent$LoggingOut",
    "net/neoforged/neoforge/client/event/ClientTickEvent",
    "net/neoforged/neoforge/client/event/ClientTickEvent$Post",
    "net/neoforged/neoforge/client/event/CustomizeGuiOverlayEvent",
    "net/neoforged/neoforge/client/event/InputEvent",
    "net/neoforged/neoforge/client/event/RecipesReceivedEvent",
    "net/neoforged/neoforge/client/event/RegisterClientCommandsEvent",
    "net/neoforged/neoforge/client/event/RegisterClientTooltipComponentFactoriesEvent",
    "net/neoforged/neoforge/client/event/RegisterKeyMappingsEvent",
    "net/neoforged/neoforge/client/event/RenderGuiEvent",
    "net/neoforged/neoforge/client/event/RenderGuiEvent$Post",
    "net/neoforged/neoforge/client/event/lifecycle/ClientLifecycleEvent",
    "net/neoforged/neoforge/client/event/lifecycle/ClientStoppingEvent",
    "net/neoforged/neoforge/client/gui/GuiLayer",
    "net/neoforged/neoforge/client/settings/IKeyConflictContext",
    "net/neoforged/neoforge/client/settings/KeyConflictContext",
    "net/neoforged/neoforge/common/ItemAbility",
    "net/neoforged/neoforge/common/TranslatableEnum",
    "net/neoforged/neoforge/common/brewing/IBrewingRecipe",
    "net/neoforged/neoforge/entity/PartEntity",
    "net/neoforged/neoforge/event/server/ServerAboutToStartEvent",
    "net/neoforged/neoforge/event/server/ServerLifecycleEvent",
    "net/neoforged/neoforge/event/server/ServerStartedEvent",
    "net/neoforged/neoforge/network/registration/HandlerThread",
    "net/neoforged/neoforge/transfer/resource/RegisteredResource",
    "net/neoforged/neoforge/transfer/resource/Resource",
]

PRIM = {"B": "byte", "C": "char", "D": "double", "F": "float", "I": "int",
        "J": "long", "S": "short", "Z": "boolean", "V": "void"}
ACC_PUBLIC, ACC_STATIC, ACC_FINAL, ACC_INTERFACE = 0x0001, 0x0008, 0x0010, 0x0200
ACC_ABSTRACT, ACC_ANNOTATION, ACC_ENUM = 0x0400, 0x2000, 0x4000


def parse_class(path):
    b = path.read_bytes()
    off = 8
    u2 = lambda o: struct.unpack_from(">H", b, o)[0]
    u4 = lambda o: struct.unpack_from(">I", b, o)[0]
    n = u2(off)
    off += 2
    cp = {}
    i = 1
    while i < n:
        t = b[off]
        off += 1
        if t == 1:
            ln = u2(off)
            off += 2
            cp[i] = ("utf", b[off:off + ln].decode("utf-8", "replace"))
            off += ln
        elif t in (7, 8, 16, 19, 20):
            cp[i] = ("ref", u2(off))
            off += 2
        elif t in (9, 10, 11, 12, 17, 18):
            cp[i] = ("ref2", u2(off), u2(off + 2))
            off += 4
        elif t in (3, 4):
            off += 4
        elif t in (5, 6):
            off += 8
            i += 1
        elif t == 15:
            off += 3
        else:
            raise ValueError("bad constant tag %d in %s" % (t, path))
        i += 1

    def s(idx):
        e = cp.get(idx)
        if e and e[0] == "utf":
            return e[1]
        if e and e[0] == "ref":
            return s(e[1])
        return "?"

    def skip_attrs(o):
        k = u2(o)
        o += 2
        for _ in range(k):
            o += 6 + u4(o + 2)
        return o

    acc, this, sup = u2(off), u2(off + 2), u2(off + 4)
    ni = u2(off + 6)
    itfs = [s(u2(off + 8 + 2 * k)) for k in range(ni)]
    off += 8 + 2 * ni
    nf = u2(off)
    off += 2
    fields = []
    for _ in range(nf):
        fa, fn, fd = u2(off), s(u2(off + 2)), s(u2(off + 4))
        fields.append((fa, fn, fd))
        off = skip_attrs(off + 6)
    nm = u2(off)
    off += 2
    methods = []
    for _ in range(nm):
        ma, mn, md = u2(off), s(u2(off + 2)), s(u2(off + 4))
        methods.append((ma, mn, md))
        off = skip_attrs(off + 6)
    if acc & ACC_INTERFACE and acc & ACC_ANNOTATION:
        kind = "annotation"
    elif acc & ACC_INTERFACE:
        kind = "interface"
    elif acc & ACC_ENUM:
        kind = "enum"
    elif acc & ACC_ABSTRACT:
        kind = "abstract"
    else:
        kind = "class"
    return dict(name=s(this), acc=acc, kind=kind, sup=s(sup), itfs=itfs,
                fields=fields, methods=methods)


def one_type(s, i):
    arr = 0
    while i < len(s) and s[i] == "[":
        arr += 1
        i += 1
    if s[i] == "L":
        j = s.index(";", i)
        tok, i = s[i:j + 1], j + 1
    else:
        tok, i = s[i], i + 1
    return "[" * arr + tok, i


def jtype(tok):
    arr = 0
    while tok.startswith("["):
        arr += 1
        tok = tok[1:]
    if tok.startswith("L") and tok.endswith(";"):
        t = tok[1:-1].replace("/", ".").replace("$", ".")
    elif tok in PRIM:
        t = PRIM[tok]
    else:
        raise ValueError("bad descriptor token " + tok)
    return t + "[]" * arr


def parse_method(desc):
    end = desc.index(")")
    s, params = desc[1:end], []
    i = 0
    while i < len(s):
        tok, i = one_type(s, i)
        params.append(jtype(tok))
    return params, jtype(desc[end + 1:])


def jname(slash):
    return slash.replace("/", ".").replace("$", ".")


def simple(slash):
    leaf = slash.rsplit("/", 1)[-1]
    return leaf.split("$")[-1]


def uoe(cls_name, method_name, indent):
    return ('%sthrow new UnsupportedOperationException("%s#%s 未实现'
            '（W3-C1 clean-room 部分实现，不返回编造值）");\n'
            % (indent, cls_name, method_name))


def super_call(sup, params, by_name):
    s = by_name.get(sup)
    if s is None:
        return ""  # java.lang.Object / compat 的 Event：隐式 super()
    ctors = [parse_method(m[2])[0] for m in s["methods"] if m[1] == "<init>"]
    if len(params) > 0 and any(len(p) == len(params) for p in ctors):
        return "super(%s);" % ", ".join("a%d" % i for i in range(len(params)))
    return ""  # 超类有无参构造（stub 生成器保证），隐式 super()


def emit_methods(c, by_name, indent):
    """生成方法区文本。返回 (文本, 字段类型表)。"""
    kind = c["kind"]
    name = c["name"]
    sname = simple(name)
    lines = []
    # 字段：以「参数最多的构造器」为准，统一声明为 private（非 final，兜底无参构造可留空）
    ctors = [(parse_method(m[2])[0], m[2]) for m in c["methods"] if m[1] == "<init>"]
    max_params = max((len(p) for p, _ in ctors), default=0)
    field_types = []
    if kind in ("class", "abstract") and max_params:
        field_types = max(ctors, key=lambda x: len(x[0]))[0]
        for i, t in enumerate(field_types):
            lines.append("%sprivate %s f%d;\n" % (indent, t, i))
        lines.append("\n")
    # 静态字段（enum 常量交给 enum 声明；$VALUES 由编译器生成）
    for fa, fn, fd in c["fields"]:
        if kind == "enum":
            continue
        if not (fa & ACC_STATIC):
            continue
        if fn == "$VALUES":
            continue
        mod = "public static %s" % ("final " if fa & ACC_FINAL else "")
        lines.append("%s%s %s %s;\n" % (indent, mod, jtype(fd), fn))
    if any(ln.startswith(indent + "public static") for ln in lines):
        lines.append("\n")

    seen = set()
    for ma, mn, md in c["methods"]:
        if mn in ("<clinit>",):
            continue
        if kind == "enum" and mn in ("<init>", "values", "valueOf"):
            continue
        params, ret = parse_method(md)
        key = (mn, md)
        if key in seen:
            continue
        seen.add(key)
        static = bool(ma & ACC_STATIC)
        sig = "%s %s(%s)" % (ret, mn, ", ".join("%s a%d" % (t, i) for i, t in enumerate(params)))
        if mn == "<init>":
            sig = "%s(%s)" % (sname, ", ".join("%s a%d" % (t, i) for i, t in enumerate(params)))
        decl = "%spublic %s%s" % (indent, "static " if static else "", sig)
        if kind == "interface":
            lines.append("%s;\n" % decl.rstrip())
            continue
        body = []
        if mn == "<init>":
            sc = super_call(c["sup"], params, by_name)
            if sc:
                body.append(indent + "    " + sc + "\n")
            for i in range(min(len(params), len(field_types))):
                body.append("%s    this.f%d = a%d;\n" % (indent, i, i))
        elif mn == "toString" and ret in ("java.lang.String", "String") and not params:
            body.append('%s    return "%s";\n' % (indent, sname))
        elif (not static and ret != "void" and not params
              and mn not in ("hashCode",)):
            hit = next((i for i, t in enumerate(field_types) if t == ret), None)
            if hit is not None:
                body.append("%s    return this.f%d;\n" % (indent, hit))
            else:
                body.append(uoe(sname, mn, indent + "    "))
        else:
            body.append(uoe(sname, mn, indent + "    "))
        lines.append("%s {\n%s%s}\n" % (decl, "".join(body), indent))
        lines.append("\n")
    # 具体类 / enum 实现接口时，补齐接口里声明但骨架没抄到的抽象方法
    if kind in ("class", "abstract", "enum"):
        for itf in c["itfs"]:
            src = by_name.get(itf)
            if not src or src["kind"] != "interface":
                continue
            for ma, mn, md in src["methods"]:
                if mn.startswith("<"):
                    continue
                if any(m[1] == mn and m[2] == md for m in c["methods"]):
                    continue
                params, ret = parse_method(md)
                lines.append("%spublic %s %s(%s) {\n" % (
                    indent, ret, mn, ", ".join("%s a%d" % (t, i) for i, t in enumerate(params))))
                lines.append(uoe(sname, mn, indent + "    "))
                lines.append("%s}\n\n" % indent)
    return "".join(lines), field_types


def emit_class(c, by_name, indent, nested=False):
    sname = simple(c["name"])
    kind = c["kind"]
    parts = []
    parts.append("%s/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/\n" % indent)
    mods = "public " + ("static " if nested else "")
    if kind == "interface":
        head = "%s%sinterface %s" % (indent, mods, sname)
        if c["itfs"]:
            head += " extends " + ", ".join(jname(x) for x in c["itfs"])
    elif kind == "enum":
        head = "%s%senum %s" % (indent, mods, sname)
        if c["itfs"]:
            head += " implements " + ", ".join(jname(x) for x in c["itfs"])
    else:
        if kind == "abstract":
            mods += "abstract "
        head = "%s%sclass %s" % (indent, mods, sname)
        if c["sup"] and c["sup"] not in ("java/lang/Object", "java/lang/Enum"):
            head += " extends " + jname(c["sup"])
        if c["itfs"]:
            head += " implements " + ", ".join(jname(x) for x in c["itfs"])
    parts.append(head + " {\n\n")
    if kind == "enum":
        consts = [fn for fa, fn, fd in c["fields"] if fa & ACC_ENUM and fn != "$VALUES"]
        parts.append(indent + "    " + ", ".join(consts) + ";\n\n")
    body, _ = emit_methods(c, by_name, indent + "    ")
    parts.append(body)
    parts.append(indent + "}\n")
    return "".join(parts)


def build():
    by_name = {}
    for n in CLASSES:
        p = PACK / (n + ".class")
        if not p.exists():
            raise SystemExit("missing pack class: " + str(p))
        c = parse_class(p)
        by_name[c["name"]] = c
    groups = {}
    for n in CLASSES:
        base = n.split("$")[0]
        groups.setdefault(base, []).append(n)
    out = {}
    for base, members in groups.items():
        pkg = base.rsplit("/", 1)[0].replace("/", ".")
        text = ["/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */\n",
                "package %s;\n\n" % pkg]
        main = by_name[base]
        text.append(emit_class(main, by_name, ""))
        for m in sorted(members):
            if m == base:
                continue
            # 嵌套类放在外部类内部：把主类的收尾 } 去掉再追加
            text[-1] = text[-1][:-2]  # 去掉主类 "}\n"
            text.append("\n")
            text.append(emit_class(by_name[m], by_name, "    ", nested=True))
            text.append("}\n")
        out[OUT / (base + ".java")] = "".join(text)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="只比对不写盘")
    args = ap.parse_args()
    files = build()
    diff, same = [], 0
    for path, content in sorted(files.items()):
        cur = path.read_text(encoding="utf-8") if path.exists() else None
        if cur == content:
            same += 1
            continue
        diff.append(path)
        if not args.check:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8", newline="\n")
    print("GENERATED_FILES=%d CLASS_COUNT=%d SAME=%d CHANGED=%d" %
          (len(files), len(CLASSES), same, len(diff)))
    for p in diff:
        print(("  WOULD_CHANGE " if args.check else "  WROTE ") + str(p.relative_to(REPO)))
    if args.check and diff:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
