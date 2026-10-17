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

    /** 可选："client" / "server" / "both"，默认 both */
    public String side = "both";

    public boolean isValid() {
        if (id == null || !id.matches("[a-z][a-z0-9-_]{1,63}")) return false;
        if (version == null || version.isEmpty()) return false;
        return true;
    }
}
