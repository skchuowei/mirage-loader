package com.example.neomod;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.objectworld.Event;

/**
 * Fixture 主类：@Mod("exampleneomod")，含一个 @SubscribeEvent 静态方法，
 * 以及一个 public static 的 DeferredRegister 字段。
 */
@Mod("exampleneomod")
public class NeoModMain {

    /** DeferredRegister 静态字段 —— 扫描器应登记为 registry hint。 */
    public static final DeferredRegister<?> ITEMS = null;

    /** @SubscribeEvent 静态方法 —— 扫描器应登记为 event listener。 */
    @SubscribeEvent
    public static void onEvent(Event e) {
        // no-op
    }
}
