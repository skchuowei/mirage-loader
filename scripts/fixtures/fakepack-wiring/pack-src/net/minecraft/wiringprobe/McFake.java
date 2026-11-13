package net.minecraft.wiringprobe;

/** W1 门禁夹具：验证窄前缀闸 —— pack 里即使有 mc 类，真启动也绝不加载它。 */
public final class McFake {
    public static String ping() { return "MC-FAKE-OK"; }
}
