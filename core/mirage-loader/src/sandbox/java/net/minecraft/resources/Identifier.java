package net.minecraft.resources;

/**
 * 沙盒 fake：MC {@code Identifier} 最小占位。
 * 26.x 真名为 Identifier（无混淆），提供 mod 静态初始化可能触达的工厂与取值方法。
 */
public class Identifier {

    public static Identifier fromNamespaceAndPath(String namespace, String path) {
        return new Identifier(namespace, path);
    }

    private final String namespace;
    private final String path;

    public Identifier(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getPath() {
        return path;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
