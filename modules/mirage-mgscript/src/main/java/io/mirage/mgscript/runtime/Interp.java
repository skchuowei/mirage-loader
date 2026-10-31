package io.mirage.mgscript.runtime;

import io.mirage.mgscript.ScriptError;
import io.mirage.mgscript.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 树遍历解释器。
 *
 * <p>三个刻意的设计选择：
 * <ol>
 *   <li><b>每步计费</b>：{@code steps} 超过预算就抛超时。玩家脚本一个 {@code while true}
 *       足以把客户端 tick 拖死，而 MC 没有「杀脚本」的机制，只能靠这里主动熔断；</li>
 *   <li><b>Java 异常只在一层转换</b>：内建库/游戏 API 抛 {@link IllegalArgumentException}，
 *       由 {@link #guard} 统一包成带脚本行号的 {@link ScriptError}，别处不许 try-catch 吞；</li>
 *   <li><b>不缓存 {@code System.out} 之类</b>：日志只经 {@link EngineHost#log}，由宿主决定去向。</li>
 * </ol>
 */
public final class Interp {

    /** 单次回调的默认指令预算。够跑几千次循环，又能在一个 tick 内熔断死循环。 */
    public static final long DEFAULT_STEP_BUDGET = 100_000L;

    private final EngineHost host;
    private final String file;
    private final Env module = new Env(null);
    private final List<String> callStack = new ArrayList<>();
    private long steps;
    private long stepBudget = DEFAULT_STEP_BUDGET;
    private String modId = "";

    public Interp(EngineHost host, String file) {
        this.host = host;
        this.file = file;
        Builtins.install(this, module);
        module.define("log", new LogObject());
    }

    public Env moduleEnv() {
        return module;
    }

    public String modId() {
        return modId;
    }

    public void setStepBudget(long budget) {
        this.stepBudget = budget;
    }

    // ---- 执行入口 ----

    /** 执行顶层块（mod/item/block/on/fn 声明 + 共享变量初始化）。 */
    public void run(Ast.Block program) {
        steps = 0;
        Flow f = execBlock(program, module);
        if (f.kind == Kind.RETURN) {
            throw error(program.line(), program.col(), "return 只能写在函数里");
        }
    }

    /**
     * 执行一个事件回调。每次回调重置步数预算——否则长跑之后所有回调都会立刻超时。
     */
    public void invokeCallback(ScriptFn fn, List<Object> args) {
        steps = 0;
        invokeScriptFn(fn, args, 0, 0);
    }

    Object invokeScriptFn(ScriptFn fn, List<Object> args, int line, int col) {
        if (args.size() != fn.params().size()) {
            throw error(line, col, "函数 " + describeFn(fn) + " 需要 " + fn.params().size()
                    + " 个参数，实际给了 " + args.size());
        }
        Env local = new Env(fn.closure());
        for (int i = 0; i < fn.params().size(); i++) {
            local.define(fn.params().get(i), args.get(i));
        }
        String label = describeFn(fn);
        if (callStack.size() > 64) {
            throw error(line, col, "调用层数太深（>64）：" + label + "，检查是否有无限递归");
        }
        callStack.add(label);
        try {
            Flow f = execBlock(fn.body(), local);
            return f.kind == Kind.RETURN ? f.value : null;
        } finally {
            callStack.remove(callStack.size() - 1);
        }
    }

    // ---- 语句 ----

    private Flow execBlock(Ast.Block block, Env env) {
        for (Ast.Stmt s : block.stmts()) {
            Flow f = exec(s, env);
            if (f.kind != Kind.NORMAL) return f;
        }
        return Flow.NORMAL;
    }

    private Flow exec(Ast.Stmt s, Env env) {
        step(s.line(), s.col());
        return switch (s) {
            case Ast.ExprStmt e -> {
                eval(e.expr(), env);
                yield Flow.NORMAL;
            }
            case Ast.Assign a -> {
                Object v = eval(a.value(), env);
                assignTo(a.target(), v, env, a.line(), a.col());
                yield Flow.NORMAL;
            }
            case Ast.Shared sh -> {
                module.define(sh.name(), eval(sh.value(), env));
                yield Flow.NORMAL;
            }
            case Ast.If iff -> execIf(iff, env);
            case Ast.For f -> execFor(f, env);
            case Ast.While w -> execWhile(w, env);
            case Ast.Return r -> new Flow(Kind.RETURN, r.value() == null ? null : eval(r.value(), env));
            case Ast.Break b -> Flow.BREAK;
            case Ast.Continue c -> Flow.CONTINUE;
            case Ast.FnDecl f -> {
                Ast.FnLit fn = f.fn();
                env.define(fn.name(), new ScriptFn(fn.name(), paramNames(fn), fn.body(), env));
                yield Flow.NORMAL;
            }
            case Ast.ModDecl m -> {
                if (!modId.isEmpty() && !modId.equals(m.id())) {
                    throw error(m.line(), m.col(), "一个脚本只能声明一个 mod（已经有 \"" + modId
                            + "\"，这里又想声明 \"" + m.id() + "\"）");
                }
                modId = m.id();
                host.declareMod(m.id(), evalProps(m.props(), env));
                yield Flow.NORMAL;
            }
            case Ast.ItemDecl d -> {
                host.declareItem(effectiveModId(d.line(), d.col()), d.name(), evalProps(d.props(), env));
                yield Flow.NORMAL;
            }
            case Ast.BlockDecl d -> {
                host.declareBlock(effectiveModId(d.line(), d.col()), d.name(), evalProps(d.props(), env));
                yield Flow.NORMAL;
            }
            case Ast.OnDecl d -> {
                Integer every = null;
                if (d.every() != null) {
                    Object v = eval(d.every(), env);
                    if (!(v instanceof Double num)) {
                        throw error(d.line(), d.col(), "every 后面需要数字（tick 数），实际是 "
                                + Values.typeName(v));
                    }
                    every = (int) Math.max(1, Math.round(num));
                }
                ScriptFn body = new ScriptFn(d.event(), d.params(), d.body(), env);
                // 未知事件是宿主（游戏绑定层）判定的，但行号在脚本侧——在这里补上位置，
                // 否则玩家只能看到「未知事件 xxx」而不知道写在哪一行
                try {
                    host.subscribe(d.event(), d.params(), every, body);
                } catch (IllegalArgumentException ex) {
                    throw guard(ex, d.line(), d.col());
                }
                yield Flow.NORMAL;
            }
            case Ast.Prop p -> throw error(p.line(), p.col(),
                    "属性 '" + p.key() + "' 只能写在声明块里（item / block 的缩进块）");
        };
    }

    private Flow execIf(Ast.If iff, Env env) {
        for (Ast.IfBranch b : iff.branches()) {
            if (Values.truthy(eval(b.cond(), env))) return execBlock(b.body(), env);
        }
        if (iff.elseBody() != null) return execBlock(iff.elseBody(), env);
        return Flow.NORMAL;
    }

    private Flow execFor(Ast.For f, Env env) {
        List<Object> items;
        try {
            items = Values.iterate(eval(f.iterable(), env));
        } catch (IllegalArgumentException ex) {
            throw error(f.line(), f.col(), ex.getMessage());
        }
        for (Object item : items) {
            step(f.line(), f.col());
            Env iter = new Env(env);
            iter.define(f.varName(), item);
            Flow r = execBlock(f.body(), iter);
            if (r.kind == Kind.RETURN) return r;
            if (r.kind == Kind.BREAK) break;
        }
        return Flow.NORMAL;
    }

    private Flow execWhile(Ast.While w, Env env) {
        while (Values.truthy(eval(w.cond(), env))) {
            step(w.line(), w.col());
            Flow r = execBlock(w.body(), env);
            if (r.kind == Kind.RETURN) return r;
            if (r.kind == Kind.BREAK) break;
        }
        return Flow.NORMAL;
    }

    private void assignTo(Ast.Expr target, Object value, Env env, int line, int col) {
        switch (target) {
            case Ast.Var v -> env.assign(v.name(), value);
            case Ast.Get g -> {
                Object obj = eval(g.target(), env);
                try {
                    if (obj instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked") Map<Object, Object> mm = (Map<Object, Object>) m;
                        mm.put(g.name(), value);
                    } else if (obj instanceof NativeObject no) {
                        no.set(g.name(), value);
                    } else if (obj == null) {
                        throw error(line, col, "不能给 none 设置属性 '" + g.name() + "'");
                    } else {
                        throw error(line, col, Values.typeName(obj) + " 不支持设置属性 '" + g.name() + "'");
                    }
                } catch (IllegalArgumentException ex) {
                    throw guard(ex, line, col);
                }
            }
            case Ast.Index ix -> {
                Object obj = eval(ix.target(), env);
                Object key = eval(ix.index(), env);
                try {
                    setIndex(obj, key, value);
                } catch (IllegalArgumentException ex) {
                    throw guard(ex, line, col);
                }
            }
            default -> throw error(line, col, "这个位置不能赋值");
        }
    }

    @SuppressWarnings("unchecked")
    private void setIndex(Object obj, Object key, Object value) {
        if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            int i = (int) Values.num(key, "列表下标");
            if (i < 0) i += list.size();
            if (i < 0 || i >= list.size()) {
                throw new IllegalArgumentException("列表下标越界：" + (int) Values.num(key, "列表下标")
                        + "（长度 " + list.size() + "）");
            }
            list.set(i, value);
            return;
        }
        if (obj instanceof Map) {
            ((Map<Object, Object>) obj).put(key, value);
            return;
        }
        throw new IllegalArgumentException(Values.typeName(obj) + " 不支持下标赋值");
    }

    // ---- 表达式 ----

    private Object eval(Ast.Expr e, Env env) {
        step(e.line(), e.col());
        return switch (e) {
            case Ast.Literal l -> l.value();
            case Ast.Str s -> evalStr(s, env);
            case Ast.Var v -> resolveVar(v.name(), v.line(), v.col(), env);
            case Ast.Get g -> readProp(eval(g.target(), env), g.name(), g.line(), g.col());
            case Ast.Index ix -> {
                Object target = eval(ix.target(), env);
                Object key = eval(ix.index(), env);
                try {
                    yield readIndex(target, key);
                } catch (IllegalArgumentException ex) {
                    throw guard(ex, ix.line(), ix.col());
                }
            }
            case Ast.Binary b -> evalBinary(b, env);
            case Ast.Unary u -> {
                Object v = eval(u.operand(), env);
                yield "not".equals(u.op()) ? !Values.truthy(v) : -asNumber(v, u.line(), u.col());
            }
            case Ast.Call c -> evalCall(c, env);
            case Ast.ListLit l -> {
                List<Object> out = new ArrayList<>(l.items().size());
                for (Ast.Expr item : l.items()) out.add(eval(item, env));
                yield out;
            }
            case Ast.MapLit m -> {
                Map<Object, Object> out = new LinkedHashMap<>();
                for (Map.Entry<Ast.Expr, Ast.Expr> en : m.entries()) {
                    out.put(eval(en.getKey(), env), eval(en.getValue(), env));
                }
                yield out;
            }
            case Ast.RangeLit r -> new RangeValue(
                    asNumber(eval(r.from(), env), r.line(), r.col()),
                    asNumber(eval(r.to(), env), r.line(), r.col()),
                    r.inclusiveEnd());
            case Ast.FnLit f -> new ScriptFn(f.name(), paramNames(f), f.body(), env);
        };
    }

    private String evalStr(Ast.Str s, Env env) {
        if (s.parts().size() == 1 && s.parts().get(0) instanceof String only) return only;
        StringBuilder sb = new StringBuilder();
        for (Object part : s.parts()) {
            sb.append(part instanceof String str ? str : Values.display(eval((Ast.Expr) part, env)));
        }
        return sb.toString();
    }

    private Object evalBinary(Ast.Binary b, Env env) {
        // 短路：and/or 返回操作数本身（Python 语义），不折成布尔
        if ("and".equals(b.op())) {
            Object left = eval(b.left(), env);
            return Values.truthy(left) ? eval(b.right(), env) : left;
        }
        if ("or".equals(b.op())) {
            Object left = eval(b.left(), env);
            return Values.truthy(left) ? left : eval(b.right(), env);
        }
        Object l = eval(b.left(), env);
        Object r = eval(b.right(), env);
        return switch (b.op()) {
            case "+" -> plus(l, r, b.line(), b.col());
            case "-" -> asNumber(l, b.line(), b.col()) - asNumber(r, b.line(), b.col());
            case "*" -> asNumber(l, b.line(), b.col()) * asNumber(r, b.line(), b.col());
            case "/" -> {
                double d = asNumber(r, b.line(), b.col());
                if (d == 0d) throw error(b.line(), b.col(), "除数不能是 0");
                yield asNumber(l, b.line(), b.col()) / d;
            }
            case "%" -> {
                double d = asNumber(r, b.line(), b.col());
                if (d == 0d) throw error(b.line(), b.col(), "取模的除数不能是 0");
                yield asNumber(l, b.line(), b.col()) % d;
            }
            case "==" -> Values.equalsDeep(l, r);
            case "!=" -> !Values.equalsDeep(l, r);
            case "<" -> compare(l, r, b.line(), b.col()) < 0;
            case "<=" -> compare(l, r, b.line(), b.col()) <= 0;
            case ">" -> compare(l, r, b.line(), b.col()) > 0;
            case ">=" -> compare(l, r, b.line(), b.col()) >= 0;
            case "in" -> contains(r, l, b.line(), b.col());
            case "not in" -> !contains(r, l, b.line(), b.col());
            default -> throw error(b.line(), b.col(), "不支持的运算符 " + b.op());
        };
    }

    private Object plus(Object l, Object r, int line, int col) {
        if (l instanceof Double a && r instanceof Double b) return a + b;
        if (l instanceof List<?> a && r instanceof List<?> b) {
            List<Object> out = new ArrayList<>(a);
            out.addAll(b);
            return out;
        }
        if (l instanceof String || r instanceof String) {
            if (l instanceof String && r instanceof String) return (String) l + r;
            // 显式失败：Python 的 str + int 隐式报错信息晦涩，这里直接指出两侧类型
            throw error(line, col, "字符串只能和字符串相加（" + Values.typeName(l) + " + "
                    + Values.typeName(r) + "）",
                    "提示: 想拼接请写 \"{值}\" 插值，或用 str(x) 显式转换");
        }
        throw error(line, col, "不能相加：" + Values.typeName(l) + " + " + Values.typeName(r));
    }

    private int compare(Object l, Object r, int line, int col) {
        if (l instanceof Double a && r instanceof Double b) return Double.compare(a, b);
        if (l instanceof String a && r instanceof String b) return a.compareTo(b);
        throw error(line, col, "不能比较 " + Values.typeName(l) + " 和 " + Values.typeName(r));
    }

    private boolean contains(Object haystack, Object needle, int line, int col) {
        if (haystack instanceof List<?> list) return list.stream().anyMatch(x -> Values.equalsDeep(x, needle));
        if (haystack instanceof Map<?, ?> map) return map.containsKey(needle);
        if (haystack instanceof String s) return s.contains(Values.display(needle));
        throw error(line, col, "in 的右边需要 list / dict / str，实际是 " + Values.typeName(haystack));
    }

    private Object evalCall(Ast.Call c, Env env) {
        // 方法调用：callee 是 Get 时先求 receiver，再按方法分派（不构造 BoundMethod 对象）
        if (c.callee() instanceof Ast.Get g) {
            Object target = eval(g.target(), env);
            List<Object> args = evalArgs(c.args(), env);
            try {
                return callMethod(target, g.name(), args, g.line(), g.col());
            } catch (IllegalArgumentException ex) {
                throw guard(ex, g.line(), g.col());
            }
        }
        Object callee = eval(c.callee(), env);
        List<Object> args = evalArgs(c.args(), env);
        if (callee == null) {
            throw error(c.line(), c.col(), "调用的函数是 none（可能拼错名字或还没定义）");
        }
        if (!(callee instanceof MgCallable fn)) {
            throw error(c.line(), c.col(), Values.typeName(callee) + " 不能当函数调用",
                    "提示: 只有 fn 和内置函数可以调用");
        }
        return fn.call(this, args, c.line(), c.col());
    }

    private List<Object> evalArgs(List<Ast.Expr> exprs, Env env) {
        List<Object> out = new ArrayList<>(exprs.size());
        for (Ast.Expr e : exprs) out.add(eval(e, env));
        return out;
    }

    private Object callMethod(Object target, String name, List<Object> args, int line, int col) {
        if (target instanceof NativeObject no) {
            return no.call(name, args);
        }
        if (target instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked") Map<Object, Object> map = (Map<Object, Object>) m;
            return Builtins.dictMethod(map, name, args);
        }
        if (target instanceof List<?> list) {
            return Builtins.listMethod(list, name, args);
        }
        if (target instanceof String s) {
            return Builtins.strMethod(s, name, args);
        }
        if (target == null) {
            throw error(line, col, "不能对 none 调用方法 '" + name + "'");
        }
        throw error(line, col, Values.typeName(target) + " 没有方法 '" + name + "'");
    }

    private Object readProp(Object target, String name, int line, int col) {
        if (target instanceof NativeObject no) {
            try {
                return no.get(name);
            } catch (IllegalArgumentException ex) {
                throw guard(ex, line, col).withHint(suggest(name, no.properties(),
                        "可读属性: " + String.join(", ", no.properties())));
            }
        }
        if (target instanceof Map<?, ?> m) {
            if (m.containsKey(name)) return m.get(name);
            if ("size".equals(name) || "length".equals(name)) return (double) m.size();
            throw error(line, col, "dict 里没有键 '" + name + "'")
                    .withHint("提示: dict 取键请写 m[\"" + name + "\"]（点号写法只用于对象属性）");
        }
        if (target instanceof List<?> l) {
            return switch (name) {
                case "size", "length" -> (double) l.size();
                case "first" -> l.isEmpty() ? null : l.get(0);
                case "last" -> l.isEmpty() ? null : l.get(l.size() - 1);
                default -> throw error(line, col, "list 没有属性 '" + name + "'")
                        .withHint("可用: size / length / first / last");
            };
        }
        if (target instanceof String s) {
            if ("size".equals(name) || "length".equals(name)) return (double) s.length();
            throw error(line, col, "str 没有属性 '" + name + "'")
                    .withHint("可用: size / length");
        }
        if (target == null) throw error(line, col, "不能读取 none 的属性 '" + name + "'");
        throw error(line, col, Values.typeName(target) + " 没有属性 '" + name + "'");
    }

    private Object readIndex(Object target, Object key) {
        if (target instanceof List<?> l) {
            int i = (int) Values.num(key, "列表下标");
            if (i < 0) i += l.size();
            if (i < 0 || i >= l.size()) {
                throw new IllegalArgumentException("列表下标越界：" + i + "（长度 " + l.size() + "）");
            }
            return l.get(i);
        }
        if (target instanceof Map<?, ?> m) return m.get(key);
        if (target instanceof String s) {
            int i = (int) Values.num(key, "字符串下标");
            if (i < 0) i += s.length();
            if (i < 0 || i >= s.length()) {
                throw new IllegalArgumentException("字符串下标越界：" + i + "（长度 " + s.length() + "）");
            }
            return String.valueOf(s.charAt(i));
        }
        throw new IllegalArgumentException(Values.typeName(target) + " 不支持下标访问");
    }

    private Object resolveVar(String name, int line, int col, Env env) {
        Object v = env.lookup(name);
        if (v != Env.MISSING) return v;
        Object g = host.global(name);
        if (g != Env.MISSING) return g;
        List<String> near = suggestList(name, collectNames(env));
        throw error(line, col, "未定义的变量 '" + name + "'")
                .withHint(near.isEmpty() ? "提示: 变量要先赋值再使用" : "是不是想写: " + String.join(", ", near));
    }

    private List<String> collectNames(Env env) {
        List<String> names = new ArrayList<>();
        for (Env e = env; e != null; e = e.parent()) {
            names.addAll(e.localNames());
        }
        return names;
    }

    private String effectiveModId(int line, int col) {
        if (modId.isEmpty()) {
            throw error(line, col, "还没有声明 mod 就写了 " + "item/block",
                    "提示: 文件开头先写 mod \"your_mod_id\" version \"1.0.0\"");
        }
        return modId;
    }

    private Map<String, Object> evalProps(Map<String, Ast.Expr> props, Env env) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Ast.Expr> e : props.entrySet()) {
            out.put(e.getKey(), eval(e.getValue(), env));
        }
        return out;
    }

    private static List<String> paramNames(Ast.FnLit fn) {
        List<String> names = new ArrayList<>(fn.params().size());
        for (Ast.Param p : fn.params()) names.add(p.name());
        return names;
    }

    private static String describeFn(ScriptFn fn) {
        return fn.name() == null || fn.name().isEmpty() ? "<匿名函数>" : fn.name() + "()";
    }

    private double asNumber(Object v, int line, int col) {
        if (v instanceof Double d) return d;
        throw error(line, col, "这里需要数字，实际是 " + Values.typeName(v));
    }

    // ---- 错误与熔断 ----

    private void step(int line, int col) {
        if (++steps > stepBudget) {
            throw error(line, col, "脚本这一步执行太久（超过 " + stepBudget + " 步），已中断",
                    "提示: 检查 while / for 的退出条件；事件回调里不能写死循环");
        }
    }

    ScriptError error(int line, int col, String message, String... hints) {
        ScriptError e = ScriptError.at(file, line, col, message, null);
        for (String h : hints) {
            if (h != null && !h.isBlank()) e = e.withHint(h);
        }
        if (!callStack.isEmpty()) {
            e = e.withHint("调用链: " + String.join(" -> ", callStack));
        }
        return e;
    }

    ScriptError guard(IllegalArgumentException ex, int line, int col) {
        String msg = ex.getMessage() == null ? "运行时错误" : ex.getMessage();
        return error(line, col, msg);
    }

    private static String suggest(String name, List<String> candidates, String fallback) {
        List<String> near = suggestList(name, candidates);
        return near.isEmpty() ? fallback : "是不是想写: " + String.join(", ", near);
    }

    /** 候选建议：编辑距离 ≤2 或前缀匹配。错误信息能不能用，八成取决于这一行。 */
    static List<String> suggestList(String name, List<String> candidates) {
        List<String> out = new ArrayList<>();
        String lower = name.toLowerCase();
        for (String c : candidates) {
            if (c.equals(name)) continue;
            String cl = c.toLowerCase();
            if (cl.startsWith(lower) || lower.startsWith(cl) || editDistance(lower, cl) <= 2) {
                out.add(c);
                if (out.size() >= 4) break;
            }
        }
        return out;
    }

    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    // ---- 内部类型 ----

    private enum Kind { NORMAL, RETURN, BREAK, CONTINUE }

    private record Flow(Kind kind, Object value) {
        static final Flow NORMAL = new Flow(Kind.NORMAL, null);
        static final Flow BREAK = new Flow(Kind.BREAK, null);
        static final Flow CONTINUE = new Flow(Kind.CONTINUE, null);
    }

    /** {@code log.info / warn / error / debug}。 */
    private final class LogObject implements NativeObject {
        @Override
        public String typeName() {
            return "log";
        }

        @Override
        public Object get(String prop) {
            throw new IllegalArgumentException("log 只有方法，没有属性 '" + prop + "'");
        }

        @Override
        public Object call(String method, List<Object> args) {
            String level = switch (method) {
                case "info", "warn", "error", "debug" -> method;
                default -> throw new IllegalArgumentException("log 没有方法 '" + method + "'");
            };
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(' ');
                sb.append(Values.display(args.get(i)));
            }
            host.log(level, sb.toString());
            return null;
        }

        @Override
        public List<String> methods() {
            return List.of("info", "warn", "error", "debug");
        }
    }

    /** 让内建库也能注册函数（{@link Builtins} 用）。 */
    void defineNative(Env env, String name, Function<List<Object>, Object> body) {
        env.define(name, new NativeFn() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Object call(Interp in, List<Object> args, int line, int col) {
                try {
                    return body.apply(args);
                } catch (IllegalArgumentException ex) {
                    throw in.guard(ex, line, col);
                }
            }
        });
    }
}
