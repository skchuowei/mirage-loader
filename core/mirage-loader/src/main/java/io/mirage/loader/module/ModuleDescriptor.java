package io.mirage.loader.module;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 可选模块的描述符 —— 对应模块 jar 内的 {@code META-INF/mirage-module.json}。
 *
 * <p><b>为什么需要它</b>：AG 总计划 G9「模块懒加载」的目标是「可选模块默认不进 fat jar、
 * 不参与启动期扫描，只有 mod 显式声明 {@code requires} 时才动态加入类加载器」。
 * 要做到「按需加载」，就必须先有办法回答两个问题：
 * <ol>
 *   <li>这个 jar <b>是不是</b>一个模块？它叫什么 id？</li>
 *   <li>这个模块依赖哪些别的模块？</li>
 * </ol>
 * 本类就是这两个问题的答案的载体。
 *
 * <p><b>格式</b>：
 * <pre>{@code
 * {
 *   "id": "mirage-config",
 *   "version": "0.1.0",
 *   "provides": ["dev.mirage.config"],
 *   "requires": [],
 *   "entrypoint": "dev.mirage.config.ConfigModule"
 * }
 * }</pre>
 *
 * <p><b>包名约定（重要，写错必然 {@code NoClassDefFoundError}）</b>：
 * {@code provides} 声明的公共 API 包前缀<b>不得</b>以 {@code io.mirage.} 开头。
 * 原因是 {@code MirageClassLoader.loadClass} 把整个 {@code io.mirage.} 前缀无条件委派给
 * {@code SystemClassLoader}（见该类第 54 行，例外只有 {@code io.mirage.neoforge.} /
 * {@code io.mirage.fabric.} / {@code io.mirage.mgscript.}），而模块 jar 只加在
 * {@code MirageClassLoader} 上、不在 app loader 的 classpath 里 ——
 * 若模块 API 落在 {@code io.mirage.} 下，委派后必然找不到类。
 * 约定的写法是 {@code dev.mirage.<模块>} 这类独立前缀。
 *
 * <p><b>纯逻辑</b>：本类不碰文件系统与类加载器，只做「JSON 文本 → 描述符」，
 * 因此可以脱离游戏单独做 JVM 回归（见 {@code scripts/verify_module_loading.ps1}）。
 */
public final class ModuleDescriptor {

    /** 模块 id 正则，与 mod id 同风格。 */
    public static final String ID_PATTERN = "[a-z][a-z0-9-]{1,63}";

    /** 描述符在 jar 内的路径。 */
    public static final String RESOURCE_PATH = "META-INF/mirage-module.json";

    private static final Gson GSON = new Gson();

    /** 模块唯一 id。 */
    public String id;

    /** 模块版本。 */
    public String version;

    /** 本模块提供的公共 API 包前缀列表。 */
    public List<String> provides = new ArrayList<>();

    /** 本模块依赖的其他模块 id。 */
    public List<String> requires = new ArrayList<>();

    /** 可选：模块初始化入口类全限定名（由 MirageClassLoader 加载后反射调用）。 */
    public String entrypoint;

    public ModuleDescriptor() {
    }

    public ModuleDescriptor(String id, String version, List<String> provides,
                            List<String> requires, String entrypoint) {
        this.id = id;
        this.version = version;
        this.provides = provides == null ? new ArrayList<>() : new ArrayList<>(provides);
        this.requires = requires == null ? new ArrayList<>() : new ArrayList<>(requires);
        this.entrypoint = entrypoint;
    }

    /**
     * 解析描述符 JSON 文本。
     *
     * @return 解析结果；文本非法或缺 id/version 时返回 {@code null}（调用方负责告警并跳过）
     */
    public static ModuleDescriptor parse(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            return null;
        }
        try {
            JsonObject root = GSON.fromJson(jsonText, JsonObject.class);
            if (root == null) {
                return null;
            }
            ModuleDescriptor d = new ModuleDescriptor();
            d.id = optString(root, "id");
            d.version = optString(root, "version");
            d.provides = optStringList(root, "provides");
            d.requires = optStringList(root, "requires");
            d.entrypoint = optString(root, "entrypoint");
            if (!d.isValid()) {
                return null;
            }
            return d;
        } catch (RuntimeException e) {
            // 注意：只能捕 RuntimeException。JsonParseException 本身就是 RuntimeException 的子类，
            // 写成 `catch (JsonParseException | RuntimeException e)` 是**非法 multi-catch**
            // （编译错误：multi-catch 中的异常类型不能有继承关系）——本文件第一版就踩了这个。
            return null;
        }
    }

    /** 描述符是否合法：id 合规、version 非空、provides 不使用被保留的 {@code io.mirage.} 前缀。 */
    public boolean isValid() {
        if (id == null || !id.matches(ID_PATTERN)) {
            return false;
        }
        if (version == null || version.isEmpty()) {
            return false;
        }
        for (String p : provides) {
            if (p == null || p.isEmpty()) {
                return false;
            }
            if (p.startsWith("io.mirage.")) {
                // 见类注释「包名约定」：这里返回 false，让调用方明确报错而不是静默加载后炸在
                // NoClassDefFoundError 上（那种失败的堆栈离原因很远）。
                return false;
            }
        }
        return true;
    }

    /** 描述符是否违规使用了被保留的 {@code io.mirage.} 前缀（供告警信息更精确）。 */
    public boolean usesReservedPackage() {
        for (String p : provides) {
            if (p != null && p.startsWith("io.mirage.")) {
                return true;
            }
        }
        return false;
    }

    public List<String> requires() {
        return requires == null ? Collections.emptyList() : requires;
    }

    public List<String> provides() {
        return provides == null ? Collections.emptyList() : provides;
    }

    @Override
    public String toString() {
        return id + " v" + version;
    }

    // ---- Gson 辅助：容忍 null / 非字符串元素 ----

    private static String optString(JsonObject root, String key) {
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        try {
            return e.getAsString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static List<String> optStringList(JsonObject root, String key) {
        List<String> out = new ArrayList<>();
        JsonElement e = root.get(key);
        if (e == null || !e.isJsonArray()) {
            return out;
        }
        JsonArray arr = e.getAsJsonArray();
        for (JsonElement item : arr) {
            if (item == null || item.isJsonNull()) {
                continue;
            }
            try {
                out.add(item.getAsString());
            } catch (RuntimeException ignored) {
                // 非字符串元素跳过，不让一个坏元素废掉整个描述符
            }
        }
        return out;
    }
}
