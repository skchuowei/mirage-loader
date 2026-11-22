package mexfixture.mixins;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import mexfixture.target.MexTarget;

@Mixin(MexTarget.class)
public class MexReturnMixin {
    @ModifyReturnValue(method = "value", at = @At("RETURN"))
    private int mex$boost(int original) {
        System.out.println("[MEX-PROBE] ModifyReturnValue fired original=" + original);
        return original + 41;
    }
}
