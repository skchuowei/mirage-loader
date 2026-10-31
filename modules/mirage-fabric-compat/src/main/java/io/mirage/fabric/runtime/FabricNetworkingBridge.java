/*
 * Mirage Fabric Compat — runtime bridge for Fabric networking (play stage).
 *
 * Mirror of FabricLifecycleBridge: Mixin injection points call these static methods,
 * which dispatch to the callbacks registered through the net.fabricmc.fabric.api.*
 * stub classes. The bridge is loaded by the MirageClassLoader together with mods,
 * so it sees the same net.fabricmc.* stub classes.
 *
 * 注入点（core/mirage-mixin，全部经 javap 在 26.2.jar 核实存在）：
 *   PlayerList.placeNewPlayer            → ServerPlayConnectionEvents.INIT (HEAD) / JOIN (TAIL)
 *   ServerGamePacketListenerImpl.onDisconnect → ServerPlayConnectionEvents.DISCONNECT
 *   ServerGamePacketListenerImpl.handleCustomPayload → ServerPlayNetworking receiver 派发
 *   ClientPacketListener.<init>          → ClientPlayConnectionEvents.INIT
 *   ClientPacketListener.handleLogin     → ClientPlayConnectionEvents.JOIN
 *   ClientPacketListener.onDisconnect    → ClientPlayConnectionEvents.DISCONNECT
 *   ClientPacketListener.handleCustomPayload → ClientPlayNetworking receiver 派发
 */
package io.mirage.fabric.runtime;

import io.mirage.api.log.MirageLog;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.channel.ChannelFutureListener;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientCommonPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerCommonPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;

/**
 * Fabric play 阶段网络事件派发入口。Mixin 只调用这里的静态方法。
 */
public final class FabricNetworkingBridge {

	private FabricNetworkingBridge() {
	}

	// =====================================================================
	// payload 类型登记（PayloadTypeRegistry 的落地）
	// 原版侧 codec 注册属深层集成（缺口清单 P2），这里只记类型表供校验/查询。
	// =====================================================================

	private static final Set<Identifier> REGISTERED_PAYLOAD_TYPES = ConcurrentHashMap.newKeySet();

	static final class PayloadTypeRegistryImpl<B extends FriendlyByteBuf> implements PayloadTypeRegistry<B> {
		private final String channel;

		PayloadTypeRegistryImpl(String channel) {
			this.channel = channel;
		}

		@Override
		@SuppressWarnings("unchecked")
		public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec) {
			REGISTERED_PAYLOAD_TYPES.add(type.id());
			// codec 形参是 ? super B，记录构造器要 StreamCodec<B, T>：桥接侧只做类型表登记，强转安全
			return new CustomPacketPayload.TypeAndCodec<>(type, (StreamCodec<B, T>) codec);
		}

		@Override
		public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> registerLarge(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec, int maxPacketSize) {
			MirageLog.MIRAGE.info("PayloadTypeRegistry(" + channel + ") registerLarge: 分包发送未实现，按普通类型登记 " + type.id());
			return register(type, codec);
		}

		@Override
		public <T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> registerLarge(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec, java.util.function.IntSupplier maxPacketSizeSupplier) {
			MirageLog.MIRAGE.info("PayloadTypeRegistry(" + channel + ") registerLarge: 分包发送未实现，按普通类型登记 " + type.id());
			return register(type, codec);
		}
	}

	public static PayloadTypeRegistry<FriendlyByteBuf> serverboundConfiguration() {
		return new PayloadTypeRegistryImpl<>("serverbound-configuration");
	}

	public static PayloadTypeRegistry<FriendlyByteBuf> clientboundConfiguration() {
		return new PayloadTypeRegistryImpl<>("clientbound-configuration");
	}

	public static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay() {
		return new PayloadTypeRegistryImpl<>("serverbound-play");
	}

	public static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay() {
		return new PayloadTypeRegistryImpl<>("clientbound-play");
	}

	// =====================================================================
	// 服务端 receiver 登记
	// =====================================================================

	private static final Map<Identifier, ServerPlayNetworking.PlayPayloadHandler<?>> SERVER_GLOBAL_RECEIVERS = new ConcurrentHashMap<>();
	private static final Map<ServerGamePacketListenerImpl, Map<Identifier, ServerPlayNetworking.PlayPayloadHandler<?>>> SERVER_PER_LISTENER_RECEIVERS =
			Collections.synchronizedMap(new WeakHashMap<>());

	public static <T extends CustomPacketPayload> boolean registerServerGlobalReceiver(CustomPacketPayload.Type<T> type, ServerPlayNetworking.PlayPayloadHandler<T> handler) {
		Objects.requireNonNull(type, "Payload type cannot be null");
		Objects.requireNonNull(handler, "Handler cannot be null");
		warnIfTypeUnregistered(type);
		return SERVER_GLOBAL_RECEIVERS.putIfAbsent(type.id(), handler) == null;
	}

	public static ServerPlayNetworking.PlayPayloadHandler<?> unregisterServerGlobalReceiver(Identifier id) {
		return SERVER_GLOBAL_RECEIVERS.remove(id);
	}

	public static Set<Identifier> getServerGlobalReceivers() {
		return Set.copyOf(SERVER_GLOBAL_RECEIVERS.keySet());
	}

	public static <T extends CustomPacketPayload> boolean registerServerReceiver(ServerGamePacketListenerImpl packetListener, CustomPacketPayload.Type<T> type, ServerPlayNetworking.PlayPayloadHandler<T> handler) {
		Objects.requireNonNull(packetListener, "Packet listener cannot be null");
		Objects.requireNonNull(type, "Payload type cannot be null");
		Objects.requireNonNull(handler, "Handler cannot be null");
		warnIfTypeUnregistered(type);
		return perListenerReceivers(packetListener).putIfAbsent(type.id(), handler) == null;
	}

	public static ServerPlayNetworking.PlayPayloadHandler<?> unregisterServerReceiver(ServerGamePacketListenerImpl packetListener, Identifier id) {
		Map<Identifier, ServerPlayNetworking.PlayPayloadHandler<?>> map = SERVER_PER_LISTENER_RECEIVERS.get(packetListener);
		return map == null ? null : map.remove(id);
	}

	public static Set<Identifier> getServerReceived(ServerGamePacketListenerImpl listener) {
		Set<Identifier> ids = new LinkedHashSet<>(SERVER_GLOBAL_RECEIVERS.keySet());
		Map<Identifier, ServerPlayNetworking.PlayPayloadHandler<?>> map = SERVER_PER_LISTENER_RECEIVERS.get(listener);
		if (map != null) {
			synchronized (map) {
				ids.addAll(map.keySet());
			}
		}
		return Set.copyOf(ids);
	}

	public static Set<Identifier> getServerSendable(ServerGamePacketListenerImpl listener) {
		// 近似：桥接侧没有「对端声明可收」的握手信息，以已登记 receiver 近似。
		return getServerReceived(listener);
	}

	private static Map<Identifier, ServerPlayNetworking.PlayPayloadHandler<?>> perListenerReceivers(ServerGamePacketListenerImpl listener) {
		synchronized (SERVER_PER_LISTENER_RECEIVERS) {
			return SERVER_PER_LISTENER_RECEIVERS.computeIfAbsent(listener, k -> new ConcurrentHashMap<>());
		}
	}

	// =====================================================================
	// 客户端 receiver 登记
	// =====================================================================

	private static final Map<Identifier, ClientPlayNetworking.PlayPayloadHandler<?>> CLIENT_GLOBAL_RECEIVERS = new ConcurrentHashMap<>();
	private static final Map<ClientPacketListener, Map<Identifier, ClientPlayNetworking.PlayPayloadHandler<?>>> CLIENT_PER_LISTENER_RECEIVERS =
			Collections.synchronizedMap(new WeakHashMap<>());

	public static <T extends CustomPacketPayload> boolean registerClientGlobalReceiver(CustomPacketPayload.Type<T> type, ClientPlayNetworking.PlayPayloadHandler<T> handler) {
		Objects.requireNonNull(type, "Payload type cannot be null");
		Objects.requireNonNull(handler, "Handler cannot be null");
		warnIfTypeUnregistered(type);
		return CLIENT_GLOBAL_RECEIVERS.putIfAbsent(type.id(), handler) == null;
	}

	public static ClientPlayNetworking.PlayPayloadHandler<?> unregisterClientGlobalReceiver(Identifier id) {
		return CLIENT_GLOBAL_RECEIVERS.remove(id);
	}

	public static Set<Identifier> getClientGlobalReceivers() {
		return Set.copyOf(CLIENT_GLOBAL_RECEIVERS.keySet());
	}

	public static <T extends CustomPacketPayload> boolean registerClientReceiver(ClientPacketListener connection, CustomPacketPayload.Type<T> type, ClientPlayNetworking.PlayPayloadHandler<T> handler) {
		Objects.requireNonNull(connection, "Connection cannot be null");
		Objects.requireNonNull(type, "Payload type cannot be null");
		Objects.requireNonNull(handler, "Handler cannot be null");
		warnIfTypeUnregistered(type);
		return perListenerClientReceivers(connection).putIfAbsent(type.id(), handler) == null;
	}

	public static ClientPlayNetworking.PlayPayloadHandler<?> unregisterClientReceiver(ClientPacketListener connection, Identifier id) {
		Map<Identifier, ClientPlayNetworking.PlayPayloadHandler<?>> map = CLIENT_PER_LISTENER_RECEIVERS.get(connection);
		return map == null ? null : map.remove(id);
	}

	public static Set<Identifier> getClientReceived(ClientPacketListener connection) {
		Set<Identifier> ids = new LinkedHashSet<>(CLIENT_GLOBAL_RECEIVERS.keySet());
		Map<Identifier, ClientPlayNetworking.PlayPayloadHandler<?>> map = CLIENT_PER_LISTENER_RECEIVERS.get(connection);
		if (map != null) {
			synchronized (map) {
				ids.addAll(map.keySet());
			}
		}
		return Set.copyOf(ids);
	}

	public static Set<Identifier> getClientSendable(ClientPacketListener connection) {
		// 近似：桥接侧没有「服务端声明可收」的握手信息，以已登记 receiver 近似。
		return getClientReceived(connection);
	}

	private static Map<Identifier, ClientPlayNetworking.PlayPayloadHandler<?>> perListenerClientReceivers(ClientPacketListener connection) {
		synchronized (CLIENT_PER_LISTENER_RECEIVERS) {
			return CLIENT_PER_LISTENER_RECEIVERS.computeIfAbsent(connection, k -> new ConcurrentHashMap<>());
		}
	}

	private static void warnIfTypeUnregistered(CustomPacketPayload.Type<?> type) {
		if (!REGISTERED_PAYLOAD_TYPES.contains(type.id())) {
			MirageLog.MIRAGE.info("FabricNetworkingBridge: payload 类型 " + type.id()
					+ " 未经 PayloadTypeRegistry 登记即注册 receiver（原版 codec 未注册时该包无法到达）");
		}
	}

	// =====================================================================
	// 发包（真实发送：走原版 Connection）
	// =====================================================================

	public static <T extends CustomPacketPayload> Packet<ClientCommonPacketListener> createClientboundPacket(T packet) {
		return new ClientboundCustomPayloadPacket(packet);
	}

	public static <T extends CustomPacketPayload> Packet<ServerCommonPacketListener> createServerboundPacket(T packet) {
		return new ServerboundCustomPayloadPacket(packet);
	}

	// =====================================================================
	// 收包派发（Mixin 调用；返回 true 表示已消费，Mixin 据此 cancel 原版处理）
	// =====================================================================

	public static boolean onServerCustomPayload(ServerGamePacketListenerImpl listener, ServerboundCustomPayloadPacket packet) {
		CustomPacketPayload payload = packet.payload();
		if (payload == null || payload.type() == null) {
			return false;
		}
		Identifier id = payload.type().id();
		ServerPlayNetworking.PlayPayloadHandler<?> handler = perListenerReceivers(listener).get(id);
		if (handler == null) {
			handler = SERVER_GLOBAL_RECEIVERS.get(id);
		}
		if (handler == null) {
			return false;
		}
		invokeServerHandler(handler, payload, listener);
		return true;
	}

	public static boolean onClientCustomPayload(ClientPacketListener listener, CustomPacketPayload payload) {
		if (payload == null || payload.type() == null) {
			return false;
		}
		Identifier id = payload.type().id();
		ClientPlayNetworking.PlayPayloadHandler<?> handler = perListenerClientReceivers(listener).get(id);
		if (handler == null) {
			handler = CLIENT_GLOBAL_RECEIVERS.get(id);
		}
		if (handler == null) {
			return false;
		}
		invokeClientHandler(handler, payload, listener);
		return true;
	}

	@SuppressWarnings("unchecked")
	private static void invokeServerHandler(ServerPlayNetworking.PlayPayloadHandler<?> handler, CustomPacketPayload payload, ServerGamePacketListenerImpl listener) {
		try {
			((ServerPlayNetworking.PlayPayloadHandler<CustomPacketPayload>) handler).receive(payload, new ServerContext(listener));
		} catch (Throwable t) {
			MirageLog.MIRAGE.warn("Fabric server payload handler 抛出异常", t);
			t.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	private static void invokeClientHandler(ClientPlayNetworking.PlayPayloadHandler<?> handler, CustomPacketPayload payload, ClientPacketListener listener) {
		try {
			((ClientPlayNetworking.PlayPayloadHandler<CustomPacketPayload>) handler).receive(payload, new ClientContext(listener));
		} catch (Throwable t) {
			MirageLog.MIRAGE.warn("Fabric client payload handler 抛出异常", t);
			t.printStackTrace();
		}
	}

	// =====================================================================
	// 连接事件派发
	// =====================================================================

	public static void fireServerInit(ServerGamePacketListenerImpl listener, net.minecraft.server.MinecraftServer server) {
		ServerPlayConnectionEvents.INIT.invoker().onPlayInit(listener, server);
	}

	public static void fireServerJoin(ServerGamePacketListenerImpl listener, net.minecraft.server.MinecraftServer server) {
		ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(listener, serverSender(listener), server);
	}

	public static void fireServerDisconnect(ServerGamePacketListenerImpl listener) {
		ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(listener, serverOf(listener));
	}

	public static void fireClientInit(ClientPacketListener listener) {
		ClientPlayConnectionEvents.INIT.invoker().onPlayInit(listener, Minecraft.getInstance());
	}

	public static void fireClientJoin(ClientPacketListener listener) {
		ClientPlayConnectionEvents.JOIN.invoker().onPlayReady(listener, clientSender(listener), Minecraft.getInstance());
	}

	public static void fireClientDisconnect(ClientPacketListener listener) {
		ClientPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(listener, Minecraft.getInstance());
	}

	// =====================================================================
	// PacketSender 实现（真实发送）
	// =====================================================================

	public static PacketSender serverSender(ServerGamePacketListenerImpl listener) {
		return new ServerSender(listener);
	}

	public static PacketSender clientSender(ClientPacketListener listener) {
		return new ClientSender(listener);
	}

	static final class ServerSender implements PacketSender {
		private final ServerGamePacketListenerImpl listener;

		ServerSender(ServerGamePacketListenerImpl listener) {
			this.listener = listener;
		}

		@Override
		public Packet<?> createPacket(CustomPacketPayload payload) {
			return createClientboundPacket(payload);
		}

		@Override
		public void sendPacket(Packet<?> packet, ChannelFutureListener callback) {
			if (callback != null) {
				listener.send(packet, callback);
			} else {
				listener.send(packet);
			}
		}

		@Override
		public void disconnect(Component disconnectReason) {
			listener.disconnect(disconnectReason);
		}
	}

	static final class ClientSender implements PacketSender {
		private final ClientPacketListener listener;

		ClientSender(ClientPacketListener listener) {
			this.listener = listener;
		}

		@Override
		public Packet<?> createPacket(CustomPacketPayload payload) {
			return createServerboundPacket(payload);
		}

		@Override
		public void sendPacket(Packet<?> packet, ChannelFutureListener callback) {
			if (callback != null) {
				// 26.2 的 ClientCommonPacketListenerImpl 只有单参 send(Packet<?>)；
				// 带回调的发送走底层 Connection（其父类持有 protected final Connection connection）
				Connection connection = connectionOf(listener);
				if (connection != null) {
					connection.send(packet, callback);
					return;
				}
				MirageLog.MIRAGE.info("ClientSender: 拿不到 Connection，忽略 ChannelFutureListener 回调");
			}
			listener.send(packet);
		}

		@Override
		public void disconnect(Component disconnectReason) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.getConnection() == listener) {
				mc.disconnectFromWorld(disconnectReason);
			}
		}
	}

	/**
	 * 反射读取 ClientPacketListener 继承链上的 Connection 字段（protected final connection）。
	 * 仅在调用方传了 ChannelFutureListener 时使用，结果按类缓存。
	 */
	private static final java.util.Map<Class<?>, java.lang.reflect.Field> CONNECTION_FIELDS = new ConcurrentHashMap<>();

	private static Connection connectionOf(ClientPacketListener listener) {
		try {
			java.lang.reflect.Field field = CONNECTION_FIELDS.get(listener.getClass());
			if (field == null) {
				field = findConnectionField(listener.getClass());
				if (field != null) {
					CONNECTION_FIELDS.put(listener.getClass(), field);
				}
			}
			return field == null ? null : (Connection) field.get(listener);
		} catch (Throwable t) {
			return null;
		}
	}

	private static java.lang.reflect.Field findConnectionField(Class<?> cls) {
		for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
			try {
				java.lang.reflect.Field field = c.getDeclaredField("connection");
				if (Connection.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					return field;
				}
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	// =====================================================================
	// Context 实现与工具
	// =====================================================================

	static final class ServerContext implements ServerPlayNetworking.Context {
		private final ServerGamePacketListenerImpl listener;

		ServerContext(ServerGamePacketListenerImpl listener) {
			this.listener = listener;
		}

		@Override
		public net.minecraft.server.MinecraftServer server() {
			return serverOf(listener);
		}

		@Override
		public ServerPlayer player() {
			return listener.player;
		}

		@Override
		public PacketSender responseSender() {
			return serverSender(listener);
		}
	}

	static final class ClientContext implements ClientPlayNetworking.Context {
		private final ClientPacketListener listener;

		ClientContext(ClientPacketListener listener) {
			this.listener = listener;
		}

		@Override
		public Minecraft client() {
			return Minecraft.getInstance();
		}

		@Override
		public net.minecraft.client.player.LocalPlayer player() {
			return Minecraft.getInstance().player;
		}

		@Override
		public PacketSender responseSender() {
			return clientSender(listener);
		}
	}

	/**
	 * 桥接 stub：未接原版 configuration 阶段，仅记录日志。
	 */
	public static void reconfigure(ServerGamePacketListenerImpl listener) {
		MirageLog.MIRAGE.info("ServerPlayNetworking.reconfigure 调用（桥接 stub：未接原版 configuration 阶段，忽略）");
	}

	private static net.minecraft.server.MinecraftServer serverOf(ServerGamePacketListenerImpl listener) {
		try {
			if (listener.player == null) {
				return null;
			}
			Level level = listener.player.level();
			return level instanceof ServerLevel serverLevel ? serverLevel.getServer() : null;
		} catch (Throwable t) {
			MirageLog.MIRAGE.warn("获取 MinecraftServer 失败", t);
			return null;
		}
	}
}
