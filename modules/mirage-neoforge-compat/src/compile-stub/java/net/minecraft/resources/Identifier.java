package net.minecraft.resources;

/**
 * 编译期镜像（compile-stub）：见 {@link net.minecraft.core.Registry} 头部说明。
 *
 * <p>静态工厂与 26.2 真 jar 签名一致（运行时由 mcl 从真 MC jar 加载真类）。</p>
 */
public class Identifier {
    public String getNamespace() {
        return "";
    }

    public String getPath() {
        return "";
    }

    // ---- 静态工厂（与 26.2 真类签名一致；stub 仅占位） ----

    public static Identifier parse(String s) {
        return new Identifier();
    }

    public static Identifier tryParse(String s) {
        return new Identifier();
    }

    public static Identifier fromNamespaceAndPath(String namespace, String path) {
        return new Identifier();
    }

    public static Identifier withDefaultNamespace(String path) {
        return new Identifier();
    }
}
