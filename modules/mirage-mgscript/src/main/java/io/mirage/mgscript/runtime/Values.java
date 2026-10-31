package io.mirage.mgscript.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MGScript 值工具：真值判定、数字转换、深比较、显示渲染。
 *
 * <p>值模型刻意用 Java 原生类型（{@code null} / {@link Boolean} / {@link Double} / {@link String} /
 * {@link List} / {@link LinkedHashMap}）而不是包一层 {@code Value} 类：
 * 少一层装箱、少一层 GC 压力，事件回调每 tick 跑的时候差别明显。
 * <b>代价</b>是类型错误必须在访问点显式检查——所以这里集中放检查函数，别处不许裸转。
 */
public final class Values {

    private Values() {}

    public static String typeName(Object v) {
        if (v == null) return "none";
        if (v instanceof Boolean) return "bool";
        if (v instanceof Double) return "number";
        if (v instanceof String) return "str";
        if (v instanceof List) return "list";
        if (v instanceof Map) return "dict";
        if (v instanceof RangeValue) return "range";
        if (v instanceof ScriptFn) return "fn";
        if (v instanceof NativeFn) return "fn";
        if (v instanceof NativeObject) return ((NativeObject) v).typeName();
        return v.getClass().getSimpleName();
    }

    public static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Double d) return d != 0d;
        if (v instanceof String s) return !s.isEmpty();
        if (v instanceof List<?> l) return !l.isEmpty();
        if (v instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    public static double num(Object v, String where) {
        if (v instanceof Double d) return d;
        if (v instanceof Boolean b) return b ? 1 : 0;
        throw new IllegalArgumentException("需要一个数字，实际是 " + typeName(v) + "（" + where + "）");
    }

    public static int intOf(Object v, String where) {
        return (int) Math.floor(num(v, where));
    }

    /** {@code str()} / 字符串插值 / 日志拼接的统一渲染。整数值不带 {@code .0}。 */
    public static String display(Object v) {
        if (v == null) return "none";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Double d) {
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) (double) d);
            }
            return String.valueOf(d);
        }
        if (v instanceof String s) return s;
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(l.get(i) instanceof String s ? "\"" + s + "\"" : display(l.get(i)));
            }
            return sb.append("]").toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(display(e.getKey())).append(": ");
                sb.append(e.getValue() instanceof String s ? "\"" + s + "\"" : display(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (v instanceof RangeValue r) {
            return r.from() + (r.inclusiveEnd() ? "..=" : "..") + r.to();
        }
        if (v instanceof ScriptFn f) return "<fn " + (f.name() == null ? "anonymous" : f.name()) + ">";
        if (v instanceof NativeFn f) return "<native " + f.name() + ">";
        return String.valueOf(v);
    }

    /** 深比较：列表/字典按内容比，不按引用（`[1,2] == [1,2]` 为 true）。 */
    public static boolean equalsDeep(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Double da && b instanceof Double db) return da.doubleValue() == db.doubleValue();
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!equalsDeep(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!equalsDeep(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        return a.equals(b);
    }

    /** for-in 的迭代序列；不支持的类型抛出（调用方包装成带位置的 ScriptError）。 */
    public static List<Object> iterate(Object v) {
        if (v instanceof List<?> l) return new ArrayList<>(l);
        if (v instanceof Map<?, ?> m) return new ArrayList<>(m.keySet());
        if (v instanceof String s) {
            List<Object> chars = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) chars.add(String.valueOf(s.charAt(i)));
            return chars;
        }
        if (v instanceof RangeValue r) {
            List<Object> out = new ArrayList<>();
            double end = r.inclusiveEnd() ? r.to() + 1 : r.to();
            for (double i = r.from(); i < end; i++) out.add(i);
            return out;
        }
        throw new IllegalArgumentException("不能遍历 " + typeName(v)
                + "（可用: list / dict / str / range）");
    }

    /** 归一化为字典；已在字典上时原样返回。 */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) return (Map<Object, Object>) m;
        throw new IllegalArgumentException("需要一个 dict，实际是 " + typeName(v));
    }

    public static Map<Object, Object> newMap() {
        return new LinkedHashMap<>();
    }
}
