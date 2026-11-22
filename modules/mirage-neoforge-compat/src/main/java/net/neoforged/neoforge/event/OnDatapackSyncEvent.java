package net.neoforged.neoforge.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.Event;

/**
 * 运行时桥接 stub：NeoForge {@code OnDatapackSyncEvent}（clean-room，部分实现）。
 *
 * <p>形状取自 26.2.0.90 universal jar 的公开字节码签名观察：继承 {@link Event}，
 * 三个 final 字段（playerList / player / recipeTypesToSend），2 参构造器，
 * getter 与两个 {@code sendRecipes} 重载。
 *
 * <p>实现边界（N1 原则）：构造器保存实参、简单 getter 返回真实字段值；
 * 涉及第三方集合（fastutil {@code ReferenceSet}）与跨玩家/recipes 发送语义的方法
 * 抛 {@link UnsupportedOperationException} 带「类名#方法名」，<b>不返回编造值</b>。
 * 这是部分实现：解决链接期形状，不宣称行为兼容。
 *
 * <p>合规边界：NeoForge 为 LGPL 2.1；不复制、不反编译移植其实现代码。
 */
public class OnDatapackSyncEvent extends Event {

    private final PlayerList playerList;
    private final ServerPlayer player;
    private final it.unimi.dsi.fastutil.objects.ReferenceSet<RecipeType<?>> recipeTypesToSend;

    public OnDatapackSyncEvent(PlayerList playerList, ServerPlayer player) {
        this.playerList = playerList;
        this.player = player;
        this.recipeTypesToSend = null;
    }

    /** 返回构造时传入的真实 {@link PlayerList} 实例。 */
    public PlayerList getPlayerList() {
        return playerList;
    }

    /** 返回构造时传入的真实 {@link ServerPlayer} 实例。 */
    public ServerPlayer getPlayer() {
        return player;
    }

    /** 需要真实玩家集合语义，MG 侧不编造流。 */
    public java.util.stream.Stream<ServerPlayer> getRelevantPlayers() {
        throw new UnsupportedOperationException(
                "OnDatapackSyncEvent#getRelevantPlayers: 不返回编造值（clean-room 部分实现）");
    }

    /** 需要真实网络发送语义，MG 侧不编造行为。 */
    public void sendRecipes(RecipeType<?>... types) {
        throw new UnsupportedOperationException(
                "OnDatapackSyncEvent#sendRecipes(varargs): 不返回编造值（clean-room 部分实现）");
    }

    /** 需要真实网络发送语义，MG 侧不编造行为。 */
    public void sendRecipes(Iterable<RecipeType<?>> types) {
        throw new UnsupportedOperationException(
                "OnDatapackSyncEvent#sendRecipes(Iterable): 不返回编造值（clean-room 部分实现）");
    }

    /** 返回类型涉及 fastutil 第三方链，按 C104 暗门②不暴露。 */
    public java.util.Set<RecipeType<?>> getRecipeTypesToSend() {
        throw new UnsupportedOperationException(
                "OnDatapackSyncEvent#getRecipeTypesToSend: 不返回编造值（clean-room 部分实现）");
    }
}
