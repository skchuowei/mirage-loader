package orderfixture;

import net.minecraft.world.item.CreativeModeTab;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 织入优先级门禁夹具（冲突档）：用「类字面量」形式声明目标，
 * 目标 {@code net.minecraft.world.item.CreativeModeTab} 正是 MG 自研 ASM 的 7 个硬编码目标之一。
 *
 * <p>空 mixin 就够了——门禁要验证的是「MG 是否读到了 mod 的 @Mixin 目标并让路」，
 * 不是注入点写得好不好（注入点由 verify_mixin_registry.ps1 负责）。
 */
@Mixin(CreativeModeTab.class)
public abstract class OrderTabMixin {
}
