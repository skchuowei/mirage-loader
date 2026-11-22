package mexfixture;

import io.mirage.loader.MirageClassLoader;
import io.mirage.mixin.MirageMixinBootstrap;

import java.io.File;
import java.net.URL;
import java.util.List;

/**
 * MixinExtras 门禁行为层探针（headless）。
 *
 * <p>走真实生产链路：MirageClassLoader（parent = platform，与 TransformStage 同构造）
 * + MirageMixinBootstrap.init + mcl.loadClass 触发织入。用法：
 * <pre>
 *   java mexfixture.MexProbeMain &lt;config名&gt; &lt;fatJar路径&gt; &lt;fixtures目录&gt;
 * </pre>
 * 输出 KEY=VALUE ASCII 标记供门禁断言。
 */
public final class MexProbeMain {

    public static void main(String[] args) throws Exception {
        String config = args[0];
        URL fatJar = new File(args[1]).toURI().toURL();
        URL fixtures = new File(args[2]).toURI().toURL();

        MirageClassLoader mcl = new MirageClassLoader(
                new URL[]{fatJar, fixtures},
                ClassLoader.getSystemClassLoader().getParent());

        try {
            MirageMixinBootstrap.init(mcl, List.of(config));
        } catch (Throwable t) {
            StringBuilder chain = new StringBuilder();
            for (Throwable c = t; c != null; c = c.getCause()) {
                if (chain.length() > 0) chain.append(" | ");
                chain.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
                if (c.getCause() == c) break;
            }
            System.out.println("[MEX-PROBE] INIT_THREW " + chain);
            boolean fatal = chain.indexOf("mixinextras") >= 0 && chain.indexOf("9.9.9") >= 0;
            System.out.println("[MEX-PROBE] MINVERSION_FATAL=" + (fatal ? "yes" : "no"));
            return;
        }
        System.out.println("[MEX-PROBE] INIT_OK");

        Class<?> c = mcl.loadClass("mexfixture.target.MexTarget");
        Object inst = c.getConstructor().newInstance();
        Object v = c.getMethod("value").invoke(inst);
        Object calc = c.getMethod("calc", int.class).invoke(inst, -7);
        System.out.println("[MEX-PROBE] value()=" + v + " calc(-7)=" + calc);
        boolean pass = Integer.valueOf(42).equals(v) && Integer.valueOf(107).equals(calc);
        System.out.println("[MEX-PROBE] RESULT " + (pass ? "PASS" : "FAIL"));
    }

    private MexProbeMain() {
    }
}
