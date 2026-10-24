package net.neoforged.fml.javafmlmod;

import io.mirage.loader.sandbox.Sink;
import net.neoforged.bus.api.FakeEventBus;
import net.neoforged.bus.api.IEventBus;

/**
 * 沙盒 fake：{@code FMLJavaModLoadingContext} 全局单例。
 * {@code getModEventBus()} 返回录制用 {@link FakeEventBus} 单例。
 */
public final class FMLJavaModLoadingContext {

    private static final FakeEventBus MOD_BUS = new FakeEventBus();
    private static final FMLJavaModLoadingContext INSTANCE = new FMLJavaModLoadingContext();

    public static FMLJavaModLoadingContext get() {
        Sink.touch("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext.get");
        return INSTANCE;
    }

    public IEventBus getModEventBus() {
        Sink.touch("net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext.getModEventBus");
        return MOD_BUS;
    }

    private FMLJavaModLoadingContext() {}
}
