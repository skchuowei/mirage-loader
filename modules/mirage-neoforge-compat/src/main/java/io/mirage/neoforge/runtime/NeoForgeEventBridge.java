package io.mirage.neoforge.runtime;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.function.BooleanSupplier;

/**
 * 游戏事件派发入口：Mixin 只调用这里的静态方法，事件构造细节留在本类。
 *
 * <p>全局单例总线与 {@code FMLJavaModLoadingContext.getModEventBus()} 是同一个实例，
 * 因此 mod 用 {@code @SubscribeEvent} 或 {@code addListener} 注册的监听器都能收到这里派发的事件。
 */
public final class NeoForgeEventBridge {

    private static final NeoEventBusImpl BUS = new NeoEventBusImpl();

    private NeoForgeEventBridge() {}

    public static NeoEventBusImpl bus() {
        return BUS;
    }

    public static boolean post(Object event) {
        return BUS.post(event);
    }

    // ---- tick ----

    public static void postLevelTickPre(Level level, BooleanSupplier hasTime) {
        BUS.post(new LevelTickEvent.Pre(hasTime, level));
    }

    public static void postLevelTickPost(Level level, BooleanSupplier hasTime) {
        BUS.post(new LevelTickEvent.Post(hasTime, level));
    }

    public static void postServerTickPre(MinecraftServer server, BooleanSupplier hasTime) {
        BUS.post(new ServerTickEvent.Pre(hasTime, server));
    }

    public static void postServerTickPost(MinecraftServer server, BooleanSupplier hasTime) {
        BUS.post(new ServerTickEvent.Post(hasTime, server));
    }

    public static boolean postEntityTickPre(Entity entity) {
        EntityTickEvent.Pre event = new EntityTickEvent.Pre(entity);
        BUS.post(event);
        return event.isCanceled();
    }

    public static void postEntityTickPost(Entity entity) {
        BUS.post(new EntityTickEvent.Post(entity));
    }

    public static void postPlayerTickPre(Player player) {
        BUS.post(new PlayerTickEvent.Pre(player));
    }

    public static void postPlayerTickPost(Player player) {
        BUS.post(new PlayerTickEvent.Post(player));
    }

    // ---- 实体 / 生物 ----

    public static boolean postEntityJoinLevel(Entity entity, Level level) {
        EntityJoinLevelEvent event = new EntityJoinLevelEvent(entity, level);
        BUS.post(event);
        return event.isCanceled();
    }

    public static boolean postLivingHeal(LivingEntity entity, float amount) {
        LivingHealEvent event = new LivingHealEvent(entity, amount);
        BUS.post(event);
        return event.isCanceled();
    }

    public static boolean postLivingIncomingDamage(LivingEntity entity, DamageSource source, float amount) {
        LivingIncomingDamageEvent event = new LivingIncomingDamageEvent(entity, source, amount);
        BUS.post(event);
        return event.isCanceled();
    }

    public static void postLivingDamagePre(LivingEntity entity, DamageSource source, float amount) {
        BUS.post(new LivingDamageEvent.Pre(entity, source, amount));
    }

    public static void postLivingDamagePost(LivingEntity entity, DamageSource source, float amount) {
        BUS.post(new LivingDamageEvent.Post(entity, source, amount, amount, amount, true, 0));
    }

    public static boolean postLivingDeath(LivingEntity entity, DamageSource source) {
        LivingDeathEvent event = new LivingDeathEvent(entity, source);
        BUS.post(event);
        return event.isCanceled();
    }

    // ---- 玩家 / 指令 ----

    public static void postPlayerLoggedIn(Player player) {
        BUS.post(new PlayerEvent.PlayerLoggedInEvent(player));
    }

    public static boolean postBreakBlock(Level level, BlockPos pos, BlockState state, Player player) {
        BreakBlockEvent event = new BreakBlockEvent(level, pos, state, player);
        BUS.post(event);
        return event.isCanceled();
    }

    public static void postRegisterCommands(Commands commands, Commands.CommandSelection selection, CommandBuildContext context) {
        BUS.post(new RegisterCommandsEvent(commands.getDispatcher(), selection, context));
    }
}
