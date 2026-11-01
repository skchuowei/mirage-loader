package io.mirage.loader;

import java.util.Map;

/**
 * mirage.mod.json 的 POJO。
 * 放在 Mod jar 的 META-INF/mirage.mod.json。
 */
public class ModManifest {
    /** 必需：Mod 唯一 id，正则 [a-z][a-z0-9-_]{1,63} */
    public String id;

    /** 必需：语义化版本，如 "1.0.0" */
    public String version;

    /** 可选：展示名，默认同 id */
    public String name;

    /** 可选：主类全限定名，M4 才实例化 */
    public String mainClass;

    /** 可选：依赖表 modId -> 版本范围（M2 只做存在性校验） */
    public Map<String, String> depends;

    /**
     * 可选：本 mod 需要的**可选模块** id 列表（G9 模块懒加载）。
     *
     * <p>与 {@link #depends} 语义区分：
     * <ul>
     *   <li>{@code depends} = mod 依赖（modId → 版本范围），缺了 mod 起不来；</li>
     *   <li>{@code requires} = 模块依赖（模块 id 列表），缺了功能降级但游戏继续。</li>
     * </ul>
     *
     * <p>模块的 jar 默认在 {@code <gameDir>/modules/}，不进 fat jar、不参与启动期扫描；
     * 只有被某个 mod 声明了才会被动态加入类加载器。详见
     * {@code io.mirage.loader.module.ModuleRegistry}。
     */
    public java.util.List<String> requires;

    /** 可选："client" / "server" / "both"，默认 both */
    public String side = "both";

    public boolean isValid() {
        if (id == null || !id.matches("[a-z][a-z0-9-_]{1,63}")) return false;
        if (version == null || version.isEmpty()) return false;
        return true;
    }
}
