package io.mirage.mgscript.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * 词法作用域链。模块级（最外层）作用域同时承载 {@code shared} 变量与内建函数。
 *
 * <p>{@link #assign} 与 {@link #define} 的区别很重要：
 * <ul>
 *   <li>{@code define}：在当前层新建/覆盖（`x = 1` 在函数体内首次出现、`shared x = 0`）；</li>
 *   <li>{@code assign}：沿链找最近的已定义作用域写回（所以函数里改外层变量不需要 {@code global}
 *       这种关键字——Python 那个 {@code global}/{@code nonlocal} 的坑在这里不存在）。</li>
 * </ul>
 */
public final class Env {

    private final Env parent;
    private final Map<String, Object> vars = new HashMap<>(16);

    public Env(Env parent) {
        this.parent = parent;
    }

    public Env parent() {
        return parent;
    }

    public boolean hasLocal(String name) {
        return vars.containsKey(name);
    }

    /** 沿链查找；找不到返回 {@link #MISSING}（用哨兵而不是 null，因为 none 是合法值）。 */
    public Object lookup(String name) {
        for (Env e = this; e != null; e = e.parent) {
            if (e.vars.containsKey(name)) return e.vars.get(name);
        }
        return MISSING;
    }

    public boolean isDefined(String name) {
        return lookup(name) != MISSING;
    }

    public void define(String name, Object value) {
        vars.put(name, value);
    }

    /** 找到最近的已定义作用域写回；全链都没有则定义在当前层（容忍「先赋值后使用」）。 */
    public void assign(String name, Object value) {
        for (Env e = this; e != null; e = e.parent) {
            if (e.vars.containsKey(name)) {
                e.vars.put(name, value);
                return;
            }
        }
        vars.put(name, value);
    }

    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "<未定义>";
        }
    };

    /** 本层已定义的变量名（用于「是不是想写 xxx」的候选提示）。 */
    public java.util.Set<String> localNames() {
        return vars.keySet();
    }
}
