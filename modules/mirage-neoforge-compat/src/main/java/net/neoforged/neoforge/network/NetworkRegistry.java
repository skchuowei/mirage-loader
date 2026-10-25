package net.neoforged.neoforge.network;

import net.minecraft.resources.Identifier;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Mirage NeoForge 兼容层 —— 旧 API 频道工厂。
 *
 * <p>对应 NeoForge 1.20.x 的 {@code NetworkRegistry.newSimpleChannel(...)}。</p>
 */
public final class NetworkRegistry {

    private NetworkRegistry() {}

    /** 完整签名（版本供给 + 双端版本校验谓词；本地通路不做协商，全部接受）。 */
    public static SimpleChannel newSimpleChannel(Identifier name,
            Supplier<String> networkProtocolVersion,
            Predicate<String> clientAcceptedVersions,
            Predicate<String> serverAcceptedVersions) {
        String v = networkProtocolVersion != null ? networkProtocolVersion.get() : "1";
        return new SimpleChannel(name, v);
    }

    /** 完整签名（缺省版本）。 */
    public static SimpleChannel newSimpleChannel(Identifier name) {
        return new SimpleChannel(name, "1");
    }

    /** 字符串名重载（mod 可能未用 Identifier 工厂）。 */
    public static SimpleChannel newSimpleChannel(String name) {
        int i = name.indexOf(':');
        Identifier id = i >= 0 ? Identifier.fromNamespaceAndPath(name.substring(0, i), name.substring(i + 1)) : Identifier.withDefaultNamespace(name);
        return new SimpleChannel(id, "1");
    }
}
