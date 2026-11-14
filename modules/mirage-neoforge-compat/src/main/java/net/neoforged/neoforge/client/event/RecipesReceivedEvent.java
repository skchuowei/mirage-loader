/* 本文件由 scripts/msr_w3c_c1_gen.py 生成，请勿手改；改生成器后重跑 --check。 */
package net.neoforged.neoforge.client.event;

/** W3-C1 clean-room：形状与签名取自 real-shape 骨架；未支持方法抛 UOE，不返回编造值。*/
public class RecipesReceivedEvent extends net.neoforged.bus.api.Event {

    private java.util.Set f0;
    private net.minecraft.world.item.crafting.RecipeMap f1;

    public RecipesReceivedEvent(java.util.Set a0, net.minecraft.world.item.crafting.RecipeMap a1) {
        this.f0 = a0;
        this.f1 = a1;
    }

    public java.util.Set getRecipeTypes() {
        return this.f0;
    }

    public net.minecraft.world.item.crafting.RecipeMap getRecipeMap() {
        return this.f1;
    }

    public RecipesReceivedEvent() {
    }

}
