package net.neoforged.bus.api;

/**
 * 运行时桥接 stub：NeoForge 事件体系的顶层基类。
 *
 * <p>真实 NeoForge 的生命周期/业务事件都继承它。第一版只做空标识基类，
 * 让 mod 里 {@code extends Event} 的类能链接；具体事件子类的桥接表见 README TODO。
 */
public abstract class Event {
    /** 真实 NeoForge 事件是可取消的；第一版不实现取消语义。 */
    public boolean isCancelable() {
        return false;
    }
}
