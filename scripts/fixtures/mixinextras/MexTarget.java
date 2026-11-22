package mexfixture.target;

/** MixinExtras 门禁目标类：两个方法分别被 @ModifyReturnValue / @WrapOperation 织入。 */
public class MexTarget {
    public int value() {
        return 1;
    }

    public int calc(int x) {
        return Math.abs(x);
    }
}
