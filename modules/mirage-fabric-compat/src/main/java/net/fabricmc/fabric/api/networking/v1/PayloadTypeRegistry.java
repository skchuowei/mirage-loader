/*
 * Mirage Fabric Compat — clean-room bridge stub of Fabric API's PayloadTypeRegistry.
 *
 * Fabric API 原作以 Apache License 2.0 发布；本文件仅镜像其公开 API 形状
 * （泛型签名与静态工厂），供编译期链接真实 fabric-api 的 Fabric mod 在
 * Mirage 运行时解析符号。实现侧只做「类型表登记」，不注入原版 payload
 * 注册表（原版侧注册属深层集成，见 docs/Fabric兼容层缺口清单.md）。
 */
package net.fabricmc.fabric.api.networking.v1;

import java.util.function.IntSupplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import io.mirage.fabric.runtime.FabricNetworkingBridge;

/**
 * A registry for payload types.
 */
public interface PayloadTypeRegistry<B extends FriendlyByteBuf> {
	/**
	 * Registers a custom payload type.
	 *
	 * <p>This must be done on both the sending and receiving side, usually during mod initialization
	 * and <strong>before registering a packet handler</strong>.
	 */
	<T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> register(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec);

	/**
	 * Registers a large custom payload type.
	 */
	<T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> registerLarge(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec, int maxPacketSize);

	/**
	 * Registers a large custom payload type.
	 */
	<T extends CustomPacketPayload> CustomPacketPayload.TypeAndCodec<? super B, T> registerLarge(CustomPacketPayload.Type<T> type, StreamCodec<? super B, T> codec, IntSupplier maxPacketSizeSupplier);

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the serverbound (client to server) configuration channel.
	 */
	static PayloadTypeRegistry<FriendlyByteBuf> serverboundConfiguration() {
		return FabricNetworkingBridge.serverboundConfiguration();
	}

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the clientbound (server to client) configuration channel.
	 */
	static PayloadTypeRegistry<FriendlyByteBuf> clientboundConfiguration() {
		return FabricNetworkingBridge.clientboundConfiguration();
	}

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the serverbound (client to server) play channel.
	 */
	static PayloadTypeRegistry<RegistryFriendlyByteBuf> serverboundPlay() {
		return FabricNetworkingBridge.serverboundPlay();
	}

	/**
	 * @return the {@link PayloadTypeRegistry} instance for the clientbound (server to client) play channel.
	 */
	static PayloadTypeRegistry<RegistryFriendlyByteBuf> clientboundPlay() {
		return FabricNetworkingBridge.clientboundPlay();
	}
}
