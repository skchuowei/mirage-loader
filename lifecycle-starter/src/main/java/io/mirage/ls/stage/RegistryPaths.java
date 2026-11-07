package io.mirage.ls.stage;

/**
 * 注册表路径归一（纯 JDK，无游戏类依赖，可离线验证）。
 *
 * <p><b>为什么需要它（已知 bug #3）</b>：{@code DeferredRegister#registryPath()} 的取值有两种来源——
 * <ol>
 *   <li>NeoForge 兼容层显式带入的注册表 key 路径，是 **蛇形**：
 *       {@code creative_mode_tab}、{@code point_of_interest_type}；</li>
 *   <li>拿不到时退化为元素类型简单名小写，是 **无下划线** 的：{@code creativemodetab}。</li>
 * </ol>
 * 实机日志（`expore:creative_mode_tab 桥接 1 个条目`）证明创造模式标签页走的是第 ①种，
 * 而旧代码按第 ②种的形态硬比较（{@code "creativemodetab".equals(path)}），分支恒为 false
 * ⇒ intrusive holder 从未被创建 ⇒ {@code MappedRegistry.register} 断言
 * {@code Missing intrusive holder for ResourceKey[.../expore:expore]} ⇒ expore 的创造标签页不出现。
 *
 * <p><b>所以比较前先归一</b>：去掉下划线 + 转小写，两种形态天然重合。这样以后新增
 * 「元素类型简单名 ≠ key 路径」的注册表（{@code point_of_interest_type} → {@code PoiType}）
 * 也不会再踩同一个坑。
 *
 * <p>放在独立类里而不是塞进 {@link RegistryWindow} 的私有方法：RegistryWindow 引了整条
 * 反射/游戏依赖链，纯 JVM 根本加载不了它，抽出来才能对着生产代码跑离线断言
 * （{@code scripts/verify_registry_paths.ps1}）。
 */
public final class RegistryPaths {

    private RegistryPaths() {}

    /** 归一后的创造模式标签页路径。 */
    public static final String CREATIVE_MODE_TAB = "creativemodetab";

    /** 归一后的物品注册表路径。 */
    public static final String ITEM = "item";

    /**
     * 归一：去掉下划线，其余字符逐字符转小写。
     *
     * <p>用 {@link Character#toLowerCase(char)} 而不是 {@code String.toLowerCase(Locale)}
     * ——注册表名是 ASCII，逐字符转换不引入 locale 参与（土耳其语 locale 下
     * {@code "I".toLowerCase()} 会变成 {@code ı}，这类坑在别人机器上才炸）。
     *
     * @return 归一后的字符串；{@code null} / 空串返回空串（调用方比较时自然为 false）
     */
    public static String normalize(String registryPath) {
        if (registryPath == null || registryPath.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(registryPath.length());
        for (int i = 0; i < registryPath.length(); i++) {
            char c = registryPath.charAt(i);
            if (c == '_') {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 是否为创造模式标签页注册表（蛇形 {@code creative_mode_tab} 与 {@code creativemodetab} 都命中）。 */
    public static boolean isCreativeModeTab(String registryPath) {
        return CREATIVE_MODE_TAB.equals(normalize(registryPath));
    }

    /** 是否为物品注册表（归一后 {@code item}）。 */
    public static boolean isItem(String registryPath) {
        return ITEM.equals(normalize(registryPath));
    }
}
