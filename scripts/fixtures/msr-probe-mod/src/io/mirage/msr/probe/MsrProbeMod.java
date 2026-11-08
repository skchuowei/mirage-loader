package io.mirage.msr.probe;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * MSR P3 · <b>合成探针 mod</b>（2026-11-07）。
 *
 * <p><b>用途</b>：给 P3 的「游戏侧观测 + diff 驱动」提供<b>真值已知</b>的验收基准 ——
 * 这个 mod 注册了什么、订阅了什么，是<b>写在源码里、可逐条核对</b>的，
 * 所以 P3 跑完能直接判断「观测有没有漏、有没有多」。
 *
 * <p><b>刻意覆盖的观测面</b>：
 * <ul>
 *   <li><b>注册项</b>：1 个方块（{@code msrprobe:probe_block}）+ 1 个物品
 *       （{@code msrprobe:probe_item}）⇒ 对应 {@code MappedRegistry#register} 插桩点；</li>
 *   <li><b>mod bus 订阅</b>：{@code FMLCommonSetupEvent}（是 {@code IModBusEvent}）
 *       ⇒ 对应 {@code EventListenerFactory#create} 插桩点；</li>
 *   <li><b>game bus 订阅</b>：{@code LevelTickEvent.Post}（注册到 {@code NeoForge.EVENT_BUS}）
 *       ⇒ 验证「非 mod bus 的事件也能被观测到」。</li>
 * </ul>
 *
 * <p><b>刻意避开的一个坑</b>：game bus 事件<b>不能</b>和 mod bus 事件放在同一个类里 ——
 * {@code modBus.register(this)} 会把该类里<b>所有</b> {@code @SubscribeEvent} 方法都注册到
 * mod bus，于是 game bus 事件被拒：
 * 「This bus only accepts subclasses of interface IModBusEvent」⇒ mod 加载失败。
 * 本轮实测：{@code neop0test} 与本探针的第一版都踩了这个坑。
 * 所以 game bus 监听必须拆到<b>独立类</b>，只注册到 {@code NeoForge.EVENT_BUS}。
 */
@Mod("msrprobe")
public class MsrProbeMod {

    /** 方块注册器。 */
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, "msrprobe");

    /** 物品注册器。 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, "msrprobe");

    /**
     * 探针方块 —— P3 应当观测到 {@code msrprobe:probe_block}。
     *
     * <p>26.2 起 {@code Block}'s {@code Properties} <b>必须</b>带 id，否则注册期抛
     * {@code NullPointerException: Block id not set}（本轮实测）。所以用
     * {@code register(String, Function<Identifier, ...>)} 这个重载，让注册器把 id 传进来。
     */
    public static final DeferredHolder<Block, Block> PROBE_BLOCK =
            BLOCKS.register("probe_block", id -> new Block(
                    BlockBehaviour.Properties.of()
                            .setId(ResourceKey.create(Registries.BLOCK, id))));

    /** 探针物品 —— P3 应当观测到 {@code msrprobe:probe_item}（同上，必须 setId）。 */
    public static final DeferredHolder<Item, Item> PROBE_ITEM =
            ITEMS.register("probe_item", id -> new Item(
                    new Item.Properties()
                            .setId(ResourceKey.create(Registries.ITEM, id))));

    public MsrProbeMod(IEventBus modBus) {
        System.out.println("[MSRProbe] 构造，modBus=" + modBus.getClass().getName());
        // 注意方向：是「注册器.register(bus)」，不是「bus.register(注册器)」。
        // 后者会命中 IEventBus.register(Object) 而编译期不报错，运行期抛
        // IllegalArgumentException: class DeferredRegister has no @SubscribeEvent methods
        // —— 本轮实测踩过。
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        // mod bus 事件：只注册本类（本类只含 FMLCommonSetupEvent）
        modBus.register(this);
        // game bus 事件：必须走 NeoForge.EVENT_BUS，且监听器要是<b>独立类</b>
        NeoForge.EVENT_BUS.register(new GameBusListener());
        System.out.println("[MSRProbe] 注册完成");
    }

    /** mod bus 事件（P3 应当观测到这个订阅）。 */
    @SubscribeEvent
    public void onCommonSetup(FMLCommonSetupEvent event) {
        System.out.println("[MSRProbe] FMLCommonSetupEvent 已生效");
    }
}
