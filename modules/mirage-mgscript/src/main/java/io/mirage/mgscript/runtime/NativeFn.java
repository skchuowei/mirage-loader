package io.mirage.mgscript.runtime;

import java.util.List;

/** Java 侧实现的函数（内建库 / 游戏 API）。 */
public interface NativeFn extends MgCallable {
    String name();
}
