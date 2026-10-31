package io.mirage.mgscript.runtime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * MGScript 标准库：全局函数 + str/list/dict 的方法。
 *
 * <p>设计口径：<b>只放 mod 开发真正会用到的</b>，不做 Python 全集。
 * 每个函数都优先给出「能指出问题在哪」的错误消息（参数个数、类型、越界）。
 */
public final class Builtins {

    private Builtins() {}

    /** 安装全局函数到模块作用域。 */
    public static void install(Interp in, Env module) {
        in.defineNative(module, "len", args -> (double) len(one(args, "len")));
        in.defineNative(module, "str", args -> Values.display(one(args, "str")));
        in.defineNative(module, "type", args -> Values.typeName(one(args, "type")));
        in.defineNative(module, "num", args -> {
            Object v = one(args, "num");
            if (v instanceof Double d) return d;
            if (v instanceof String s) {
                try {
                    return Double.parseDouble(s.strip());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + s + "\" 不是数字");
                }
            }
            if (v instanceof Boolean b) return b ? 1d : 0d;
            throw new IllegalArgumentException("num() 不能转换 " + Values.typeName(v));
        });
        in.defineNative(module, "int", args -> (double) (long) Values.num(one(args, "int"), "int()"));
        in.defineNative(module, "bool", args -> Values.truthy(one(args, "bool")));
        in.defineNative(module, "abs", args -> Math.abs(Values.num(one(args, "abs"), "abs()")));
        in.defineNative(module, "floor", args -> Math.floor(Values.num(one(args, "floor"), "floor()")));
        in.defineNative(module, "ceil", args -> Math.ceil(Values.num(one(args, "ceil"), "ceil()")));
        in.defineNative(module, "round", args -> (double) Math.round(Values.num(one(args, "round"), "round()")));
        in.defineNative(module, "min", args -> numbers(args, "min").stream().min(Comparator.naturalOrder()).orElse(0d));
        in.defineNative(module, "max", args -> numbers(args, "max").stream().max(Comparator.naturalOrder()).orElse(0d));
        in.defineNative(module, "keys", args -> {
            List<Object> out = new ArrayList<>();
            out.addAll(Values.asMap(one(args, "keys")).keySet());
            return out;
        });
        in.defineNative(module, "values", args -> {
            List<Object> out = new ArrayList<>();
            out.addAll(Values.asMap(one(args, "values")).values());
            return out;
        });
        in.defineNative(module, "has", args -> {
            if (args.size() != 2) throw new IllegalArgumentException("has(容器, 值) 需要 2 个参数");
            Object c = args.get(0);
            Object v = args.get(1);
            if (c instanceof List<?> l) return l.stream().anyMatch(x -> Values.equalsDeep(x, v));
            if (c instanceof Map<?, ?> m) return m.containsKey(v);
            if (c instanceof String s) return s.contains(Values.display(v));
            throw new IllegalArgumentException("has() 的第一个参数需要 list/dict/str，实际是 " + Values.typeName(c));
        });
        in.defineNative(module, "clamp", args -> {
            if (args.size() != 3) throw new IllegalArgumentException("clamp(值, 最小, 最大) 需要 3 个参数");
            double v = Values.num(args.get(0), "clamp");
            double lo = Values.num(args.get(1), "clamp");
            double hi = Values.num(args.get(2), "clamp");
            return Math.max(lo, Math.min(hi, v));
        });
    }

    // ---- str 方法 ----

    public static Object strMethod(String s, String name, List<Object> args) {
        return switch (name) {
            case "upper" -> s.toUpperCase();
            case "lower" -> s.toLowerCase();
            case "strip", "trim" -> s.strip();
            case "split" -> {
                String sep = args.isEmpty() ? " " : Values.display(args.get(0));
                if (sep.isEmpty()) throw new IllegalArgumentException("split 的分隔符不能是空字符串");
                List<Object> out = new ArrayList<>();
                for (String part : s.split(java.util.regex.Pattern.quote(sep), -1)) out.add(part);
                yield out;
            }
            case "replace" -> {
                if (args.size() != 2) throw new IllegalArgumentException("replace(旧, 新) 需要 2 个参数");
                yield s.replace(Values.display(args.get(0)), Values.display(args.get(1)));
            }
            case "find" -> (double) s.indexOf(Values.display(args.get(0)));
            case "has", "contains" -> s.contains(Values.display(args.get(0)));
            case "starts_with", "starts" -> s.startsWith(Values.display(args.get(0)));
            case "ends_with", "ends" -> s.endsWith(Values.display(args.get(0)));
            case "sub" -> {
                int from = args.isEmpty() ? 0 : Values.intOf(args.get(0), "sub");
                int to = args.size() < 2 ? s.length() : Values.intOf(args.get(1), "sub");
                if (from < 0) from += s.length();
                if (to < 0) to += s.length();
                from = Math.max(0, Math.min(s.length(), from));
                to = Math.max(from, Math.min(s.length(), to));
                yield s.substring(from, to);
            }
            case "repeat" -> s.repeat(Math.max(0, Values.intOf(args.get(0), "repeat")));
            case "chars" -> {
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
                yield out;
            }
            default -> throw new IllegalArgumentException("str 没有方法 '" + name + "'"
                    + "（可用: upper/lower/strip/split/replace/find/has/starts_with/ends_with/sub/repeat/chars）");
        };
    }

    // ---- list 方法 ----

    @SuppressWarnings("unchecked")
    public static Object listMethod(List<?> raw, String name, List<Object> args) {
        List<Object> list = (List<Object>) raw;
        switch (name) {
            case "push", "append", "add" -> {
                list.add(args.size() > 0 ? args.get(0) : null);
                return null;
            }
            case "pop" -> {
                if (list.isEmpty()) throw new IllegalArgumentException("pop() 时列表是空的");
                int i = args.isEmpty() ? list.size() - 1 : Values.intOf(args.get(0), "pop");
                if (i < 0) i += list.size();
                if (i < 0 || i >= list.size()) throw new IllegalArgumentException("pop 下标越界: " + i);
                return list.remove(i);
            }
            case "insert" -> {
                if (args.size() != 2) throw new IllegalArgumentException("insert(下标, 值) 需要 2 个参数");
                int i = Values.intOf(args.get(0), "insert");
                i = Math.max(0, Math.min(list.size(), i));
                list.add(i, args.get(1));
                return null;
            }
            case "remove" -> {
                int i = Values.intOf(args.get(0), "remove");
                if (i < 0) i += list.size();
                if (i < 0 || i >= list.size()) throw new IllegalArgumentException("remove 下标越界: " + i);
                return list.remove(i);
            }
            case "clear" -> {
                list.clear();
                return null;
            }
            case "join" -> {
                String sep = args.isEmpty() ? "" : Values.display(args.get(0));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) sb.append(sep);
                    sb.append(Values.display(list.get(i)));
                }
                return sb.toString();
            }
            case "has", "contains" -> {
                Object v = args.get(0);
                return list.stream().anyMatch(x -> Values.equalsDeep(x, v));
            }
            case "index_of" -> {
                Object v = args.get(0);
                for (int i = 0; i < list.size(); i++) {
                    if (Values.equalsDeep(list.get(i), v)) return (double) i;
                }
                return -1d;
            }
            case "reverse" -> {
                java.util.Collections.reverse(list);
                return null;
            }
            case "sort" -> {
                list.sort((a, b) -> {
                    if (a instanceof Double x && b instanceof Double y) return Double.compare(x, y);
                    return Values.display(a).compareTo(Values.display(b));
                });
                return null;
            }
            case "slice" -> {
                int from = args.isEmpty() ? 0 : Values.intOf(args.get(0), "slice");
                int to = args.size() < 2 ? list.size() : Values.intOf(args.get(1), "slice");
                if (from < 0) from += list.size();
                if (to < 0) to += list.size();
                from = Math.max(0, Math.min(list.size(), from));
                to = Math.max(from, Math.min(list.size(), to));
                return new ArrayList<>(list.subList(from, to));
            }
            default -> throw new IllegalArgumentException("list 没有方法 '" + name + "'"
                    + "（可用: push/pop/insert/remove/clear/join/has/index_of/reverse/sort/slice）");
        }
    }

    // ---- dict 方法 ----

    public static Object dictMethod(Map<Object, Object> map, String name, List<Object> args) {
        switch (name) {
            case "get" -> {
                Object k = args.get(0);
                if (map.containsKey(k)) return map.get(k);
                return args.size() > 1 ? args.get(1) : null;
            }
            case "set" -> {
                if (args.size() != 2) throw new IllegalArgumentException("set(键, 值) 需要 2 个参数");
                map.put(args.get(0), args.get(1));
                return null;
            }
            case "has", "contains" -> {
                return map.containsKey(args.get(0));
            }
            case "remove" -> {
                return map.remove(args.get(0));
            }
            case "clear" -> {
                map.clear();
                return null;
            }
            case "keys" -> {
                return new ArrayList<>(map.keySet());
            }
            case "values" -> {
                return new ArrayList<>(map.values());
            }
            case "items" -> {
                List<Object> out = new ArrayList<>();
                for (Map.Entry<Object, Object> e : map.entrySet()) {
                    List<Object> pair = new ArrayList<>(2);
                    pair.add(e.getKey());
                    pair.add(e.getValue());
                    out.add(pair);
                }
                return out;
            }
            default -> throw new IllegalArgumentException("dict 没有方法 '" + name + "'"
                    + "（可用: get/set/has/remove/clear/keys/values/items）");
        }
    }

    // ---- 小工具 ----

    private static Object one(List<Object> args, String fname) {
        if (args.size() != 1) {
            throw new IllegalArgumentException(fname + "() 需要 1 个参数，实际给了 " + args.size());
        }
        return args.get(0);
    }

    private static int len(Object v) {
        if (v instanceof List<?> l) return l.size();
        if (v instanceof Map<?, ?> m) return m.size();
        if (v instanceof String s) return s.length();
        if (v instanceof RangeValue r) return r.size();
        throw new IllegalArgumentException("len() 不支持 " + Values.typeName(v));
    }

    private static List<Double> numbers(List<Object> args, String fname) {
        if (args.isEmpty()) throw new IllegalArgumentException(fname + "() 至少需要 1 个参数");
        List<Double> out = new ArrayList<>(args.size());
        for (Object a : args) out.add(Values.num(a, fname + "()"));
        return out;
    }
}
