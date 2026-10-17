package io.mirage.mixin;

import org.spongepowered.asm.launch.platform.container.IContainerHandle;

import java.util.Collection;
import java.util.List;

/**
 * 空的 IContainerHandle，兜底给 getPrimaryContainer()。
 *
 * MG 没有传统意义上的"容器"（mod jar / launch container），
 * Mixin 内部遍历时需要一个非 null 的 handle，这里返回空集合兜底。
 */
public class MirageContainerHandle implements IContainerHandle {

    @Override public String getId() { return "mirage"; }
    @Override public String getDescription() { return "Mirage primary container"; }
    @Override public String getAttribute(String key) { return null; }
    @Override public Collection<IContainerHandle> getNestedContainers() { return List.of(); }
}
