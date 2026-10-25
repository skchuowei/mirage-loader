package io.mirage.test;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Mirage NeoForge 兼容层 P0 冒烟测试 mod（极简）：
 * 注入式构造 + modBus.register(this) + 两个游戏事件监听。
 * 日志标记：[NeoP0Test]
 */
@Mod("neop0test")
public class NeoP0TestMod {

    private static int levelTicks = 0;

    public NeoP0TestMod(IEventBus modBus) {
        System.out.println("[NeoP0Test] 构造，modBus=" + modBus.getClass().getName());
        modBus.register(this);
        System.out.println("[NeoP0Test] register 完成");
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        levelTicks++;
        if (levelTicks == 1 || levelTicks % 600 == 0) {
            System.out.println("[NeoP0Test] LevelTickEvent.Post 已生效 count=" + levelTicks);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        System.out.println("[NeoP0Test] PlayerLoggedInEvent 已生效 player=" + event.getEntity().getName().getString());
    }
}
