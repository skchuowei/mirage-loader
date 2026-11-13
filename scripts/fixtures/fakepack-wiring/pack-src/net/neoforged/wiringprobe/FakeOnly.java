package net.neoforged.wiringprobe;

/** W1 门禁夹具：只在 pack 里存在、classpath 里绝对没有的 neo 类。 */
public final class FakeOnly {
    public static String ping() { return "FAKE-OK"; }
}
