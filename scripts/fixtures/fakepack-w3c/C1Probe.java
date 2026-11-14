package probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * W3-C1 行为层探针（纯 headless，不创建任何窗口）。
 *
 * 只输出 ASCII 的 KEY=VALUE，避免 Windows 控制台编码问题（C76）。
 * classpath 中给真实 MC jar 仅为让反射解析方法签名里的 MC 类型，不启动游戏。
 */
public final class C1Probe {

    public static void main(String[] args) throws Exception {
        List<String> names = Files.readAllLines(Path.of(args[0]));
        int ok = 0;
        for (String n : names) {
            if (n.isBlank()) continue;
            try {
                Class.forName(n);
                ok++;
            } catch (Throwable t) {
                System.out.println("LOADFAIL " + n + " " + t.getClass().getSimpleName());
            }
        }
        System.out.println("LOAD_TOTAL=" + names.size());
        System.out.println("LOAD_OK=" + ok);

        // 真实值（N1 原则）：带参构造器保存的实参必须原样保留，不能是编造值/new 对象。
        // 特意选签名只依赖 java.util.Map 的类：RecipeMap 会拉起 Guava 依赖链，
        // 与 clean-room 实现本身无关，不该让门禁因第三方库缺席而假红。
        try {
            Class<?> k = Class.forName(
                    "net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent");
            Constructor<?> c = k.getConstructor(java.util.Map.class);
            java.util.Map<String, String> marker = new java.util.HashMap<>();
            marker.put("k", "w3c1-marker");
            Object ev = c.newInstance(marker);
            java.lang.reflect.Field f = k.getDeclaredField("f0");
            f.setAccessible(true);
            System.out.println("FIELD_IDENTITY=" + (f.get(ev) == marker));
        } catch (Throwable t) {
            System.out.println("FIELD_ERROR=" + t.toString());
        }

        // 守卫层另核对 getter 源码里是 return this.f0（不在此处触发 MC 类加载）

        // 未支持方法必须抛 UOE 且消息带「类名#方法名」，不得返回编造值
        printUoe("UOE_1", "net.neoforged.neoforge.common.ItemAbility", "getActions", null, true);
        try {
            Class<?> k = Class.forName("net.neoforged.neoforge.client.settings.KeyConflictContext");
            Object universal = k.getField("UNIVERSAL").get(null);
            printUoe("UOE_2", null, "isActive", universal, false);
        } catch (Throwable t) {
            System.out.println("UOE_2=NO_CLASS");
        }

        // DEFAULT：不给 -D 开关时诊断 fallback 必须关闭
        System.out.println("RUNTIME_OFF=" + (System.getProperty("mirage.msr.fakepack.runtime") == null));
        System.out.println("KILL_OFF=" + (System.getProperty("mirage.msr.fakeprovider") == null));
    }

    private static void printUoe(String key, String className, String method, Object target, boolean isStatic) {
        try {
            Class<?> k = Class.forName(className != null ? className : target.getClass().getName());
            java.lang.reflect.Method m = k.getMethod(method);
            Object instance = isStatic ? null : (target != null ? target : k.getDeclaredConstructor().newInstance());
            try {
                Object r = m.invoke(instance);
                System.out.println(key + "=RETURNED_VALUE " + r);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                String want = k.getSimpleName() + "#" + method;
                System.out.println(key + "="
                        + (cause instanceof UnsupportedOperationException
                            && cause.getMessage() != null
                            && cause.getMessage().contains(want)
                            && cause.getMessage().contains("不返回编造值") ? "OK" : "BAD"));
            }
        } catch (Throwable t) {
            System.out.println(key + "=ERROR " + t.getClass().getSimpleName());
        }
    }
}
