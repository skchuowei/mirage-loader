package io.mirage.ls.stage;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * NeoForge mod 主类构造签名选择（纯 JDK，可离线断言）。
 *
 * <p>真实 NeoForge 允许的构造器形态（按本项目要覆盖的子集）：
 * <pre>
 *   ()
 *   (IEventBus)
 *   (IEventBus, Dist)
 *   (IEventBus, ModContainer)
 *   (IEventBus, ModContainer, Dist)
 * </pre>
 * 选择顺序 = 参数最多 → 参数最少：先注入最全的上下文，再逐级回退；
 * 任何一个参数为 {@code null}（例如 ModList 未登记、Dist 未注入）就跳过该形态，
 * 绝不把 {@code null} 传给 mod 构造器。
 *
 * <p>比较参数类型用**类名字符串**而不是直接 {@code import}：本类由 app loader 加载，
 * 而这些 neo 类型由 MirageClassLoader 加载（见 {@code NeoCompatBootstrap} 类注释），
 * 直接引用会得到两份 {@code Class} 对象，{@code getConstructor} 永远匹配不上。
 */
public final class ModConstructorPicker {

    private static final String BUS = "net.neoforged.bus.api.IEventBus";
    private static final String DIST = "net.neoforged.api.distmarker.Dist";
    private static final String CONTAINER = "net.neoforged.fml.ModContainer";

    private ModConstructorPicker() {
    }

    /** 选中的构造器 + 实参；{@link #signature()} 只用于日志。 */
    public static final class Match {

        public final Constructor<?> constructor;
        public final Object[] args;

        private Match(Constructor<?> constructor, Object[] args) {
            this.constructor = constructor;
            this.args = args;
        }

        public String signature() {
            List<String> names = new ArrayList<>();
            for (Class<?> p : constructor.getParameterTypes()) {
                names.add(p.getSimpleName());
            }
            return "(" + String.join(", ", names) + ")";
        }
    }

    /**
     * 按「参数最多 → 最少」选择构造器；找不到返回 {@code null}。
     *
     * @param type      mod 主类（由 MirageClassLoader 加载）
     * @param modBus    NeoForge mod bus（可为 null）
     * @param dist      当前端侧 Dist 枚举（可为 null）
     * @param container 该 mod 的 ModContainer（可为 null）
     */
    public static Match pick(Class<?> type, Object modBus, Object dist, Object container) {
        if (type == null) {
            return null;
        }
        Match match = tryPick(type, new Object[]{modBus, container, dist},
                new String[]{BUS, CONTAINER, DIST});
        if (match != null) {
            return match;
        }
        match = tryPick(type, new Object[]{modBus, container}, new String[]{BUS, CONTAINER});
        if (match != null) {
            return match;
        }
        match = tryPick(type, new Object[]{modBus, dist}, new String[]{BUS, DIST});
        if (match != null) {
            return match;
        }
        match = tryPick(type, new Object[]{modBus}, new String[]{BUS});
        if (match != null) {
            return match;
        }
        return tryPick(type, new Object[0], new String[0]);
    }

    private static Match tryPick(Class<?> type, Object[] args, String[] paramNames) {
        for (Object arg : args) {
            if (arg == null) {
                return null;
            }
        }
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != paramNames.length) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < params.length; i++) {
                if (!params[i].getName().equals(paramNames[i])) {
                    same = false;
                    break;
                }
            }
            if (!same) {
                continue;
            }
            if (!ctor.trySetAccessible()) {
                continue;
            }
            return new Match(ctor, args);
        }
        return null;
    }
}
