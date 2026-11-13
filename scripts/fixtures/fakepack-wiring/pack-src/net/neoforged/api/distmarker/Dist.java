package net.neoforged.api.distmarker;

/**
 * W1 门禁夹具：与 compat stub 同名，但带一个真 stub 没有的 PACK_MARKER。
 * 若 fallback 顶掉了 compat 资源，探针会看到这个字段 ⇒ 「compat 优先」断言变红。
 */
public final class Dist {
    public static final String PACK_MARKER = "PACK";
    public static String ping() { return "PACK-DIST"; }
}
