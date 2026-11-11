package probe;

import io.mirage.loader.sandbox.FakePackProvider;
import io.mirage.loader.sandbox.FakeProvider;

/**
 * P4b 自动发现探针：验证 FakePackProvider.fromSystemProperty() 在没有
 * -Dmirage.msr.fakepack 时，能靠 -Dmirage.msr.fakepack.home=<dir> 找到 pack；
 * 两个属性都没给时返回 null（绝不偷偷扫全盘）。
 *
 * 用法：java -cp <loader classes> -Dmirage.msr.fakepack.home=<packDir> probe.FakePackProbe <className>
 * 输出：PROBE ok classCount=N            （找到了）
 *       PROBE null                      （没找到，符合预期）
 *       PROBE find <class> -> <bytes> B （findFakeBytes 能取到字节码）
 */
public class FakePackProbe {
    public static void main(String[] args) throws Exception {
        FakeProvider p = FakePackProvider.fromSystemProperty();
        if (p == null) {
            System.out.println("PROBE null");
            return;
        }
        int cc = (p instanceof FakePackProvider fpp) ? fpp.classCount() : -1;
        System.out.println("PROBE ok classCount=" + cc);
        if (args.length > 0) {
            byte[] b = p.findFakeBytes(args[0]);
            System.out.println("PROBE find " + args[0] + " -> " + (b != null ? (b.length + " bytes") : "null"));
        }
    }
}
