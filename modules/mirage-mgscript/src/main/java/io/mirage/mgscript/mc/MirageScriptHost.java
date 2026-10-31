package io.mirage.mgscript.mc;

import io.mirage.api.log.MirageLog;
import io.mirage.api.registry.DeferredRegister;
import io.mirage.mgscript.ScriptEngine;
import io.mirage.mgscript.ScriptError;
import io.mirage.mgscript.runtime.EngineHost;
import io.mirage.mgscript.runtime.Env;
import io.mirage.mgscript.runtime.Interp;
import io.mirage.mgscript.runtime.ScriptFn;
import io.mirage.mgscript.runtime.Values;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MGScript 与 MG 运行时的桥：脚本 mod 的唯一入口。
 *
 * <p><b>包名必须留在 {@code io.mirage.mgscript.} 下</b>——MirageClassLoader 依赖这个前缀把它
 * 交给 MCL 加载。本类直接引用 {@code net.minecraft.*}，一旦被 app classloader 加载，
 * 那批 MC 类会被 app 从启动 classpath 再定义一份，与 MCL 版本冲突（LinkageError）。
 *
 * <p>生命周期（由 S6 反射调用 {@link #load()}）：
 * <ol>
 *   <li>建 {@link DeferredRegister}（Item / Block）并挂到 S4 已装的 hook 监听器——
 *       此时注册表仍在 unfreeze 窗口，S6 末尾统一 flush；</li>
 *   <li>执行 {@code scripts/*.mgs}：期间收集 {@code item}/{@code block}/事件订阅；</li>
 *   <li>跑 {@code on init}（P0 里它是「加载期回调」，不是游戏内事件）；</li>
 *   <li>tick 类事件反射挂到 NeoForge 游戏总线（派发点已由 Mixin 织入，见 NeoForgeEventBridge）。</li>
 * </ol>
 */
public final class MirageScriptHost implements EngineHost {

    /** 可用事件名 → NeoForge 事件类。顺序即错误提示里的候选顺序。 */
    private static final Map<String, String> EVENT_CLASSES = new LinkedHashMap<>();

    static {
        EVENT_CLASSES.put("init", null);   // 加载期回调，不走总线
        EVENT_CLASSES.put("tick", "net.neoforged.neoforge.event.tick.ServerTickEvent$Post");
        EVENT_CLASSES.put("server_tick", "net.neoforged.neoforge.event.tick.ServerTickEvent$Post");
        EVENT_CLASSES.put("level_tick", "net.neoforged.neoforge.event.tick.LevelTickEvent$Post");
        EVENT_CLASSES.put("player_tick", "net.neoforged.neoforge.event.tick.PlayerTickEvent$Post");
    }

    private final String modId;
    private final Path modRoot;
    private final ClassLoader mcl;
    private final MirageLog.Channel log;

    private final DeferredRegister<Item> items;
    private final DeferredRegister<Block> blocks;
    private final List<ScriptFn> initHandlers = new ArrayList<>();

    private Interp interp;
    private int itemCount;
    private int blockCount;
    private int eventCount;

    public MirageScriptHost(String modId, Path modRoot, ClassLoader mcl) {
        this.modId = modId;
        this.modRoot = modRoot;
        this.mcl = mcl;
        this.log = MirageLog.of("MGS:" + modId);
        this.items = DeferredRegister.create(Item.class, modId);
        this.blocks = DeferredRegister.create(Block.class, modId);
    }

    /** S6 通过反射调用（LS 侧不 import 本类）。脚本的解析/运行期错误在这里收敛成日志。 */
    public void load() {
        Path scriptsDir = resolveScriptsDir();
        log.info("加载脚本 mod " + modId + " ← " + modRoot.getFileName()
                + (scriptsDir == null ? "（没找到 scripts/ 目录）" : "（" + scriptsDir.getFileName() + "/）"));
        if (scriptsDir == null) {
            throw new IllegalStateException("脚本目录里没有 .mgs 文件: " + modRoot);
        }

        // 先挂注册器再跑脚本：脚本里 declareItem 时 items 已在 S6 的 flush 清单里
        items.register();
        blocks.register();

        try {
            interp = ScriptEngine.runDirectory(scriptsDir, this);
        } catch (ScriptError e) {
            // 诊断块（file:line:col + 源码行 + 候选）直接进日志，这是降门槛的核心体验
            log.error("脚本解析/执行失败，该 mod 被跳过：\n" + ScriptEngine.describe(e));
            throw e;
        } catch (IOException e) {
            log.error("读取脚本文件失败: " + scriptsDir, e);
            throw new IllegalStateException(e);
        }

        runInitHandlers();

        log.info("脚本 mod 就绪: item=" + itemCount + " block=" + blockCount
                + " 事件=" + eventCount);
    }

    /** 有没有 scripts/ 子目录：有就只跑它，没有就把 mod 根当脚本目录（单文件 mod 更省事）。 */
    private Path resolveScriptsDir() {
        Path scripts = modRoot.resolve("scripts");
        if (Files.isDirectory(scripts)) return scripts;
        try {
            if (!ScriptEngine.listScripts(modRoot).isEmpty()) return modRoot;
        } catch (IOException ignored) {
            // 读不了就当没有，下面统一报错
        }
        return null;
    }

    private void runInitHandlers() {
        for (ScriptFn fn : initHandlers) {
            try {
                interp.invokeCallback(fn, List.of());
            } catch (ScriptError e) {
                log.error("on init 执行出错：\n" + ScriptEngine.describe(e));
            }
        }
        initHandlers.clear();
    }

    // ---- EngineHost ----

    @Override
    public void log(String level, String text) {
        switch (level) {
            case "error" -> log.error(text);
            case "warn" -> log.warn(text);
            case "debug" -> log.debug(text);
            default -> log.info(text);
        }
    }

    @Override
    public void declareMod(String id, Map<String, Object> props) {
        if (!id.equals(modId)) {
            log.warn("脚本声明的 mod id \"" + id + "\" 与描述符的 \"" + modId
                    + "\" 不一致，以描述符为准");
        }
        log.debug("declareMod " + id + " " + ScriptEngine.propsToString(props));
    }

    @Override
    public void declareItem(String declaringModId, String name, Map<String, Object> props) {
        items.register(name, () -> createItem(declaringModId, name, props));
        itemCount++;
        log.debug("声明物品 " + declaringModId + ":" + name + " " + ScriptEngine.propsToString(props));
    }

    @Override
    public void declareBlock(String declaringModId, String name, Map<String, Object> props) {
        // Block 立即构造：BlockItem 要复用同一个实例，两个 supplier 各自 new 会得到两个方块
        Block block = createBlock(declaringModId, name, props);
        blocks.register(name, () -> block);
        items.register(name, () -> new BlockItem(block, itemProps(declaringModId, name)));
        blockCount++;
        log.debug("声明方块 " + declaringModId + ":" + name + " " + ScriptEngine.propsToString(props));
    }

    @Override
    public void subscribe(String event, List<String> params, Integer every, ScriptFn body) {
        if (!EVENT_CLASSES.containsKey(event)) {
            throw new IllegalArgumentException("未知事件 \"" + event + "\""
                    + "（可用: " + String.join(", ", EVENT_CLASSES.keySet()) + "）");
        }
        String className = EVENT_CLASSES.get(event);
        if (className == null) {
            initHandlers.add(body);      // on init
        } else {
            bindNeoEvent(event, className, every, body);
        }
        eventCount++;
    }

    @Override
    public Object global(String name) {
        // P0：server / world / player 还没接到脚本（需要 MC 对象包装层，见计划 P1）
        return Env.MISSING;
    }

    @Override
    public String modId() {
        return modId;
    }

    // ---- 物品 / 方块构造 ----

    private Item createItem(String owner, String name, Map<String, Object> props) {
        Item.Properties p = new Item.Properties().setId(itemKey(owner, name));
        Object stack = props.get("stack");
        if (stack instanceof Double d) {
            p = stacksTo(p, (int) Math.round(d));
        }
        return new Item(p);
    }

    private Block createBlock(String owner, String name, Map<String, Object> props) {
        ResourceKey<Block> key = ResourceKey.create(BuiltInRegistries.BLOCK.key(),
                Identifier.fromNamespaceAndPath(owner, name));
        BlockBehaviour.Properties p = BlockBehaviour.Properties.of().setId(key);
        Object hardness = props.get("hardness");
        if (hardness instanceof Double d) {
            p = p.strength(d.floatValue());
        }
        return new Block(p);
    }

    private Item.Properties itemProps(String owner, String name) {
        return new Item.Properties().setId(itemKey(owner, name));
    }

    private static ResourceKey<Item> itemKey(String owner, String name) {
        return ResourceKey.create(BuiltInRegistries.ITEM.key(),
                Identifier.fromNamespaceAndPath(owner, name));
    }

    /**
     * {@code Properties.stacksTo(int)} 在 26.x 里返回新的 Properties（不可变风格），
     * 用反射调用以免版本间签名变化把整个模块编译卡死；失败就保持默认 64 并记一笔。
     */
    private Item.Properties stacksTo(Item.Properties p, int size) {
        try {
            Method m = Item.Properties.class.getMethod("stacksTo", int.class);
            Object out = m.invoke(p, size);
            return out instanceof Item.Properties np ? np : p;
        } catch (ReflectiveOperationException e) {
            log.warn("Item.Properties.stacksTo(" + size + ") 调用失败，堆叠数保持默认（26.2 签名可能变了）");
            return p;
        }
    }

    // ---- 事件桥 ----

    /**
     * 把脚本回调挂到 NeoForge 游戏总线。走的是 {@code IEventBus.addListener(priority,
     * receiveCancelled, Class, Consumer)} —— 该函数式入口本就存在，不需要改事件总线实现。
     */
    private void bindNeoEvent(String eventName, String eventClassName, Integer every, ScriptFn body) {
        try {
            Class<?> eventType = Class.forName(eventClassName, true, mcl);
            Object bus = neoBus();
            Class<?> prioCls = Class.forName("net.neoforged.bus.api.EventPriority", true, mcl);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object normal = Enum.valueOf((Class<? extends Enum>) prioCls.asSubclass(Enum.class), "NORMAL");
            Method addListener = bus.getClass().getMethod("addListener",
                    prioCls, boolean.class, Class.class, Consumer.class);

            int[] seen = {0};
            boolean[] dead = {false};
            Consumer<Object> handler = ev -> {
                if (dead[0]) return;
                if (every != null && every > 1) {
                    seen[0]++;
                    if (seen[0] % every != 0) return;
                }
                try {
                    List<Object> args = new ArrayList<>();
                    for (int i = 0; i < body.params().size(); i++) args.add(null);
                    interp.invokeCallback(body, args);
                } catch (Throwable t) {
                    // 熔断：同一个回调每次 tick 都炸会把日志刷爆，也会掩盖别的错误
                    dead[0] = true;
                    log.error("事件 " + eventName + " 的回调出错，已停用该回调（重启或 reload 后恢复）", t);
                }
            };
            addListener.invoke(bus, normal, false, eventType, handler);
            log.debug("已订阅事件 " + eventName + " → " + eventClassName
                    + (every != null ? "（每 " + every + " tick）" : ""));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("事件类不存在: " + eventClassName
                    + "（该事件在当前版本不可用）", e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("订阅事件 " + eventName + " 失败: " + eventClassName, e);
        }
    }

    private Object neoBus() throws ReflectiveOperationException {
        Class<?> bridge = Class.forName("io.mirage.neoforge.runtime.NeoForgeEventBridge", true, mcl);
        return bridge.getMethod("bus").invoke(null);
    }

    /** 给 LS 侧诊断用：脚本里声明的物品/方块数量。 */
    public String summary() {
        return "item=" + itemCount + " block=" + blockCount + " event=" + eventCount;
    }

    /** 供日志打印属性表（P1 会用于 IR 静态提取）。 */
    static String renderProps(Map<String, Object> props) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(e.getKey()).append('=').append(Values.display(e.getValue()));
        }
        return sb.toString();
    }
}
