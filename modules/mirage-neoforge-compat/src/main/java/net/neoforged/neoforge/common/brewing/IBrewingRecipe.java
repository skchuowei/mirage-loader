/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.common.brewing;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public interface IBrewingRecipe {

    public boolean isInput(net.minecraft.world.item.ItemStack a0);
    public boolean isIngredient(net.minecraft.world.item.ItemStack a0);
    public net.minecraft.world.item.ItemStack getOutput(net.minecraft.world.item.ItemStack a0, net.minecraft.world.item.ItemStack a1);
}
