package orderfixture;

import net.minecraft.server.packs.VanillaPackResourcesBuilder;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 织入优先级门禁夹具（MG 自有 config 档）：只在探针的<strong>内存资源</strong>里以
 * {@code mirage.mixins.json} 的名义出现，用来验证「MG 自己的 mixin config 不算 mod 目标」。
 *
 * <p>若 {@code ModMixinTargets} 没排除 {@code mirage.mixins.json}，
 * {@code net.minecraft.server.packs.VanillaPackResourcesBuilder} 就会进 mod 目标集合、
 * 被误判成冲突、让 MG 自研织入自噬——门禁的 {@code SELF_IGNORED} 断言专门盯这一条。
 */
@Mixin(VanillaPackResourcesBuilder.class)
public abstract class OrderPackMixin {
}
