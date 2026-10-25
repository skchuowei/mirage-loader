package net.neoforged.neoforge.network.event;

import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Mirage NeoForge 兼容层 —— 网络 payload 注册事件（新 API）。
 *
 * <p>mod 在 {@code @SubscribeEvent} 里监听：
 * <pre>{@code
 * @SubscribeEvent
 * public static void register(RegisterPayloadHandlersEvent event) {
 *     PayloadRegistrar registrar = event.registrar("1");
 *     registrar.playToServer(...);
 * }
 * }</pre>
 * Mirage 在 CommonSetup 之后向 mod bus post 本事件。</p>
 */
public class RegisterPayloadHandlersEvent extends Event {

    private PayloadRegistrar cachedRegistrar;

    /** 取（或创建）本事件的 registrar；同一事件内多次调用返回同一实例。 */
    public PayloadRegistrar registrar(String version) {
        if (cachedRegistrar == null) {
            cachedRegistrar = new PayloadRegistrar(version);
        }
        return cachedRegistrar;
    }
}
