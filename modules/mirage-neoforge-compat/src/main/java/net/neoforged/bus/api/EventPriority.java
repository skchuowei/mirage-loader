package net.neoforged.bus.api;

/** 运行时桥接 stub：监听器优先级，数值越小越先执行。 */
public enum EventPriority {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST;

    /** 收取消事件的高优先级标记（对齐 NeoForge 语义，仅用于排序比较）。 */
    public static final EventPriority[] VALUES = values();
}
