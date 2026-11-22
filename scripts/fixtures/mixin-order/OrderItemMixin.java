package orderfixture;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 织入优先级门禁夹具（对照档）：用「字符串 targets」形式声明目标，且目标
 * {@code net.minecraft.world.item.Item} <strong>不是</strong> MG 自研 ASM 的目标类。
 *
 * <p>作用有二：① 证明 {@code @Mixin(targets = "...")} 这种写法也能被收集到；
 * ② 证明「mod 有 mixin 但没撞 MG 目标」时 MG 自研织入照旧执行（零回归）。
 */
@Mixin(targets = "net.minecraft.world.item.Item")
public abstract class OrderItemMixin {
}
