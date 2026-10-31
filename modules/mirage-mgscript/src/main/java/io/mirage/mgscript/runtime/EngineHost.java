package io.mirage.mgscript.runtime;

import java.util.List;
import java.util.Map;

/**
 * 引擎与宿主（MG 加载器）之间的唯一接口。
 *
 * <p>引擎本身<b>不知道</b> Minecraft、不知道注册表、不知道日志框架；所有副作用都从这里出去。
 * 这样做的直接好处：引擎可以在纯 JVM 下被单测（{@code scripts/verify_mgscript.ps1}），
 * 不需要启动游戏。
 */
public interface EngineHost {

    /** 日志：level 为 info / warn / error / debug。 */
    void log(String level, String text);

    /** 顶层 {@code mod "id" ...} 声明。 */
    void declareMod(String id, Map<String, Object> props);

    /** {@code item "ruby"} 声明（属性已求值成普通 Java 值）。 */
    void declareItem(String modId, String name, Map<String, Object> props);

    /** {@code block "ruby_ore"} 声明。 */
    void declareBlock(String modId, String name, Map<String, Object> props);

    /**
     * 事件订阅。
     *
     * @param every null 表示每次触发；非 null 是周期（tick）
     */
    void subscribe(String event, List<String> params, Integer every, ScriptFn body);

    /**
     * 未知全局名（如 {@code server} / {@code world}）由宿主提供；返回 null 表示没有这个名字。
     * 引擎据此决定是「未定义变量」还是「宿主对象」。
     */
    Object global(String name);

    /** 当前脚本所属 modId（事件回调里给 {@code this_mod} 之类的自省用）。 */
    default String modId() {
        return "";
    }
}
