package io.mirage.mgscript.runtime;

import java.util.List;

/**
 * 脚本能看到的「外部对象」（玩家、世界、服务器、日志器、物品栈……）。
 *
 * <p>游戏 API 全部通过这些接口接入，<b>引擎核心不认识任何 MC 类型</b>——
 * 这样引擎模块可以零 compileOnly 依赖，也避免 {@code net.minecraft.*} 在 app classloader
 * 被加载（那会与 MirageClassLoader 形成两份类，见计划文档 §「类加载」）。
 */
public interface NativeObject {

    /** 类型名，出现在错误消息里（如 "player" / "world"）。 */
    String typeName();

    /** 读属性；不支持时抛 {@link IllegalArgumentException}，由解释器包装成带行号的错误。 */
    Object get(String prop);

    /** 写属性；默认不支持。 */
    default void set(String prop, Object value) {
        throw new IllegalArgumentException(typeName() + " 不支持给属性 '" + prop + "' 赋值");
    }

    /** 调方法；不支持时抛 {@link IllegalArgumentException}。 */
    Object call(String method, List<Object> args);

    /** 有哪些可读属性（用于「你是不是想写 xxx」的候选提示）。 */
    default List<String> properties() {
        return List.of();
    }

    /** 有哪些可调方法（同上）。 */
    default List<String> methods() {
        return List.of();
    }
}
