package net.neoforged.neoforge.network;

import net.minecraft.world.entity.player.Player;

/**
 * Mirage NeoForge 兼容层 —— 网络事件包（含 {@link Context}）。
 *
 * <p>Mirage 本地通路里 Context 提供方向、sender（可能为 null）与
 * enqueueWork（立即执行）。</p>
 */
public final class NetworkEvent {

    private NetworkEvent() {}

    /** 处理上下文。真实 NeoForge 每 包一个实例；Mirage 每次派发新建。 */
    public static final class Context {
        private final NetworkDirection direction;
        private final Player sender;
        private boolean packetHandled = false;

        public Context(NetworkDirection direction) {
            this(direction, null);
        }

        public Context(NetworkDirection direction, Player sender) {
            this.direction = direction;
            this.sender = sender;
        }

        /** 本包方向。 */
        public NetworkDirection getDirection() {
            return direction;
        }

        /** 服务端侧收到时为发送方玩家；客户端侧或无 sender 时为 null。 */
        public Player getSender() {
            return sender;
        }

        /**
         * 在主线程执行任务。Mirage 本地通路立即在当前线程执行
         * （不做主线程排队，见 ChannelRegistry 类注）。
         */
        public void enqueueWork(Runnable task) {
            task.run();
        }

        /** 标记本包已处理（阻止链上其它 handler）。 */
        public void setPacketHandled(boolean b) {
            packetHandled = b;
        }

        public boolean getPacketHandled() {
            return packetHandled;
        }
    }
}
