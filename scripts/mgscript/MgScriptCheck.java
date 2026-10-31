import io.mirage.mgscript.ScriptEngine;
import io.mirage.mgscript.ScriptError;
import io.mirage.mgscript.runtime.EngineHost;
import io.mirage.mgscript.runtime.Env;
import io.mirage.mgscript.runtime.Interp;
import io.mirage.mgscript.runtime.NativeObject;
import io.mirage.mgscript.runtime.ScriptFn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MGScript 纯 JVM 回归检查程序（不启动 MC）。
 *
 * <p>由 {@code scripts/verify_mgscript.ps1} 用 javac 编译后运行；每条断言打印
 * {@code [MGS-CHECK] <name> PASS|FAIL}，脚本统计 FAIL 数与退出码。
 *
 * <p>刻意不放 grep 式的"日志里出现某字符串"——每个用例都断言<b>行为</b>：
 * 日志内容、声明回调收到的属性、异常消息里的关键词、熔断是否生效。
 */
public final class MgScriptCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        run("basic", MgScriptCheck::caseBasic);
        run("interpolation", MgScriptCheck::caseInterpolation);
        run("precedence", MgScriptCheck::casePrecedence);
        run("range-half-open", MgScriptCheck::caseRangeHalfOpen);
        run("range-inclusive", MgScriptCheck::caseRangeInclusive);
        run("str-plus-type-error", MgScriptCheck::caseStrPlusTypeError);
        run("undefined-var-hint", MgScriptCheck::caseUndefinedVarHint);
        run("timeout-fuse", MgScriptCheck::caseTimeoutFuse);
        run("indent-error", MgScriptCheck::caseIndentError);
        run("item-props", MgScriptCheck::caseItemProps);
        run("subscribe-every", MgScriptCheck::caseSubscribeEvery);
        run("closure", MgScriptCheck::caseClosure);
        run("dict-list-methods", MgScriptCheck::caseDictListMethods);
        run("escaped-brace", MgScriptCheck::caseEscapedBrace);

        System.out.println("[MGS-CHECK] SUMMARY passed=" + passed + " failed=" + failed);
        if (failed > 0) System.exit(1);
    }

    // ---- 用例 ----

    /** 最小闭环：mod + item + on init + 日志。 */
    private static void caseBasic(Host host) {
        String src = """
                mod "hello" version "1.0.0"

                item "ruby"
                    name "Ruby"
                    stack 64

                on init
                    log.info("hello-mgs loaded")
                """;
        runFired("hello.mgs", src, host);
        check("declareMod", host.mods.equals(List.of("hello")));
        check("declareItem", host.items.size() == 1 && host.items.get(0).startsWith("hello:ruby"));
        check("log", host.logs.stream().anyMatch(l -> l.contains("hello-mgs loaded")));
        check("subscribe init", host.events.contains("init@null"));
    }

    /** 插值：数字不带 .0，字符串属性直出。 */
    private static void caseInterpolation(Host host) {
        String src = """
                on init
                    log.info("HP {p.hp} name {p.name}")
                """;
        runFired("interp.mgs", src, host);
        check("interp text", host.logs.stream().anyMatch(l -> l.contains("HP 7 name Steve")));
    }

    /** 运算符优先级：`1 + 2 * 3` 必须是 7，不是 9。 */
    private static void casePrecedence(Host host) {
        String src = """
                on init
                    log.info(1 + 2 * 3)
                    log.info((1 + 2) * 3)
                """;
        runFired("prec.mgs", src, host);
        check("prec 7", host.logs.stream().anyMatch(l -> l.endsWith(":7")));
        check("prec 9", host.logs.stream().anyMatch(l -> l.endsWith(":9")));
    }

    /** `0..4` 左闭右开：累加 0+1+2+3 = 6。 */
    private static void caseRangeHalfOpen(Host host) {
        String src = """
                on init
                    sum = 0
                    for i in 0..4
                        sum = sum + i
                    log.info(sum)
                """;
        runFired("range1.mgs", src, host);
        check("range sum 6", host.logs.stream().anyMatch(l -> l.endsWith(":6")));
    }

    /** `0..=3` 闭区间：4 次迭代。 */
    private static void caseRangeInclusive(Host host) {
        String src = """
                on init
                    n = 0
                    for i in 0..=3
                        n = n + 1
                    log.info(n)
                """;
        runFired("range2.mgs", src, host);
        check("inclusive 4", host.logs.stream().anyMatch(l -> l.endsWith(":4")));
    }

    /** 字符串 + 数字必须显式失败，并给出「用插值 / str()」的修法。 */
    private static void caseStrPlusTypeError(Host host) {
        String src = """
                on init
                    log.info("a" + 1)
                """;
        ScriptError e = expectError(() -> runFired("bad.mgs", src, host));
        check("error is ScriptError", e != null);
        check("msg mentions 字符串", e != null && e.getMessage().contains("字符串只能和字符串相加"));
        check("hint mentions str()", e != null && e.diagnose().contains("str(x)"));
    }

    /** 未定义变量要给出候选（编辑距离），而不是干巴巴一句 undefined。 */
    private static void caseUndefinedVarHint(Host host) {
        String src = """
                on init
                    player = 1
                    log.info(playr)
                """;
        ScriptError e = expectError(() -> runFired("undef.mgs", src, host));
        check("undefined msg", e != null && e.getMessage().contains("未定义的变量"));
        check("candidate player", e != null && e.diagnose().contains("player"));
    }

    /** 死循环必须被步数熔断拦住，而不是把进程挂死。 */
    private static void caseTimeoutFuse(Host host) {
        String src = """
                on init
                    while true
                        x = 1
                """;
        long t0 = System.currentTimeMillis();
        ScriptError e = expectError(() -> runFired("loop.mgs", src, host));
        long ms = System.currentTimeMillis() - t0;
        check("timeout error", e != null && e.getMessage().contains("执行太久"));
        check("timeout fast", ms < 3000);
    }

    /** 缩进错位要有可读诊断，而不是直接崩在某个 NullPointer。 */
    private static void caseIndentError(Host host) {
        String src = """
                on init
                    x = 1
                      y = 2
                """;
        ScriptError e = expectError(() -> runFired("indent.mgs", src, host));
        check("indent error", e != null && (e.getMessage().contains("缩进") || e.getMessage().contains("多余")));
    }

    /** 声明块属性必须原样送到宿主（含数字/字符串类型）。 */
    private static void caseItemProps(Host host) {
        String src = """
                mod "p"

                item "ruby"
                    name "Ruby"
                    stack 64
                    rarity rare
                """;
        runFired("props.mgs", src, host);
        check("props size", host.items.size() == 1);
        String detail = host.items.isEmpty() ? "" : host.items.get(0);
        check("props name", detail.contains("name=Ruby"));
        check("props stack", detail.contains("stack=64"));
        check("props rarity", detail.contains("rarity=rare"));
    }

    /** `on tick(server) every 20`：事件名、参数、周期都要正确传达。 */
    private static void caseSubscribeEvery(Host host) {
        String src = """
                on tick(server) every 20
                    log.info("tick")
                """;
        runFired("tick.mgs", src, host);
        check("event tick", host.events.contains("tick@20"));
        check("param count", host.eventParams.equals(List.of("server")));
    }

    /** 闭包：函数捕获定义时的外层变量。 */
    private static void caseClosure(Host host) {
        String src = """
                on init
                    base = 10
                    fn add(n)
                        return base + n
                    log.info(add(5))
                """;
        runFired("closure.mgs", src, host);
        check("closure 15", host.logs.stream().anyMatch(l -> l.endsWith(":15")));
    }

    /** 容器方法：dict.get / list.push / list.join。 */
    private static void caseDictListMethods(Host host) {
        String src = """
                on init
                    d = {"a": 1}
                    d.set("b", 2)
                    log.info(d.get("b"))
                    xs = [1]
                    xs.push(2)
                    log.info(xs.join("-"))
                """;
        runFired("containers.mgs", src, host);
        check("dict get 2", host.logs.stream().anyMatch(l -> l.endsWith(":2")));
        check("list join 1-2", host.logs.stream().anyMatch(l -> l.endsWith(":1-2")));
    }

    /** `\{` 转义成字面量大括号，不触发插值。 */
    private static void caseEscapedBrace(Host host) {
        String src = """
                on init
                    log.info("literal \\{not_interp}")
                """;
        runFired("escape.mgs", src, host);
        check("escaped brace", host.logs.stream().anyMatch(l -> l.contains("literal {not_interp}")));
    }

    // ---- 断言与工具 ----

    private interface Case {
        void run(Host host);
    }

    private interface Thrower {
        void run();
    }

    /**
     * 跑一段脚本并<b>触发已注册的回调</b>。
     *
     * <p>这一步是必须的：`on init` 只是注册，body 要等宿主动事件时才执行。
     * 第一版检查程序漏了这一步，13 个用例集体假失败——说明断言确实在测行为，
     * 不是"脚本能跑不报错"就算过。
     */
    private static Interp runFired(String file, String src, Host host) {
        Interp in = ScriptEngine.runSource(file, src, host);
        host.fireAll(in);
        return in;
    }

    private static void run(String name, Case c) {
        Host host = new Host();
        try {
            c.run(host);
            System.out.println("[MGS-CHECK] " + name + (failedThisCase == 0 ? " PASS" : " FAIL"));
        } catch (Throwable t) {
            System.out.println("[MGS-CHECK] " + name + " FAIL (异常: " + t + ")");
            failed++;
        } finally {
            failedThisCase = 0;
        }
    }

    private static int failedThisCase;

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            failed++;
            failedThisCase++;
            System.out.println("[MGS-CHECK]   ✗ " + what);
        }
    }

    private static ScriptError expectError(Thrower t) {
        try {
            t.run();
            return null;
        } catch (ScriptError e) {
            return e;
        }
    }

    /** 记录宿主，并提供一个假的 player（hp=7 / name=Steve / message()）。 */
    private static final class Host implements EngineHost {
        final List<String> logs = new ArrayList<>();
        final List<String> mods = new ArrayList<>();
        final List<String> items = new ArrayList<>();
        final List<String> blocks = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> eventParams = new ArrayList<>();
        final List<ScriptFn> callbacks = new ArrayList<>();
        final FakePlayer player = new FakePlayer(this);

        @Override
        public void log(String level, String text) {
            logs.add(level + ":" + text);
        }

        @Override
        public void declareMod(String id, Map<String, Object> props) {
            mods.add(id);
        }

        @Override
        public void declareItem(String modId, String name, Map<String, Object> props) {
            items.add(modId + ":" + name + " " + render(props));
        }

        @Override
        public void declareBlock(String modId, String name, Map<String, Object> props) {
            blocks.add(modId + ":" + name + " " + render(props));
        }

        @Override
        public void subscribe(String event, List<String> params, Integer every, ScriptFn body) {
            events.add(event + "@" + every);
            eventParams.clear();
            eventParams.addAll(params);
            callbacks.add(body);
        }

        /** 模拟宿主派发：参数补 none（P0 的事件参数还没有真实绑定对象）。 */
        void fireAll(Interp interp) {
            for (ScriptFn fn : callbacks) {
                List<Object> args = new ArrayList<>();
                for (int i = 0; i < fn.params().size(); i++) args.add(null);
                interp.invokeCallback(fn, args);
            }
            callbacks.clear();
        }

        @Override
        public Object global(String name) {
            return "p".equals(name) ? player : Env.MISSING;
        }

        @Override
        public String modId() {
            return mods.isEmpty() ? "" : mods.get(0);
        }

        private static String render(Map<String, Object> props) {
            StringBuilder sb = new StringBuilder();
            Map<String, Object> ordered = new LinkedHashMap<>(props);
            for (Map.Entry<String, Object> e : ordered.entrySet()) {
                if (sb.length() > 0) sb.append(' ');
                // 用引擎同款渲染：64.0 要显示成 64，否则断言等于在测 Double.toString
                sb.append(e.getKey()).append('=')
                  .append(io.mirage.mgscript.runtime.Values.display(e.getValue()));
            }
            return sb.toString();
        }
    }

    private static final class FakePlayer implements NativeObject {
        private final Host host;
        double hp = 7;
        String name = "Steve";
        final List<String> messages = new ArrayList<>();

        FakePlayer(Host host) {
            this.host = host;
        }

        @Override
        public String typeName() {
            return "player";
        }

        @Override
        public Object get(String prop) {
            return switch (prop) {
                case "hp", "health" -> hp;
                case "name" -> name;
                default -> throw new IllegalArgumentException("player 没有属性 '" + prop + "'");
            };
        }

        @Override
        public void set(String prop, Object value) {
            switch (prop) {
                case "hp", "health" -> hp = ((Number) value).doubleValue();
                case "name" -> name = String.valueOf(value);
                default -> throw new IllegalArgumentException("player 不支持设置 '" + prop + "'");
            }
        }

        @Override
        public Object call(String method, List<Object> args) {
            if ("message".equals(method)) {
                messages.add(String.valueOf(args.get(0)));
                return null;
            }
            throw new IllegalArgumentException("player 没有方法 '" + method + "'");
        }

        @Override
        public List<String> properties() {
            return List.of("hp", "health", "name");
        }

        @Override
        public List<String> methods() {
            return List.of("message");
        }
    }
}
