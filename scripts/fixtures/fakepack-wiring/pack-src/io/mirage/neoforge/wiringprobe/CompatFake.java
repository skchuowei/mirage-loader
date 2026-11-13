package io.mirage.neoforge.wiringprobe;

/** W1 门禁夹具：验证 io.mirage.neoforge.* 也不进 pack / 不记账。 */
public final class CompatFake {
    public static String ping() { return "ION-FAKE-OK"; }
}
