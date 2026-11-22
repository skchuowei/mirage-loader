package mexfixture.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import mexfixture.target.MexTarget;

@Mixin(MexTarget.class)
public class MexWrapMixin {
    @WrapOperation(method = "calc", at = @At(value = "INVOKE", target = "Ljava/lang/Math;abs(I)I"))
    private int mex$wrapAbs(int x, Operation<Integer> original) {
        System.out.println("[MEX-PROBE] WrapOperation fired x=" + x);
        return original.call(x) + 100;
    }
}
